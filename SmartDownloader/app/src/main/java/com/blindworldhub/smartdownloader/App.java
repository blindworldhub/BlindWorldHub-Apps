package com.blindworldhub.smartdownloader;

import android.app.Application;

/**
 * Reads the kept list of downloads, starts getting the download engine ready as soon as the app runs, and keeps a
 * note of crashes for reports.
 */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                AppLog.error(App.this, "Crash", "the app stopped (" + t.getName() + ")", e);
                if (old != null) old.uncaughtException(t, e);
            }
        });
        DownloadService.load(this);
        DownloadService.channels(this);
        Engine.start(this);
    }
}
