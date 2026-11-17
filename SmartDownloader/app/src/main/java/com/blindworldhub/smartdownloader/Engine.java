package com.blindworldhub.smartdownloader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * The download engine: yt-dlp with Python, QuickJS (YouTube needs it now) and FFmpeg (joining video and sound,
 * making MP3), all inside the app. It is made ready once, in the background, when the app starts. YouTube changes
 * often, so yt-dlp itself is brought up to date from its official GitHub page: the first time, and then at most once
 * a day when nothing is downloading.
 */
final class Engine {
    private Engine() { }

    static final int NOT_STARTED = 0, PREPARING = 1, READY = 2, FAILED = 3;

    interface Listener { void onEngine(int state, String text); }

    private static volatile int state = NOT_STARTED;
    private static volatile String text = "";
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Updating yt-dlp replaces its file, so nothing may run while it is being replaced. */
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final long DAY = 24L * 3600 * 1000;

    static int state() { return state; }
    static String text() { return text; }

    static void listen(Listener l) { synchronized (LISTENERS) { if (!LISTENERS.contains(l)) LISTENERS.add(l); } }
    static void unlisten(Listener l) { synchronized (LISTENERS) { LISTENERS.remove(l); } }

    private static void set(int s, String t) {
        state = s;
        text = t;
        final int fs = s;
        final String ft = t;
        MAIN.post(new Runnable() {
            @Override public void run() {
                List<Listener> copy;
                synchronized (LISTENERS) { copy = new ArrayList<Listener>(LISTENERS); }
                for (Listener l : copy) l.onEngine(fs, ft);
            }
        });
    }

    /** Starts getting ready (once). */
    static synchronized void start(final Context ctx) {
        if (state == PREPARING || state == READY) return;
        final Context c = ctx.getApplicationContext();
        set(PREPARING, "Getting the downloader ready. The first time this takes up to a minute.");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    YoutubeDL.getInstance().init(c);
                    FFmpeg.getInstance().init(c);
                    AppLog.info(c, "Engine", "ready, yt-dlp " + version(c));
                } catch (Throwable t) {
                    AppLog.error(c, "Engine", "could not get ready", t);
                    set(FAILED, "The downloader could not get ready on this phone. Please send a problem report from the menu.");
                    return;
                }
                if (Prefs.engineUpdated(c) == 0) {
                    set(PREPARING, "Getting the newest download engine. This needs internet, the first time only.");
                    update(c);
                }
                set(READY, "");
                maybeUpdateLater(c);
            }
        }, "engine-start").start();
    }

    /** Once a day, quietly, when nothing is downloading. */
    static void maybeUpdateLater(final Context c) {
        if (System.currentTimeMillis() - Prefs.engineUpdated(c) < DAY) return;
        new Thread(new Runnable() {
            @Override public void run() {
                if (DownloadService.busy()) return;
                update(c);
            }
        }, "engine-update").start();
    }

    /** Brings yt-dlp up to date. Returns a sentence for the person. Runs on a background thread. */
    static String update(Context c) {
        LOCK.writeLock().lock();
        try {
            YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(c, YoutubeDL.UpdateChannel.STABLE.INSTANCE);
            Prefs.setEngineUpdated(c, System.currentTimeMillis());
            AppLog.info(c, "Engine", "update: " + s + ", now " + version(c));
            return s == YoutubeDL.UpdateStatus.DONE ? "The download engine was updated to version " + version(c) + "."
                    : "The download engine is already the newest, version " + version(c) + ".";
        } catch (Throwable t) {
            AppLog.error(c, "Engine", "update failed", t);
            return "The download engine could not be updated. Check the internet and try again.";
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    static String version(Context c) {
        try {
            String v = YoutubeDL.getInstance().versionName(c);
            if (v == null || v.length() == 0) v = YoutubeDL.getInstance().version(c);
            return v == null || v.length() == 0 ? "2025.11.12 (built in)" : v;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Facts about a link (yt-dlp's JSON). Runs on a background thread. */
    static String info(String url, boolean playlist) throws Exception {
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--dump-single-json");
        r.addOption("--no-warnings");
        r.addOption("--socket-timeout", "30");
        if (playlist) {
            r.addOption("--flat-playlist");
            r.addOption("--yes-playlist");
        } else {
            r.addOption("--no-playlist");
        }
        LOCK.readLock().lock();
        try {
            YoutubeDLResponse res = YoutubeDL.getInstance().execute(r, null, false, null);
            return res.getOut();
        } finally {
            LOCK.readLock().unlock();
        }
    }

    interface Progress { void on(float percent, String line); }

    /** A download; throws when it fails or is cancelled. Runs on a background thread. */
    static void download(YoutubeDLRequest r, String id, final Progress p) throws Exception {
        LOCK.readLock().lock();
        try {
            YoutubeDL.getInstance().execute(r, id, false, new Function3<Float, Long, String, Unit>() {
                @Override public Unit invoke(Float percent, Long eta, String line) {
                    p.on(percent == null ? -1f : percent, line == null ? "" : line);
                    return Unit.INSTANCE;
                }
            });
        } finally {
            LOCK.readLock().unlock();
        }
    }

    static void cancel(String id) {
        try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Throwable ignored) { }
    }

    /** yt-dlp's error, said simply. */
    static String plain(String err) {
        String e = err == null ? "" : err;
        String low = e.toLowerCase();
        if (low.contains("sign in to confirm") || low.contains("not a bot"))
            return "YouTube asked to confirm that this is not a robot. Wait a few minutes, or change between Wi-Fi and mobile data, and try again.";
        if (low.contains("private video")) return "This video is private, so it cannot be downloaded.";
        if (low.contains("members-only") || low.contains("join this channel")) return "This video is only for the channel's members.";
        if (low.contains("age") && low.contains("confirm your age")) return "YouTube allows this video only to signed-in adults, so it cannot be downloaded here.";
        if (low.contains("video unavailable") || low.contains("has been removed") || low.contains("not available"))
            return "This video is not available. It may have been removed, or it is blocked in your country.";
        if (low.contains("unsupported url")) return "This link is not supported. Please check the link.";
        if (low.contains("is not a valid url") || low.contains("no such file")) return "This does not look like a video link. Please check the link.";
        if (low.contains("unable to download webpage") || low.contains("name resolution") || low.contains("network is unreachable")
                || low.contains("timed out") || low.contains("connection") || low.contains("errno 7"))
            return "No internet, or the connection is too slow. Check the internet and try again.";
        if (low.contains("http error 403") || low.contains("forbidden"))
            return "YouTube refused the download. Press Menu, then Update the download engine, and try again.";
        if (low.contains("no space left")) return "The phone's storage is full. Free some space and try again.";
        if (low.contains("requested format is not available")) return "This quality is not available for this video. Please choose another one.";
        return "The download did not work. Press Menu, then Update the download engine, and try again. If it still fails, press Report.";
    }
}
