package com.blindworldhub.smartdownloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

    @Test public void video() throws Exception {
        Formats.Media m = Formats.parse(VIDEO);
        assertEquals("Test video", m.title);
        assertFalse(m.playlist);
        assertEquals(3, m.video.size());
        assertTrue(m.video.get(0).label, m.video.get(0).label.startsWith("2160p (4K) video, MP4, about"));
        assertTrue(m.video.get(1).label, m.video.get(1).label.startsWith("720p (HD) video, MP4, about 21 MB"));
        assertTrue(m.video.get(2).label, m.video.get(2).label.startsWith("360p video, MP4, about 6.7 MB"));
        assertTrue(m.video.get(1).args.contains("mp4"));
        assertEquals(4, m.audio.size());
        assertTrue(m.audio.get(0).label, m.audio.get(0).label.startsWith("M4A audio, original quality, 130 kbps, about 1.9 MB"));
        assertTrue(m.audio.get(1).label, m.audio.get(1).label.startsWith("MP3 audio, 320 kbps (best), about 4.8 MB"));
        assertTrue(m.audio.get(1).args.contains("mp3"));
        assertEquals("2 minutes 5 seconds", Formats.duration(m.duration));
    }

    @Test public void playlist() throws Exception {
        Formats.Media m = Formats.parse("{\"_type\":\"playlist\",\"title\":\"Lectures\",\"entries\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        assertTrue(m.playlist);
        assertEquals(2, m.count);
        assertEquals(4, m.video.size());
        assertEquals(4, m.audio.size());
    }

    @Test public void noFormats() throws Exception {
        Formats.Media m = Formats.parse("{\"title\":\"Clip\",\"url\":\"https://x/y.mp4\"}");
        assertEquals(1, m.video.size());
        assertEquals("Best quality video", m.video.get(0).label);
    }

    @Test public void links() {
        assertEquals("https://youtu.be/abc?si=1", Formats.findLink("Watch this https://youtu.be/abc?si=1 now"));
        assertTrue(Formats.videoInPlaylist("https://www.youtube.com/watch?v=a&list=PL1"));
        assertTrue(Formats.playlistOnly("https://www.youtube.com/playlist?list=PL1"));
        assertFalse(Formats.playlistOnly("https://youtu.be/a?list=PL1"));
        assertEquals("1 hour 1 minute", Formats.duration(3661));
    }
}
