package lol.yukichat.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads a newer APK from the Yuki Wallet server and hands it to the system installer. */
class AppUpdater {

    private final Activity activity;
    private final String allowedHost;
    private String urlAwaitingPermission;
    private volatile boolean cancelled;
    private boolean running;

    /** When set (update started from the web popup), progress goes to the page instead of a native dialog. */
    interface Listener { void onUpdate(String state, int pct, long doneKb, long totalKb); }
    private Listener listener;
    void setListener(Listener l) { listener = l; }
    void cancel() { cancelled = true; }

    AppUpdater(Activity activity, String allowedHost) {
        this.activity = activity;
        this.allowedHost = allowedHost;
    }

    /** Asks the server for the published version and offers the update if it is newer. */
    void check() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.UPDATE_JSON).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) return;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                org.json.JSONObject info = new org.json.JSONObject(out.toString("UTF-8"));
                if (info.optInt("version_code") <= BuildConfig.VERSION_CODE) return;
                String notes = info.optString("summary", info.optString("notes", ""));
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    new AlertDialog.Builder(activity)
                            .setTitle("Yuki Wallet " + info.optString("version_name") + " is available")
                            .setMessage(notes.isEmpty() ? "A new version of the app is ready to install." : notes)
                            .setPositiveButton("Update", (d, w) -> start(info.optString("url")))
                            .setNegativeButton("Later", null)
                            .show();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    /** Downloads and installs the APK at url. Must run on the UI thread. */
    void start(String url) {
        if (running) return;
        report("starting", 0, 0, 0);
        Uri uri = Uri.parse(url);
        if (uri.getHost() == null || !uri.getHost().equals(allowedHost)) return;

        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow updates")
                    .setMessage("To install updates, Android needs you to allow Yuki Wallet to install apps. "
                            + "Turn on \"Allow from this source\", then come back.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        urlAwaitingPermission = url;
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            urlAwaitingPermission = null;
                            toast("Couldn't open settings");
                            report("failed", 0, 0, 0);
                        }
                    })
                    .setNegativeButton("Cancel", (d, w) -> report("cancelled", 0, 0, 0))
                    .show();
            return;
        }
        download(url);
    }

    /** Call from Activity.onResume: continues the update after the user returns from settings. */
    void onResume() {
        if (urlAwaitingPermission == null) return;
        String url = urlAwaitingPermission;
        urlAwaitingPermission = null;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast("Update cancelled: install permission not granted");
            report("cancelled", 0, 0, 0);
            return;
        }
        download(url);
    }

    private void download(String url) {
        running = true;
        cancelled = false;

        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, activity.getResources().getDisplayMetrics());
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        TextView label = new TextView(activity);
        label.setText("Starting download…");
        label.setPadding(0, pad / 2, 0, 0);
        box.addView(bar);
        box.addView(label);

        AlertDialog dialog = listener != null ? null : new AlertDialog.Builder(activity)
                .setTitle("Updating Yuki Wallet")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled = true)
                .show();

        new Thread(() -> {
            File dir = new File(activity.getCacheDir(), "updates");
            File apk = new File(dir, "yukiwallet-update.apk");
            HttpURLConnection conn = null;
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("no cache dir");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[32 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled) throw new InterruptedException();
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                long doneKb = done / 1024, totalKb = total / 1024;
                                activity.runOnUiThread(() -> {
                                    report("progress", pct, doneKb, totalKb);
                                    bar.setIndeterminate(false);
                                    bar.setProgress(pct);
                                    label.setText(pct + "%  ·  " + doneKb + " / " + totalKb + " KB");
                                });
                            }
                        }
                    }
                }
                activity.runOnUiThread(() -> {
                    if (dialog != null) dialog.dismiss();
                    running = false;
                    report("installing", 100, 0, 0);
                    install(apk);
                });
            } catch (InterruptedException e) {
                apk.delete();
                activity.runOnUiThread(() -> { running = false; report("cancelled", 0, 0, 0); });
            } catch (Exception e) {
                apk.delete();
                activity.runOnUiThread(() -> {
                    if (dialog != null) dialog.dismiss();
                    running = false;
                    report("failed", 0, 0, 0);
                    if (listener == null) toast("Update failed. Check your connection and try again.");
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void install(File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("No installer found on this device");
        }
    }

    private void report(String state, int pct, long doneKb, long totalKb) {
        if (listener != null) listener.onUpdate(state, pct, doneKb, totalKb);
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
    }
}
