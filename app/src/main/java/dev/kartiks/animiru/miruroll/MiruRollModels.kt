package dev.kartiks.animiru.miruroll

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long = 3600,
    val refresh_token: String? = null,
    val scope: String? = null,
    val country: String? = null,
    val account_id: String? = null,
)

@Serializable
data class BrowseResponse(
    val total: Int = 0,
    val data: List<BrowseItem> = emptyList(),
)

@Serializable
data class SearchResponse(
    val total: Int = 0,
    val data: List<SearchSection> = emptyList(),
)

@Serializable
data class SearchSection(
    val type: String = "",
    val count: Int = 0,
    val items: List<BrowseItem> = emptyList(),
)

@Serializable
data class BrowseItem(
    val id: String,
    val type: String = "series",
    val title: String = "",
    val description: String = "",
    val images: BrowseImages? = null,
    @SerialName("series_metadata")
    val seriesMetadata: SeriesMetadata? = null,
)

@Serializable
data class BrowseImages(
    @SerialName("poster_tall")
    val posterTall: List<List<ImageSource>>? = null,
)

@Serializable
data class ImageSource(
    val width: Int = 0,
    val height: Int = 0,
    val type: String = "",
    val source: String = "",
)

@Serializable
data class SeriesMetadata(
    @SerialName("season_count")
    val seasonCount: Int = 1,
    @SerialName("episode_count")
    val episodeCount: Int = 0,
    @SerialName("is_simulcast")
    val isSimulcast: Boolean = false,
    @SerialName("audio_locales")
    val audioLocales: List<String> = emptyList(),
    @SerialName("subtitle_locales")
    val subtitleLocales: List<String> = emptyList(),
    @SerialName("tenant_categories")
    val categories: List<String> = emptyList(),
    val maturity_ratings: List<String> = emptyList(),
)

@Serializable
data class SeasonsResponse(
    val total: Int = 0,
    val data: List<SeasonItem> = emptyList(),
)

@Serializable
data class SeasonItem(
    val id: String,
    @SerialName("series_id")
    val seriesId: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("season_number")
    val seasonNumber: Double = 1.0,
    @SerialName("season_sequence_number")
    val sequenceNumber: Int = 1,
    @SerialName("number_of_episodes")
    val numberOfEpisodes: Int = 0,
    @SerialName("is_subbed")
    val isSubbed: Boolean = true,
    @SerialName("is_dubbed")
    val isDubbed: Boolean = false,
    @SerialName("audio_locale")
    val audioLocale: String? = null,
    @SerialName("audio_locales")
    val audioLocales: List<String> = emptyList(),
    @SerialName("subtitle_locales")
    val subtitleLocales: List<String> = emptyList(),
)

@Serializable
data class EpisodesResponse(
    val total: Int = 0,
    val data: List<EpisodeItem> = emptyList(),
)

@Serializable
data class EpisodeItem(
    val id: String,
    @SerialName("series_id")
    val seriesId: String = "",
    @SerialName("season_id")
    val seasonId: String = "",
    val title: String = "",
    val description: String = "",
    val episode: String = "1",
    @SerialName("episode_number")
    val episodeNumber: Float = 1f,
    @SerialName("sequence_number")
    val sequenceNumber: Float = 1f,
    val duration_ms: Long = 0L,
    @SerialName("is_premium_only")
    val isPremiumOnly: Boolean = false,
    @SerialName("is_subbed")
    val isSubbed: Boolean = true,
    @SerialName("is_dubbed")
    val isDubbed: Boolean = false,
    @SerialName("audio_locale")
    val audioLocale: String = "ja-JP",
    @SerialName("upload_date")
    val uploadDate: String? = null,
    val images: BrowseImages? = null,
    val streams_link: String? = null,
    val versions: List<EpisodeVersion>? = null,
)

@Serializable
data class EpisodeVersion(
    val audio_locale: String = "",
    val guid: String = "",
    @SerialName("media_guid")
    val mediaGuid: String = "",
    val original: Boolean = false,
    @SerialName("is_premium_only")
    val isPremiumOnly: Boolean = false,
)

@Serializable
data class PlaybackV4Response(
    val url: String = "",
    @SerialName("audioLocale")
    val audioLocale: String = "ja-JP",
    val token: String? = null,
    val drm: DrmEntry? = null,
    val drms: List<DrmEntry>? = null,
    val subtitles: Map<String, PlaybackSubtitle>? = null,
    val hardSubs: Map<String, HardSubEntry>? = null,
    val versions: List<EpisodeVersion>? = null,
)

@Serializable
data class DrmEntry(
    val name: String = "",
    val drmUrl: String = "",
)

@Serializable
data class HardSubEntry(
    val hlang: String = "",
    val url: String = "",
    val quality: String = "adaptive",
)

@Serializable
data class PlaybackSubtitle(
    val format: String = "ass",
    val language: String = "",
    val url: String = "",
)

@Serializable
data class MiruEpisodeData(
    val episodeId: String,
    val audioLocale: String,
    val versionGuid: String? = null,
    val mediaGuid: String? = null,
    val streamsLink: String? = null,
    val availableVersions: List<EpisodeVersion> = emptyList(),
)
