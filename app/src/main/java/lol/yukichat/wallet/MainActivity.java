package lol.yukichat.wallet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** WebView shell around wallet.yukichat.lol with self-updates. */
public class MainActivity extends Activity {

    static volatile boolean visible;
    private static final String HOST = "wallet.yukichat.lol";
    private static final int FILE_REQUEST = 4711;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private AppUpdater updater;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        updater = new AppUpdater(this, HOST);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " YukiWalletApp/" + BuildConfig.VERSION_NAME);
        web.addJavascriptInterface(new NativeBridge(this, new Bio(this, web)), "YukiNative");
        web.setWebChromeClient(new WebChromeClient() {
            // <input type="file"> (attachments) needs a native picker
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // "Continue with YukiChat" (yukichat.lol/?sso=mail) must stay in-app so it can redirect back here
                if ("yukichat.lol".equals(u.getHost()) && ("mail".equals(u.getQueryParameter("sso")) || "/".equals(u.getPath()))) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        String tx = getIntent().getStringExtra("tx");
        web.loadUrl(startUrl(getIntent()) + (tx != null ? "#tx=" + tx : ""));
        updater.check();
        WalletService.start(this);
    }


    private String startUrl(Intent i) {
        Uri d = i != null ? i.getData() : null;
        return d != null && HOST.equals(d.getHost()) ? d.toString() : BuildConfig.START_URL;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Uri d = i.getData();
        if (d != null && "/connect".equals(d.getPath())) {
            // YUKI Connect link while the app is open: hand it to the page, no reload (stays unlocked)
            String q = d.getEncodedQuery();
            if (q != null && q.matches("[A-Za-z0-9=&_.~%-]{1,4000}")) web.evaluateJavascript("window.__ywConnect&&__ywConnect('" + q + "')", null);
        } else if (d != null) web.loadUrl(startUrl(i));
        // tapped a payment notification while the app was open: show it without reloading (keeps the wallet unlocked)
        String tx = i.getStringExtra("tx");
        if (tx != null && tx.matches("[0-9a-f]{1,128}")) web.evaluateJavascript("window.__ywOpenTx&&__ywOpenTx('" + tx + "')", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        web.onResume();
        updater.onResume();
        WalletNotifier.cancelAll(this);
    }

    @Override
    protected void onPause() {
        visible = false;
        web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    static class NativeBridge {
        private final Context app;
        private final Activity owner;
        private final Bio bio;
        NativeBridge(Activity owner, Bio bio) { this.owner = owner; this.bio = bio; this.app = owner.getApplicationContext(); }

        /** Status/navigation bar colour + icon tone for the web theme ("#rrggbb", light = dark icons). */
        @JavascriptInterface
        public void setBars(String color, boolean light) {
            owner.runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(color);
                    android.view.Window w = owner.getWindow();
                    w.setStatusBarColor(c);
                    w.setNavigationBarColor(c);
                    w.getDecorView().setBackgroundColor(c);
                    int f = w.getDecorView().getSystemUiVisibility() & ~(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10);
                    if (light) f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? 0x10 : 0);
                    w.getDecorView().setSystemUiVisibility(f);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public String bioStatus() { return bio.status(); }

        @JavascriptInterface
        public String bioInfo() { return bio.info(); }

        /** Prompts for a fingerprint, then keeps the passcode encrypted by a biometric-only Keystore key. */
        @JavascriptInterface
        public void bioEnable(String pass) { bio.enable(pass); }

        /** Prompts for a fingerprint and hands the passcode back through window.__ywBio. */
        @JavascriptInterface
        public void bioUnlock() { bio.unlock(); }

        @JavascriptInterface
        public void bioDisable() { bio.disable(); }

        /** Public address to watch for incoming payments; "" turns notifications off. Asks for permission on Android 13+. */
        @JavascriptInterface
        public void notifySet(String address) {
            String a = address == null ? "" : address.trim();
            if (!a.isEmpty() && !a.matches("yuki1[a-z0-9]{38}")) return;
            if (!a.isEmpty() && Build.VERSION.SDK_INT >= 33 && owner != null
                    && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                owner.runOnUiThread(() -> owner.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3));
            }
            WalletNotifier.setAddress(app, a);
        }

        /** System notification settings for this app (after the permission was denied for good). */
        @JavascriptInterface
        public void notifySettings() {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, app.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { app.startActivity(i); } catch (Exception ignored) { }
        }

        /** "on" | "off" | "blocked" (system permission denied). */
        @JavascriptInterface
        public String notifyStatus() {
            if (WalletNotifier.address(app).isEmpty()) return "off";
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "blocked";
            return "on";
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public int versionCode() {
            return BuildConfig.VERSION_CODE;
        }

        /** Saves an attachment (base64) to Downloads; WebView can't download the page's blob: links. */
        @JavascriptInterface
        public boolean saveFile(String name, String mime, String base64) {
            String safe = name == null || name.isEmpty() ? "attachment" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return false;
                    try (OutputStream out = app.getContentResolver().openOutputStream(uri)) { out.write(data); }
                } else {
                    if (app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        if (owner != null) owner.runOnUiThread(() -> owner.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2));
                        return false; // the user taps download again after allowing
                    }
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(new File(dir, safe))) { out.write(data); }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
