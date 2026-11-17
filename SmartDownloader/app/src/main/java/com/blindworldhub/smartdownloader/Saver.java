package com.blindworldhub.smartdownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Puts a finished file in the phone's Download folder, under "Smart Downloader" (a playlist gets its own folder in
 * it), where every file manager and music or video player finds it. Android 10 and newer: through Android's media
 * store (no storage permission needed); Android 8 and 9: straight into the folder.
 */
final class Saver {
    private Saver() { }

    static final String FOLDER = "Smart Downloader";

    static final class Saved {
        final Uri uri;
        final String name, where, mime;
        Saved(Uri uri, String name, String where, String mime) { this.uri = uri; this.name = name; this.where = where; this.mime = mime; }
    }

    static Saved save(Context c, File f, String subFolder) throws Exception {
        String mime = mime(f.getName());
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + (subFolder == null || subFolder.length() == 0 ? "" : "/" + subFolder);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("the media store did not make the file");
            try {
                OutputStream out = cr.openOutputStream(uri);
                if (out == null) throw new Exception("could not write the file");
                copy(new FileInputStream(f), out);
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } catch (Exception e) {
                try { cr.delete(uri, null, null); } catch (Throwable ignored) { }
                throw e;
            }
            return new Saved(uri, f.getName(), rel, mime);
        }
        File dir = new File(Environment.getExternalStorageDirectory(), rel);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("could not make the folder " + dir);
        File to = unique(dir, f.getName());
        if (!f.renameTo(to)) copy(new FileInputStream(f), new FileOutputStream(to));
        MediaScannerConnection.scanFile(c, new String[] { to.getAbsolutePath() }, new String[] { mime }, null);
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".files", to);
        return new Saved(uri, to.getName(), rel, mime);
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + " " + System.currentTimeMillis() + ext);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
            out.close();
        }
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".opus") || n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    /** A folder name that every phone accepts. */
    static String safeFolder(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (t.startsWith(".")) t = t.substring(1).trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.length() == 0 ? "Playlist" : t;
    }
}
