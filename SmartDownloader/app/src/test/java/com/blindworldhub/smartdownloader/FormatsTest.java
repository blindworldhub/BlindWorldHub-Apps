package com.blindworldhub.smartdownloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FormatsTest {

    private static final String VIDEO = "{\"_type\":\"video\",\"title\":\"  Test  video \",\"uploader\":\"Blind World Hub\",\"duration\":125,"
            + "\"webpage_url\":\"https://www.youtube.com/watch?v=abc\",\"formats\":["
            + "{\"format_id\":\"sb0\",\"ext\":\"mhtml\",\"vcodec\":\"none\",\"acodec\":\"none\",\"format_note\":\"storyboard\"},"
            + "{\"format_id\":\"140\",\"ext\":\"m4a\",\"vcodec\":\"none\",\"acodec\":\"mp4a.40.2\",\"abr\":129.5,\"filesize\":2000000},"
            + "{\"format_id\":\"251\",\"ext\":\"webm\",\"vcodec\":\"none\",\"acodec\":\"opus\",\"abr\":140,\"filesize\":2100000},"
            + "{\"format_id\":\"18\",\"ext\":\"mp4\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"height\":360,\"filesize\":5000000},"
            + "{\"format_id\":\"136\",\"ext\":\"mp4\",\"vcodec\":\"avc1\",\"acodec\":\"none\",\"height\":720,\"filesize\":20000000},"
            + "{\"format_id\":\"247\",\"ext\":\"webm\",\"vcodec\":\"vp9\",\"acodec\":\"none\",\"height\":720,\"filesize\":15000000},"
            + "{\"format_id\":\"313\",\"ext\":\"webm\",\"vcodec\":\"vp9\",\"acodec\":\"none\",\"height\":2160,\"tbr\":8000}"
            + "]}";

    private static List<String> labels(List<Formats.Option> l) {
        List<String> out = new ArrayList<String>();
        for (Formats.Option o : l) out.add(o.label);
        return out;
    }

    @Test public void video() throws Exception {
        Formats.Media m = Formats.parse(VIDEO);
        assertEquals("Test video", m.title);
        assertFalse(m.playlist);
        assertEquals(Arrays.asList("MP4", "MKV", "WEBM", "3GP"), new ArrayList<String>(m.video.keySet()));
        List<Formats.Option> mp4 = m.video.get("MP4");
        assertEquals(3, mp4.size());
        assertTrue(mp4.get(0).label, mp4.get(0).label.startsWith("2160p (4K), about"));
        assertEquals("720p (HD), about 21 MB, sound 130 kbps", mp4.get(1).label);
        assertEquals("360p, about 6.7 MB, sound 130 kbps", mp4.get(2).label);
        assertTrue(mp4.get(1).args.contains("mp4"));
        assertEquals(20000000L + 2000000L, mp4.get(1).bytes);
        // WEBM only where there is a WEBM picture (2160 and 720), with the opus sound
        assertEquals(2, m.video.get("WEBM").size());
        assertTrue(m.video.get("WEBM").get(1).label, m.video.get("WEBM").get(1).label.startsWith("720p (HD), about 16 MB, sound 140 kbps"));
        assertTrue(m.video.get("MKV").get(0).args.contains("mkv"));
        // 3GP: only 360p or smaller
        assertEquals(1, m.video.get("3GP").size());
        assertTrue(m.video.get("3GP").get(0).args.contains("3gp"));
        assertEquals("2 minutes 5 seconds", Formats.duration(m.duration));
    }

    @Test public void audio() throws Exception {
        Formats.Media m = Formats.parse(VIDEO);
        assertEquals(Arrays.asList("MP3", "M4A", "AAC", "OPUS", "OGG", "WAV", "FLAC"), new ArrayList<String>(m.audio.keySet()));
        List<Formats.Option> mp3 = m.audio.get("MP3");
        assertEquals(8, mp3.size());
        assertEquals("320 kbps (best), about 4.8 MB", mp3.get(0).label);
        assertEquals("128 kbps (good, smaller file), about 1.9 MB", mp3.get(4).label);
        assertEquals("48 kbps (smallest file, for speech), about 0.7 MB", mp3.get(7).label);
        assertTrue(mp3.get(4).args.containsAll(Arrays.asList("--audio-format", "mp3", "--audio-quality", "128K")));
        List<Formats.Option> m4a = m.audio.get("M4A");
        assertEquals(9, m4a.size());
        assertEquals("Original quality, as it comes, 130 kbps, about 1.9 MB (fastest)", m4a.get(0).label);
        assertTrue(m4a.get(1).args.contains("ba[acodec^=opus]/ba/b"));
        assertEquals(9, m.audio.get("OPUS").size());
        assertTrue(m.audio.get("OGG").get(0).args.contains("vorbis"));
        assertEquals(1, m.audio.get("WAV").size());
        assertEquals(1, m.audio.get("FLAC").size());
        assertTrue(m.audio.get("FLAC").get(0).args.contains("flac"));
        for (List<Formats.Option> l : m.audio.values()) for (Formats.Option o : l) assertTrue(o.audio);
        for (List<Formats.Option> l : m.video.values()) for (Formats.Option o : l) assertFalse(o.audio);
    }

    @Test public void playlist() throws Exception {
        Formats.Media m = Formats.parse("{\"_type\":\"playlist\",\"title\":\"Lectures\",\"entries\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        assertTrue(m.playlist);
        assertEquals(2, m.count);
        assertEquals(4, m.video.get("MP4").size());
        assertEquals(2, m.video.get("3GP").size());
        assertEquals(9, m.audio.get("M4A").size());
        assertEquals(8, m.audio.get("MP3").size());
    }

    @Test public void noFormats() throws Exception {
        Formats.Media m = Formats.parse("{\"title\":\"Clip\",\"url\":\"https://x/y.mp4\"}");
        assertEquals(1, m.video.get("MP4").size());
        assertEquals("Best quality", m.video.get("MP4").get(0).label);
        assertFalse(m.video.containsKey("WEBM"));
        assertEquals(1, m.video.get("3GP").size());
        // no M4A or OPUS sound alone: no "original" choice
        assertEquals(8, m.audio.get("M4A").size());
        assertEquals(labels(m.audio.get("MP3")).size(), 8);
    }

    @Test public void links() {
        assertEquals("https://youtu.be/abc?si=1", Formats.findLink("Watch this https://youtu.be/abc?si=1 now"));
        assertEquals("", Formats.onlyLink("no link here"));
        assertTrue(Formats.videoInPlaylist("https://www.youtube.com/watch?v=a&list=PL1"));
        assertTrue(Formats.playlistOnly("https://www.youtube.com/playlist?list=PL1"));
        assertFalse(Formats.playlistOnly("https://youtu.be/a?list=PL1"));
        assertEquals("1 hour 1 minute", Formats.duration(3661));
        assertTrue(Formats.knownSite("https://vm.tiktok.com/ZMabc/"));
        assertTrue(Formats.knownSite("https://www.instagram.com/reel/xyz/"));
        assertTrue(Formats.knownSite("https://fb.watch/abc/"));
        assertTrue(Formats.knownSite("https://m.youtube.com/watch?v=a"));
        assertFalse(Formats.knownSite("https://example.com/youtube.com"));
        assertEquals("instagram", Formats.loginSite("https://www.instagram.com/p/abc/"));
        assertEquals("facebook", Formats.loginSite("https://www.facebook.com/share/v/abc/"));
        assertNull(Formats.loginSite("https://www.tiktok.com/@a/video/1"));
    }

    @Test public void sameLink() {
        assertEquals(Job.sameLink("https://youtu.be/abcdefgh?si=XYZ"), Job.sameLink("https://www.youtube.com/watch?v=abcdefgh"));
        assertEquals(Job.sameLink("https://m.youtube.com/watch?v=abcdefgh&feature=share"), Job.sameLink("https://www.youtube.com/watch?v=abcdefgh"));
        assertEquals(Job.sameLink("https://www.instagram.com/reel/xyz/?igsh=123"), Job.sameLink("https://instagram.com/reel/xyz"));
        assertFalse(Job.sameLink("https://youtu.be/abcdefgh").equals(Job.sameLink("https://youtu.be/abcdefgX")));
        assertEquals("35 seconds left", Job.left(35));
        assertEquals("2 minutes left", Job.left(100));
        assertEquals("1 hour 5 minutes left", Job.left(3900));
    }

    @Test public void search() throws Exception {
        List<Formats.Found> f = Formats.searchResults("{\"_type\":\"playlist\",\"entries\":["
                + "{\"id\":\"abc12345678\",\"title\":\"Naat\",\"channel\":\"Ch\",\"duration\":245,\"url\":\"https://www.youtube.com/watch?v=abc12345678\"},"
                + "{\"id\":\"UCxyz\",\"title\":\"A channel\",\"url\":\"https://www.youtube.com/channel/UCxyz\"},"
                + "{\"id\":\"def\",\"title\":\"Talk\"}]}");
        assertEquals(2, f.size());
        assertEquals("Naat, by Ch, 4 minutes 5 seconds", f.get(0).spoken());
        assertEquals("https://www.youtube.com/watch?v=def", f.get(1).url);
    }
}
