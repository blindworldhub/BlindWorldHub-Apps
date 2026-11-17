package com.blindworldhub.smartdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * New versions of Smart Downloader. The Blind World Hub server is asked first (the owner publishes there from his
 * admin page), and the website when the server does not answer. Update downloads the new version inside the app,
 * checks it is whole and signed by the same key as this app, and only then opens Android's installer.
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

    private static void show(final Activity a, final Update u, boolean canSkip) {
        if (a.isFinishing()) return;
        StringBuilder msg = new StringBuilder();
        if (u.whatsNew != null && u.whatsNew.trim().length() > 0) msg.append("What's new:\n").append(u.whatsNew.trim()).append("\n\n");
        if (u.size > 0) msg.append("Size: ").append(Math.max(1, Math.round(u.size / (1024.0 * 1024.0)))).append(" MB. ");
        msg.append("Press Update: the new version is downloaded, checked to be from Blind World Hub, and then Android asks you to install it. Your downloads are kept.");
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Smart Downloader " + u.version + " is available")
                .setMessage(msg.toString())
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { download(a, u); }
                })
                .setNegativeButton("Later", null);
        if (canSkip) b.setNeutralButton("Skip this version", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { prefs(a).edit().putInt("skipped", u.versionCode).apply(); }
        });
        b.show();
    }

    private static File apkFile(Context c) {
        return new File(new File(c.getCacheDir(), "update"), "SmartDownloader-update.apk");
    }

    private static void download(final Activity a, final Update u) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            toast(a, "Allow Smart Downloader to install apps, then press Update again.");
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Throwable t) {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u.url))); } catch (Throwable ignored) { }
            }
            return;
        }
        toast(a, "Downloading the new version...");
        new Thread(new Runnable() {
            @Override public void run() {
                String problem = null;
                File f = apkFile(a);
                try {
                    f.getParentFile().mkdirs();
                    save(u.url, f);
                    if (u.sha256 != null && u.sha256.length() == 64 && !u.sha256.equalsIgnoreCase(sha256(f))) problem = "fingerprint";
                    else if (!sameSigner(a, f)) problem = "signer";
                } catch (Throwable t) {
                    problem = "download: " + t.getMessage();
                }
                final String p = problem;
                AppLog.info(a, "Update", "version " + u.version + ": " + (p == null ? "downloaded and checked" : p));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (p == null) install(a);
                        else if (p.startsWith("download")) toast(a, "The download failed. Please try again.");
                        else { apkFile(a).delete(); toast(a, "The downloaded file is not from Blind World Hub, so it was not installed."); }
                    }
                });
            }
        }, "update-download").start();
    }

    private static void install(Activity a) {
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apkFile(a));
            a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            AppLog.error(a, "Update", "the installer did not open", t);
            toast(a, "The installer did not open. Please try again.");
        }
    }

    @SuppressWarnings("deprecation")
    static boolean sameSigner(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo other = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
            PackageInfo me = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            if (other == null || !c.getPackageName().equals(other.packageName)) return false;
            Set<String> a = prints(other.signatures), b = prints(me.signatures);
            if (Build.VERSION.SDK_INT >= 28 && a.isEmpty()) {
                PackageInfo o2 = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo m2 = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (o2 != null && o2.signingInfo != null && m2.signingInfo != null) {
                    a = prints(o2.signingInfo.getApkContentsSigners());
                    b = prints(m2.signingInfo.getApkContentsSigners());
                }
            }
            return !a.isEmpty() && a.equals(b);
        } catch (Throwable t) {
            AppLog.error(c, "Update", "could not read the new version's signature", t);
            return false;
        }
    }

    private static Set<String> prints(Signature[] sigs) throws Exception {
        Set<String> out = new HashSet<String>();
        if (sigs == null) return out;
        for (Signature s : sigs) out.add(Arrays.toString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        return out;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void save(String url, File to) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", Server.UA);
        conn.setInstanceFollowRedirects(true);
        InputStream in = null;
        FileOutputStream out = new FileOutputStream(to);
        try {
            if (conn.getResponseCode() != 200) throw new Exception("http " + conn.getResponseCode());
            in = conn.getInputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            out.close();
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            conn.disconnect();
        }
    }

    static void toast(Context c, String s) { Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_LONG).show(); }
}
