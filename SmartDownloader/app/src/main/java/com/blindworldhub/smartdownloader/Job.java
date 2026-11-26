package com.blindworldhub.smartdownloader;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One download the person asked for: one video, or a whole playlist. Jobs are kept in a file (see DownloadService),
 * so the list of downloads is still there when the app is opened again; a download that was stopped can go on
 * from where it was (Resume).
 */
final class Job {
    static final int WAITING = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4, PAUSED = 5;

    final String id, url, title, choice;
    final boolean playlist, audio;
    final List<String> args;
    /** About how many bytes the finished file will be (0 when not known); used to check the free space. */
    final long bytes;
    final long made;                                    // when the person asked for it (milliseconds)

    volatile int state = WAITING;
    volatile float percent = -1;
    volatile String step = "Waiting";
    volatile String error = "", errorDetail = "";
    volatile String note = "";                          // e.g. "The subtitles could not be saved."
    volatile int item, items;                           // a playlist: "video 3 of 20"
    volatile String got = "", total = "", speed = "";   // "12.5 MB", "40 MB", "1.2 MB per second"
    volatile long eta = -1;                             // seconds left, -1 when not known
    final List<Saver.Saved> saved = Collections.synchronizedList(new ArrayList<Saver.Saved>());

    Job(String id, String url, String title, String choice, boolean playlist, boolean audio, List<String> args, long bytes, long made) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.choice = choice;
        this.playlist = playlist;
        this.audio = audio;
        this.args = Collections.unmodifiableList(new ArrayList<String>(args));
        this.bytes = bytes;
        this.made = made;
    }

    /** The same download again, as a new job. */
    Job again() {
        return new Job("j" + System.currentTimeMillis(), url, title, choice, playlist, audio, args, bytes, System.currentTimeMillis());
    }

    boolean finished() { return state == DONE || state == FAILED || state == CANCELLED; }

    /** The file to open: the video or the audio (not a subtitle file). */
    Saver.Saved main() {
        synchronized (saved) {
            for (Saver.Saved s : saved) if (!s.isSubtitle()) return s;
            return saved.isEmpty() ? null : saved.get(0);
        }
    }

    int mediaCount() {
        int n = 0;
        synchronized (saved) { for (Saver.Saved s : saved) if (!s.isSubtitle()) n++; }
        return n;
    }

    /** One sentence for the screen and the screen reader. */
    String status() {
        switch (state) {
            case WAITING: return "Waiting to start.";
            case PAUSED: return "Paused" + (percent > 0 ? " at " + Math.round(percent) + " percent" : "") + ". Press Resume to go on."
                    + (error.length() > 0 ? " " + error : "");
            case RUNNING: {
                StringBuilder b = new StringBuilder();
                if (playlist && items > 0) b.append(audio ? "Item " : "Video ").append(item).append(" of ").append(items).append(". ");
                b.append(step);
                if (percent >= 0 && step.startsWith("Downloading")) {
                    b.append(", ").append(Math.round(percent)).append(" percent");
                    if (got.length() > 0 && total.length() > 0) b.append(", ").append(got).append(" of ").append(total);
                    if (speed.length() > 0) b.append(", ").append(speed);
                    if (eta >= 0) b.append(", ").append(left(eta));
                }
                return b.append('.').toString();
            }
            case DONE: {
                int n = mediaCount();
                if (saved.isEmpty()) return "Finished, but no file was saved.";
                Saver.Saved m = main();
                String s = (n <= 1 ? "Saved: " + m.name : n + " files saved") + ", in the " + m.where + " folder.";
                int subs = saved.size() - n;
                if (subs > 0) s += " With " + (subs == 1 ? "1 subtitle file." : subs + " subtitle files.");
                if (error.length() > 0) s += " " + error;
                if (note.length() > 0) s += " " + note;
                return s;
            }
            case FAILED: return "Failed. " + error;
            default: return "Cancelled.";
        }
    }

    static String left(long s) {
        if (s < 60) return s + (s == 1 ? " second left" : " seconds left");
        long m = (s + 30) / 60;
        if (m < 60) return m + (m == 1 ? " minute left" : " minutes left");
        long h = m / 60, mm = m % 60;
        return h + (h == 1 ? " hour" : " hours") + (mm > 0 ? " " + mm + (mm == 1 ? " minute" : " minutes") : "") + " left";
    }

    /** For "already downloaded?": the same link, whatever tracking parts it has. */
    static String sameLink(String url) {
        String u = url == null ? "" : url.trim();
        u = u.replaceAll("[?&](si|feature|pp|utm_[a-z]+|igsh|igshid|mibextid|rdid|share_url)=[^&#]*", "");
        u = u.replaceFirst("^https?://(www\\.|m\\.|mobile\\.)?", "");
        u = u.replaceFirst("^youtu\\.be/([A-Za-z0-9_-]{6,})\\??", "youtube.com/watch?v=$1&");
        u = u.replaceFirst("^youtube\\.com/shorts/([A-Za-z0-9_-]{6,})\\??", "youtube.com/watch?v=$1&");
        u = u.replaceAll("[?&]+$", "").replaceAll("/+$", "");
        return u.toLowerCase(Locale.US);
    }

    // ------------------------------------------------------------------------------------------------ kept in a file

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id).put("url", url).put("title", title).put("choice", choice).put("playlist", playlist).put("audio", audio);
        o.put("bytes", bytes).put("made", made).put("state", state).put("error", error).put("note", note);
        o.put("percent", (double) percent);
        JSONArray a = new JSONArray();
        for (String s : args) a.put(s);
        o.put("args", a);
        JSONArray sv = new JSONArray();
        synchronized (saved) {
            for (Saver.Saved s : saved) {
                sv.put(new JSONObject().put("uri", s.uri.toString()).put("name", s.name).put("where", s.where)
                        .put("mime", s.mime).put("path", s.path == null ? "" : s.path));
            }
        }
        o.put("saved", sv);
        return o;
    }

    static Job fromJson(JSONObject o) {
        List<String> args = new ArrayList<String>();
        JSONArray a = o.optJSONArray("args");
        if (a != null) for (int i = 0; i < a.length(); i++) args.add(a.optString(i));
        Job j = new Job(o.optString("id"), o.optString("url"), o.optString("title"), o.optString("choice"),
                o.optBoolean("playlist"), o.optBoolean("audio"), args, o.optLong("bytes"), o.optLong("made"));
        j.state = o.optInt("state", FAILED);
        j.error = o.optString("error", "");
        j.note = o.optString("note", "");
        j.percent = (float) o.optDouble("percent", -1);
        JSONArray sv = o.optJSONArray("saved");
        if (sv != null) for (int i = 0; i < sv.length(); i++) {
            JSONObject s = sv.optJSONObject(i);
            if (s == null || s.optString("uri").length() == 0) continue;
            String p = s.optString("path", "");
            j.saved.add(new Saver.Saved(Uri.parse(s.optString("uri")), s.optString("name"), s.optString("where"),
                    s.optString("mime"), p.length() == 0 ? null : p));
        }
        // a download that was going on when the app was closed can go on later
        if (j.state == RUNNING || j.state == WAITING) {
            j.state = PAUSED;
            j.error = "It stopped when the app was closed.";
        }
        return j;
    }
}
