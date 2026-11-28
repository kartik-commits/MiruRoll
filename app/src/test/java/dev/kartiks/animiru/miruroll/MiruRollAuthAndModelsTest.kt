package dev.kartiks.animiru.miruroll

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class MiruRollAuthAndModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun buildJwtWithPayload(payloadJson: String): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
        return "$header.$payload.sig"
    }

    @Test
    fun `extractJwtClaim reads claims cleanly and rejects invalid JWTs`() {
        val jwt = buildJwtWithPayload("""{"account_id":"ACC-123","profile_id":"PROF-9"}""")
        assertEquals("ACC-123", MiruRollAuth.extractJwtClaim(json, jwt, "account_id"))
        assertEquals("PROF-9", MiruRollAuth.extractJwtClaim(json, jwt, "profile_id"))
        assertNull(MiruRollAuth.extractJwtClaim(json, jwt, "missing_claim"))
        assertNull(MiruRollAuth.extractJwtClaim(json, "invalid-jwt", "account_id"))
    }

    @Test
    fun `MiruEpisodeData round trips cleanly even when versionGuid is null or omitted`() {
        val ep = MiruEpisodeData(episodeId = "EP-1", audioLocale = "ja-JP")
        val encoded = json.encodeToString(ep)
        val decoded = json.decodeFromString<MiruEpisodeData>(encoded)
        assertEquals("EP-1", decoded.episodeId)
        assertNull(decoded.versionGuid)
    }

    @Test
    fun `MoviesResponse and BrowseItem movie_listing_metadata deserialize cleanly`() {
        val browseJson = """
            {
              "total": 1,
              "data": [{
                "id": "MOV-LIST-1",
                "type": "movie_listing",
                "title": "Your Name",
                "movie_listing_metadata": {
                  "is_dubbed": true,
                  "tenant_categories": ["drama", "romance"]
                }
              }]
            }
        """.trimIndent()
        val browse = json.decodeFromString<BrowseResponse>(browseJson)
        assertEquals("movie_listing", browse.data.first().type)
        assertEquals(listOf("drama", "romance"), browse.data.first().movieListingMetadata?.categories)

        val moviesJson = """
            {
              "total": 1,
              "data": [{
                "id": "MOV-1",
                "listing_id": "MOV-LIST-1",
                "title": "Your Name",
                "available_date": "2024-06-01T00:00:00Z",
                "audio_locale": "ja-JP",
                "versions": [{"guid": "GV-1", "media_guid": "MV-1", "audio_locale": "ja-JP", "original": true}]
              }]
            }
        """.trimIndent()
        val movies = json.decodeFromString<MoviesResponse>(moviesJson)
        assertEquals("MOV-1", movies.data.first().id)
        assertEquals("GV-1", movies.data.first().versions?.first()?.guid)
    }

    @Test
    fun `PassTokenResponse picks non-blank code or pass_token`() {
        assertEquals("CODE-1", json.decodeFromString<PassTokenResponse>("""{"code":"CODE-1"}""").effectiveToken)
        assertEquals("PT-2", json.decodeFromString<PassTokenResponse>("""{"pass_token":"PT-2"}""").effectiveToken)
    }

    @Test
    fun `PlaybackV4Response deserializes token and session state`() {
        val raw = """
            {
              "url": "https://example.com/manifest.mpd",
              "audioLocale": "ja-JP",
              "token": "vid-tok-123",
              "session": {
                "renewSeconds": 30,
                "noNetworkRetryIntervalSeconds": 10,
                "noNetworkTimeoutSeconds": 300,
                "maximumPauseSeconds": 600,
                "sessionExpirationSeconds": 14400,
                "endOfVideoUnloadSeconds": 60,
                "usesStreamLimits": true
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<PlaybackV4Response>(raw)
        assertEquals("vid-tok-123", parsed.token)
        assertEquals(30, parsed.session?.renewSeconds)
        assertEquals(600, parsed.session?.maximumPauseSeconds)
        assertEquals(true, parsed.session?.usesStreamLimits)
    }

    @Test
    fun `SelectFilter selectedValue guards out-of-bounds state`() {
        val sortFilter = MiruRollFilters.SortFilter()
        sortFilter.state = 99
        assertEquals("popularity", sortFilter.selectedValue)
        sortFilter.state = 1
        assertEquals("newly_added", sortFilter.selectedValue)
        assertTrue(MiruRollFilters.FILTER_LIST.isNotEmpty())
    }
}
