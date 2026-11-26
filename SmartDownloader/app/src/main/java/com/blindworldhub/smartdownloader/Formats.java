package com.blindworldhub.smartdownloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns yt-dlp's facts about a link into the choices the person sees. Video: the file type (MP4, MKV, WEBM, 3GP)
 * and then the quality (each resolution with its size and the sound's kbps). Audio: the file type (MP3, M4A, AAC,
 * OPUS, OGG, WAV, FLAC) and then the quality in kbps (48 to 320) with the size. Every choice carries the yt-dlp
 * options it needs.
 */
final class Formats {
    private Formats() { }

    static final String[] VIDEO_TYPES = { "MP4", "MKV", "WEBM", "3GP" };
    static final String[] AUDIO_TYPES = { "MP3", "M4A", "AAC", "OPUS", "OGG", "WAV", "FLAC" };
    static final int[] KBPS = { 320, 256, 192, 160, 128, 96, 64, 48 };

    static final class Option {
        final String type, key, label;
        final boolean audio;
        final long bytes;           // about how big the file will be, 0 when not known
        final List<String> args;
        Option(String type, String key, String label, boolean audio, long bytes, String... args) {
            this.type = type;
            this.key = key;
            this.label = label;
            this.audio = audio;
            this.bytes = bytes;
            this.args = Collections.unmodifiableList(Arrays.asList(args));
        }
    }

    static final class Media {
        String title = "", uploader = "", url = "";
        long duration;              // seconds, 0 when not known
        boolean playlist;
        int count;                  // videos in a playlist
        /** file type ("MP4") -> its qualities, best first; only the types this link can give */
        final Map<String, List<Option>> video = new LinkedHashMap<String, List<Option>>();
        final Map<String, List<Option>> audio = new LinkedHashMap<String, List<Option>>();
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
        Facts f = facts(o.optJSONArray("formats"), m.duration);
        addVideoChoices(m, f);
        addAudioChoices(m, f, m.duration);
        return m;
    }

    // ------------------------------------------------------------------------------------------------ the facts

    /** What the link offers, per height and for the sound alone. */
    private static final class Facts {
        // height -> {best MP4 video size, best WEBM video size, best video size of any kind}
        final TreeMap<Integer, long[]> heights = new TreeMap<Integer, long[]>(Collections.<Integer>reverseOrder());
        long m4a, opus, anyAudio;           // sizes of the best sound alone
        int m4aKbps, opusKbps, anyKbps;
        boolean webmAudio;
    }

    private static Facts facts(JSONArray formats, long duration) {
        Facts x = new Facts();
        if (formats == null) return x;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null || isJunk(f)) continue;
            String v = f.optString("vcodec", "none"), a = f.optString("acodec", "none"), ext = f.optString("ext", "");
            boolean hasV = !"none".equals(v) && !"null".equals(v), hasA = !"none".equals(a) && !"null".equals(a);
            long size = size(f, duration);
            int kbps = (int) Math.round(f.optDouble("abr", 0));
            if (!hasV && hasA) {
                if (kbps <= 0) kbps = (int) Math.round(f.optDouble("tbr", 0));
                if ("m4a".equals(ext) && size >= x.m4a) { x.m4a = size; x.m4aKbps = kbps; }
                if (a.startsWith("opus") && size >= x.opus) { x.opus = size; x.opusKbps = kbps; }
                if ("webm".equals(ext)) x.webmAudio = true;
                if (size >= x.anyAudio) { x.anyAudio = size; x.anyKbps = kbps; }
                continue;
            }
            int h = f.optInt("height", 0);
            if (!hasV || h < 100) continue;
            long[] s = x.heights.get(h);
            if (s == null) { s = new long[3]; x.heights.put(h, s); }
            if ("mp4".equals(ext)) s[0] = Math.max(s[0], size);
            if ("webm".equals(ext)) s[1] = Math.max(s[1], size);
            s[2] = Math.max(s[2], size);
            if (hasA && kbps > 0 && x.anyKbps == 0) x.anyKbps = kbps;     // a file with picture and sound together
        }
        return x;
    }

    // ------------------------------------------------------------------------------------------------ one video

    private static void addVideoChoices(Media m, Facts f) {
        long sound = f.m4a > 0 ? f.m4a : f.anyAudio;
        int soundKbps = f.m4a > 0 ? f.m4aKbps : f.anyKbps;
        List<Option> mp4 = new ArrayList<Option>(), mkv = new ArrayList<Option>(), webm = new ArrayList<Option>(), g3 = new ArrayList<Option>();
        for (Map.Entry<Integer, long[]> e : f.heights.entrySet()) {
            int h = e.getKey();
            long[] s = e.getValue();
            long v = s[0] > 0 ? s[0] : s[2];
            mp4.add(new Option("MP4", "" + h, quality(h) + about(v > 0 ? v + sound : 0) + kbps(soundKbps), false, v > 0 ? v + sound : 0,
                    "-f", "bv*[height=" + h + "][ext=mp4]+ba[ext=m4a]/bv*[height=" + h + "]+ba/b[height=" + h + "]/bv*[height<=" + h + "]+ba/b",
                    MERGE, "mp4"));
            mkv.add(new Option("MKV", "" + h, quality(h) + about(s[2] > 0 ? s[2] + f.anyAudio : 0) + kbps(f.anyKbps), false,
                    s[2] > 0 ? s[2] + f.anyAudio : 0,
                    "-f", "bv*[height=" + h + "]+ba/b[height=" + h + "]/bv*[height<=" + h + "]+ba/b",
                    MERGE, "mkv", "--remux-video", "mkv"));
            if (s[1] > 0 && f.webmAudio) {
                long ws = s[1] + (f.opus > 0 ? f.opus : f.anyAudio);
                webm.add(new Option("WEBM", "" + h, quality(h) + about(ws) + kbps(f.opusKbps > 0 ? f.opusKbps : f.anyKbps), false, ws,
                        "-f", "bv*[height=" + h + "][ext=webm]+ba[ext=webm]", MERGE, "webm"));
            }
            if (h <= 360) g3.add(threeGp(h, h + "p", m.duration));
        }
        if (mp4.isEmpty()) {
            // the site does not say its qualities (some Facebook and TikTok links): the best one it has
            mp4.add(new Option("MP4", "best", "Best quality" + kbps(soundKbps), false, 0, "-f", "bv*+ba/b", MERGE, "mp4", "--remux-video", "mp4"));
            mkv.add(new Option("MKV", "best", "Best quality", false, 0, "-f", "bv*+ba/b", MERGE, "mkv", "--remux-video", "mkv"));
        }
        if (g3.isEmpty()) g3.add(threeGp(360, "360p or less", m.duration));
        m.video.put("MP4", mp4);
        m.video.put("MKV", mkv);
        if (!webm.isEmpty()) m.video.put("WEBM", webm);
        m.video.put("3GP", g3);
    }

    /** 3GP is made on the phone from a small video (picture MPEG-4, sound AAC), for old phones and small files. */
    private static Option threeGp(int h, String what, long duration) {
        long bytes = duration > 0 ? duration * (h <= 144 ? 160 : h <= 240 ? 300 : 500) * 125L : 0;
        return new Option("3GP", "" + h, what + about(bytes) + ", sound 64 kbps (made on the phone, slower)", false, bytes,
                "-f", "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/wv*+ba/w",
                "--recode-video", "3gp",
                "--postprocessor-args", "VideoConvertor:-c:v mpeg4 -q:v 5 -c:a aac -b:a 64k -ac 2 -ar 44100");
    }

    private static void addAudioChoices(Media m, Facts f, long duration) {
        for (String t : AUDIO_TYPES) m.audio.put(t, audioType(t, f, duration));
    }

    /** The qualities of one audio file type. f is null for a playlist (sizes are not known then). */
    private static List<Option> audioType(String t, Facts f, long duration) {
        List<Option> list = new ArrayList<Option>();
        String fmt = t.toLowerCase(Locale.US);
        if ("OGG".equals(t)) fmt = "vorbis";
        if ("WAV".equals(t) || "FLAC".equals(t)) {
            long bytes = duration * ("WAV".equals(t) ? 1411 : 850) * 125L;
            list.add(new Option(t, "lossless", "Lossless, the best (a big file)" + about(bytes), true, bytes,
                    "-f", "ba/b", "-x", "--audio-format", fmt, "--embed-metadata"));
            return list;
        }
        if ("M4A".equals(t) && (f == null || f.m4a > 0)) {
            list.add(new Option(t, "original", "Original quality, as it comes" + (f != null && f.m4aKbps > 0 ? ", " + f.m4aKbps + " kbps" : "")
                    + about(f == null ? 0 : f.m4a) + " (fastest)", true, f == null ? 0 : f.m4a,
                    "-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a", "--embed-metadata"));
        }
        if ("OPUS".equals(t) && (f == null || f.opus > 0)) {
            list.add(new Option(t, "original", "Original quality, as it comes" + (f != null && f.opusKbps > 0 ? ", " + f.opusKbps + " kbps" : "")
                    + about(f == null ? 0 : f.opus) + " (fastest)", true, f == null ? 0 : f.opus,
                    "-f", "ba[acodec^=opus]/ba/b", "-x", "--audio-format", "opus", "--embed-metadata"));
        }
        // made at the chosen kbps: from a sound of another kind, so that it is really made again at that quality
        String from = "M4A".equals(t) || "AAC".equals(t) ? "ba[acodec^=opus]/ba/b" : "OPUS".equals(t) ? "ba[ext=m4a]/ba/b" : "ba/b";
        for (int k : KBPS) {
            String q = k >= 320 ? " (best)" : k == 128 ? " (good, smaller file)" : k <= 48 ? " (smallest file, for speech)" : "";
            long bytes = duration * k * 125L;
            list.add(new Option(t, "" + k, k + " kbps" + q + about(bytes), true, bytes,
                    "-f", from, "-x", "--audio-format", fmt, "--audio-quality", k + "K", "--embed-metadata"));
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------ a playlist

    private static void addPlaylistChoices(Media m) {
        List<Option> mp4 = new ArrayList<Option>(), mkv = new ArrayList<Option>(), g3 = new ArrayList<Option>();
        for (int h : new int[] { 1080, 720, 480, 360 }) {
            String what = h == 1080 ? "Best, up to 1080p" : quality(h);
            mp4.add(new Option("MP4", "" + h, what, false, 0,
                    "-f", "bv*[height<=" + h + "][ext=mp4]+ba[ext=m4a]/bv*[height<=" + h + "]+ba/b[height<=" + h + "]/b",
                    MERGE, "mp4"));
            mkv.add(new Option("MKV", "" + h, what, false, 0,
                    "-f", "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/b", MERGE, "mkv", "--remux-video", "mkv"));
        }
        g3.add(threeGp(360, "360p or less", 0));
        g3.add(threeGp(240, "240p or less", 0));
        m.video.put("MP4", mp4);
        m.video.put("MKV", mkv);
        m.video.put("3GP", g3);
        for (String t : AUDIO_TYPES) m.audio.put(t, audioType(t, null, 0));
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

    private static String kbps(int k) {
        return k > 0 ? ", sound " + k + " kbps" : "";
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

    /** Only a web link, or "" (for the copied-link offer). */
    static String onlyLink(String text) {
        if (text == null) return "";
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("https?://[^\\s<>\"']+").matcher(text);
        return mm.find() ? mm.group() : "";
    }

    /** A link of a site Smart Downloader knows well (the copied-link offer is made only for these). */
    static boolean knownSite(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.matches("https?://([a-z0-9-]+\\.)*(youtube\\.com|youtu\\.be|tiktok\\.com|instagram\\.com|facebook\\.com|fb\\.watch|fb\\.com)(/.*)?");
    }

    /** The site that may need signing in (Instagram or Facebook), or null. */
    static String loginSite(String url) {
        String u = url.toLowerCase(Locale.US);
        if (u.matches("https?://([a-z0-9-]+\\.)*instagram\\.com(/.*)?")) return "instagram";
        if (u.matches("https?://([a-z0-9-]+\\.)*(facebook\\.com|fb\\.watch|fb\\.com)(/.*)?")) return "facebook";
        return null;
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

    // ------------------------------------------------------------------------------------------------ search

    static final class Found {
        final String title, by, url;
        final long duration;
        Found(String title, String by, String url, long duration) { this.title = title; this.by = by; this.url = url; this.duration = duration; }
        String spoken() {
            String d = duration(duration);
            return title + (by.length() > 0 ? ", by " + by : "") + (d.length() > 0 ? ", " + d : "");
        }
    }

    /** yt-dlp's answer to a YouTube search ("ytsearch20:words" with --flat-playlist). */
    static List<Found> searchResults(String json) throws Exception {
        List<Found> out = new ArrayList<Found>();
        JSONArray e = new JSONObject(json.trim()).optJSONArray("entries");
        if (e == null) return out;
        for (int i = 0; i < e.length(); i++) {
            JSONObject o = e.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String url = o.optString("url", "");
            if (!url.startsWith("http")) url = id.length() > 0 ? "https://www.youtube.com/watch?v=" + id : "";
            if (url.length() == 0 || url.contains("/channel/") || url.contains("/@")) continue;
            String t = clean(o.optString("title", ""));
            if (t.length() == 0) continue;
            out.add(new Found(t, clean(o.optString("channel", o.optString("uploader", ""))), url, (long) o.optDouble("duration", 0)));
        }
        return out;
    }
}
