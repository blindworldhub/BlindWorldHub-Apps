package com.blindworldhub.smartdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads one after the other, even with the app closed or the screen off. The notification shows the progress
 * (percent, megabytes, speed and the time left); a finished download gets its own notification, which opens the file.
 * A failure says what went wrong simply. A download can be paused and resumed: yt-dlp goes on from the part it
 * already has. The list of downloads (also the finished ones: the history) is kept in a file inside the app.
 */
public final class DownloadService extends Service {

    static final String ACTION_START = "start";
    private static final String CH_WORK = "downloading", CH_DONE = "finished";
    private static final int NOTE_WORK = 1;
    private static final int KEEP = 300;                    // the newest downloads kept in the list

    interface Listener { void onJobs(); }

    private static final List<Job> JOBS = new ArrayList<Job>();
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<Job>();
    private static volatile Job current;
    private static volatile boolean running;
    private static volatile boolean loaded;
    private static Context app;

    private PowerManager.WakeLock wake;
    private long lastNote;

    // ------------------------------------------------------------------------------------------------ the list, kept

    private static File listFile(Context c) { return new File(c.getFilesDir(), "downloads.json"); }

    /** Reads the kept list once (when the app starts). */
    static synchronized void load(Context c) {
        app = c.getApplicationContext();
        if (loaded) return;
        loaded = true;
        try {
            File f = listFile(app);
            if (!f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; } finally { in.close(); }
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            synchronized (JOBS) {
                for (int i = 0; i < a.length(); i++) {
                    if (a.optJSONObject(i) != null) JOBS.add(Job.fromJson(a.getJSONObject(i)));
                }
            }
        } catch (Throwable t) {
            AppLog.error(app, "Download", "could not read the list of downloads", t);
        }
    }

    /** Writes the list (in the background, the newest state). */
    private static void save() {
        final Context c = app;
        if (c == null) return;
        final String text;
        try {
            JSONArray a = new JSONArray();
            synchronized (JOBS) {
                while (JOBS.size() > KEEP) {
                    Job last = JOBS.get(JOBS.size() - 1);
                    if (!last.finished()) break;
                    JOBS.remove(JOBS.size() - 1);
                }
                for (Job j : JOBS) a.put(j.toJson());
            }
            text = a.toString();
        } catch (Throwable t) {
            AppLog.error(c, "Download", "could not keep the list of downloads", t);
            return;
        }
        SAVER.post(new Runnable() {
            @Override public void run() {
                try {
                    File f = listFile(c), tmp = new File(c.getFilesDir(), "downloads.json.new");
                    FileOutputStream out = new FileOutputStream(tmp);
                    try { out.write(text.getBytes("UTF-8")); out.getFD().sync(); } finally { out.close(); }
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                } catch (Throwable t) {
                    AppLog.error(c, "Download", "could not write the list of downloads", t);
                }
            }
        });
    }

    private static final Handler SAVER;
    static {
        android.os.HandlerThread ht = new android.os.HandlerThread("downloads-list");
        ht.start();
        SAVER = new Handler(ht.getLooper());
    }

    // ------------------------------------------------------------------------------------------------ for the screen

    static List<Job> jobs() { synchronized (JOBS) { return new ArrayList<Job>(JOBS); } }
    static boolean busy() { return running || !QUEUE.isEmpty(); }
    static void listen(Listener l) { synchronized (LISTENERS) { if (!LISTENERS.contains(l)) LISTENERS.add(l); } }
    static void unlisten(Listener l) { synchronized (LISTENERS) { LISTENERS.remove(l); } }

    /** A finished, waiting or paused download of the same link with the same choice, or null. */
    static Job same(String url, String choice) {
        String u = Job.sameLink(url);
        synchronized (JOBS) {
            for (Job j : JOBS) {
                if (j.choice.equals(choice) && Job.sameLink(j.url).equals(u) && (j.state != Job.FAILED && j.state != Job.CANCELLED)) return j;
            }
        }
        return null;
    }

    static void remove(Context c, Job j) {
        if (!j.finished() && j.state != Job.PAUSED) return;
        if (j.state == Job.PAUSED) deleteAll(tmpDir(c, j));
        synchronized (JOBS) { JOBS.remove(j); }
        changed(true);
    }

    /** Removes the finished downloads from the list (their files stay). */
    static int clearFinished() {
        int n = 0;
        synchronized (JOBS) {
            for (Job j : new ArrayList<Job>(JOBS)) if (j.finished()) { JOBS.remove(j); n++; }
        }
        changed(true);
        return n;
    }

    /** Something changed: the screen is told; with keep, the list is written too. */
    static void changed(boolean keep) {
        if (keep) save();
        MAIN.post(new Runnable() {
            @Override public void run() {
                List<Listener> copy;
                synchronized (LISTENERS) { copy = new ArrayList<Listener>(LISTENERS); }
                for (Listener l : copy) l.onJobs();
            }
        });
    }

    static void add(Context c, Job j) {
        synchronized (JOBS) { JOBS.add(0, j); }
        QUEUE.add(j);
        changed(true);
        startService(c);
    }

    private static void startService(Context c) {
        Intent i = new Intent(c, DownloadService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void cancel(Context c, Job j) {
        if (j.finished()) return;
        boolean wasWaiting = QUEUE.remove(j);
        boolean wasPaused = j.state == Job.PAUSED;
        j.state = Job.CANCELLED;
        if (!wasWaiting && !wasPaused) Engine.cancel(j.id);
        if (wasPaused) deleteAll(tmpDir(c, j));
        changed(true);
    }

    /** Stops a download for now; Resume goes on from what it already has. */
    static void pause(Context c, Job j) {
        if (j.state != Job.WAITING && j.state != Job.RUNNING) return;
        boolean wasWaiting = QUEUE.remove(j);
        j.state = Job.PAUSED;
        j.error = "";
        j.speed = "";
        j.eta = -1;
        if (!wasWaiting) Engine.cancel(j.id);
        changed(true);
    }

    static void resume(Context c, Job j) {
        if (j.state != Job.PAUSED) return;
        j.state = Job.WAITING;
        j.error = "";
        j.step = "Waiting";
        QUEUE.add(j);
        changed(true);
        startService(c);
    }

    private static File tmpDir(Context c, Job j) { return new File(new File(c.getCacheDir(), "downloads"), j.id); }

    /** Free space where the download is made (the app's own storage). */
    static long freeBytes(Context c) {
        try { return new StatFs(c.getCacheDir().getAbsolutePath()).getAvailableBytes(); } catch (Throwable t) { return -1; }
    }

    // ------------------------------------------------------------------------------------------------ the service

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        load(this);
        channels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(working("Starting the download..."));
        synchronized (DownloadService.class) {
            if (!running) {
                running = true;
                new Thread(new Runnable() { @Override public void run() { work(); } }, "downloads").start();
            }
        }
        return START_NOT_STICKY;
    }

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTE_WORK, n);
        } catch (Throwable t) {
            AppLog.error(this, "Download", "could not show the download notification", t);
        }
    }

    /** Android 15 gives data downloads 6 hours a day; when they are used up, pause politely (Resume goes on later). */
    @Override
    public void onTimeout(int startId, int fgsType) {
        stopAll("Android paused the downloads for today. Press Resume later to go on.");
    }

    @Override
    public void onTimeout(int startId) {
        stopAll("Android paused the downloads. Press Resume later to go on.");
    }

    private void stopAll(String why) {
        Job cur = current;
        Job w;
        while ((w = QUEUE.poll()) != null) { w.state = Job.PAUSED; w.error = why; }
        if (cur != null && !cur.finished()) { cur.state = Job.PAUSED; cur.error = why; Engine.cancel(cur.id); }
        changed(true);
        stopForeground(true);
        stopSelf();
    }

    private void work() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        try {
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartDownloader:download");
                wake.setReferenceCounted(false);
                wake.acquire(6L * 3600 * 1000);
            }
            while (true) {
                Job j = QUEUE.poll();
                if (j == null) break;
                if (j.state != Job.WAITING) continue;
                current = j;
                run(j);
                current = null;
                changed(true);
                done(j);
            }
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
            synchronized (DownloadService.class) { running = false; }
            MAIN.post(new Runnable() {
                @Override public void run() {
                    if (running || !QUEUE.isEmpty()) {
                        startService(new Intent(DownloadService.this, DownloadService.class).setAction(ACTION_START));
                        return;
                    }
                    stopForeground(true);
                    stopSelf();
                }
            });
        }
    }

    private static final Pattern ITEM = Pattern.compile("Downloading (?:item|video) (\\d+) of (\\d+)");
    private static final Pattern PROGRESS = Pattern.compile(
            "\\[download\\]\\s+([\\d.]+)%\\s+of\\s+~?\\s*([\\d.]+)\\s*([KMGT]?i?B)(?:\\s+at\\s+([\\d.]+)\\s*([KMGT]?i?B)/s)?(?:\\s+ETA\\s+([\\d:]+))?");

    /** Options that only add extras (the cover picture, subtitles, the title in the file); left out on the second try. */
    private static final List<String> EXTRAS = Arrays.asList("--embed-thumbnail", "--write-subs", "--write-auto-subs", "--embed-metadata");
    private static final List<String> EXTRAS_WITH_VALUE = Arrays.asList("--convert-thumbnails", "--sub-langs", "--convert-subs");

    private static List<String> withoutExtras(List<String> args) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (EXTRAS.contains(a)) continue;
            if (EXTRAS_WITH_VALUE.contains(a)) { i++; continue; }
            out.add(a);
        }
        return out;
    }

    private static boolean hasExtras(List<String> args) {
        for (String a : args) if (EXTRAS.contains(a) || EXTRAS_WITH_VALUE.contains(a)) return true;
        return false;
    }

    private YoutubeDLRequest request(Job j, File tmp, List<String> args) {
        YoutubeDLRequest r = new YoutubeDLRequest(j.url);
        r.addCommands(args);
        Login.addCookies(this, r, j.url);
        r.addOption("-o", tmp.getAbsolutePath() + "/" + (j.playlist ? "%(playlist_index)03d - %(title).120B.%(ext)s" : "%(title).150B.%(ext)s"));
        r.addOption(j.playlist ? "--yes-playlist" : "--no-playlist");
        if (j.playlist) r.addOption("--ignore-errors");
        r.addOption("--no-mtime");
        r.addOption("--windows-filenames");
        r.addOption("--continue");
        r.addOption("--retries", "10");
        r.addOption("--fragment-retries", "10");
        r.addOption("--socket-timeout", "30");
        r.addOption("--newline");
        return r;
    }

    private void run(final Job j) {
        j.state = Job.RUNNING;
        j.step = "Getting ready";
        j.error = "";
        changed(true);
        // wait until the engine is ready (the first start of the app)
        for (int i = 0; i < 600 && Engine.state() != Engine.READY && Engine.state() != Engine.FAILED && j.state == Job.RUNNING; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        if (j.state != Job.RUNNING) return;
        if (Engine.state() != Engine.READY) {
            j.state = Job.FAILED;
            j.error = "The downloader is not ready. Please close the app, open it again, and try again.";
            return;
        }
        File tmp = tmpDir(this, j);
        tmp.mkdirs();                          // what a paused download already has stays here
        List<String> args = j.args;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                j.step = "Downloading";
                changed(false);
                Engine.download(request(j, tmp, args), j.id, new Engine.Progress() {
                    @Override public void on(float percent, String line) { progress(j, percent, line); }
                });
                break;
            } catch (Throwable t) {
                if (j.state == Job.PAUSED) return;                                  // Resume goes on from here
                if (j.state == Job.CANCELLED) { deleteAll(tmp); return; }
                String msg = String.valueOf(t.getMessage());
                j.errorDetail = msg.length() > 6000 ? msg.substring(msg.length() - 6000) : msg;
                AppLog.error(this, "Download", "failed: " + j.url + (attempt > 0 ? " (second try, without the extras)" : ""), t);
                String low = msg.toLowerCase(Locale.US);
                boolean extrasFailed = low.contains("postprocess") || low.contains("thumbnail") || low.contains("subtitle")
                        || low.contains("metadata") || low.contains("conversion failed");
                if (attempt == 0 && hasExtras(args) && extrasFailed && !low.contains("no space left")) {
                    // the cover picture or the subtitles did not work: the file itself is downloaded again without them
                    args = withoutExtras(args);
                    j.note = low.contains("subtitle") ? "The subtitles could not be saved." : "The cover picture could not be added.";
                    j.percent = -1;
                    continue;
                }
                if (!j.playlist || !hasFiles(tmp)) {
                    j.state = Job.FAILED;
                    if (j.error.length() == 0) j.error = Engine.plain(msg);
                    deleteAll(tmp);
                    return;
                }
                j.error = "Some videos of the playlist could not be downloaded.";
                break;
            }
        }
        if (j.state != Job.RUNNING) { if (j.state == Job.CANCELLED) deleteAll(tmp); return; }
        j.step = "Saving to the Download folder";
        j.percent = -1;
        j.speed = "";
        j.eta = -1;
        changed(false);
        File[] files = tmp.listFiles();
        if (files != null) Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        String sub = j.playlist ? Saver.safeFolder(j.title) : "";
        int failedSaves = 0;
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isDirectory() || n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") || n.contains(".part-frag")
                    || n.endsWith(".jpg") || n.endsWith(".webp") || n.endsWith(".png")) continue;
            if (j.choice.startsWith("3GP") && n.endsWith(".mov")) {
                File g = new File(f.getParentFile(), f.getName().substring(0, f.getName().length() - 4) + ".3gp");
                if (f.renameTo(g)) f = g;
            }
            try {
                Saver.Saved s = Saver.save(this, f, sub);
                j.saved.add(s);
            } catch (Throwable t) {
                failedSaves++;
                AppLog.error(this, "Download", "could not save " + f.getName(), t);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();                         // at once, so the phone never holds a big file twice for long
        }
        deleteAll(tmp);
        if (j.saved.isEmpty()) {
            j.state = Job.FAILED;
            j.error = failedSaves > 0 ? "The file could not be saved in the Download folder. Is the storage full?" : Engine.plain(j.errorDetail);
        } else {
            if (failedSaves > 0) j.error = failedSaves + " file(s) could not be saved.";
            j.state = Job.DONE;
        }
        AppLog.info(this, "Download", (j.state == Job.DONE ? "saved " + j.saved.size() + " file(s)" : "failed") + " for " + j.choice);
    }

    private void progress(Job j, float percent, String line) {
        if (j.state != Job.RUNNING) return;
        Matcher m = ITEM.matcher(line);
        Matcher p = PROGRESS.matcher(line);
        if (m.find()) {
            try { j.item = Integer.parseInt(m.group(1)); j.items = Integer.parseInt(m.group(2)); } catch (Exception ignored) { }
            j.percent = 0;
            j.step = "Downloading";
        } else if (line.startsWith("[Merger]")) {
            j.step = "Joining the video and the sound";
            j.speed = "";
            j.eta = -1;
        } else if (line.startsWith("[ExtractAudio]")) {
            j.step = "Making the " + j.choice.split(",")[0].trim() + " file";
            j.speed = "";
            j.eta = -1;
        } else if (line.startsWith("[VideoConvertor]")) {
            j.step = "Making the 3GP file. This takes a while";
            j.speed = "";
            j.eta = -1;
        } else if (line.startsWith("[EmbedThumbnail]")) {
            j.step = "Adding the cover picture";
        } else if (line.startsWith("[download]") && percent >= 0) {
            j.step = "Downloading";
            j.percent = percent;
            if (p.find()) {
                try {
                    double total = bytes(p.group(2), p.group(3));
                    j.total = mb(total);
                    j.got = mb(total * Math.min(100, Math.max(0, Double.parseDouble(p.group(1)))) / 100.0);
                    j.speed = p.group(4) != null ? mb(bytes(p.group(4), p.group(5))) + " per second" : "";
                    j.eta = p.group(6) != null ? seconds(p.group(6)) : -1;
                } catch (Exception ignored) { }
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastNote > 1000) {
            lastNote = now;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) try { nm.notify(NOTE_WORK, working(j.title + ": " + j.status())); } catch (Throwable ignored) { }
            changed(false);
        }
    }

    static double bytes(String n, String unit) {
        double v = Double.parseDouble(n);
        String u = unit.toUpperCase(Locale.US);
        double k = u.contains("I") ? 1024 : 1000;
        if (u.startsWith("K")) return v * k;
        if (u.startsWith("M")) return v * k * k;
        if (u.startsWith("G")) return v * k * k * k;
        if (u.startsWith("T")) return v * k * k * k * k;
        return v;
    }

    static String mb(double bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        if (mb >= 100) return Math.round(mb) + " MB";
        if (mb >= 0.1) return String.format(Locale.US, "%.1f MB", mb);
        return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    static long seconds(String eta) {
        String[] p = eta.split(":");
        long s = 0;
        for (String x : p) s = s * 60 + Long.parseLong(x);
        return s;
    }

    private static boolean hasFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && !n.endsWith(".part") && !n.endsWith(".ytdl") && !n.contains(".part-frag")) return true;
        }
        return false;
    }

    private static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] in = f.listFiles();
        if (in != null) for (File x : in) deleteAll(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------------------------------------------ notifications

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel w = new NotificationChannel(CH_WORK, "Downloading", NotificationManager.IMPORTANCE_LOW);
        w.setDescription("Shows the progress while files download.");
        w.setShowBadge(false);
        nm.createNotificationChannel(w);
        NotificationChannel d = new NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT);
        d.setDescription("Says when a download is saved, or why it failed.");
        nm.createNotificationChannel(d);
    }

    private PendingIntent openApp(int code) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private Notification working(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_WORK) : new Notification.Builder(this);
        Job j = current;
        b.setSmallIcon(R.drawable.ic_stat_download).setContentTitle("Smart Downloader").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(1));
        if (j != null && j.percent >= 0 && "Downloading".equals(j.step)) b.setProgress(100, Math.round(j.percent), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private void done(Job j) {
        if (j.state == Job.CANCELLED || j.state == Job.PAUSED || j.state == Job.WAITING) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_DONE) : new Notification.Builder(this);
        boolean ok = j.state == Job.DONE;
        String title = ok ? "Download finished" : "Download failed";
        String text = j.title + ". " + j.status();
        PendingIntent pi = openApp(2);
        Saver.Saved s = j.main();
        if (ok && s != null && j.mediaCount() == 1) {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            pi = PendingIntent.getActivity(this, 3 + Math.abs(j.id.hashCode() % 100000), Intent.createChooser(v, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        b.setSmallIcon(R.drawable.ic_stat_download).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi);
        try { nm.notify(100 + Math.abs(j.id.hashCode() % 100000), b.build()); } catch (Throwable ignored) { }
    }
}
