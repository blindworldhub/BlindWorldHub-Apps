package com.blindworldhub.smartdownloader;

import java.util.ArrayList;
import java.util.List;

/** One download the person asked for: one video, or a whole playlist. */
final class Job {
    static final int WAITING = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4;

    final String id, url, title, choice;
    final boolean playlist, audio;
    final List<String> args;

    volatile int state = WAITING;
    volatile float percent = -1;
    volatile String step = "Waiting";
    volatile String error = "", errorDetail = "";
    volatile int item, items;                           // a playlist: "video 3 of 20"
    final List<Saver.Saved> saved = new ArrayList<Saver.Saved>();

    Job(String id, String url, String title, String choice, boolean playlist, boolean audio, List<String> args) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.choice = choice;
        this.playlist = playlist;
        this.audio = audio;
        this.args = args;
    }

    boolean finished() { return state == DONE || state == FAILED || state == CANCELLED; }

    /** One sentence for the screen and the screen reader. */
    String status() {
        switch (state) {
            case WAITING: return "Waiting to start.";
            case RUNNING: {
                StringBuilder b = new StringBuilder();
                if (playlist && items > 0) b.append(audio ? "Item " : "Video ").append(item).append(" of ").append(items).append(". ");
                b.append(step);
                if (percent >= 0 && step.startsWith("Downloading")) b.append(", ").append(Math.round(percent)).append(" percent");
                return b.append('.').toString();
            }
            case DONE: {
                int n = saved.size();
                if (n == 0) return "Finished, but no file was saved.";
                String where = saved.get(0).where;
                return (n == 1 ? "Saved: " + saved.get(0).name : n + " files saved") + ", in the " + where + " folder."
                        + (error.length() > 0 ? " " + error : "");
            }
            case FAILED: return "Failed. " + error;
            default: return "Cancelled.";
        }
    }
}
