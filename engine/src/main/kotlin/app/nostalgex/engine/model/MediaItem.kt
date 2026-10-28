package app.nostalgex.engine.model

/**
 * One programme in the library: a film, an episode or a music video.
 *
 * Field for field the item the upstream web tuner's loaders build (plex-tuner.html
 * `scanOnePlexServer`, `fetchJellyfinLibraryFromServer`), which is what the channel filter and
 * the schedule read. `duration` is whole minutes, truncated as the Apple TV does
 * (`minutesFromPlexMs`, `minutesFromTicks`). For an episode, `title` is the show's title and
 * `episodeTitle` the episode's own.
 */
data class MediaItem(
    val ratingKey: String,
    val title: String,
    val type: String = "movie",
    val duration: Int = 0,
    val addedAt: Long = 0,
    val librarySource: String = "movie",
    val artist: String? = null,
    val episodeTitle: String? = null,
    val summary: String = "",
    val year: Int? = null,
    val originallyAvailableAt: String? = null,
    val genres: List<String> = emptyList(),
    val contentRating: String? = null,
    val rating: Double = 0.0,
    val userRating: Double = 0.0,
    val viewCount: Int = 0,
    val studio: String? = null,
    val tmdbID: String? = null,
    val doviProfile: Int? = null,
    val enrichment: Enrichment? = null,
    // ── Playback and display only; nothing in the filter or the schedule reads these ──
    /** Which signed-in server the item came from (index into the saved server list). */
    val serverIndex: Int = 0,
    /** Plex: the part key (`/library/parts/...`). Jellyfin and Emby: the MediaSourceId. */
    val partKey: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    /** Kbps. */
    val bitrate: Int = 0,
    val videoHeight: Int = 0,
    val thumb: String? = null,
    val showKey: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val director: String? = null,
)

/**
 * TMDB/OMDb enrichment as the Apple TV's `MediaEnrichment` carries it. This build doesn't fetch
 * enrichment (it needs upstream's shared cache keys), so items carry none and the filter behaves
 * as the Apple TV does with no record: additive rules don't fire, OMDb gates reject.
 */
data class Enrichment(
    val keywords: List<String> = emptyList(),
    val productionCompanies: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val tmdbGenres: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val imdbVotes: Double? = null,
    val rottenTomatoesScore: Double? = null,
    val metacriticScore: Double? = null,
    val awards: String? = null,
)
