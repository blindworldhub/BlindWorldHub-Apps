package com.blindworldhub.smartdownloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Turns yt-dlp's facts about a link into the choices the person sees: the video qualities (each with its size) and
 * the audio kinds (M4A as it comes, or MP3 in three qualities). Every choice carries the yt-dlp options it needs.
 */
final class Formats {
    private Formats() { }

    static final class Option {
        final String label;
        final boolean audio;
        final List<String> args;
        Option(String label, boolean audio, String... args) {
            this.label = label;
            this.audio = audio;
            this.args = Collections.unmodifiableList(Arrays.asList(args));
        }
    }

    static final class Media {
        String title = "", uploader = "", url = "";
        long duration;              // seconds, 0 when not known
        boolean playlist;
        int count;                  // videos in a playlist
        final List<Option> video = new ArrayList<Option>();
        final List<Option> audio = new ArrayList<Option>();
    }

    private static final String MERGE = "--merge-output-format";

    static Media parse(String json) throws Exception {
        JSONObject o = new JSONObject(json.trim());
        Media m = new Media();
        m.title = clean(o.optString("title", ""));
        m.uploader = clean(o.optString("uploader", o.optString("channel", "")));
        m.url = o.optString("webpage_url", o.optString("original_url", ""));
        String type = o.optString("_type", "video");
        if ("playlist".equals(type) || "multi_video".equals(type)) {
            m.playlist = true;
            JSONArray e = o.optJSONArray("entries");
            m.count = o.optInt("playlist_count", e == null ? 0 : e.length());
            if (e != null && e.length() > 0) m.count = e.length();
            if (m.title.length() == 0) m.title = "Playlist";
            addPlaylistChoices(m);
            return m;
        }
        if (m.title.length() == 0) m.title = "Video";
        m.duration = (long) o.optDouble("duration", 0);
        addVideoChoices(m, o.optJSONArray("formats"));
        addAudioChoices(m, o.optJSONArray("formats"));
        return m;
    }

    // ------------------------------------------------------------------------------------------------ one video

    private static void addVideoChoices(Media m, JSONArray formats) {
        long audioM4a = 0, audioAny = 0;
        // height -> {best size of an MP4 video at that height, best size of any video at that height}
        TreeMap<Integer, long[]> byHeight = new TreeMap<Integer, long[]>(Collections.<Integer>reverseOrder());
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null || isJunk(f)) continue;
                String v = f.optString("vcodec", "none"), a = f.optString("acodec", "none"), ext = f.optString("ext", "");
                boolean hasV = !"none".equals(v) && !"null".equals(v), hasA = !"none".equals(a) && !"null".equals(a);
                long size = size(f, m.duration);
                if (!hasV && hasA) {
                    if ("m4a".equals(ext)) audioM4a = Math.max(audioM4a, size);
                    audioAny = Math.max(audioAny, size);
                    continue;
                }
                int h = f.optInt("height", 0);
                if (!hasV || h < 100) continue;
                long[] s = byHeight.get(h);
                if (s == null) { s = new long[2]; byHeight.put(h, s); }
                if ("mp4".equals(ext)) s[0] = Math.max(s[0], size);
                s[1] = Math.max(s[1], size);
            }
        }
        long sound = audioM4a > 0 ? audioM4a : audioAny;
        for (java.util.Map.Entry<Integer, long[]> e : byHeight.entrySet()) {
            int h = e.getKey();
            long[] s = e.getValue();
            long v = s[0] > 0 ? s[0] : s[1];
            long total = v > 0 ? v + sound : 0;
            m.video.add(new Option(quality(h) + " video, MP4" + about(total), false,
                    "-f", "bv*[height=" + h + "][ext=mp4]+ba[ext=m4a]/bv*[height=" + h + "]+ba/b[height=" + h + "]/bv*[height<=" + h + "]+ba/b",
                    MERGE, "mp4"));
        }
        if (m.video.isEmpty()) {
            m.video.add(new Option("Best quality video" + about(0), false, "-f", "bv*+ba/b", MERGE, "mp4"));
        }
    }

    private static void addAudioChoices(Media m, JSONArray formats) {
        long m4a = 0, any = 0;
        int kbps = 0;
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null || isJunk(f)) continue;
                String v = f.optString("vcodec", "none"), a = f.optString("acodec", "none");
                if (!"none".equals(v) || "none".equals(a)) continue;
                long size = size(f, m.duration);
                if ("m4a".equals(f.optString("ext"))) {
                    if (size >= m4a) { m4a = size; kbps = (int) Math.round(f.optDouble("abr", f.optDouble("tbr", 0))); }
                }
                any = Math.max(any, size);
            }
        }
        long orig = m4a > 0 ? m4a : any;
        m.audio.add(new Option("M4A audio, original quality" + (kbps > 0 ? ", " + kbps + " kbps" : "") + about(orig), true,
                "-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a", "--embed-metadata"));
        addMp3(m, m.duration);
    }

    private static void addMp3(Media m, long duration) {
        for (int k : new int[] { 320, 192, 128 }) {
            String q = k == 320 ? "best" : k == 192 ? "good" : "smaller file";
            m.audio.add(new Option("MP3 audio, " + k + " kbps (" + q + ")" + about(duration * k * 125L), true,
                    "-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", k + "K", "--embed-metadata"));
        }
    }

    // ------------------------------------------------------------------------------------------------ a playlist

    private static void addPlaylistChoices(Media m) {
        for (int h : new int[] { 1080, 720, 480, 360 }) {
            m.video.add(new Option((h == 1080 ? "Best video, up to 1080p" : quality(h) + " video") + ", MP4", false,
                    "-f", "bv*[height<=" + h + "][ext=mp4]+ba[ext=m4a]/bv*[height<=" + h + "]+ba/b[height<=" + h + "]/b",
                    MERGE, "mp4"));
        }
        m.audio.add(new Option("M4A audio, original quality", true,
                "-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a", "--embed-metadata"));
        addMp3(m, 0);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static boolean isJunk(JSONObject f) {
        String note = f.optString("format_note", "").toLowerCase(Locale.US);
        String ext = f.optString("ext", "");
        return note.contains("storyboard") || "mhtml".equals(ext) || f.optString("format_id", "").startsWith("sb");
    }

    private static long size(JSONObject f, long duration) {
        long s = f.optLong("filesize", 0);
        if (s <= 0) s = f.optLong("filesize_approx", 0);
        if (s <= 0 && duration > 0) {
            double tbr = f.optDouble("tbr", 0);
            if (tbr > 0) s = (long) (tbr * 125.0 * duration);
        }
        return Math.max(0, s);
    }

    static String quality(int h) {
        if (h >= 2160) return h + "p (4K)";
        if (h >= 1440) return h + "p (2K)";
        if (h >= 1080) return h + "p (Full HD)";
        if (h >= 720) return h + "p (HD)";
        return h + "p";
    }

    private static String about(long bytes) {
        return bytes > 0 ? ", about " + size(bytes) : "";
    }

    static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        if (mb >= 10) return Math.round(mb) + " MB";
        return String.format(Locale.US, "%.1f MB", Math.max(0.1, mb));
    }

    static String duration(long s) {
        if (s <= 0) return "";
        long h = s / 3600, mi = (s % 3600) / 60, se = s % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(h == 1 ? " hour " : " hours ");
        if (mi > 0) b.append(mi).append(mi == 1 ? " minute " : " minutes ");
        if (se > 0 && h == 0) b.append(se).append(se == 1 ? " second" : " seconds");
        return b.toString().trim();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** The first web link in shared text (YouTube's Share gives "title https://youtu.be/..." in some apps). */
    static String findLink(String text) {
        if (text == null) return "";
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("https?://[^\\s<>\"']+").matcher(text);
        return mm.find() ? mm.group() : text.trim();
    }

    /** A YouTube video link that also names a playlist (…watch?v=X&list=Y). */
    static boolean videoInPlaylist(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.contains("list=") && (u.contains("v=") || u.contains("youtu.be/"));
    }

    /** A link to a playlist only. */
    static boolean playlistOnly(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.contains("list=") && !videoInPlaylist(url);
    }
}
