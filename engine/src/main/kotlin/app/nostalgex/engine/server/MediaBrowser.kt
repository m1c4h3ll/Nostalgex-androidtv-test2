package app.nostalgex.engine.server

import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.arr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.double
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.long
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.json.strings
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.schedule.DailyManifest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Jellyfin and Emby: the same MediaBrowser API with a few differences, as upstream's
 * `scripts/nostalgex-jellyfin-urls.js` and `nostalgex-signin.js` and the Roku port handle them.
 * One server at a time, as on the Apple TV.
 */
enum class ServerKind(val id: String, val display: String) {
    JELLYFIN("jellyfin", "Jellyfin"), EMBY("emby", "Emby");

    companion object {
        fun from(id: String?): ServerKind? = entries.firstOrNull { it.id == id }
    }
}

data class MediaBrowserSession(
    val kind: ServerKind,
    val url: String,
    val userId: String,
    val token: String,
    val deviceId: String,
    val serverName: String,
    val userName: String,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "kind" to kind.id, "url" to url, "userId" to userId, "token" to token,
        "deviceId" to deviceId, "serverName" to serverName, "userName" to userName,
    )

    companion object {
        fun fromJson(o: Map<String, Any?>): MediaBrowserSession? {
            val kind = ServerKind.from(o.str("kind")) ?: return null
            return MediaBrowserSession(
                kind, o.str("url") ?: return null, o.str("userId") ?: return null, o.str("token") ?: return null,
                o.str("deviceId") ?: UUID.randomUUID().toString(), o.str("serverName") ?: kind.display, o.str("userName") ?: "",
            )
        }
    }
}

object MediaBrowser {
    const val DEFAULT_HTTP_PORT = 8096
    private const val DISCOVERY_PORT = 7359

    /** The MediaBrowser Authorization header every Jellyfin and Emby request carries. */
    fun authHeader(deviceId: String, token: String? = null): String {
        val fields = mutableListOf("Client=\"Nostalgex\"", "Device=\"Android TV\"", "DeviceId=\"$deviceId\"", "Version=\"1.0\"")
        if (!token.isNullOrEmpty()) fields.add("Token=\"$token\"")
        return "MediaBrowser " + fields.joinToString(", ")
    }

    fun headers(deviceId: String, token: String? = null) = mapOf(
        "Accept" to "application/json",
        "Authorization" to authHeader(deviceId, token),
        "X-Emby-Authorization" to authHeader(deviceId, token),
    )

    /**
     * Base URLs to try, best guess first (signin.js `addressCandidates`). A plain http address
     * with no port and no path tries :8096 first, then the address as typed. No scheme means http.
     */
    fun addressCandidates(raw: String): List<String> {
        var s = raw.filterNot { it.isWhitespace() }
        if (s.isEmpty()) return emptyList()
        var scheme: String? = null
        val sep = s.indexOf("://")
        if (sep > 0 && s.substring(0, sep).all { it.isLetterOrDigit() || it in "+.-" }) {
            scheme = s.substring(0, sep).lowercase()
            s = s.substring(sep + 3)
        }
        val authorityEnd = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) s else s.substring(0, authorityEnd)
        var rest = if (authorityEnd < 0) "" else s.substring(authorityEnd)
        if (authority.isEmpty()) return emptyList()
        val cut = rest.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        val parts = rest.split('/').filter { it.isNotEmpty() }.toMutableList()
        val web = parts.indexOfFirst { it.equals("web", ignoreCase = true) }
        if (web >= 0) while (parts.size > web) parts.removeAt(parts.size - 1)
        val path = if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
        val hasPort = Regex(":[0-9]+$").containsMatchIn(authority) && !authority.endsWith("]")
        val sch = scheme ?: "http"
        val host = authority.lowercase()
        return if (!hasPort && sch == "http" && path.isEmpty()) listOf("http://$host:$DEFAULT_HTTP_PORT", "http://$host")
        else listOf("$sch://$host$path")
    }

    data class PublicInfo(val kind: ServerKind?, val name: String, val id: String)

    /** /System/Info/Public. Jellyfin names itself in ProductName; Emby 4.x leaves it out. */
    fun publicInfo(http: Http, base: String, timeoutMs: Int = 6_000): PublicInfo? {
        val res = try { http.get("$base/System/Info/Public", mapOf("Accept" to "application/json"), timeoutMs) } catch (_: Exception) { return null }
        if (!res.ok) return null
        val o = Json.parseOrNull(res.body).asObj() ?: return null
        val product = o["ProductName"] as? String
        val kind = when {
            product != null -> if (product.contains("jellyfin", ignoreCase = true)) ServerKind.JELLYFIN else null
            o.arr("LocalAddresses") != null && o["Version"] is String && o["Id"] is String -> ServerKind.EMBY
            else -> null
        }
        return PublicInfo(kind, o.str("ServerName") ?: kind?.display ?: "Server", o.str("Id") ?: "")
    }

    /** The first candidate address that answers as a server, with what it says it is. */
    fun checkAddress(http: Http, raw: String): Pair<String, PublicInfo>? {
        for (c in addressCandidates(raw)) publicInfo(http, c)?.let { return c to it }
        return null
    }

    data class Found(val address: String, val id: String, val name: String)

    /** One broadcast "who is JellyfinServer?" (or EmbyServer) on UDP 7359, answers for `windowMs`. */
    fun discover(kind: ServerKind, windowMs: Int = 3_000): List<Found> {
        val found = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 400
                val msg = (if (kind == ServerKind.JELLYFIN) "who is JellyfinServer?" else "who is EmbyServer?").toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val deadline = System.currentTimeMillis() + windowMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val o = Json.parseOrNull(String(p.data, 0, p.length, Charsets.UTF_8)).asObj() ?: continue
                    val address = (o["Address"] as? String)?.trim()?.trimEnd('/') ?: continue
                    val id = (o["Id"] as? String)?.trim() ?: continue
                    if (address.isEmpty() || id.isEmpty()) continue
                    val name = (o["Name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: address
                    found.putIfAbsent(id, Found(address, id, name))
                }
            }
        } catch (_: Exception) {
            // No network permission, no route: nothing found.
        }
        return found.values.toList()
    }

    data class PublicUser(val id: String, val name: String, val hasPassword: Boolean)

    fun publicUsers(http: Http, base: String): List<PublicUser> {
        val res = try { http.get("$base/Users/Public", mapOf("Accept" to "application/json"), 8_000) } catch (_: Exception) { return emptyList() }
        if (!res.ok) return emptyList()
        return (Json.parseOrNull(res.body) as? List<*>)?.mapNotNull { it.asObj() }?.mapNotNull {
            val name = it.str("Name") ?: return@mapNotNull null
            PublicUser(it.str("Id") ?: "", name, it.bool("HasPassword") != false)
        } ?: emptyList()
    }

    fun quickConnectEnabled(http: Http, base: String): Boolean? {
        val res = try { http.get("$base/QuickConnect/Enabled", mapOf("Accept" to "application/json"), 6_000) } catch (_: Exception) { return null }
        if (!res.ok) return null
        return when (res.body.trim()) { "true" -> true; "false" -> false; else -> null }
    }

    class SignInException(val code: String, message: String) : Exception(message)

    private fun sessionFrom(kind: ServerKind, base: String, deviceId: String, serverName: String, body: String): MediaBrowserSession {
        val o = Json.parseOrNull(body).asObj() ?: throw SignInException("notReached", "The server sent no sign-in")
        val token = o.str("AccessToken") ?: throw SignInException("notReached", "The server sent no sign-in")
        val user = o.obj("User")
        return MediaBrowserSession(kind, base, user.str("Id") ?: "", token, deviceId, serverName, user.str("Name") ?: "")
    }

    /** POST /Users/AuthenticateByName { Username, Pw }. */
    fun signInWithPassword(http: Http, kind: ServerKind, base: String, deviceId: String, serverName: String, username: String, password: String): MediaBrowserSession {
        val res = try {
            http.post(
                "$base/Users/AuthenticateByName",
                headers(deviceId) + ("Content-Type" to "application/json"),
                Json.stringify(mapOf("Username" to username, "Pw" to password)),
            )
        } catch (e: Exception) {
            throw SignInException("network", "Couldn't reach the server")
        }
        if (res.status == 401 || res.status == 403) throw SignInException("incorrect", "That name or password isn't right")
        if (!res.ok) throw SignInException("notReached", "The server answered HTTP ${res.status}")
        return sessionFrom(kind, base, deviceId, serverName, res.body)
    }

    data class QuickConnectStart(val code: String, val secret: String)

    /** POST, then GET for servers that only take GET (`quickConnectInitiate`). */
    fun quickConnectInitiate(http: Http, base: String, deviceId: String): QuickConnectStart? {
        var res = http.post("$base/QuickConnect/Initiate", headers(deviceId))
        if (res.status == 404 || res.status == 405) res = http.get("$base/QuickConnect/Initiate", headers(deviceId))
        if (!res.ok) return null
        val o = Json.parseOrNull(res.body).asObj() ?: return null
        val code = o.str("Code")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val secret = o.str("Secret")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return QuickConnectStart(code, secret)
    }

    fun quickConnectApproved(http: Http, base: String, deviceId: String, secret: String): Boolean = try {
        val res = http.get("$base/QuickConnect/Connect?Secret=${enc(secret)}", headers(deviceId))
        Json.parseOrNull(res.body).asObj().bool("Authenticated") == true
    } catch (_: Exception) {
        false
    }

    fun quickConnectSignIn(http: Http, kind: ServerKind, base: String, deviceId: String, serverName: String, secret: String): MediaBrowserSession {
        val res = http.post(
            "$base/Users/AuthenticateWithQuickConnect",
            headers(deviceId) + ("Content-Type" to "application/json"),
            Json.stringify(mapOf("Secret" to secret)),
        )
        if (!res.ok) throw SignInException("failed", "Quick Connect sign-in failed (HTTP ${res.status})")
        return sessionFrom(kind, base, deviceId, serverName, res.body)
    }

    /** `/UserViews` on Jellyfin, `/Users/{id}/Views` on Emby. */
    fun userViewsPath(kind: ServerKind, userId: String): String =
        if (kind == ServerKind.EMBY) "/Users/${enc(userId)}/Views" else "/UserViews?userId=${enc(userId)}"

    /** Emby's ExtendedVideoSubType "DoviProfile50" is profile 5; null when it isn't Dolby Vision. */
    fun embyDoviProfile(extendedVideoSubType: String?): Int? {
        val m = Regex("^doviprofile([0-9]{2,})$", RegexOption.IGNORE_CASE).find(extendedVideoSubType ?: "") ?: return null
        return m.groupValues[1].dropLast(1).toIntOrNull()
    }
}

/** Reads a Jellyfin or Emby library, as the web tuner's `fetchJellyfinLibraryFromServer`. */
class MediaBrowserLibrary(private val http: Http, private val session: MediaBrowserSession) {
    private fun headers() = MediaBrowser.headers(session.deviceId, session.token)

    private fun getJson(path: String, timeoutMs: Int = 60_000): Map<String, Any?> {
        val res = http.get(session.url + path, headers(), timeoutMs)
        if (res.status == 401 || res.status == 403) throw ServerException("The server refused the sign-in (${res.status})", res.status)
        if (!res.ok) throw ServerException("HTTP ${res.status} for $path", res.status)
        return Json.parse(res.body).asObj() ?: throw ServerException("Malformed answer for $path")
    }

    data class View(val id: String, val name: String, val collectionType: String)

    fun views(): List<View> {
        var res = http.get(session.url + MediaBrowser.userViewsPath(session.kind, session.userId), headers(), 30_000)
        // An Emby server saved as Jellyfin answers /UserViews with a 404.
        if (res.status == 404 && session.kind == ServerKind.JELLYFIN) {
            res = http.get(session.url + MediaBrowser.userViewsPath(ServerKind.EMBY, session.userId), headers(), 30_000)
        }
        if (res.status == 401 || res.status == 403) throw ServerException("The server refused the sign-in (${res.status})", res.status)
        if (!res.ok) throw ServerException("Libraries: HTTP ${res.status}", res.status)
        return Json.parse(res.body).asObj().objs("Items").map {
            View(it.str("Id") ?: "", it.str("Name") ?: "Library", (it.str("CollectionType") ?: "").lowercase())
        }
    }

    /** Paginated /Items under a parent, with the fields the channel rules need. */
    fun items(parentId: String, includeItemTypes: String): List<Map<String, Any?>> {
        val collected = ArrayList<Map<String, Any?>>()
        var start = 0
        while (true) {
            val d = getJson(
                "/Items?userId=${enc(session.userId)}&ParentId=${enc(parentId)}&Recursive=true" +
                    "&IncludeItemTypes=$includeItemTypes" +
                    "&Fields=ProviderIds,Overview,Genres,Studios,MediaSources,ProductionYear,PremiereDate,DateCreated,UserData" +
                    "&StartIndex=$start&Limit=200&SortBy=SortName",
            )
            val page = d.objs("Items")
            collected.addAll(page)
            val total = d.int("TotalRecordCount") ?: collected.size
            start += page.size
            if (page.isEmpty() || collected.size >= total) break
        }
        return collected
    }

    fun scan(onProgress: (String, Int) -> Unit = { _, _ -> }, shouldStop: () -> Boolean = { false }): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val pool = Executors.newFixedThreadPool(5)
        try {
            for (view in views()) {
                if (shouldStop()) break
                val type = when (view.collectionType) {
                    "tvshows" -> "show"
                    "musicvideos" -> "musicvideo"
                    else -> "movie"
                }
                val isMv = type == "musicvideo"
                onProgress(view.name, out.size)
                try {
                    if (type == "show") {
                        val series = items(view.id, "Series")
                        var count = 0
                        for (batch in series.chunked(5)) {
                            if (shouldStop()) break
                            val results = batch.map { show -> pool.submit(Callable { episodesOf(show, isMv) }) }
                                .map { f -> try { f.get(180, TimeUnit.SECONDS) } catch (_: Exception) { emptyList() } }
                            for (eps in results) { out.addAll(eps); count += eps.size }
                            onProgress("${view.name} · $count eps", out.size)
                        }
                    } else {
                        for (jf in items(view.id, "Movie")) movieItem(jf, isMv)?.let(out::add)
                        onProgress(view.name, out.size)
                    }
                } catch (e: ServerException) {
                    if (e.status == 401 || e.status == 403) throw e
                } catch (_: Exception) {
                    // Skip a library that fails, as upstream does.
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    private fun episodesOf(show: Map<String, Any?>, isMv: Boolean): List<MediaItem> {
        val showGenres = show.strings("Genres")
        val showTmdb = tmdbFromProviderIds(show.obj("ProviderIds"))
        val eps = try { items(show.str("Id") ?: return emptyList(), "Episode") } catch (_: Exception) { return emptyList() }
        return eps.mapNotNull { ep ->
            val minutes = DailyManifest.minutesFromTicks(ep.long("RunTimeTicks"))
            if (minutes <= 0) return@mapNotNull null
            val mi = mediaInfo(ep)
            MediaItem(
                ratingKey = ep.str("Id") ?: return@mapNotNull null,
                title = show.str("Name") ?: "Unknown",
                episodeTitle = ep.str("Name") ?: "",
                summary = ep.str("Overview")?.takeIf { it.isNotEmpty() } ?: show.str("Overview") ?: "",
                year = show.int("ProductionYear")?.takeIf { it != 0 },
                originallyAvailableAt = (ep.str("PremiereDate")?.takeIf { it.isNotEmpty() }
                    ?: show.str("PremiereDate")?.takeIf { it.isNotEmpty() })?.take(10),
                contentRating = show.str("OfficialRating")?.takeIf { it.isNotEmpty() } ?: ep.str("OfficialRating") ?: "",
                duration = minutes,
                genres = showGenres,
                rating = show.double("CommunityRating") ?: 0.0,
                type = "episode",
                showKey = show.str("Id"),
                viewCount = ep.obj("UserData").int("PlayCount") ?: 0,
                addedAt = DailyManifest.unixSecondsFromISO(ep.str("DateCreated")),
                studio = show.objs("Studios").firstOrNull()?.str("Name") ?: "",
                librarySource = if (isMv) "musicVideo" else "tv",
                tmdbID = showTmdb,
                doviProfile = mi.doviProfile,
                seasonNumber = ep.int("ParentIndexNumber"),
                episodeNumber = ep.int("IndexNumber"),
                partKey = mi.msid,
                container = mi.container,
                videoCodec = mi.videoCodec,
                audioCodec = mi.audioCodec,
                bitrate = mi.bitrate,
                videoHeight = mi.height,
            )
        }
    }

    private fun movieItem(jf: Map<String, Any?>, isMvSection: Boolean): MediaItem? {
        val genres = jf.strings("Genres").toMutableList()
        if (isMvSection && genres.none { it.contains("music", ignoreCase = true) }) genres.add("Music Video")
        val minutes = DailyManifest.minutesFromTicks(jf.long("RunTimeTicks"))
        if (minutes <= 0) return null
        val isMv = isMvSection || (minutes <= 10 && genres.any { it.contains("music", ignoreCase = true) })
        val mi = mediaInfo(jf)
        var title = jf.str("Name") ?: "Unknown"
        var artist: String? = null
        if (isMv) {
            val p = MusicTitle.parseMusicVideo(jf.str("parentTitle"), jf.str("grandparentTitle"), title)
            title = p.first
            artist = p.second
        }
        return MediaItem(
            ratingKey = jf.str("Id") ?: return null,
            title = title,
            artist = artist,
            summary = jf.str("Overview") ?: "",
            year = jf.int("ProductionYear")?.takeIf { it != 0 },
            // No release date on a film, as the web tuner's loader: episode rules read it only
            // for episodes, and releasedWithinMonths then goes by the year, as upstream's web does.
            contentRating = jf.str("OfficialRating") ?: "",
            duration = minutes,
            genres = genres,
            rating = jf.double("CommunityRating") ?: 0.0,
            type = "movie",
            viewCount = jf.obj("UserData").int("PlayCount") ?: 0,
            addedAt = DailyManifest.unixSecondsFromISO(jf.str("DateCreated")),
            studio = jf.objs("Studios").firstOrNull()?.str("Name") ?: "",
            librarySource = if (isMv) "musicVideo" else "movie",
            tmdbID = tmdbFromProviderIds(jf.obj("ProviderIds")),
            doviProfile = mi.doviProfile,
            partKey = mi.msid,
            container = mi.container,
            videoCodec = mi.videoCodec,
            audioCodec = mi.audioCodec,
            bitrate = mi.bitrate,
            videoHeight = mi.height,
        )
    }

    class MediaInfo(
        val msid: String?, val container: String?, val videoCodec: String?, val audioCodec: String?,
        val doviProfile: Int?, val bitrate: Int, val height: Int,
    )

    /** The highest-quality source, and its codecs (web `mediaInfo`). */
    private fun mediaInfo(jf: Map<String, Any?>): MediaInfo {
        val sources = jf.objs("MediaSources")
        fun height(s: Map<String, Any?>) = s.objs("MediaStreams").firstOrNull { it.str("Type") == "Video" }?.int("Height") ?: 0
        val src = if (sources.isEmpty()) null
        else if (sources.none { it.double("Bitrate") != null }) sources[0]
        else sources.reduce { best, v ->
            val bb = best.double("Bitrate") ?: -1.0
            val bv = v.double("Bitrate") ?: -1.0
            if (bv != bb) (if (bv > bb) v else best) else if (height(v) > height(best)) v else best
        }
        val streams = src?.objs("MediaStreams") ?: emptyList()
        val v = streams.firstOrNull { it.str("Type") == "Video" }
        val a = streams.firstOrNull { it.str("Type") == "Audio" }
        return MediaInfo(
            msid = src?.str("Id"),
            container = src?.str("Container")?.lowercase(),
            videoCodec = v?.str("Codec")?.lowercase(),
            audioCodec = a?.str("Codec")?.lowercase(),
            doviProfile = v?.let { MediaBrowser.embyDoviProfile(it.str("ExtendedVideoSubType")) },
            bitrate = src?.double("Bitrate")?.let { if (it > 0) Math.round(it / 1000).toInt() else 0 } ?: 0,
            height = v?.int("Height") ?: 0,
        )
    }

    companion object {
        fun tmdbFromProviderIds(ids: Map<String, Any?>?): String? {
            if (ids == null) return null
            for ((k, v) in ids) if (k.lowercase() == "tmdb") return v?.toString()
            return null
        }
    }
}

/** Jellyfin and Emby stream addresses, after the Roku port's measured requests. */
object MediaBrowserStreams {
    fun canDirectPlay(item: MediaItem, caps: DeviceCaps): Boolean =
        Codecs.directPlayable(item.container, item.videoCodec, item.audioCodec, item.videoHeight, caps)

    fun staticStreamUrl(s: MediaBrowserSession, item: MediaItem): String {
        val msid = item.partKey ?: item.ratingKey
        val c = (item.container ?: "").lowercase().split(',').firstOrNull()?.trim() ?: ""
        val ext = when (c) {
            "mkv", "webm" -> ".$c"
            "mp4", "m4v", "mov" -> ".mp4"
            else -> ""
        }
        return "${s.url}/Videos/${enc(item.ratingKey)}/stream$ext?Static=true&MediaSourceId=${enc(msid)}"
    }

    /**
     * The server stream: `/Videos/{id}/master.m3u8` with HEVC and Dolby audio offered only when
     * this device decodes them, a high bitrate ceiling and the TV's width. The playlist spans
     * the whole file; the player joins at the offset.
     */
    fun hlsUrl(s: MediaBrowserSession, item: MediaItem, playSessionId: String, caps: DeviceCaps, capped: Boolean): String {
        val msid = item.partKey ?: item.ratingKey
        val videoCodecs = if (caps.hevc && !capped) "h264,hevc" else "h264"
        var audio = "aac"
        if (caps.ac3) audio += ",ac3"
        if (caps.eac3) audio += ",eac3"
        val sb = StringBuilder("${s.url}/Videos/${enc(item.ratingKey)}/master.m3u8?")
        sb.append("MediaSourceId=").append(enc(msid))
        sb.append("&DeviceId=").append(enc(s.deviceId))
        sb.append("&PlaySessionId=").append(playSessionId)
        sb.append("&VideoCodec=").append(videoCodecs)
        sb.append("&AudioCodec=").append(audio)
        sb.append("&TranscodingMaxAudioChannels=").append(if (caps.ac3 || caps.eac3) 6 else 2)
        when {
            capped -> sb.append("&VideoBitrate=8000000&MaxWidth=1920&AllowVideoStreamCopy=false")
            item.bitrate > 20_000 -> sb.append("&VideoBitrate=40000000&MaxWidth=${caps.maxWidth}&AllowVideoStreamCopy=false")
            else -> sb.append("&VideoBitrate=40000000&MaxWidth=3840")
        }
        sb.append("&SegmentContainer=ts&SegmentLength=2")
        // Both spellings: Jellyfin with legacy auth off refuses `api_key` alone.
        sb.append("&api_key=").append(enc(s.token)).append("&ApiKey=").append(enc(s.token))
        return sb.toString()
    }

    fun stopUrl(s: MediaBrowserSession, playSessionId: String) =
        "${s.url}/Videos/ActiveEncodings?deviceId=${enc(s.deviceId)}&playSessionId=${enc(playSessionId)}"

    fun newPlaySessionId(): String = UUID.randomUUID().toString().replace("-", "")

    fun imageUrl(s: MediaBrowserSession, itemId: String, width: Int = 300) =
        "${s.url}/Items/${enc(itemId)}/Images/Primary?maxWidth=$width&quality=85"
}
