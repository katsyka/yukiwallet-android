package lol.yukichat.wallet;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.ServiceCompat;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/** Follows the chain's block stream (SSE) so a payment becomes a notification within one block. */
public class WalletService extends Service {
    private static final String STREAM = "https://chain.yukichat.lol/api/stream";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .readTimeout(90, TimeUnit.SECONDS) // the server pings every 25s
            .build();

    private volatile boolean stopped;
    private volatile Call call;
    private Thread loop;
    private final Object wake = new Object();
    private ConnectivityManager.NetworkCallback netCallback;

    static void start(Context c) {
        if (WalletNotifier.address(c).isEmpty()) return;
        Intent i = new Intent(c, WalletService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // Android 12+ refuses from the background; the next app open or boot starts it
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, WalletService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        try {
            ServiceCompat.startForeground(this, WalletNotifier.ID_CONNECTION, WalletNotifier.connection(this), type);
        } catch (Exception e) {
            stopSelf();
            return;
        }
        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (WalletNotifier.address(this).isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (loop == null) {
            loop = new Thread(this::run, "yuki-stream");
            loop.start();
        }
        return START_STICKY;
    }

    private void run() {
        int attempt = 0;
        while (!stopped) {
            try {
                call = HTTP.newCall(new Request.Builder().url(STREAM).build());
                try (Response r = call.execute()) {
                    if (r.isSuccessful() && r.body() != null) {
                        attempt = 0;
                        check(); // catch up on anything from while we were offline
                        BufferedSource src = r.body().source();
                        String line;
                        while (!stopped && (line = src.readUtf8Line()) != null) {
                            // only blocks that carry transactions can change the wallet
                            if (line.startsWith("data:") && !line.contains("\"txs\":0}")) check();
                        }
                    }
                }
            } catch (Exception ignored) { }
            if (stopped) break;
            // 1s, 2s, 4s ... up to a minute; a network change retries at once
            long delay = Math.min(60_000L, 1000L << Math.min(attempt++, 6));
            synchronized (wake) { try { wake.wait(delay); } catch (InterruptedException e) { return; } }
        }
    }

    private void check() {
        try { WalletNotifier.check(this); } catch (Exception ignored) { }
    }

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                synchronized (wake) { wake.notifyAll(); }
            }
        };
        try { cm.registerDefaultNetworkCallback(netCallback); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        stopped = true;
        Call c = call;
        if (c != null) c.cancel();
        synchronized (wake) { wake.notifyAll(); }
        if (netCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
