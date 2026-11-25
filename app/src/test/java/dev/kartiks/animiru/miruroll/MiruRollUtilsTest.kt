package dev.kartiks.animiru.miruroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiruRollUtilsTest {

    @Test
    fun `parseManifestResolutions extracts DASH heights and filters bounds`() {
        val mpd = """
            <MPD>
              <AdaptationSet mimeType="video/mp4">
                <Representation id="1" width="1280" height="720"/>
                <Representation id="2" width="1920" height="1080"/>
                <Representation id="3" width="640" height="360"/>
                <Representation id="4" width="640" height="360"/>
                <Representation id="bad" width="100" height="50"/>
                <Representation id="huge" width="9999" height="8000"/>
              </AdaptationSet>
            </MPD>
        """.trimIndent()

        assertEquals(listOf(1080, 720, 360), MiruRoll.parseManifestResolutions(mpd))
    }

    @Test
    fun `parseManifestResolutions falls back to HLS RESOLUTION tags`() {
        val hls = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS="avc1"
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            480.m3u8
        """.trimIndent()

        assertEquals(listOf(1080, 720, 480), MiruRoll.parseManifestResolutions(hls))
    }

    @Test
    fun `parseIsoDateMillis parses offset and instant timestamps safely`() {
        val utcMs = MiruRoll.parseIsoDateMillis("2025-01-15T12:00:00Z")
        val offsetMs = MiruRoll.parseIsoDateMillis("2025-01-15T21:00:00+09:00")
        assertTrue(utcMs > 0L)
        assertEquals(utcMs, offsetMs)

        assertEquals(0L, MiruRoll.parseIsoDateMillis(null))
        assertEquals(0L, MiruRoll.parseIsoDateMillis(""))
        assertEquals(0L, MiruRoll.parseIsoDateMillis("not-a-date"))
    }

    @Test
    fun `formatEpisodeTitle formats whole fractional and special episode numbers`() {
        assertEquals("E12 - Awakening", MiruRoll.formatEpisodeTitle(12f, "12", "Awakening"))
        assertEquals("E13.5 - Recap", MiruRoll.formatEpisodeTitle(13.5f, "13.5", "Recap"))
        assertEquals("ESP1 - OVA", MiruRoll.formatEpisodeTitle(0f, "SP1", "OVA"))
        assertEquals("Prologue", MiruRoll.formatEpisodeTitle(0f, "", "Prologue"))
    }

    @Test
    fun `formatGenres normalizes Crunchyroll tenant categories`() {
        assertEquals(
            "Action, Slice Of Life, Sci-Fi",
            MiruRoll.formatGenres(listOf("action", "slice_of_life", "sci-fi", "Action", " ")),
        )
        assertNull(MiruRoll.formatGenres(emptyList()))
    }

    @Test
    fun `formatAudioSummary shows primary locale and dub counts`() {
        val versions = listOf(
            EpisodeVersion(audio_locale = "ja-JP", guid = "G1", original = true),
            EpisodeVersion(audio_locale = "en-US", guid = "G2", original = false),
            EpisodeVersion(audio_locale = "es-419", guid = "G3", original = false),
        )
        assertEquals("Audio: ja-JP (+2 dubs)", MiruRoll.formatAudioSummary(versions, "ja-JP"))
        assertEquals("Audio: ja-JP (+1 dub)", MiruRoll.formatAudioSummary(versions.take(2), "ja-JP"))
        assertEquals("Audio: ja-JP", MiruRoll.formatAudioSummary(versions.take(1), "ja-JP"))
        assertEquals("Audio: ja-JP", MiruRoll.formatAudioSummary(emptyList(), "ja-JP"))
    }
}
