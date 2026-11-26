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
        final String path;          // Android 8 and 9: the file itself (null on newer Android)
        Saved(Uri uri, String name, String where, String mime, String path) {
            this.uri = uri; this.name = name; this.where = where; this.mime = mime; this.path = path;
        }
        boolean isSubtitle() { return isSubtitleName(name); }
    }

    static boolean isSubtitleName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass");
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
            return new Saved(uri, f.getName(), rel, mime, null);
        }
        File dir = new File(Environment.getExternalStorageDirectory(), rel);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("could not make the folder " + dir);
        File to = unique(dir, f.getName());
        if (!f.renameTo(to)) copy(new FileInputStream(f), new FileOutputStream(to));
        MediaScannerConnection.scanFile(c, new String[] { to.getAbsolutePath() }, new String[] { mime }, null);
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".files", to);
        return new Saved(uri, to.getName(), rel, mime, to.getAbsolutePath());
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
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".srt")) return "application/x-subrip";
        if (n.endsWith(".vtt")) return "text/vtt";
        return "application/octet-stream";
    }

    /**
     * Gives a saved file a new name (the person's words, the file type kept). Returns the file with its new name,
     * or throws with a reason.
     */
    static Saved rename(Context c, Saved s, String wanted) throws Exception {
        int dot = s.name.lastIndexOf('.');
        String ext = dot > 0 ? s.name.substring(dot) : "";
        String base = wanted == null ? "" : wanted.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (base.toLowerCase(Locale.US).endsWith(ext.toLowerCase(Locale.US)) && ext.length() > 0) base = base.substring(0, base.length() - ext.length()).trim();
        while (base.startsWith(".")) base = base.substring(1).trim();
        if (base.length() == 0) throw new Exception("Please write a name.");
        if (base.length() > 150) base = base.substring(0, 150).trim();
        String name = base + ext;
        if (name.equals(s.name)) return s;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (c.getContentResolver().update(s.uri, v, null, null) < 1) throw new Exception("The file could not be renamed. Was it deleted or moved?");
            String now = name;
            try {
                android.database.Cursor cur = c.getContentResolver().query(s.uri, new String[] { MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null);
                if (cur != null) { try { if (cur.moveToFirst()) now = cur.getString(0); } finally { cur.close(); } }
            } catch (Throwable ignored) { }
            return new Saved(s.uri, now, s.where, s.mime, null);
        }
        if (s.path == null) throw new Exception("The file could not be renamed.");
        File from = new File(s.path);
        if (!from.exists()) throw new Exception("The file is not there any more. Was it deleted or moved?");
        File to = new File(from.getParentFile(), name);
        if (to.exists()) throw new Exception("A file with this name is already there. Please choose another name.");
        if (!from.renameTo(to)) throw new Exception("The file could not be renamed.");
        MediaScannerConnection.scanFile(c, new String[] { from.getAbsolutePath(), to.getAbsolutePath() }, null, null);
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".files", to);
        return new Saved(uri, to.getName(), s.where, s.mime, to.getAbsolutePath());
    }

    /** A folder name that every phone accepts. */
    static String safeFolder(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (t.startsWith(".")) t = t.substring(1).trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.length() == 0 ? "Playlist" : t;
    }
}
