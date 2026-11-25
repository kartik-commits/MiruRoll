package dev.kartiks.animiru.miruroll

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoDrm
import eu.kanade.tachiyomi.animesource.model.VideoDrmScheme
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime

class MiruRoll : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MiruRoll"
    override val baseUrl = "https://beta-api.crunchyroll.com"
    override val lang = "all"
    override val id: Long = 7463514907068706782L
    override val versionId = 1

    override fun getHomeUrl(): String = "https://www.crunchyroll.com"

    override val supportsLatest = true
    override val supportsRelatedAnime = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val auth by lazy {
        MiruRollAuth(preferences, json)
    }

    override val client by lazy {
        super.client.newBuilder()
            .addInterceptor(auth)
            .build()
    }

    // ============================== Popular / Discover ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val start = (page - 1) * PAGE_SIZE
        return GET("$baseUrl/content/v2/discover/browse?start=$start&n=$PAGE_SIZE&sort_by=popularity&locale=en-US")
    }

    private fun applyBrowseItemDetails(target: SAnime, info: BrowseItem) {
        val isMovie = info.type == "movie_listing"
        target.title = info.title
        target.description = info.description
        target.thumbnail_url = info.images?.posterTall?.firstOrNull()?.lastOrNull()?.source
        val categories = if (isMovie) info.movieListingMetadata?.categories else info.seriesMetadata?.categories
        formatGenres(categories.orEmpty())?.let { target.genre = it }
        target.status = when {
            isMovie -> SAnime.COMPLETED
            info.seriesMetadata?.isSimulcast == true -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun BrowseItem.toSAnime(): SAnime = SAnime.create().apply {
        val isMovie = type == "movie_listing"
        url = if (isMovie) "/movie_listing/$id" else "/series/$id"
        fetch_type = if (isMovie) FetchType.Episodes else FetchType.Seasons
        applyBrowseItemDetails(this, this@toSAnime)
        initialized = true
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val parsed = json.decodeFromString<BrowseResponse>(body)
        val animeList = parsed.data.map { it.toSAnime() }
        val start = response.request.url.queryParameter("start")?.toIntOrNull() ?: 0
        return AnimesPage(animeList, start + PAGE_SIZE < parsed.total)
    }

    // =============================== Latest Updates ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val start = (page - 1) * PAGE_SIZE
        return GET("$baseUrl/content/v2/discover/browse?start=$start&n=$PAGE_SIZE&sort_by=newly_added&locale=en-US")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val start = (page - 1) * PAGE_SIZE
        return if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            GET("$baseUrl/content/v2/discover/search?q=$encodedQuery&start=$start&n=$PAGE_SIZE&locale=en-US")
        } else {
            val params = MiruRollFilters.getFilterParams(filters)
            GET("$baseUrl/content/v2/discover/browse?start=$start&n=$PAGE_SIZE&sort_by=${params.sort}${params.media}${params.category}&locale=en-US")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        return if (response.request.url.encodedPath.contains("search")) {
            val parsed = json.decodeFromString<SearchResponse>(body)
            val topSection = parsed.data.firstOrNull { it.type == "top_results" }
            val seriesSection = parsed.data.firstOrNull { it.type == "series" }
            val movieSection = parsed.data.firstOrNull { it.type == "movie_listing" }
            val prioritizedItems = buildList {
                topSection?.items?.filter { it.type == "series" || it.type == "movie_listing" }?.let(::addAll)
                seriesSection?.items?.let(::addAll)
                movieSection?.items?.let(::addAll)
            }
            val items = prioritizedItems.ifEmpty {
                parsed.data.flatMap { it.items }.filter { it.type == "series" || it.type == "movie_listing" }
            }
            val animeList = items.distinctBy { it.id }.map { it.toSAnime() }
            val start = response.request.url.queryParameter("start")?.toIntOrNull() ?: 0
            val maxSectionCount = maxOf(seriesSection?.count ?: 0, movieSection?.count ?: 0)
            AnimesPage(animeList, start + PAGE_SIZE < maxSectionCount)
        } else {
            popularAnimeParse(response)
        }
    }

    override fun getFilterList(): AnimeFilterList = MiruRollFilters.FILTER_LIST

    // =========================== Native Season Updates ============================

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        val updatedParent = anime.apply {
            if (fetchDetails && url.startsWith("/series/")) {
                val seriesId = url.removePrefix("/series/")
                runCatching {
                    client.newCall(GET("$baseUrl/content/v2/cms/series/$seriesId?locale=en-US")).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val details = json.decodeFromString<BrowseResponse>(resp.body.string())
                        details.data.firstOrNull()?.let { info ->
                            applyBrowseItemDetails(this, info)
                            initialized = true
                        }
                    }
                }
            }
        }

        val childSeasons = if (fetchSeasons && anime.url.startsWith("/series/")) {
            val seriesId = anime.url.removePrefix("/series/")
            val body = client.newCall(GET("$baseUrl/content/v2/cms/series/$seriesId/seasons?locale=en-US"))
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Failed to fetch seasons (HTTP ${resp.code})")
                    }
                    resp.body.string()
                }
            val parsedSeasons = json.decodeFromString<SeasonsResponse>(body)
            parsedSeasons.data.sortedBy { it.sequenceNumber }.map { season ->
                SAnime.create().apply {
                    url = "/season/${season.id}"
                    title = season.title.ifBlank { "Season ${season.seasonNumber.toInt()}" }
                    description = season.description
                    fetch_type = FetchType.Episodes
                    season_number = season.seasonNumber
                    initialized = true
                }
            }
        } else {
            seasons
        }

        return SAnimeSeasonUpdate(updatedParent, childSeasons)
    }

    private fun fetchPlayheadsForCandidates(allCandidateIds: List<String>): Map<String, CrPlayhead> {
        if (allCandidateIds.isEmpty()) return emptyMap()
        val shouldSyncHistory = preferences.getBoolean("cr_pref_sync_history", true) && auth.isUserLoggedIn()
        return if (shouldSyncHistory) auth.getPlayheads(allCandidateIds) else emptyMap()
    }

    private fun buildPlayableEpisode(
        id: String,
        audioLocale: String,
        streamsLink: String?,
        versions: List<EpisodeVersion>,
        episodeNumber: Float,
        episodeName: String,
        uploadDate: String?,
        playheads: Map<String, CrPlayhead>,
    ): SEpisode {
        val origVersion = versions.firstOrNull { it.original } ?: versions.firstOrNull()
        val epData = MiruEpisodeData(
            episodeId = id,
            audioLocale = origVersion?.audio_locale ?: audioLocale,
            versionGuid = origVersion?.guid,
            mediaGuid = origVersion?.mediaGuid,
            streamsLink = streamsLink,
            availableVersions = versions,
        )
        val epCandidates = (listOf(id) + versions.map { it.guid }).distinct()
        val matchingPlayheads = epCandidates.mapNotNull { playheads[it] }
        val crPlayhead = matchingPlayheads.firstOrNull { it.fullyWatched }
            ?: matchingPlayheads.maxByOrNull { it.playhead }
        return SEpisode.create().apply {
            url = json.encodeToString(epData)
            name = episodeName
            episode_number = episodeNumber
            date_upload = parseIsoDateMillis(uploadDate)
            scanlator = formatAudioSummary(versions, audioLocale)
            if (crPlayhead != null) {
                memo = buildJsonObject {
                    put("cr_playhead", JsonPrimitive(crPlayhead.playhead))
                    put("cr_fully_watched", JsonPrimitive(crPlayhead.fullyWatched))
                }
            }
        }
    }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val updated = anime.apply {
            if (fetchDetails && url.startsWith("/movie_listing/")) {
                val listingId = url.removePrefix("/movie_listing/")
                runCatching {
                    client.newCall(GET("$baseUrl/content/v2/cms/movie_listings/$listingId?locale=en-US"))
                        .execute()
                        .use { resp ->
                            if (!resp.isSuccessful) return@use
                            val details = json.decodeFromString<BrowseResponse>(resp.body.string())
                            details.data.firstOrNull()?.let { info ->
                                applyBrowseItemDetails(this, info)
                                status = SAnime.COMPLETED
                                initialized = true
                            }
                        }
                }
            } else if (fetchDetails) {
                initialized = true
            }
        }

        val childEpisodes = if (fetchEpisodes && anime.url.startsWith("/season/")) {
            val seasonId = anime.url.removePrefix("/season/")
            val body = client.newCall(GET("$baseUrl/content/v2/cms/seasons/$seasonId/episodes?locale=en-US"))
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Failed to fetch episodes (HTTP ${resp.code})")
                    }
                    resp.body.string()
                }
            val parsedEpisodes = json.decodeFromString<EpisodesResponse>(body)

            val allCandidateIds = parsedEpisodes.data.flatMap { ep ->
                listOf(ep.id) + (ep.versions?.map { it.guid } ?: emptyList())
            }.distinct()
            val playheads = fetchPlayheadsForCandidates(allCandidateIds)

            parsedEpisodes.data.sortedBy { it.sequenceNumber }.map { ep ->
                val versions = ep.versions ?: emptyList()
                buildPlayableEpisode(
                    id = ep.id,
                    audioLocale = ep.audioLocale,
                    streamsLink = ep.streams_link,
                    versions = versions,
                    episodeNumber = ep.episodeNumber,
                    episodeName = formatEpisodeTitle(ep.episodeNumber, ep.episode, ep.title),
                    uploadDate = ep.uploadDate,
                    playheads = playheads,
                )
            }
        } else if (fetchEpisodes && anime.url.startsWith("/movie_listing/")) {
            val listingId = anime.url.removePrefix("/movie_listing/")
            val body = client.newCall(GET("$baseUrl/content/v2/cms/movie_listings/$listingId/movies?locale=en-US"))
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Failed to fetch movies (HTTP ${resp.code})")
                    }
                    resp.body.string()
                }
            val parsedMovies = json.decodeFromString<MoviesResponse>(body)

            val allCandidateIds = parsedMovies.data.flatMap { movie ->
                listOf(movie.id) + (movie.versions?.map { it.guid } ?: emptyList())
            }.distinct()
            val playheads = fetchPlayheadsForCandidates(allCandidateIds)

            parsedMovies.data.mapIndexed { index, movie ->
                val versions = movie.versions ?: emptyList()
                val movieNumber = (index + 1).toFloat()
                val displayName = if (parsedMovies.data.size > 1) {
                    "Movie ${index + 1} - ${movie.title}"
                } else {
                    movie.title.ifBlank { "Movie" }
                }
                buildPlayableEpisode(
                    id = movie.id,
                    audioLocale = movie.audioLocale,
                    streamsLink = movie.streams_link,
                    versions = versions,
                    episodeNumber = movieNumber,
                    episodeName = displayName,
                    uploadDate = movie.availableDate,
                    playheads = playheads,
                )
            }
        } else {
            episodes
        }

        return SAnimeEpisodeUpdate(updated, childEpisodes)
    }

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    // ============================ Playback & Widevine DRM =============================

    private data class CachedPlayback(
        val targetId: String,
        val response: PlaybackV4Response,
        val cachedAtMs: Long,
    )

    private val resolutionCache = ConcurrentHashMap<String, List<Int>>()
    private val requestMutex = Mutex()
    private var lastRequestTime = 0L
    private val cachedAuthEpoch = AtomicLong(-1L)
    private val failedTargets = ConcurrentHashMap<String, Long>()
    private val playbackCache = ConcurrentHashMap<String, CachedPlayback>()

    private fun invalidateCachesIfAuthChanged() {
        val currentEpoch = auth.getAuthEpoch()
        val prevEpoch = cachedAuthEpoch.getAndSet(currentEpoch)
        if (prevEpoch != -1L && prevEpoch != currentEpoch) {
            playbackCache.clear()
            resolutionCache.clear()
            failedTargets.clear()
        }
    }

    private fun resolveTargetVersion(epData: MiruEpisodeData): EpisodeVersion? {
        val isSpecificDub = !epData.versionGuid.isNullOrBlank()
        if (isSpecificDub) {
            return epData.availableVersions.firstOrNull { it.guid == epData.versionGuid }
        }
        val preferredAudio = auth.getActiveProfilePreferredAudioLanguage()
        val preferredMatch = if (!preferredAudio.isNullOrBlank()) {
            epData.availableVersions.firstOrNull {
                it.audio_locale.equals(preferredAudio, ignoreCase = true)
            }
        } else {
            null
        }
        return preferredMatch
            ?: epData.availableVersions.firstOrNull { it.original }
            ?: epData.availableVersions.firstOrNull()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val epData = runCatching {
            json.decodeFromString<MiruEpisodeData>(episode.url)
        }.getOrNull()

        val parsedHeights = if (epData != null) {
            val cacheKey = epData.versionGuid ?: epData.episodeId
            resolutionCache[cacheKey] ?: runCatching {
                val (_, playback) = getPlayback(epData)
                val dashUrl = extractStreamUrl(playback)
                if (!dashUrl.isNullOrBlank()) {
                    val heights = fetchDashResolutions(dashUrl)
                    if (heights.isNotEmpty()) {
                        resolutionCache[cacheKey] = heights
                        heights
                    } else null
                } else null
            }.getOrNull()
        } else null

        val heights = parsedHeights?.takeIf { it.isNotEmpty() } ?: DEFAULT_QUALITIES

        val videoList = mutableListOf(
            Video(
                videoUrl = episode.url,
                videoTitle = "Auto (Adaptive)",
                resolution = null,
                preferred = true,
                initialized = false,
            ),
        )

        heights.forEach { h ->
            videoList.add(
                Video(
                    videoUrl = episode.url,
                    videoTitle = "${h}p",
                    resolution = h,
                    preferred = false,
                    initialized = false,
                ),
            )
        }

        return listOf(
            Hoster(
                hosterName = Hoster.NO_HOSTER_LIST,
                videoList = videoList,
            ),
        )
    }

    private suspend fun fetchDashResolutions(dashUrl: String): List<Int> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(dashUrl)
            .headers(
                Headers.headersOf(
                    "User-Agent", MiruRollAuth.WEB_USER_AGENT,
                    "Authorization", "Bearer ${auth.getAccessToken()}",
                ),
            )
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.use { r ->
            if (!r.isSuccessful) return@use emptyList()
            parseManifestResolutions(r.body.string())
        }
    }

    private fun extractStreamUrl(playback: PlaybackV4Response): String? =
        playback.url.ifBlank {
            playback.hardSubs?.get("en-US")?.url
                ?: playback.hardSubs?.get("none")?.url
                ?: playback.hardSubs?.values?.firstOrNull()?.url
        }?.ifBlank { null }

    private suspend fun getPlayback(epData: MiruEpisodeData): Pair<String, PlaybackV4Response> {
        invalidateCachesIfAuthChanged()
        val isSpecificDub = !epData.versionGuid.isNullOrBlank()
        val targetVersion = resolveTargetVersion(epData)

        val candidateIds = if (isSpecificDub) {
            listOfNotNull(
                epData.versionGuid,
                targetVersion?.guid,
                targetVersion?.mediaGuid?.removeSuffix("V"),
                targetVersion?.mediaGuid,
            ).distinct()
        } else {
            listOfNotNull(
                targetVersion?.guid,
                targetVersion?.mediaGuid?.removeSuffix("V"),
                epData.episodeId,
                epData.mediaGuid?.removeSuffix("V"),
            ).distinct()
        }

        val now = System.currentTimeMillis()
        var resolvedTargetId = candidateIds.firstOrNull().orEmpty()
        var parsed: PlaybackV4Response? = candidateIds.firstNotNullOfOrNull { id ->
            val entry = playbackCache[id] ?: return@firstNotNullOfOrNull null
            if (now - entry.cachedAtMs < PLAYBACK_CACHE_TTL_MS) {
                resolvedTargetId = entry.targetId
                entry.response
            } else {
                playbackCache.remove(id, entry)
                null
            }
        }

        var lastHttpFailure = 0
        if (parsed == null) {
            val activeCandidates = candidateIds.filter { targetId ->
                (failedTargets[targetId] ?: 0L) <= now
            }
            if (activeCandidates.isEmpty() && candidateIds.isNotEmpty()) {
                val profileName = auth.getActiveProfileName()
                throw IllegalStateException("Playback unavailable for profile '$profileName' (content may be restricted by maturity rating or region)")
            }

            var fatalThrottled = false
            for (targetId in activeCandidates) {
                val playUrl = "https://www.crunchyroll.com/playback/v4/$targetId/web/firefox/play"
                val request = Request.Builder()
                    .url(playUrl)
                    .headers(
                        Headers.headersOf(
                            "User-Agent", MiruRollAuth.WEB_USER_AGENT,
                            "Authorization", "Bearer ${auth.getAccessToken()}",
                        ),
                    )
                    .get()
                    .build()

                runCatching {
                    val resp = requestMutex.withLock {
                        val currentTime = System.currentTimeMillis()
                        val diff = currentTime - lastRequestTime
                        if (diff < 350L) {
                            delay(350L - diff)
                        }
                        lastRequestTime = System.currentTimeMillis()
                        client.newCall(request).execute()
                    }

                    resp.use { r ->
                        if (r.isSuccessful) {
                            val body = r.body.string()
                            if (body.isNotBlank()) {
                                val decoded = json.decodeFromString<PlaybackV4Response>(body)
                                parsed = decoded
                                resolvedTargetId = targetId
                                val cachedAt = System.currentTimeMillis()
                                val entry = CachedPlayback(targetId, decoded, cachedAt)
                                if (playbackCache.size >= 32) {
                                    playbackCache.entries.removeIf { cachedAt - it.value.cachedAtMs >= PLAYBACK_CACHE_TTL_MS }
                                }
                                playbackCache[targetId] = entry
                                if (isSpecificDub && !epData.versionGuid.isNullOrBlank()) {
                                    playbackCache[epData.versionGuid] = entry
                                }
                                failedTargets.remove(targetId)
                            }
                        } else {
                            lastHttpFailure = r.code
                            Log.w(TAG, "Play endpoint failed: $playUrl -> HTTP ${r.code}")
                            if (r.code == 420 || r.code == 429 || r.code == 403) {
                                failedTargets[targetId] = System.currentTimeMillis() + 60_000L
                                fatalThrottled = true
                                return@use
                            } else if (r.code == 404) {
                                failedTargets[targetId] = System.currentTimeMillis() + 300_000L
                            }
                        }
                    }
                }

                if (parsed != null || fatalThrottled) break
            }
        }

        val nonNullParsed = parsed ?: when {
            lastHttpFailure == 403 && targetVersion?.isPremiumOnly == true && !auth.isUserLoggedIn() ->
                throw IllegalStateException("Premium episode requires logging into Crunchyroll in extension settings or WebView")
            lastHttpFailure == 403 ->
                throw IllegalStateException("Audio dub is unavailable or region-locked (HTTP 403)")
            else ->
                throw IllegalStateException("Audio dub is unavailable or region-locked")
        }
        if (!isSpecificDub && epData.episodeId.isNotBlank()) {
            val cachedAt = System.currentTimeMillis()
            playbackCache[epData.episodeId] = CachedPlayback(resolvedTargetId, nonNullParsed, cachedAt)
        }

        return Pair(resolvedTargetId, nonNullParsed)
    }

    override suspend fun resolveVideo(video: Video): Video {
        val epData = runCatching {
            json.decodeFromString<MiruEpisodeData>(video.videoUrl)
        }.getOrNull() ?: return video

        val (resolvedTargetId, nonNullParsed) = getPlayback(epData)

        val targetVersion = resolveTargetVersion(epData)

        val dashUrl = extractStreamUrl(nonNullParsed)
            ?: throw IllegalStateException("No DASH playback URL found in response")

        val licenseUrl = nonNullParsed.drm?.drmUrl?.ifBlank { null }
            ?: nonNullParsed.drms?.firstOrNull { it.name.equals("widevine", ignoreCase = true) }?.drmUrl?.ifBlank { null }
            ?: "https://www.crunchyroll.com/license/v1/license/widevine?specconform=true"

        val subtitleTracks = nonNullParsed.subtitles?.filter {
            it.key != "none" && !it.value.url.isNullOrBlank()
        }?.map { (langKey, sub) ->
            val lang = sub.language.ifBlank { langKey }
            Track(sub.url, lang)
        } ?: emptyList()

        val versionsList = (nonNullParsed.versions?.ifEmpty { null } ?: epData.availableVersions)
            .filter { it.guid.isNotBlank() }
            .distinctBy { it.guid }
        val audioTracks = if (versionsList.isNotEmpty()) {
            versionsList.map { ver ->
                val langDisplay = getLanguageDisplayName(ver.audio_locale) + (if (ver.original) " (Original)" else " Dub")
                Track(ver.guid, langDisplay)
            }
        } else {
            listOf(Track(resolvedTargetId, getLanguageDisplayName(epData.audioLocale)))
        }

        val activeAudioDub = if (versionsList.isNotEmpty()) {
            val matched = versionsList.firstOrNull { it.guid == resolvedTargetId }
                ?: versionsList.firstOrNull { it.mediaGuid == resolvedTargetId || it.mediaGuid.removeSuffix("V") == resolvedTargetId }
                ?: targetVersion
                ?: versionsList.firstOrNull { it.original }
                ?: versionsList.first()
            getLanguageDisplayName(matched.audio_locale) + (if (matched.original) " (Original)" else " Dub")
        } else {
            getLanguageDisplayName(epData.audioLocale)
        }

        val drmHeadersBuilder = Headers.Builder()
            .add("Authorization", "Bearer ${auth.getAccessToken()}")
            .add("User-Agent", MiruRollAuth.WEB_USER_AGENT)
            .add("X-Cr-Content-Id", resolvedTargetId)

        if (!nonNullParsed.token.isNullOrBlank()) {
            drmHeadersBuilder.add("X-Cr-Video-Token", nonNullParsed.token)
        }

        val drm = VideoDrm(
            scheme = VideoDrmScheme.WIDEVINE,
            licenseUrl = licenseUrl,
            licenseHeaders = drmHeadersBuilder.build(),
        )

        val mediaHeaders = Headers.Builder()
            .add("User-Agent", MiruRollAuth.WEB_USER_AGENT)
            .add("Authorization", "Bearer ${auth.getAccessToken()}")
            .build()

        val cleanTitle = video.videoTitle.replace(Regex("""\[.*?\]"""), "").trim().ifBlank { "Auto (Adaptive)" }
        val titleWithDub = "$cleanTitle [$activeAudioDub]"

        return video.copy(
            videoUrl = dashUrl,
            videoTitle = titleWithDub,
            resolution = video.resolution,
            initialized = true,
            drm = drm,
            headers = mediaHeaders,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
        )
    }

    private fun getLanguageDisplayName(locale: String): String {
        return when (locale.lowercase()) {
            "en-us", "en" -> "English"
            "es-419", "es-la" -> "Spanish (Latin America)"
            "es-es", "es" -> "Spanish (Spain)"
            "pt-br", "pt" -> "Portuguese (Brazil)"
            "fr-fr", "fr" -> "French"
            "de-de", "de" -> "German"
            "it-it", "it" -> "Italian"
            "ar-sa", "ar-me", "ar" -> "Arabic"
            "ru-ru", "ru" -> "Russian"
            "hi-in", "hi" -> "Hindi"
            "ta-in", "ta" -> "Tamil"
            "te-in", "te" -> "Telugu"
            "id-id", "id" -> "Indonesian"
            "ms-my", "ms" -> "Malay"
            "th-th", "th" -> "Thai"
            "vi-vn", "vi" -> "Vietnamese"
            "zh-cn", "zh-hk", "zh-tw", "zh" -> "Chinese"
            "ko-kr", "ko" -> "Korean"
            "pl-pl", "pl" -> "Polish"
            "ja-jp", "ja" -> "Japanese"
            else -> locale
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    // ============================ Playhead & Watch History Sync =============================

    private val lastPlayheadSyncTime = ConcurrentHashMap<String, Long>()
    private val lastPlayheadSyncPos = ConcurrentHashMap<String, Long>()
    private val lastMarkedWatched = ConcurrentHashMap<String, Long>()

    private fun getCandidateEpisodeIds(epData: MiruEpisodeData): List<String> {
        val candidates = mutableListOf<String>()
        if (epData.episodeId.isNotBlank()) candidates.add(epData.episodeId)
        epData.versionGuid?.let { if (it.isNotBlank()) candidates.add(it) }
        epData.mediaGuid?.removeSuffix("V")?.let { if (it.isNotBlank()) candidates.add(it) }
        epData.availableVersions.forEach { v ->
            if (v.guid.isNotBlank()) candidates.add(v.guid)
            v.mediaGuid.removeSuffix("V").let { if (it.isNotBlank()) candidates.add(it) }
        }
        return candidates.distinct()
    }

    private fun fetchBestPlayhead(episodeUrl: String): CrPlayhead? {
        if (!preferences.getBoolean("cr_pref_sync_history", true)) return null
        if (!auth.isUserLoggedIn()) return null
        val epData = runCatching {
            json.decodeFromString<MiruEpisodeData>(episodeUrl)
        }.getOrNull() ?: return null
        val candidateIds = getCandidateEpisodeIds(epData)
        val playheads = auth.getPlayheads(candidateIds)
        return playheads.values.firstOrNull { it.fullyWatched }
            ?: playheads.values.maxByOrNull { it.playhead }
    }

    /** Single-call fast path used by Animiru SourcePlayheadSync: `[playheadSeconds, isFullyWatchedFlag]`. */
    fun getPlayheadState(episodeUrl: String): LongArray {
        val best = fetchBestPlayhead(episodeUrl) ?: return longArrayOf(0L, 0L)
        return longArrayOf(
            best.playhead.coerceAtLeast(0L),
            if (best.fullyWatched) 1L else 0L,
        )
    }

    fun getPlayhead(episodeUrl: String): Long =
        fetchBestPlayhead(episodeUrl)?.playhead ?: 0L

    fun isEpisodeFullyWatched(episodeUrl: String): Boolean =
        fetchBestPlayhead(episodeUrl)?.fullyWatched == true

    fun savePlayhead(episodeUrl: String, positionSeconds: Long, totalSeconds: Long, onComplete: Boolean) {
        if (!preferences.getBoolean("cr_pref_sync_history", true)) return
        if (!auth.isUserLoggedIn()) return
        val epData = runCatching {
            json.decodeFromString<MiruEpisodeData>(episodeUrl)
        }.getOrNull() ?: return

        val epId = epData.episodeId
        val candidateIds = getCandidateEpisodeIds(epData)
        val now = System.currentTimeMillis()

        if (onComplete) {
            val lastWatchedTime = lastMarkedWatched[epId] ?: 0L
            if (now - lastWatchedTime < 30_000L) {
                return
            }
            lastMarkedWatched[epId] = now
            candidateIds.forEach { lastMarkedWatched[it] = now }
        } else {
            val lastTime = lastPlayheadSyncTime[epId] ?: 0L
            val lastPos = lastPlayheadSyncPos[epId] ?: -1L
            val timeElapsed = now - lastTime
            val posDiff = Math.abs(positionSeconds - lastPos)
            if (timeElapsed < 10_000L && posDiff < 5) {
                return
            }
            lastPlayheadSyncTime[epId] = now
            lastPlayheadSyncPos[epId] = positionSeconds
        }

        scope.launch {
            try {
                if (onComplete) {
                    for (chunk in candidateIds.chunked(50)) {
                        val idsParam = chunk.joinToString(",")
                        Log.d(TAG, "Marking episode candidates $idsParam as watched on Crunchyroll")
                        auth.markAsWatched(idsParam).getOrThrow()
                    }
                } else if (positionSeconds > 0) {
                    Log.d(TAG, "Saving playhead ${positionSeconds}s for episode $epId on Crunchyroll")
                    auth.savePlayhead(epId, positionSeconds).getOrThrow()
                }
            } catch (e: Exception) {
                if (onComplete) {
                    lastMarkedWatched.remove(epId, now)
                    candidateIds.forEach { lastMarkedWatched.remove(it, now) }
                } else {
                    lastPlayheadSyncTime.remove(epId, now)
                    lastPlayheadSyncPos.remove(epId, positionSeconds)
                }
                Log.w(TAG, "Failed to sync playhead for $epId", e)
            }
        }
    }

    fun deletePlayhead(episodeUrl: String) {
        if (!preferences.getBoolean("cr_pref_sync_history", true)) return
        if (!auth.isUserLoggedIn()) return
        val epData = runCatching {
            json.decodeFromString<MiruEpisodeData>(episodeUrl)
        }.getOrNull() ?: return

        val candidateIds = getCandidateEpisodeIds(epData)
        candidateIds.forEach { id ->
            lastMarkedWatched.remove(id)
            lastPlayheadSyncTime.remove(id)
            lastPlayheadSyncPos.remove(id)
        }

        scope.launch {
            try {
                for (chunk in candidateIds.chunked(50)) {
                    val idsParam = chunk.joinToString(",")
                    Log.d(TAG, "Deleting watch history for episodes $idsParam on Crunchyroll")
                    auth.deleteWatchHistory(idsParam).getOrThrow()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete watch history for ${epData.episodeId}", e)
            }
        }
    }

    fun setEpisodesSeen(episodeUrls: List<String>, seen: Boolean) {
        if (!preferences.getBoolean("cr_pref_sync_history", true)) return
        if (!auth.isUserLoggedIn()) return
        val allCandidateIds = episodeUrls.flatMap { url ->
            val epData = runCatching { json.decodeFromString<MiruEpisodeData>(url) }.getOrNull()
            if (epData != null) getCandidateEpisodeIds(epData) else emptyList()
        }.distinct()
        if (allCandidateIds.isEmpty()) return

        allCandidateIds.forEach { id ->
            lastMarkedWatched.remove(id)
            lastPlayheadSyncTime.remove(id)
            lastPlayheadSyncPos.remove(id)
        }

        scope.launch {
            try {
                for (chunk in allCandidateIds.chunked(50)) {
                    val idsParam = chunk.joinToString(",")
                    if (seen) {
                        Log.d(TAG, "Batch marking episodes $idsParam as watched on Crunchyroll")
                        auth.markAsWatched(idsParam).getOrThrow()
                    } else {
                        Log.d(TAG, "Batch deleting watch history for episodes $idsParam on Crunchyroll")
                        auth.deleteWatchHistory(idsParam).getOrThrow()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to batch sync seen status ($seen)", e)
            }
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        val scope = CoroutineScope(Dispatchers.IO)

        fun getAccountSummary(): String {
            return if (auth.isUserLoggedIn()) {
                val activeProfile = auth.getActiveProfileName()
                val webViewRt = auth.getRefreshTokenFromWebView()
                val email = preferences.getString(MiruRollAuth.PREF_EMAIL, null)
                val method = when {
                    !webViewRt.isNullOrBlank() -> "WebView Session"
                    !email.isNullOrBlank() -> email
                    else -> "Refresh Token"
                }
                "Logged in ($method) • Profile: $activeProfile"
            } else {
                "Guest Mode. Tap the 🌐 Globe icon on top right to Log In via WebView!"
            }
        }

        val accountPref = Preference().apply {
            key = "pref_account_status"
            title = "Account Status"
            summary = getAccountSummary()
        }
        screen.addPreference(accountPref)

        SwitchPreferenceCompat(context).apply {
            key = "cr_pref_sync_history"
            title = "Sync Watch History"
            summary = "Synchronize playhead positions and watched episodes with Crunchyroll"
            setDefaultValue(true)
        }.also(screen::addPreference)

        if (auth.isUserLoggedIn()) {
            val cachedProfiles = auth.getCachedProfiles()
            val activeId = auth.getActiveProfileId()
            val activeName = auth.getActiveProfileName()

            fun formatProfileLabel(p: CrProfile): String {
                val lock = if (p.isPinProtected) " 🔒" else ""
                val primary = if (p.isPrimary) " (Primary)" else ""
                return "${p.profileName}$primary$lock"
            }

            val profilePref = ListPreference(context).apply {
                key = MiruRollAuth.PREF_ACTIVE_PROFILE
                title = "Active Profile"
                summary = "Current: $activeName"

                if (cachedProfiles.isNotEmpty()) {
                    entries = cachedProfiles.map { formatProfileLabel(it) }.toTypedArray()
                    entryValues = cachedProfiles.map { it.profileId }.toTypedArray()
                    value = activeId ?: cachedProfiles.firstOrNull()?.profileId
                } else {
                    entries = arrayOf(activeName)
                    entryValues = arrayOf(activeId ?: "")
                    value = activeId
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val newProfileId = newValue as String
                    val pin = preferences.getString(MiruRollAuth.PREF_PROFILE_PIN, null)?.trim()
                    scope.launch {
                        val result = auth.switchProfile(newProfileId, pin)
                        withContext(Dispatchers.Main) {
                            result.onSuccess { switched ->
                                summary = "Current: ${switched.profileName}"
                                value = switched.profileId
                                accountPref.summary = getAccountSummary()
                                playbackCache.clear()
                                resolutionCache.clear()
                                failedTargets.clear()
                                Toast.makeText(context, "Switched profile to ${switched.profileName}", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "Failed to switch profile: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    true
                }
            }
            screen.addPreference(profilePref)

            EditTextPreference(context).apply {
                key = MiruRollAuth.PREF_PROFILE_PIN
                title = "Profile PIN (Optional)"
                summary = if (preferences.getString(MiruRollAuth.PREF_PROFILE_PIN, null).isNullOrBlank()) {
                    "Not set (only needed for PIN-protected profiles)"
                } else {
                    "••••"
                }
                dialogTitle = "Enter Profile PIN"
                setOnPreferenceChangeListener { _, newValue ->
                    val newPin = (newValue as? String)?.trim()
                    preferences.edit().putString(MiruRollAuth.PREF_PROFILE_PIN, newPin).apply()
                    summary = if (newPin.isNullOrBlank()) {
                        "Not set (only needed for PIN-protected profiles)"
                    } else {
                        "••••"
                    }
                    true
                }
            }.also(screen::addPreference)

            Preference().apply {
                key = "pref_refresh_profiles"
                title = "Refresh Profiles"
                summary = "Reload profile list from Crunchyroll"
                setOnPreferenceClickListener {
                    summary = "Refreshing profiles..."
                    scope.launch {
                        runCatching {
                            auth.fetchProfiles()
                        }.onSuccess { profiles ->
                            withContext(Dispatchers.Main) {
                                profilePref.entries = profiles.map { formatProfileLabel(it) }.toTypedArray()
                                profilePref.entryValues = profiles.map { it.profileId }.toTypedArray()
                                val active = profiles.find { it.isSelected } ?: profiles.firstOrNull()
                                if (active != null) {
                                    profilePref.value = active.profileId
                                    profilePref.summary = "Current: ${active.profileName}"
                                }
                                summary = "Reload profile list from Crunchyroll"
                                accountPref.summary = getAccountSummary()
                                Toast.makeText(context, "Profiles refreshed (${profiles.size} found)", Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { err ->
                            withContext(Dispatchers.Main) {
                                summary = "Failed to refresh profiles"
                                Toast.makeText(context, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    true
                }
            }.also(screen::addPreference)

            // Auto-refresh profiles in the background when settings screen opens
            scope.launch {
                runCatching { auth.fetchProfiles() }.onSuccess { profiles ->
                    withContext(Dispatchers.Main) {
                        profilePref.entries = profiles.map { formatProfileLabel(it) }.toTypedArray()
                        profilePref.entryValues = profiles.map { it.profileId }.toTypedArray()
                        val active = profiles.find { it.isSelected } ?: profiles.firstOrNull()
                        if (active != null) {
                            profilePref.value = active.profileId
                            profilePref.summary = "Current: ${active.profileName}"
                            accountPref.summary = getAccountSummary()
                        }
                    }
                }
            }
        }

        EditTextPreference(context).apply {
            key = MiruRollAuth.PREF_EMAIL
            title = "MiruRoll Account Email"
            summary = preferences.getString(MiruRollAuth.PREF_EMAIL, null)?.ifBlank { null }
                ?: "Leave empty for guest access"
            dialogTitle = "Enter MiruRoll Email"
            setOnPreferenceChangeListener { _, _ ->
                auth.clearSession()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = MiruRollAuth.PREF_PASSWORD
            title = "MiruRoll Password"
            summary = if (preferences.getString(MiruRollAuth.PREF_PASSWORD, null).isNullOrBlank()) {
                "Password for subscriber access"
            } else {
                "••••••••"
            }
            dialogTitle = "Enter Password"
            setOnPreferenceChangeListener { _, _ ->
                auth.clearSession()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(context).apply {
            key = MiruRollAuth.PREF_CUSTOM_REFRESH_TOKEN
            title = "Custom Refresh Token (Optional)"
            summary = "For Google/Apple SSO or 2FA: paste your Crunchyroll refresh_token"
            dialogTitle = "Enter Refresh Token"
            setOnPreferenceChangeListener { _, _ ->
                auth.clearSession()
                true
            }
        }.also(screen::addPreference)
    }

    private companion object {
        private const val TAG = "MiruRoll"
        private const val PAGE_SIZE = 24
        private const val PLAYBACK_CACHE_TTL_MS = 5 * 60_000L
        private val DEFAULT_QUALITIES = listOf(1080, 720, 480, 360, 240)
        private val MPD_HEIGHT_REGEX = Regex("""\bheight=["'](\d+)["']""")
        private val HLS_RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

        internal fun parseManifestResolutions(manifest: String): List<Int> {
            val matches = MPD_HEIGHT_REGEX.findAll(manifest).toList().ifEmpty {
                HLS_RESOLUTION_REGEX.findAll(manifest).toList()
            }
            return matches
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 144..4320 }
                .distinct()
                .sortedDescending()
                .toList()
        }

        internal fun parseIsoDateMillis(isoDate: String?): Long {
            val raw = isoDate?.trim()?.ifBlank { null } ?: return 0L
            return runCatching {
                OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            }.getOrElse {
                runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
            }
        }

        internal fun formatEpisodeTitle(episodeNumber: Float, episodeLabel: String, title: String): String {
            val prefix = when {
                episodeNumber > 0f -> {
                    if (episodeNumber % 1f == 0f) "E${episodeNumber.toInt()}" else "E$episodeNumber"
                }
                episodeLabel.isNotBlank() && episodeLabel != "0" -> "E$episodeLabel"
                else -> null
            }
            return when {
                prefix == null -> title
                title.isBlank() -> prefix
                else -> "$prefix - $title"
            }
        }

        internal fun formatGenres(categories: List<String>): String? {
            if (categories.isEmpty()) return null
            return categories.mapNotNull { raw ->
                val clean = raw.trim().lowercase()
                if (clean.isBlank()) {
                    null
                } else if (clean == "sci-fi" || clean == "sci_fi") {
                    "Sci-Fi"
                } else {
                    clean.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { word ->
                        word.replaceFirstChar { ch -> ch.uppercaseChar() }
                    }
                }
            }.distinct().joinToString(", ").ifBlank { null }
        }

        internal fun formatAudioSummary(versions: List<EpisodeVersion>, fallbackLocale: String): String {
            val distinctLocales = versions.mapNotNull { it.audio_locale.ifBlank { null } }.distinct()
            if (distinctLocales.isEmpty()) return "Audio: $fallbackLocale"
            val primary = versions.firstOrNull { it.original }?.audio_locale?.ifBlank { null } ?: distinctLocales.first()
            val dubCount = (distinctLocales.size - 1).coerceAtLeast(0)
            return if (dubCount > 0) {
                "Audio: $primary (+$dubCount dub${if (dubCount == 1) "" else "s"})"
            } else {
                "Audio: $primary"
            }
        }
    }
}
