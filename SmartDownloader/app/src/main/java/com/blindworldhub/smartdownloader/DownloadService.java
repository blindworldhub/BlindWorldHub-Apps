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

import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads one after the other, even with the app closed or the screen off. The notification shows the progress;
 * a finished download gets its own notification, which opens the file. A failure says what went wrong simply.
 */
public final class DownloadService extends Service {

    static final String ACTION_START = "start", ACTION_CANCEL = "cancel";
    private static final String CH_WORK = "downloading", CH_DONE = "finished";
    private static final int NOTE_WORK = 1;

    interface Listener { void onJobs(); }

    private static final List<Job> JOBS = new ArrayList<Job>();
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<Job>();
    private static volatile Job current;
    private static volatile boolean running;

    private PowerManager.WakeLock wake;
    private long lastNote;

    // ------------------------------------------------------------------------------------------------ for the screen

    static List<Job> jobs() { synchronized (JOBS) { return new ArrayList<Job>(JOBS); } }
    static boolean busy() { return running || !QUEUE.isEmpty(); }
    static void listen(Listener l) { synchronized (LISTENERS) { if (!LISTENERS.contains(l)) LISTENERS.add(l); } }
    static void unlisten(Listener l) { synchronized (LISTENERS) { LISTENERS.remove(l); } }

    static void remove(Job j) {
        if (!j.finished()) return;
        synchronized (JOBS) { JOBS.remove(j); }
        changed();
    }

    private static void changed() {
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
        changed();
        Intent i = new Intent(c, DownloadService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void cancel(Context c, Job j) {
        if (j.finished()) return;
        boolean wasWaiting = QUEUE.remove(j);
        j.state = Job.CANCELLED;
        if (!wasWaiting) Engine.cancel(j.id);
        changed();
    }

    // ------------------------------------------------------------------------------------------------ the service

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
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

    /** Android 15 gives data downloads 6 hours a day; when they are used up, stop politely. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        stopAll("Android paused the downloads for today. Please start them again later.");
    }

    @Override
    public void onTimeout(int startId) {
        stopAll("Android paused the downloads. Please start them again later.");
    }

    private void stopAll(String why) {
        Job cur = current;
        Job w;
        while ((w = QUEUE.poll()) != null) { w.state = Job.FAILED; w.error = why; }
        if (cur != null && !cur.finished()) { cur.error = why; Engine.cancel(cur.id); }
        changed();
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
                if (j.state == Job.CANCELLED) continue;
                current = j;
                run(j);
                current = null;
                changed();
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

    private void run(final Job j) {
        j.state = Job.RUNNING;
        j.step = "Getting ready";
        changed();
        // wait until the engine is ready (the first start of the app)
        for (int i = 0; i < 600 && Engine.state() != Engine.READY && Engine.state() != Engine.FAILED && j.state == Job.RUNNING; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        if (j.state == Job.CANCELLED) return;
        if (Engine.state() != Engine.READY) {
            j.state = Job.FAILED;
            j.error = "The downloader is not ready. Please close the app, open it again, and try again.";
            return;
        }
        File tmp = new File(new File(getCacheDir(), "downloads"), j.id);
        deleteAll(tmp);
        tmp.mkdirs();
        try {
            YoutubeDLRequest r = new YoutubeDLRequest(j.url);
            r.addCommands(j.args);
            r.addOption("-o", tmp.getAbsolutePath() + "/" + (j.playlist ? "%(playlist_index)03d - %(title).120B.%(ext)s" : "%(title).150B.%(ext)s"));
            r.addOption(j.playlist ? "--yes-playlist" : "--no-playlist");
            if (j.playlist) r.addOption("--ignore-errors");
            r.addOption("--no-mtime");
            r.addOption("--windows-filenames");
            r.addOption("--retries", "10");
            r.addOption("--fragment-retries", "10");
            r.addOption("--socket-timeout", "30");
            r.addOption("--newline");
            j.step = "Downloading";
            changed();
            Engine.download(r, j.id, new Engine.Progress() {
                @Override public void on(float percent, String line) { progress(j, percent, line); }
            });
        } catch (Throwable t) {
            if (j.state == Job.CANCELLED) { deleteAll(tmp); return; }
            String msg = String.valueOf(t.getMessage());
            j.errorDetail = msg.length() > 6000 ? msg.substring(msg.length() - 6000) : msg;
            AppLog.error(this, "Download", "failed: " + j.url, t);
            if (!j.playlist || !hasFiles(tmp)) {
                j.state = Job.FAILED;
                if (j.error.length() == 0) j.error = Engine.plain(msg);
                deleteAll(tmp);
                return;
            }
            j.error = "Some videos of the playlist could not be downloaded.";
        }
        if (j.state == Job.CANCELLED) { deleteAll(tmp); return; }
        j.step = "Saving to the Download folder";
        j.percent = -1;
        changed();
        File[] files = tmp.listFiles();
        if (files != null) Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        String sub = j.playlist ? Saver.safeFolder(j.title) : "";
        int failedSaves = 0;
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory() || n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") || n.contains(".part-frag")) continue;
            try {
                Saver.Saved s = Saver.save(this, f, sub);
                synchronized (j.saved) { j.saved.add(s); }
            } catch (Throwable t) {
                failedSaves++;
                AppLog.error(this, "Download", "could not save " + f.getName(), t);
            }
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
        Matcher m = ITEM.matcher(line);
        if (m.find()) {
            try { j.item = Integer.parseInt(m.group(1)); j.items = Integer.parseInt(m.group(2)); } catch (Exception ignored) { }
            j.percent = 0;
            j.step = "Downloading";
        } else if (line.startsWith("[Merger]")) {
            j.step = "Joining the video and the sound";
        } else if (line.startsWith("[ExtractAudio]")) {
            j.step = j.audio && j.choice.startsWith("MP3") ? "Making the MP3 file" : "Preparing the audio file";
        } else if (line.startsWith("[download]") && percent >= 0) {
            j.step = "Downloading";
            j.percent = percent;
        }
        long now = System.currentTimeMillis();
        if (now - lastNote > 1000) {
            lastNote = now;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) try { nm.notify(NOTE_WORK, working(j.title + ": " + j.status())); } catch (Throwable ignored) { }
            changed();
        }
    }

    private static boolean hasFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
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
        if (j.state == Job.CANCELLED) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_DONE) : new Notification.Builder(this);
        boolean ok = j.state == Job.DONE;
        String title = ok ? "Download finished" : "Download failed";
        String text = j.title + ". " + j.status();
        PendingIntent pi = openApp(2);
        if (ok && j.saved.size() == 1) {
            Saver.Saved s = j.saved.get(0);
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
