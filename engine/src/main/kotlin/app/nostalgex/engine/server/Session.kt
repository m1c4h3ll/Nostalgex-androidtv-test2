package app.nostalgex.engine.server

import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.arr
import app.nostalgex.engine.json.asArr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.model.MediaItem

/** Who the app is signed in as: Plex (one or more servers), or one Jellyfin or Emby server. */
sealed class Session {
    abstract val backend: String

    /** Keys the schedule's day manifests, as the web tuner's `manifestServerKey`. */
    abstract val manifestKey: String

    abstract val displayName: String

    data class Plex(val clientId: String, val accountToken: String, val servers: List<SavedPlexServer>) : Session() {
        override val backend = "plex"
        override val manifestKey: String
            get() = "plex_" + servers.joinToString("_") { it.machineIdentifier.ifEmpty { it.name } }.replace(Regex("[^a-zA-Z0-9]"), "_")
        override val displayName: String get() = servers.joinToString(", ") { it.name }
    }

    data class MediaBrowser(val session: MediaBrowserSession) : Session() {
        override val backend = session.kind.id
        override val manifestKey: String get() = session.url.replace(Regex("[^a-zA-Z0-9]"), "_")
        override val displayName: String get() = "${session.serverName} (${session.kind.display})"
    }

    fun toJson(): String = Json.stringify(
        when (this) {
            is Plex -> mapOf(
                "backend" to "plex", "clientId" to clientId, "accountToken" to accountToken,
                "servers" to servers.map { it.toJson() },
            )
            is MediaBrowser -> mapOf("backend" to backend, "session" to session.toJson())
        },
    )

    companion object {
        fun fromJson(text: String?): Session? {
            val o = Json.parseOrNull(text).asObj() ?: return null
            return when (o.str("backend")) {
                "plex" -> Plex(
                    o.str("clientId") ?: return null,
                    o.str("accountToken") ?: "",
                    o.objs("servers").map(SavedPlexServer::fromJson),
                ).takeIf { it.servers.isNotEmpty() }
                "jellyfin", "emby" -> o.obj("session")?.let(MediaBrowserSession::fromJson)?.let(::MediaBrowser)
                else -> null
            }
        }
    }
}

/**
 * The saved library: every item as a compact row, so a launch can show the guide before the
 * server answers (upstream's `LibrarySnapshotStore`). Rows are positional; [VERSION] changes
 * whenever the layout does, and an old file is then ignored.
 */
object ItemCodec {
    const val VERSION = 1

    fun encode(items: List<MediaItem>, savedAtMs: Long, signature: String): String {
        val sb = StringBuilder(items.size * 220)
        sb.append("{\"v\":").append(VERSION).append(",\"savedAt\":").append(savedAtMs)
        sb.append(",\"signature\":")
        Json.write(sb, signature)
        sb.append(",\"items\":[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            Json.write(
                sb,
                listOf(
                    it.ratingKey, it.title, it.type, it.duration, it.addedAt, it.librarySource, it.artist,
                    it.episodeTitle, it.summary, it.year, it.originallyAvailableAt, it.genres, it.contentRating,
                    it.rating, it.userRating, it.viewCount, it.studio, it.tmdbID, it.doviProfile, it.serverIndex,
                    it.partKey, it.container, it.videoCodec, it.audioCodec, it.bitrate, it.videoHeight, it.thumb,
                    it.showKey, it.seasonNumber, it.episodeNumber, it.director,
                ),
            )
        }
        sb.append("]}")
        return sb.toString()
    }

    class Snapshot(val items: List<MediaItem>, val savedAtMs: Long, val signature: String)

    fun decode(text: String?): Snapshot? {
        val o = Json.parseOrNull(text).asObj() ?: return null
        if ((o["v"] as? Number)?.toInt() != VERSION) return null
        val rows = o.arr("items") ?: return null
        val items = rows.mapNotNull { r ->
            val a = r.asArr() ?: return@mapNotNull null
            fun s(i: Int) = a.getOrNull(i) as? String
            fun n(i: Int) = a.getOrNull(i) as? Number
            MediaItem(
                ratingKey = s(0) ?: return@mapNotNull null,
                title = s(1) ?: "",
                type = s(2) ?: "movie",
                duration = n(3)?.toInt() ?: 0,
                addedAt = n(4)?.toLong() ?: 0,
                librarySource = s(5) ?: "movie",
                artist = s(6),
                episodeTitle = s(7),
                summary = s(8) ?: "",
                year = n(9)?.toInt(),
                originallyAvailableAt = s(10),
                genres = a.getOrNull(11).asArr()?.mapNotNull { it as? String } ?: emptyList(),
                contentRating = s(12),
                rating = n(13)?.toDouble() ?: 0.0,
                userRating = n(14)?.toDouble() ?: 0.0,
                viewCount = n(15)?.toInt() ?: 0,
                studio = s(16),
                tmdbID = s(17),
                doviProfile = n(18)?.toInt(),
                serverIndex = n(19)?.toInt() ?: 0,
                partKey = s(20),
                container = s(21),
                videoCodec = s(22),
                audioCodec = s(23),
                bitrate = n(24)?.toInt() ?: 0,
                videoHeight = n(25)?.toInt() ?: 0,
                thumb = s(26),
                showKey = s(27),
                seasonNumber = n(28)?.toInt(),
                episodeNumber = n(29)?.toInt(),
                director = s(30),
            )
        }
        return Snapshot(items, (o["savedAt"] as? Number)?.toLong() ?: 0, o.str("signature") ?: "")
    }
}
