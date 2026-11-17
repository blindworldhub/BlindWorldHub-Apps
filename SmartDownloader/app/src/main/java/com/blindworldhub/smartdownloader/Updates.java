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
import android.widget.Toast;


import org.json.JSONObject;


/**
 * New versions of Smart Downloader. The Blind World Hub server is asked first (the owner publishes there from his
 * admin page), and the website when the server does not answer. Update opens the new version in the browser.
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
        msg.append("Press Update: the new version downloads in your browser; then open the downloaded file to install it. Your downloads are kept.");
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

    /**
     * The new version is opened in the browser, which downloads it; the person then opens the downloaded file and
     * Android installs it. The app never installs anything itself (Play Protect blocks apps that do).
     */
    private static void download(Activity a, Update u) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast(a, "The new version is downloading in your browser. When it is finished, open the downloaded file to install it.");
        } catch (Throwable t) {
            AppLog.error(a, "Update", "the browser did not open", t);
            toast(a, "No browser opened. Please download the new version from the Blind World Hub website.");
        }
    }

    static void toast(Context c, String s) { Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_LONG).show(); }
}
