package com.blindworldhub.smartdownloader;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** The app's small settings: the last choices, what was already shown, when the download engine was last updated. */
final class Prefs {
    private Prefs() { }

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("sd", Context.MODE_PRIVATE);
    }

    static long lastMessage(Context c) { return p(c).getLong("lastMessage", -1); }
    static void setLastMessage(Context c, long id) { p(c).edit().putLong("lastMessage", id).apply(); }

    static long seenPoll(Context c) { return p(c).getLong("seenPoll", -1); }
    static void setSeenPoll(Context c, long id) { p(c).edit().putLong("seenPoll", id).apply(); }
    static long notedPoll(Context c) { return p(c).getLong("notedPoll", 0); }
    static void setNotedPoll(Context c, long id) { p(c).edit().putLong("notedPoll", id).apply(); }

    static long engineUpdated(Context c) { return p(c).getLong("engineUpdated", 0); }
    static void setEngineUpdated(Context c, long t) { p(c).edit().putLong("engineUpdated", t).apply(); }

    static int lastKind(Context c) { return p(c).getInt("lastKind", 0); }           // 0 video, 1 audio
    static void setLastKind(Context c, int k) { p(c).edit().putInt("lastKind", k).apply(); }

    static String lastType(Context c, boolean audio) { return p(c).getString(audio ? "audioType" : "videoType", audio ? "MP3" : "MP4"); }
    static void setLastType(Context c, boolean audio, String t) { p(c).edit().putString(audio ? "audioType" : "videoType", t).apply(); }

    /** The quality last chosen for a file type ("720", "128", "original"). */
    static String lastQuality(Context c, String type) { return p(c).getString("q_" + type, ""); }
    static void setLastQuality(Context c, String type, String key) { p(c).edit().putString("q_" + type, key).apply(); }

    static boolean cover(Context c) { return p(c).getBoolean("cover", true); }
    static void setCover(Context c, boolean on) { p(c).edit().putBoolean("cover", on).apply(); }

    static boolean subtitles(Context c) { return p(c).getBoolean("subs", false); }
    static void setSubtitles(Context c, boolean on) { p(c).edit().putBoolean("subs", on).apply(); }

    /** The copied link last offered (offered once). */
    static String offeredLink(Context c) { return p(c).getString("offered", ""); }
    static void setOfferedLink(Context c, String s) { p(c).edit().putString("offered", s).apply(); }

    static boolean askedNotifications(Context c) { return p(c).getBoolean("askedNotif", false); }
    static void setAskedNotifications(Context c) { p(c).edit().putBoolean("askedNotif", true).apply(); }

    /** A random number for this installation (votes and reports are counted once per phone by it). */
    static synchronized String installId(Context c) {
        String id = p(c).getString("iid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p(c).edit().putString("iid", id).apply();
        }
        return id;
    }
}
