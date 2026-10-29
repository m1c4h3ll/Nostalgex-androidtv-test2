package app.nostalgex.tv.tuner

import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.server.Http
import app.nostalgex.engine.server.MediaBrowserSession
import app.nostalgex.engine.server.SavedPlexServer
import app.nostalgex.engine.server.enc

/**
 * The audio and subtitle language a server stream carries. Direct play hands the player the
 * whole file, and the player picks the tracks itself. A server stream carries only the tracks
 * the server picked, so they're chosen on the server first:
 *
 *  - Jellyfin and Emby: the stream indices go on the HLS request (`AudioStreamIndex`,
 *    `SubtitleStreamIndex` with WebVTT subtitles in the playlist).
 *  - Plex: the part's streams are selected on the server (`PUT /library/parts/{id}`), as Plex's
 *    own apps do when you pick a track, then the transcoder uses them.
 *
 * Only text subtitles are picked: picture subtitles (PGS, VobSub) would be burned into the
 * picture by the server.
 */
internal object TrackChoice {
    /** The codes and names a server may use for a language. */
    private val ALIASES = mapOf(
        "fr" to setOf("fr", "fre", "fra", "french", "français", "francais"),
        "en" to setOf("en", "eng", "english"),
    )
    private val TEXT_SUBTITLES = setOf("srt", "subrip", "ass", "ssa", "webvtt", "vtt", "mov_text", "tx3g", "text", "smi", "sami")
    private const val TIMEOUT_MS = 8_000

    fun matches(language: String?, want: String): Boolean {
        val l = language?.trim()?.lowercase() ?: return false
        return ALIASES[want]?.contains(l) ?: (l == want)
    }

    /**
     * Jellyfin and Emby: what to add to the HLS request, like `&AudioStreamIndex=2`, or "" to
     * leave the server's own choice.
     */
    fun mediaBrowserParams(http: Http, s: MediaBrowserSession, headers: Map<String, String>, item: MediaItem, audio: String?, subtitle: String?): String {
        if (audio == null && subtitle == null) return ""
        var res = http.get("${s.url}/Users/${enc(s.userId)}/Items/${enc(item.ratingKey)}", headers, TIMEOUT_MS)
        if (!res.ok) res = http.get("${s.url}/Items/${enc(item.ratingKey)}?userId=${enc(s.userId)}", headers, TIMEOUT_MS)
        if (!res.ok) return ""
        val o = Json.parseOrNull(res.body).asObj() ?: return ""
        val sources = o.objs("MediaSources")
        val source = sources.firstOrNull { it.str("Id") == item.partKey } ?: sources.firstOrNull() ?: return ""
        val streams = source.objs("MediaStreams")
        val sb = StringBuilder()
        if (audio != null) {
            val tracks = streams.filter { it.str("Type") == "Audio" }
            val current = tracks.firstOrNull { it.bool("IsDefault") == true } ?: tracks.firstOrNull()
            if (current == null || !matches(current.str("Language"), audio)) {
                tracks.firstOrNull { matches(it.str("Language"), audio) }?.int("Index")?.let { sb.append("&AudioStreamIndex=").append(it) }
            }
        }
        if (subtitle != null) {
            streams.firstOrNull {
                it.str("Type") == "Subtitle" && it.bool("IsTextSubtitleStream") != false &&
                    matches(it.str("Language"), subtitle)
            }?.int("Index")?.let { sb.append("&SubtitleStreamIndex=").append(it).append("&SubtitleMethod=Hls") }
        }
        return sb.toString()
    }

    /** Plex: selects the preferred audio and subtitle streams of the item's part on the server. */
    fun selectPlexStreams(http: Http, server: SavedPlexServer, headers: Map<String, String>, item: MediaItem, audio: String?, subtitle: String?) {
        if (audio == null && subtitle == null) return
        val h = headers + ("Accept" to "application/json")
        val res = http.get("${server.url}/library/metadata/${enc(item.ratingKey)}", h, TIMEOUT_MS)
        if (!res.ok) return
        val meta = Json.parseOrNull(res.body).asObj().obj("MediaContainer").objs("Metadata").firstOrNull() ?: return
        val parts = meta.objs("Media").flatMap { it.objs("Part") }
        val part = parts.firstOrNull { it.str("key") == item.partKey } ?: parts.firstOrNull() ?: return
        val partId = part.int("id") ?: return
        val streams = part.objs("Stream")
        fun language(st: Map<String, Any?>) = st.str("languageCode") ?: st.str("language")
        val query = StringBuilder()
        if (audio != null) {
            val tracks = streams.filter { it.int("streamType") == 2 }
            val current = tracks.firstOrNull { it.bool("selected") == true } ?: tracks.firstOrNull()
            if (current == null || !matches(language(current), audio)) {
                tracks.firstOrNull { matches(language(it), audio) }?.int("id")?.let { query.append("&audioStreamID=").append(it) }
            }
        }
        if (subtitle != null) {
            val subs = streams.filter { it.int("streamType") == 3 }
            val current = subs.firstOrNull { it.bool("selected") == true }
            if (current == null || !matches(language(current), subtitle)) {
                subs.firstOrNull {
                    matches(language(it), subtitle) && (it.str("codec")?.lowercase() ?: "") in TEXT_SUBTITLES
                }?.int("id")?.let { query.append("&subtitleStreamID=").append(it) }
            }
        }
        if (query.isEmpty()) return
        http.request("PUT", "${server.url}/library/parts/$partId?allParts=1$query", h, null, TIMEOUT_MS)
    }
}
