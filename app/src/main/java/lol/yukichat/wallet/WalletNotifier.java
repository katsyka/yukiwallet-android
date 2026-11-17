package lol.yukichat.wallet;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Incoming-payment and name-expiry notifications. WalletService checks on every new block;
 * this periodic job is the fallback for when the live stream is down.
 * Only the public address is stored here, never keys.
 */
public class WalletNotifier extends JobService {
    static final String CHANNEL_PAY = "payments";
    static final String CHANNEL_NAMES = "names";
    static final String CHANNEL_CONN = "connection";
    static final int ID_CONNECTION = 1;
    private static final String API = "https://chain.yukichat.lol/api/account/";
    private static final int JOB_ID = 6101;
    private static final long DAY = 86_400_000L;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notify", MODE_PRIVATE);
    }

    static String address(Context c) {
        return prefs(c).getString("addr", "");
    }

    /** Set by the page after unlock (or the Settings switch); empty = off → stop everything. */
    static void setAddress(Context c, String addr) {
        if (addr == null) addr = "";
        SharedPreferences p = prefs(c);
        boolean changed = !addr.equals(p.getString("addr", ""));
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (addr.isEmpty()) {
            p.edit().clear().apply();
            js.cancel(JOB_ID);
            WalletService.stop(c);
            cancelAll(c);
            return;
        }
        // another wallet: start from a fresh baseline so its history isn't announced
        if (changed) p.edit().clear().putString("addr", addr).apply();
        if (js.getPendingJob(JOB_ID) == null) {
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, WalletNotifier.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build());
        }
        WalletService.start(c);
        if (changed) new Thread(() -> { try { check(c); } catch (Exception ignored) { } }).start();
    }

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PAY, "Incoming YUKI", NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel names = new NotificationChannel(CHANNEL_NAMES, "Name reminders", NotificationManager.IMPORTANCE_DEFAULT);
        names.setDescription("A week before a name.yuki expires");
        nm.createNotificationChannel(names);
        NotificationChannel conn = new NotificationChannel(CHANNEL_CONN, "Background connection", NotificationManager.IMPORTANCE_MIN);
        conn.setDescription("Keeps Yuki Wallet connected so payments show up instantly. You can hide this notification.");
        conn.setShowBadge(false);
        nm.createNotificationChannel(conn);
    }

    /** The silent ongoing notification Android requires for WalletService. */
    static android.app.Notification connection(Context c) {
        createChannels(c);
        return new NotificationCompat.Builder(c, CHANNEL_CONN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Watching for payments")
                .setContentIntent(openApp(c, null, 0))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private static PendingIntent openApp(Context c, String tx, int req) {
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (tx != null) open.putExtra("tx", tx);
        return PendingIntent.getActivity(c, req, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String yuki(String nano) {
        try {
            return new BigDecimal(nano).movePointLeft(9).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return nano;
        }
    }

    static String shortAddr(String a) {
        return a.length() > 16 ? a.substring(0, 9) + "…" + a.substring(a.length() - 4) : a;
    }

    private static void notify(Context c, int id, NotificationCompat.Builder nb) {
        try { c.getSystemService(NotificationManager.class).notify(id, nb.build()); } catch (SecurityException ignored) { }
    }

    /** Looks at the newest transactions and the wallet's names; announces what is new. */
    static synchronized void check(Context c) throws Exception {
        String addr = address(c);
        if (addr.isEmpty()) return;
        HttpURLConnection con = (HttpURLConnection) new URL(API + addr + "?limit=20").openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        if (con.getResponseCode() != 200) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = con.getInputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        JSONObject acc = new JSONObject(out.toString("UTF-8"));
        SharedPreferences p = prefs(c);
        if (!addr.equals(address(c))) return;
        long last = p.getLong("last", -1), newest = last;
        JSONArray txs = acc.optJSONArray("txs");
        if (txs != null) {
            for (int i = txs.length() - 1; i >= 0; i--) {
                JSONObject v = txs.getJSONObject(i), t = v.optJSONObject("tx");
                long time = v.optLong("time");
                if (t == null || time <= last) continue;
                newest = Math.max(newest, time);
                if (last < 0 || MainActivity.visible) continue; // first run = baseline; open app shows its own banner
                if (!"transfer".equals(t.optString("type")) || !addr.equals(t.optString("to")) || addr.equals(t.optString("from"))) continue;
                String amount = yuki(t.optString("amount", "0")), memo = t.optString("memo", "");
                String from = "faucet".equals(memo) ? "Faucet" : shortAddr(t.optString("from"));
                createChannels(c);
                notify(c, t.optString("hash").hashCode(), new NotificationCompat.Builder(c, CHANNEL_PAY)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(0xFFF26A3F)
                        .setContentTitle("+" + amount + " YUKI")
                        .setContentText("from " + from + (memo.isEmpty() || "faucet".equals(memo) ? "" : " · " + memo))
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setWhen(time)
                        .setContentIntent(openApp(c, t.optString("hash"), t.optString("hash").hashCode()))
                        .setGroup("pay")
                        .setAutoCancel(true));
            }
        }
        if (last < 0 && newest < 0) newest = 0;
        p.edit().putLong("last", newest).apply();

        // names: one reminder per expiry date, a week ahead
        JSONArray names = acc.optJSONArray("names");
        long now = System.currentTimeMillis();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                JSONObject n = names.getJSONObject(i);
                long exp = n.optLong("expires");
                String key = "exp_" + n.optString("name");
                if (!n.optBoolean("active") || exp - now > 7 * DAY || exp < now || p.getLong(key, 0) == exp) continue;
                p.edit().putLong(key, exp).apply();
                long days = Math.max(1, (exp - now + DAY - 1) / DAY);
                createChannels(c);
                notify(c, key.hashCode(), new NotificationCompat.Builder(c, CHANNEL_NAMES)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(0xFFF26A3F)
                        .setContentTitle(n.optString("name") + ".yuki expires in " + days + (days == 1 ? " day" : " days"))
                        .setContentText("Renew it in the Names tab to keep it")
                        .setContentIntent(openApp(c, null, key.hashCode()))
                        .setAutoCancel(true));
            }
        }
    }

    /** Opening the app clears payment notifications (the activity list shows them). */
    static void cancelAll(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications()) {
            if (n.getId() != ID_CONNECTION) nm.cancel(n.getId());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { check(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
