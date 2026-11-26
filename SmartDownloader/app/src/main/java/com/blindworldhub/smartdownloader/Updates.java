package com.blindworldhub.smartdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * New versions of Smart Downloader. The Blind World Hub server is asked first (the owner publishes there from his
 * admin page), and the website when the server does not answer. Update downloads the new version inside the app (the
 * percent is said as it goes), checks it, and opens Android's installer; the browser is offered when that fails.
 */
final class Updates {
    private Updates() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Update {
        String version, url, whatsNew, sha256;
        int versionCode;
        long size;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    static int myVersionCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String myVersionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    /** A newer version, or null. Background thread. */
    static Update check(Context c) {
        try {
            return parse(c, Net.get(Server.URL + "/v1/update/" + Server.APP));
        } catch (Throwable t) {
            AppLog.info(c, "Update", "the server did not answer (" + t.getMessage() + "); asking the website");
        }
        try {
            return parse(c, Net.get(Server.WEBSITE_UPDATE));
        } catch (Throwable t) {
            AppLog.info(c, "Update", "the website did not answer: " + t.getMessage());
            return null;
        }
    }

    private static Update parse(Context c, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        int code = o.optInt("versionCode", 0);
        if (code <= myVersionCode(c)) return null;
        Update u = new Update();
        u.version = o.optString("version", "");
        u.versionCode = code;
        u.url = o.optString("url", "");
        u.size = o.optLong("size", 0);
        u.whatsNew = o.optString("whatsNew", "");
        u.sha256 = o.optString("sha256", "");
        return u.url.startsWith("https://") ? u : null;
    }

    /** When the app opens: at most twice a day; a pop-up only when a new version is there. */
    static void checkOnOpen(final Activity a) {
        new Thread(new Runnable() { @Override public void run() { cleanOld(a); } }, "update-clean").start();
        if (System.currentTimeMillis() - prefs(a).getLong("last", 0) < 12L * 3600 * 1000) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final Update u = check(a);
                prefs(a).edit().putLong("last", System.currentTimeMillis()).apply();
                if (u == null || prefs(a).getInt("skipped", -1) == u.versionCode) return;
                MAIN.post(new Runnable() { @Override public void run() { show(a, u, true); } });
            }
        }, "update-check").start();
    }

    /** From the background check: a notification once per new version. Background thread. */
    @SuppressWarnings("deprecation")
    static void checkQuietly(Context c) {
        Update u = check(c);
        if (u == null || prefs(c).getInt("noted", -1) == u.versionCode || prefs(c).getInt("skipped", -1) == u.versionCode) return;
        prefs(c).edit().putInt("noted", u.versionCode).apply();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("updates", "New versions", NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "updates") : new Notification.Builder(c);
        Intent open = new Intent(c, MainActivity.class).putExtra("checkUpdate", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setSmallIcon(R.drawable.ic_stat_download).setContentTitle("Smart Downloader " + u.version + " is available")
                .setContentText("Open Smart Downloader to update.").setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 9, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try { nm.notify(9, b.build()); } catch (Throwable ignored) { }
    }

    /** The menu's Check for updates: always says the result. */
    static void checkNow(final Activity a) {
        toast(a, "Checking for a new version...");
        new Thread(new Runnable() {
            @Override public void run() {
                final Update u = check(a);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (u != null) show(a, u, false);
                        else toast(a, "You have the newest version of Smart Downloader.");
                    }
                });
            }
        }, "update-now").start();
    }

    private static void show(final Activity a, final Update u, final boolean canSkip) {
        if (a.isFinishing()) return;
        StringBuilder msg = new StringBuilder();
        if (u.whatsNew != null && u.whatsNew.trim().length() > 0) msg.append("What's new:\n").append(u.whatsNew.trim()).append("\n\n");
        if (u.size > 0) msg.append("Size: ").append(Math.max(1, Math.round(u.size / (1024.0 * 1024.0)))).append(" MB. ");
        msg.append("Press Update: the new version is downloaded here in Smart Downloader, and the percent is said as it goes. "
                + "Then Android's installer opens: press Update or Install. Your downloads are kept.");
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Smart Downloader " + u.version + " is available")
                .setMessage(msg.toString())
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startDownload(a, u); }
                })
                .setNegativeButton("Later", null);
        if (canSkip) b.setNeutralButton("Skip this version", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { prefs(a).edit().putInt("skipped", u.versionCode).apply(); }
        });
        b.show();
    }

    // ------------------------------------------------------------------------------------------------ the download

    /** A checked download waiting for the person to allow installs, or for the app to be open again. */
    private static File pendingApk;
    private static boolean askedAllow;
    private static volatile boolean busy;
    private static final long SAY_GAP_MS = 500;

    static File downloadDir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** The download inside the app, in a box that stays on the screen and says each percent. */
    private static void startDownload(final Activity a, final Update u) {
        if (busy) { toast(a, "The new version is already downloading."); return; }
        if (a.isFinishing()) return;
        busy = true;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final String head = "Downloading Smart Downloader " + u.version + ": ";
        final AlertDialog box = new AlertDialog.Builder(a)
                .setTitle("Updating Smart Downloader")
                .setMessage(head + "0 percent")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) { stop.set(true); }
                })
                .setCancelable(false)
                .create();
        box.show();
        final int[] shown = { 0 }, said = { -1 };
        final long[] saidAt = { 0 };
        final boolean[] waiting = { true };
        final Runnable say = new Runnable() {
            @Override public void run() {
                waiting[0] = false;
                if (!box.isShowing() || shown[0] == said[0]) return;
                said[0] = shown[0];
                saidAt[0] = android.os.SystemClock.uptimeMillis();
                View v = box.findViewById(android.R.id.message);
                if (v == null && box.getWindow() != null) v = box.getWindow().getDecorView();
                if (v != null) v.announceForAccessibility(shown[0] + " percent");
            }
        };
        MAIN.postDelayed(say, 700);
        new Thread(new Runnable() {
            @Override public void run() {
                File apk = fetch(a, u, new Progress() {
                    @Override public boolean on(final int percent) {
                        if (percent >= 0) MAIN.post(new Runnable() {
                            @Override public void run() {
                                if (percent == shown[0] || !box.isShowing()) return;
                                shown[0] = percent;
                                box.setMessage(head + percent + " percent");
                                if (waiting[0]) return;
                                long wait = saidAt[0] + SAY_GAP_MS - android.os.SystemClock.uptimeMillis();
                                waiting[0] = true;
                                MAIN.postDelayed(say, Math.max(0, wait));
                            }
                        });
                        return !stop.get();
                    }
                });
                final File done = apk != null && isOurUpdate(a, apk, u) ? apk : null;
                if (apk != null && done == null) apk.delete();
                busy = false;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        MAIN.removeCallbacks(say);
                        try { box.dismiss(); } catch (Throwable ignored) { }
                        if (stop.get()) toast(a, "The download was cancelled.");
                        else if (done == null) { AppLog.info(a, "Update", "the download of " + u.version + " did not finish or was not right"); failed(a, u); }
                        else if (a.isFinishing()) pendingApk = done;
                        else { toast(a, "100 percent. Download complete."); install(a, done); }
                    }
                });
            }
        }, "update-download").start();
    }

    interface Progress { boolean on(int percent); }

    /** Downloads the new version and checks its checksum. The file, or null on any problem. Background thread. */
    private static File fetch(Context c, Update u, Progress p) {
        File out = new File(downloadDir(c), "SmartDownloader-" + u.versionCode + ".apk");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            File[] old = downloadDir(c).listFiles();
            if (old != null) for (File f : old) if (!f.equals(out)) f.delete();
            conn = (HttpURLConnection) new URL(u.url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", Server.UA);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return null;
            long total = u.size > 0 ? u.size : conn.getContentLength();
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            long got = 0;
            int last = -2, n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) Math.min(100, got * 100 / total) : -1;
                if (pct != last) {
                    last = pct;
                    if (p != null && !p.on(pct)) { fos.close(); fos = null; out.delete(); return null; }
                }
            }
            fos.close();
            fos = null;
            if (u.sha256 != null && u.sha256.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                if (!sb.toString().equalsIgnoreCase(u.sha256)) { out.delete(); return null; }
            }
            return out;
        } catch (Throwable t) {
            AppLog.error(c, "Update", "download failed", t);
            out.delete();
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) { }
        }
    }

    /** The file is Smart Downloader itself, of the version offered. */
    @SuppressWarnings("deprecation")
    private static boolean isOurUpdate(Context c, File apk, Update u) {
        try {
            FileInputStream in = new FileInputStream(apk);
            byte[] h = new byte[4];
            int n;
            try { n = in.read(h); } finally { in.close(); }
            if (n != 4 || h[0] != 'P' || h[1] != 'K' || h[2] != 3 || h[3] != 4) return false;
            PackageInfo p = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (p == null) return u.sha256 != null && u.sha256.length() > 0;
            long code = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            return c.getPackageName().equals(p.packageName) && code == u.versionCode;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void failed(final Activity a, final Update u) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a)
                .setTitle("The download did not finish")
                .setMessage("Check your internet and press Try again. Or press Download with browser: the browser "
                        + "downloads it, then open the downloaded file and press Install or Update.")
                .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) { startDownload(a, u); }
                })
                .setNeutralButton("Download with browser", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) {
                        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u.url))); }
                        catch (Throwable t) { toast(a, "No browser opened. Please download the new version from the Blind World Hub website."); }
                    }
                })
                .setNegativeButton("Cancel", null)
                .create().show();
    }

    /** The app is on the screen again: a checked download waiting for "Install unknown apps" goes on now. */
    static void resumed(final Activity a) {
        final File apk = pendingApk;
        if (apk == null || busy) return;
        if (!apk.exists()) { pendingApk = null; return; }
        if (allowed(a)) { pendingApk = null; install(a, apk); return; }
        if (askedAllow) return;
        askedAllow = true;
        new AlertDialog.Builder(a)
                .setTitle("One step to finish the update")
                .setMessage("The new version is downloaded. To install it, Android needs Install unknown apps turned "
                        + "on for Smart Downloader (Allow from this source). Press Open setting, turn it on and come back.")
                .setPositiveButton("Open setting", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) { askedAllow = false; openAllow(a); }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) { pendingApk = null; askedAllow = false; }
                })
                .setCancelable(false)
                .create().show();
    }

    private static boolean allowed(Context c) {
        try { return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls(); } catch (Throwable t) { return true; }
    }

    private static void openAllow(Activity a) {
        try {
            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable t) {
            try { a.startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)); }
            catch (Throwable ignored) { toast(a, "Could not open the setting. Open Settings, Apps, Smart Downloader, Install unknown apps."); }
        }
    }

    private static void install(Activity a, File apk) {
        try {
            if (!allowed(a)) {
                pendingApk = apk;
                askedAllow = true;
                toast(a, "Turn on Allow from this source for Smart Downloader, then come back. The update goes on by itself.");
                openAllow(a);
                return;
            }
            pendingApk = null;
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ApkProvider.uriFor(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            toast(a, "Press Update or Install to finish.");
        } catch (Throwable t) {
            AppLog.error(a, "Update", "could not open the installer", t);
            toast(a, "Could not open the installer.");
        }
    }

    /** A downloaded version that is already installed (or older) is deleted. */
    static void cleanOld(Context c) {
        try {
            if (busy || pendingApk != null) return;
            int mine = myVersionCode(c);
            File[] fs = downloadDir(c).listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName();
                int code = -1;
                if (n.startsWith("SmartDownloader-") && n.endsWith(".apk")) {
                    try { code = Integer.parseInt(n.substring(16, n.length() - 4)); } catch (Exception ignored) { }
                }
                if (code <= mine) f.delete();
            }
        } catch (Throwable ignored) { }
    }

    static void toast(Context c, String s) { Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_LONG).show(); }
}
