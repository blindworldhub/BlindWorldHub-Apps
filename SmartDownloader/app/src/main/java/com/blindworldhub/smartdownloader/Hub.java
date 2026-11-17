package com.blindworldhub.smartdownloader;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** The developer's polls, and problem reports, through the Blind World Hub server. */
final class Hub {
    private Hub() { }

    static final class Poll {
        final long id;
        final String question;
        final String[] options;
        Poll(long id, String question, String[] options) { this.id = id; this.question = question; this.options = options; }
    }

    /** The newest open poll not shown yet, or null. The first time, only a poll of the last 7 days. Background thread. */
    static Poll newPoll(Context c) {
        try {
            long seen = Prefs.seenPoll(c);
            JSONObject o = new JSONObject(Net.get(Server.URL + "/v1/polls?app=" + Server.APP + "&after=" + Math.max(0, seen)));
            JSONArray arr = o.optJSONArray("polls");
            if (arr == null) return null;
            long weekAgo = System.currentTimeMillis() / 1000 - 7L * 86400L;
            for (int i = 0; i < arr.length(); i++) {                     // newest first
                JSONObject m = arr.getJSONObject(i);
                long id = m.optLong("id", 0);
                if (id <= Math.max(0, seen)) continue;
                if (seen < 0 && m.optLong("t", 0) < weekAgo) continue;
                JSONArray op = m.optJSONArray("options");
                if (op == null || op.length() < 2) continue;
                String[] opts = new String[Math.min(5, op.length())];
                for (int k = 0; k < opts.length; k++) opts[k] = op.optString(k, "");
                return new Poll(id, m.optString("question", ""), opts);
            }
            if (seen < 0) Prefs.setSeenPoll(c, arr.length() > 0 ? arr.getJSONObject(0).optLong("id", 0) : 0);
        } catch (Throwable t) {
            AppLog.info(c, "Polls", "not checked: " + t.getMessage());
        }
        return null;
    }

    static void pollShown(Context c, Poll p) {
        Prefs.setSeenPoll(c, Math.max(p.id, Prefs.seenPoll(c)));
        Prefs.setNotedPoll(c, Math.max(p.id, Prefs.notedPoll(c)));
    }

    /** Sends the chosen answer's number. Only the app's random install number goes with it. Background thread. */
    static boolean vote(Context c, Poll p, int choice) {
        try {
            JSONObject o = new JSONObject();
            o.put("poll", p.id);
            o.put("choice", choice);
            o.put("iid", Prefs.installId(c));
            return new JSONObject(Net.post(Server.URL + "/v1/vote", o.toString())).optBoolean("ok", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A problem report the person chose to send. Returns null when sent, or a sentence saying why not. Background thread. */
    static String report(Context c, String summary, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("app", Server.APP);
            o.put("version", Updates.myVersionName(c) + " (" + Updates.myVersionCode(c) + ")");
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("android", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            o.put("kind", "manual");
            o.put("summary", summary);
            o.put("body", AppLog.header(c) + "\n" + text + "\n\n--- the app's log ---\n" + AppLog.tail(AppLog.file(c), 120 * 1024));
            o.put("iid", Prefs.installId(c));
            JSONObject r = new JSONObject(Net.post(Server.URL + "/v1/report", o.toString()));
            if (r.optBoolean("ok", false)) return null;
            return r.has("error") && r.optString("error").contains("enough") ? "You have sent enough reports today. Please try again tomorrow."
                    : "The report could not be sent. Please try again.";
        } catch (Throwable t) {
            return "No internet. Please try again when you are connected.";
        }
    }
}
