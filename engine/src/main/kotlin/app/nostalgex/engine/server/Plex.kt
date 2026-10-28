package app.nostalgex.engine.server

import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.asArr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.double
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.long
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.schedule.DailyManifest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Plex sign-in through plex.tv/link, as upstream's web tuner and the Roku port do it. */
class PlexAuth(private val http: Http, private val clientId: String) {

    data class Pin(val id: Long, val code: String)

    fun headers(token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", VERSION)
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Device", "Android TV")
        put("X-Plex-Device-Name", "Nostalgex")
        if (token != null) put("X-Plex-Token", token)
    }

    /** A four-character code for plex.tv/link. */
    fun requestPin(): Pin {
        val res = http.post("https://plex.tv/api/v2/pins", headers(), null)
        if (!res.ok) throw ServerException("PIN request failed: HTTP ${res.status}", res.status)
        val o = Json.parse(res.body).asObj() ?: throw ServerException("plex.tv sent no PIN")
        val id = o.long("id") ?: throw ServerException("plex.tv sent no PIN id")
        return Pin(id, (o.str("code") ?: "").uppercase())
    }

    /** The account token once the code has been entered, else null. */
    fun checkPin(id: Long): String? {
        val res = http.get("https://plex.tv/api/v2/pins/$id", headers())
        if (!res.ok) return null
        return Json.parseOrNull(res.body).asObj().str("authToken")?.takeIf { it.isNotEmpty() }
    }

    /** The servers on the account. */
    fun servers(token: String): List<PlexServer> {
        val res = http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", headers(token))
        if (res.status == 401) throw ServerException("Plex sign-in expired", 401)
        if (!res.ok) throw ServerException("plex.tv resources: HTTP ${res.status}", res.status)
        val list = Json.parse(res.body).asArr() ?: return emptyList()
        return list.mapNotNull { it.asObj() }
            .filter { (it.str("provides") ?: "").split(',').contains("server") }
            .map { r ->
                PlexServer(
                    name = r.str("name") ?: "Plex",
                    machineIdentifier = r.str("clientIdentifier") ?: "",
                    accessToken = r.str("accessToken") ?: token,
                    owned = r.bool("owned") == true,
                    presence = r.bool("presence") == true,
                    connections = r.objs("connections").mapNotNull { c ->
                        val uri = c.str("uri") ?: return@mapNotNull null
                        PlexConnection(
                            uri = trimBase(uri),
                            local = c.bool("local") == true,
                            relay = c.bool("relay") == true,
                            protocol = c.str("protocol") ?: "",
                        )
                    },
                )
            }
    }

    companion object {
        const val PRODUCT = "Nostalgex"
        const val VERSION = "1.0.0"

        /**
         * ServerPickerView's default: owned servers on, all of them when none is owned. The
         * picker shows only when the account lists more than one server.
         */
        fun defaultChoice(servers: List<PlexServer>): Set<String> {
            val owned = servers.filter { it.owned }
            return (if (owned.isNotEmpty()) owned else servers).mapTo(LinkedHashSet()) { it.machineIdentifier }
        }
    }
}

data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean, val protocol: String)

data class PlexServer(
    val name: String,
    val machineIdentifier: String,
    val accessToken: String,
    val owned: Boolean,
    val presence: Boolean,
    val connections: List<PlexConnection>,
)

/**
 * One verified address per server, best of local, remote, relay (PlexAPIService
 * .bestReachableURI): every address is asked for `/identity` at once, and an answer counts
 * only if it names that server.
 */
object PlexResolve {
    fun rank(c: PlexConnection): Int = when {
        c.local && !c.relay -> 0
        !c.relay && c.protocol == "https" -> 1
        !c.relay -> 2
        else -> 3
    }

    fun bestConnection(http: Http, server: PlexServer, timeoutMs: Int = 6_000): String? {
        if (server.connections.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(minOf(8, server.connections.size))
        try {
            val futures = server.connections.map { c ->
                c to pool.submit(Callable {
                    try {
                        val res = http.get("${c.uri}/identity", mapOf("Accept" to "application/json", "X-Plex-Token" to server.accessToken), timeoutMs)
                        if (!res.ok) return@Callable false
                        val id = Json.parseOrNull(res.body).asObj().obj("MediaContainer").str("machineIdentifier")
                        id == null || server.machineIdentifier.isEmpty() || id == server.machineIdentifier
                    } catch (_: Exception) {
                        false
                    }
                })
            }
            val ok = futures.filter { (_, f) ->
                try { f.get(timeoutMs + 2_000L, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
            }.map { it.first }
            return ok.minByOrNull(::rank)?.uri
        } finally {
            pool.shutdownNow()
        }
    }
}

/** A Plex server the viewer signed in to, as saved. */
data class SavedPlexServer(
    val name: String,
    val machineIdentifier: String,
    val url: String,
    val token: String,
    val connections: List<PlexConnection> = emptyList(),
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "name" to name, "machineIdentifier" to machineIdentifier, "url" to url, "token" to token,
        "connections" to connections.map { mapOf("uri" to it.uri, "local" to it.local, "relay" to it.relay, "protocol" to it.protocol) },
    )

    companion object {
        fun fromJson(o: Map<String, Any?>): SavedPlexServer = SavedPlexServer(
            name = o.str("name") ?: "Plex",
            machineIdentifier = o.str("machineIdentifier") ?: "",
            url = o.str("url") ?: "",
            token = o.str("token") ?: "",
            connections = o.objs("connections").map {
                PlexConnection(it.str("uri") ?: "", it.bool("local") == true, it.bool("relay") == true, it.str("protocol") ?: "")
            },
        )
    }
}

/** Reads one Plex server's library into [MediaItem]s, as the web tuner's `scanOnePlexServer`. */
class PlexLibrary(
    private val http: Http,
    private val server: SavedPlexServer,
    private val serverIndex: Int,
) {
    private fun headers() = mapOf("Accept" to "application/json", "X-Plex-Token" to server.token)

    private fun fetchJson(path: String, timeoutMs: Int = 30_000): Map<String, Any?> {
        val res = http.get(server.url + path, headers(), timeoutMs)
        if (res.status == 401 || res.status == 403) throw ServerException("Plex refused the sign-in (${res.status})", res.status)
        if (!res.ok) throw ServerException("HTTP ${res.status} from ${server.name}", res.status)
        return Json.parse(res.body).asObj()?.obj("MediaContainer") ?: throw ServerException("Malformed answer from ${server.name}")
    }

    data class Section(val key: String, val title: String, val type: String)

    fun sections(): List<Section> = fetchJson("/library/sections").objs("Directory").map {
        Section(it.str("key") ?: "", it.str("title") ?: "", it.str("type") ?: "")
    }

    /** Every item in a section, 300 at a time, with `includeGuids=1` for TMDB ids. */
    fun sectionItems(section: Section, onPage: (Int, Int?) -> Unit = { _, _ -> }): List<Map<String, Any?>> {
        val all = ArrayList<Map<String, Any?>>()
        var start = 0
        while (true) {
            val c = fetchJson(
                "/library/sections/${section.key}/all?includeGuids=1&X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE_SIZE",
            )
            val page = c.objs("Metadata")
            all.addAll(page)
            val total = c.int("totalSize")
            onPage(all.size, total)
            if (page.isEmpty()) break
            if (total != null && all.size >= total) break
            if (total == null && page.size < PAGE_SIZE) break
            start += PAGE_SIZE
        }
        return all
    }

    /**
     * The whole library. Shows' episodes are fetched five at a time and kept in show order.
     * `onProgress(detail, itemsSoFar)`. `shouldStop` ends the scan with what has been read.
     */
    fun scan(onProgress: (String, Int) -> Unit = { _, _ -> }, shouldStop: () -> Boolean = { false }): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val secs = sections()
        val pool = Executors.newFixedThreadPool(5)
        try {
            for (section in secs) {
                if (shouldStop()) break
                try {
                    val raw = sectionItems(section) { n, total ->
                        onProgress("${section.title} (${n}${total?.let { "/$it" } ?: ""})", out.size + n)
                    }
                    val isMusicVideoSection = MUSIC_VIDEO_SECTION.containsMatchIn(section.title.trim())
                    if (section.type == "show") {
                        var episodes = 0
                        for (batch in raw.chunked(5)) {
                            if (shouldStop()) break
                            val results = batch.map { show -> pool.submit(Callable { showEpisodes(show, isMusicVideoSection) }) }
                                .map { f -> try { f.get(120, TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
                            for (eps in results) { out.addAll(eps); episodes += eps.size }
                            onProgress("${section.title} · $episodes eps", out.size)
                        }
                        continue
                    }
                    for (item in raw) movieItem(item, section, isMusicVideoSection)?.let(out::add)
                    onProgress(section.title, out.size)
                } catch (e: ServerException) {
                    if (e.status == 401 || e.status == 403) throw e
                } catch (_: Exception) {
                    // A section that fails is skipped, as upstream does.
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    private fun showEpisodes(show: Map<String, Any?>, isMusicVideoSection: Boolean): List<MediaItem> {
        val showGenres = show.objs("Genre").mapNotNull { it.str("tag") }
        val showTmdb = parseTmdbIdFromPlex(show, false)
        val c = try {
            fetchJson("/library/metadata/${show.str("ratingKey")}/allLeaves", 60_000)
        } catch (_: Exception) {
            return emptyList()
        }
        return c.objs("Metadata").mapNotNull { ep ->
            val minutes = DailyManifest.minutesFromPlexMs(ep.long("duration"))
            if (minutes <= 0) return@mapNotNull null
            val media = pickBestVersion(ep.objs("Media"))
            MediaItem(
                ratingKey = ep.str("ratingKey") ?: return@mapNotNull null,
                title = show.str("title") ?: "Unknown",
                episodeTitle = ep.str("title") ?: "",
                summary = ep.str("summary")?.takeIf { it.isNotEmpty() } ?: show.str("summary") ?: "",
                year = show.int("year")?.takeIf { it != 0 },
                originallyAvailableAt = ep.str("originallyAvailableAt")?.takeIf { it.isNotEmpty() }
                    ?: show.str("originallyAvailableAt")?.takeIf { it.isNotEmpty() },
                contentRating = show.str("contentRating")?.takeIf { it.isNotEmpty() } ?: ep.str("contentRating") ?: "",
                duration = minutes,
                genres = showGenres,
                rating = show.double("rating") ?: 0.0,
                userRating = show.double("userRating") ?: 0.0,
                type = "episode",
                showKey = show.str("ratingKey"),
                thumb = ep.str("thumb") ?: show.str("thumb"),
                viewCount = ep.int("viewCount") ?: 0,
                addedAt = ep.long("addedAt") ?: 0,
                studio = show.str("studio") ?: "",
                librarySource = if (isMusicVideoSection) "musicVideo" else "tv",
                tmdbID = showTmdb ?: parseTmdbIdFromPlex(ep, true),
                serverIndex = serverIndex,
                seasonNumber = ep.int("parentIndex"),
                episodeNumber = ep.int("index"),
                // Playback
                partKey = media?.objs("Part")?.firstOrNull()?.str("key"),
                container = media?.str("container")?.lowercase(),
                videoCodec = media?.str("videoCodec")?.lowercase(),
                audioCodec = media?.str("audioCodec")?.lowercase(),
                bitrate = media?.int("bitrate")?.takeIf { it > 0 } ?: 0,
                videoHeight = media?.let { it.int("height") ?: displayHeight(it.str("videoResolution")) } ?: 0,
            )
        }
    }

    private fun movieItem(item: Map<String, Any?>, section: Section, isMusicVideoSection: Boolean): MediaItem? {
        val genres = item.objs("Genre").mapNotNull { it.str("tag") }.toMutableList()
        if (isMusicVideoSection && genres.none { MUSIC.containsMatchIn(it) }) genres.add("Music Video")
        val minutes = DailyManifest.minutesFromPlexMs(item.long("duration"))
        if (minutes <= 0) return null
        val isMusicVideo = isMusicVideoSection || (minutes <= 10 && genres.any { MUSIC.containsMatchIn(it) })
        val media = pickBestVersion(item.objs("Media"))
        var title = item.str("title") ?: "Unknown"
        var artist: String? = null
        if (isMusicVideo) {
            val parsed = MusicTitle.parseMusicVideo(item.str("parentTitle"), item.str("grandparentTitle"), title)
            title = parsed.first
            artist = parsed.second
        }
        val type = if (section.type == "show") "episode" else section.type
        return MediaItem(
            ratingKey = item.str("ratingKey") ?: return null,
            title = title,
            artist = artist,
            summary = item.str("summary") ?: "",
            year = item.int("year")?.takeIf { it != 0 },
            // No release date on a film, as the web tuner's loader (see MediaBrowserLibrary).
            contentRating = item.str("contentRating") ?: "",
            duration = minutes,
            genres = genres,
            rating = item.double("rating") ?: 0.0,
            userRating = item.double("userRating") ?: 0.0,
            type = type,
            thumb = item.str("thumb"),
            viewCount = item.int("viewCount") ?: 0,
            addedAt = item.long("addedAt") ?: 0,
            studio = item.str("studio") ?: "",
            librarySource = if (isMusicVideo) "musicVideo" else if (section.type == "movie") "movie" else section.type,
            tmdbID = parseTmdbIdFromPlex(item, false),
            serverIndex = serverIndex,
            director = item.objs("Director").mapNotNull { it.str("tag") }.joinToString(", ").takeIf { it.isNotEmpty() },
            partKey = media?.objs("Part")?.firstOrNull()?.str("key"),
            container = media?.str("container")?.lowercase(),
            videoCodec = media?.str("videoCodec")?.lowercase(),
            audioCodec = media?.str("audioCodec")?.lowercase(),
            bitrate = media?.int("bitrate")?.takeIf { it > 0 } ?: 0,
            videoHeight = media?.let { it.int("height") ?: displayHeight(it.str("videoResolution")) } ?: 0,
        )
    }

    companion object {
        const val PAGE_SIZE = 300
        private val MUSIC_VIDEO_SECTION = Regex("music.?video|^music$|^mv[s]?$", RegexOption.IGNORE_CASE)
        private val MUSIC = Regex("music", RegexOption.IGNORE_CASE)
        private val TMDB = Regex("tmdb://([0-9]+)")

        /** Plex GUIDs: `tmdb://456`, or agent URLs containing it. Episodes fall back to the show's. */
        fun parseTmdbIdFromPlex(raw: Map<String, Any?>, isEpisode: Boolean): String? {
            val candidates = ArrayList<String>()
            raw["Guid"].asArr()?.forEach { g -> g.asObj().str("id")?.let(candidates::add) }
            raw.str("guid")?.takeIf { it.isNotEmpty() }?.let(candidates::add)
            if (isEpisode) {
                raw.str("grandparentGuid")?.takeIf { it.isNotEmpty() }?.let(candidates::add)
                raw.str("parentGuid")?.takeIf { it.isNotEmpty() }?.let(candidates::add)
            }
            for (id in candidates) {
                val m = TMDB.find(id) ?: continue
                // Number(m[1]): leading zeros dropped.
                return m.groupValues[1].trimStart('0').ifEmpty { "0" }
            }
            return null
        }

        /** The version with the highest bitrate, then the most lines. */
        fun pickBestVersion(versions: List<Map<String, Any?>>): Map<String, Any?>? {
            if (versions.isEmpty()) return null
            if (versions.none { it.double("bitrate") != null }) return versions[0]
            return versions.reduce { best, v ->
                val bb = best.double("bitrate") ?: -1.0
                val bv = v.double("bitrate") ?: -1.0
                if (bv != bb) (if (bv > bb) v else best)
                else if (resolutionHeight(v.str("videoResolution")) > resolutionHeight(best.str("videoResolution"))) v else best
            }
        }

        /**
         * The web tuner's `resolutionHeight`, for choosing between versions only: it reads
         * `parseInt` of the text after the last "x", so "4k" reads as 4, as upstream's does.
         */
        fun resolutionHeight(res: String?): Int {
            if (res.isNullOrEmpty()) return 0
            val s = res.lowercase()
            app.nostalgex.engine.filter.JsText.parseIntJs(s.substringAfterLast('x'))?.let { return it }
            if (s.contains("4k")) return 2160
            if (s.contains("1080")) return 1080
            if (s.contains("720")) return 720
            return 0
        }

        /** Lines for playback decisions: "4k" is 2160. */
        fun displayHeight(res: String?): Int {
            val s = res?.lowercase() ?: return 0
            return when {
                s.contains("4k") || s.contains("2160") -> 2160
                s.contains("1080") -> 1080
                s.contains("720") -> 720
                s == "sd" -> 480
                else -> s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
        }
    }
}

/** What this device can decode, which decides direct play and what a server stream may copy. */
data class DeviceCaps(
    val hevc: Boolean = false,
    val hevc10: Boolean = false,
    val av1: Boolean = false,
    val vp9: Boolean = false,
    val mpeg2: Boolean = false,
    val ac3: Boolean = false,
    val eac3: Boolean = false,
    val dts: Boolean = false,
    val truehd: Boolean = false,
    /** 3840 on a 4K output, 1920 on 1080p, 1280 below. */
    val maxWidth: Int = 1920,
)

/** Plex stream addresses: direct play, the server stream and the 1080p rung. */
object PlexStreams {
    const val DIRECT_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts"

    fun canDirectPlay(item: MediaItem, caps: DeviceCaps): Boolean {
        if (item.partKey.isNullOrEmpty()) return false
        return Codecs.directPlayable(item.container, item.videoCodec, item.audioCodec, item.videoHeight, caps)
    }

    fun directPlayUrl(server: SavedPlexServer, item: MediaItem): String = server.url + item.partKey

    /**
     * The server stream (`/video/:/transcode/universal/start.m3u8`), after the Roku port's
     * measured request: directStream, fastSeek, the offset, WebVTT subtitles rather than burned
     * in, HEVC and Dolby audio copied when this device decodes them. Plex's playlist spans the
     * whole file whatever the offset, so the player starts at the offset as well.
     * `capped` is the 1080p 12 Mb/s rung with no HEVC.
     */
    fun transcodeUrl(
        server: SavedPlexServer,
        item: MediaItem,
        offsetSec: Long,
        session: String,
        clientId: String,
        caps: DeviceCaps,
        capped: Boolean,
    ): String {
        val hevc = caps.hevc && !capped
        var extra = "add-transcode-target(type=subtitleProfile&context=streaming&protocol=hls&container=webvtt&subtitleCodec=webvtt)"
        if (hevc) extra += "+add-transcode-target-codec(type=videoProfile&context=streaming&protocol=hls&videoCodec=hevc)"
        if (caps.ac3) extra += "+add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=ac3)"
        if (caps.eac3) {
            extra += "+add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=eac3)"
            extra += "+add-limitation(scope=videoAudioCodec&scopeName=eac3&type=upperBound&name=audio.channels&value=6)"
        }
        // One session identifier per install and decoding profile: with a new one per stream
        // Plex refuses to restart a file for minutes after stopping it (Roku port, measured).
        val profileKey = (if (hevc) "hevc" else "h264") + (if (caps.ac3) "-ac3" else "") + (if (caps.eac3) "-eac3" else "")
        val sb = StringBuilder(server.url).append("/video/:/transcode/universal/start.m3u8?")
        sb.append("path=").append(enc("/library/metadata/${item.ratingKey}"))
        sb.append("&mediaIndex=0&partIndex=0&protocol=hls&directStream=1&fastSeek=1")
        sb.append("&offset=").append(maxOf(0L, offsetSec))
        sb.append("&subtitles=auto")
        if (capped) sb.append("&maxVideoBitrate=12000&videoQuality=90&videoResolution=1920x1080")
        sb.append("&session=").append(session)
        sb.append("&X-Plex-Session-Identifier=").append(enc("$clientId-$profileKey"))
        sb.append("&X-Plex-Client-Identifier=").append(enc(clientId))
        sb.append("&X-Plex-Product=").append(enc(PlexAuth.PRODUCT))
        sb.append("&X-Plex-Version=").append(PlexAuth.VERSION)
        sb.append("&X-Plex-Platform=Android&X-Plex-Device=").append(enc("Android TV"))
        sb.append("&X-Plex-Device-Name=Nostalgex")
        sb.append("&X-Plex-Client-Profile-Extra=").append(enc(extra))
        return sb.toString()
    }

    fun stopUrl(server: SavedPlexServer, session: String) =
        "${server.url}/video/:/transcode/universal/stop?session=${enc(session)}"

    fun newSession(): String = UUID.randomUUID().toString()

    /** The server's thumbnail transcode (PlexAPIService.thumbnailURL). Token goes in a header. */
    fun thumbUrl(server: SavedPlexServer, thumb: String, width: Int = 300): String =
        "${server.url}/photo/:/transcode?url=${enc(thumb)}&width=$width&height=${width * 3 / 2}&minSize=1"
}

/** Container and codec checks for direct play. */
object Codecs {
    fun directPlayable(container: String?, videoCodec: String?, audioCodec: String?, height: Int, caps: DeviceCaps): Boolean {
        val c = container?.lowercase()?.split(',')?.map { it.trim() } ?: return false
        if (c.none { it in PlexStreams.DIRECT_CONTAINERS.split(',') }) return false
        val video = when (videoCodec?.lowercase()) {
            "h264", "avc", "avc1" -> true
            "hevc", "h265", "hev1", "hvc1" -> caps.hevc
            "vp9" -> caps.vp9
            "av1" -> caps.av1
            "mpeg2video", "mpeg2" -> caps.mpeg2
            "vp8" -> true
            else -> false
        }
        if (!video) return false
        if (height > 2200 && caps.maxWidth < 3840) return false
        val audio = when (audioCodec?.lowercase()) {
            null, "", "aac", "mp3", "opus", "vorbis", "flac", "mp2", "pcm", "alac" -> true
            "ac3" -> caps.ac3
            "eac3" -> caps.eac3
            "dca", "dts", "dts-hd ma", "dtshd" -> caps.dts
            "truehd" -> caps.truehd
            else -> false
        }
        return audio
    }
}

/** Music video titles: "Artist - Song", "Song by Artist", filename noise stripped (upstream MusicTitleParser). */
object MusicTitle {
    private val TAGS = Regex("\\[[^\\]]*]|\\{[^}]*}")
    private val YEAR_END = Regex("\\s*\\((?:19|20)[0-9][0-9]\\)\\s*$")
    private val YEAR_NOTE = Regex("\\([^)]*(?:19|20)[0-9][0-9][^)]*\\)")
    private val NOISE = Regex("(?<![A-Za-z0-9_])(official|video|hd|hq|lyrics?|remaster(?:ed)?|mv|xvid|dvdrip|vgb|prv)(?![A-Za-z0-9_])!?", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val EDGES = Regex("^[\\s\\-!.]+|[\\s\\-!.]+$")
    private val FEAT_ARTIST = Regex("\\s+(?:ft\\.?|feat\\.?|featuring|with|and the)\\s+.*$", RegexOption.IGNORE_CASE)
    private val FEAT_SONG = Regex("\\s+(?:ft\\.?|feat\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val COMMA_EDGES = Regex("^[\\s,]+|[\\s,]+$")
    private val LONE = Regex("^(.{2,}?)-(.+)$")

    fun clean(raw: String): String {
        var t = raw.replace('_', ' ')
        t = TAGS.replace(t, "")
        t = YEAR_END.replace(t, "")
        t = YEAR_NOTE.replace(t, "")
        t = NOISE.replace(t, "")
        t = SPACES.replace(t, " ")
        return EDGES.replace(t, "")
    }

    private fun strip(s: String, artistSide: Boolean): String =
        COMMA_EDGES.replace((if (artistSide) FEAT_ARTIST else FEAT_SONG).replace(s, ""), "")

    /** (artist, song). */
    fun parse(raw: String): Pair<String?, String> {
        val cleaned = clean(raw)
        val dash = cleaned.indexOf(" - ")
        if (dash > 0) {
            val artist = strip(cleaned.substring(0, dash), true)
            var song = cleaned.substring(dash + 3).trim()
            val next = song.indexOf(" - ")
            if (next > 0) song = song.substring(0, next)
            song = strip(song, false)
            if (artist.isNotEmpty() && song.isNotEmpty()) return artist to song
        }
        val byIdx = cleaned.lowercase().lastIndexOf(" by ")
        if (byIdx > 0) {
            val song = strip(cleaned.substring(0, byIdx), false)
            val artist = strip(cleaned.substring(byIdx + 4), true)
            if (artist.isNotEmpty() && song.isNotEmpty()) return artist to song
        }
        val lone = LONE.find(cleaned)
        if (lone != null && !lone.groupValues[1].contains(" - ")) {
            val artist = strip(lone.groupValues[1], true)
            val song = strip(lone.groupValues[2], false)
            if (artist.length >= 2 && song.length >= 2) return artist to song
        }
        return null to strip(cleaned, false)
    }

    /** parseMusicVideoFromPlex: (title, artist). The library's artist wins over the filename's. */
    fun parseMusicVideo(parentTitle: String?, grandparentTitle: String?, rawTitle: String): Pair<String, String?> {
        val hint = parentTitle?.takeIf { it.isNotEmpty() } ?: grandparentTitle?.takeIf { it.isNotEmpty() }
        val (artist, song) = parse(rawTitle)
        return (song.ifEmpty { rawTitle }) to (hint ?: artist)
    }
}
