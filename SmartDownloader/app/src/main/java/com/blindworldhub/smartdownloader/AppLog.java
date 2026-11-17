package com.blindworldhub.smartdownloader;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A short log in the app's own folder, sent only inside a problem report the person chose to send. */
final class AppLog {
    private AppLog() { }

    private static final long MAX = 400 * 1024;

    static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "log.txt"); }

    static synchronized void info(Context c, String tag, String msg) {
        write(c, tag, msg);
    }

    static synchronized void error(Context c, String tag, String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        write(c, tag, msg + (t == null ? "" : "\n" + sw));
    }

    private static void write(Context c, String tag, String msg) {
        try {
            Log.i("SmartDownloader", tag + ": " + msg);
            File f = file(c);
            if (f.length() > MAX) {
                String keep = tail(f, (int) (MAX / 2));
                FileOutputStream o = new FileOutputStream(f, false);
                o.write(keep.getBytes("UTF-8"));
                o.close();
            }
            FileOutputStream o = new FileOutputStream(f, true);
            String time = new SimpleDateFormat("dd-MM HH:mm:ss", Locale.US).format(new Date());
            o.write((time + " " + tag + ": " + msg + "\n").getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) { }
    }

    static String tail(File f, int bytes) {
        try {
            if (!f.exists()) return "";
            RandomAccessFile r = new RandomAccessFile(f, "r");
            long start = Math.max(0, r.length() - bytes);
            byte[] b = new byte[(int) (r.length() - start)];
            r.seek(start);
            r.readFully(b);
            r.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    static String header(Context c) {
        return "Smart Downloader " + Updates.myVersionName(c) + " (" + Updates.myVersionCode(c) + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + "), " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + ", engine " + Engine.version(c) + "\n";
    }
}
