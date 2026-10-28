package app.nostalgex.engine

import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.arr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.server.DeviceCaps
import app.nostalgex.engine.server.Http
import app.nostalgex.engine.server.HttpResponse
import app.nostalgex.engine.server.ItemCodec
import app.nostalgex.engine.server.MediaBrowser
import app.nostalgex.engine.server.MediaBrowserLibrary
import app.nostalgex.engine.server.MediaBrowserSession
import app.nostalgex.engine.server.MediaBrowserStreams
import app.nostalgex.engine.server.MusicTitle
import app.nostalgex.engine.server.PlexAuth
import app.nostalgex.engine.server.PlexConnection
import app.nostalgex.engine.server.PlexLibrary
import app.nostalgex.engine.server.PlexServer
import app.nostalgex.engine.server.PlexStreams
import app.nostalgex.engine.server.SavedPlexServer
import app.nostalgex.engine.server.ServerKind
import app.nostalgex.engine.server.Session
import java.net.URI
import java.net.URLDecoder

/** The server clients against saved server answers, and upstream's own loader's results. */
object ServerChecks {

    fun all(): List<GoldenSuite.Result> = listOf(mediaBrowserScan(), plexScan(), helpers())

    private fun query(url: String): Map<String, String> {
        val q = URI(url).rawQuery ?: return emptyMap()
        return q.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) URLDecoder.decode(it, "UTF-8") to "" else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
    }

    /** roku/tests/gen-mediabrowser.mjs `serveFixture`. */
    private class FixtureHttp(val fx: Map<String, Any?>) : Http {
        val requests = ArrayList<String>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse {
            requests.add(url.removePrefix("http://server.invalid"))
            val path = URI(url).path
            val q = query(url)
            if (path.endsWith("/UserViews") || Regex("/Users/[^/]+/Views$").containsMatchIn(path)) {
                val views = fx.arr("views")!!
                return HttpResponse(200, Json.stringify(mapOf("Items" to views, "TotalRecordCount" to views.size)))
            }
            if (path.endsWith("/Items")) {
                val parent = q["ParentId"]
                if (fx.arr("fail")?.contains(parent) == true) return HttpResponse(500, "{}")
                val all = fx.obj("items")!!.objs(parent ?: "").filter { it.str("Type") == q["IncludeItemTypes"] }
                val start = q["StartIndex"]?.toInt() ?: 0
                val limit = q["Limit"]?.toInt() ?: all.size
                return HttpResponse(200, Json.stringify(mapOf("Items" to all.drop(start).take(limit), "TotalRecordCount" to all.size)))
            }
            return HttpResponse(404, "{}")
        }
    }

    private fun itemFields(it: MediaItem): Map<String, Any?> = mapOf(
        "title" to it.title, "artist" to it.artist, "episodeTitle" to it.episodeTitle, "summary" to it.summary,
        "year" to it.year, "originallyAvailableAt" to it.originallyAvailableAt, "contentRating" to it.contentRating,
        "duration" to it.duration, "ratingKey" to it.ratingKey, "partKey" to it.partKey, "container" to it.container,
        "videoCodec" to it.videoCodec, "audioCodec" to it.audioCodec, "bitrate" to it.bitrate, "genres" to it.genres,
        "rating" to it.rating, "userRating" to it.userRating, "type" to it.type, "viewCount" to it.viewCount,
        "addedAt" to it.addedAt, "studio" to it.studio, "librarySource" to it.librarySource, "tmdbID" to it.tmdbID,
        "doviProfile" to it.doviProfile, "showKey" to it.showKey,
    )

    /** The upstream item as the port holds it: '' years become null, absent fields null. */
    private fun upstreamFields(o: Map<String, Any?>): Map<String, Any?> = itemFields(
        MediaItem(
            ratingKey = o.str("ratingKey")!!, title = o.str("title") ?: "", artist = o.str("artist"),
            episodeTitle = o.str("episodeTitle"), summary = o.str("summary") ?: "",
            year = (o["year"] as? Number)?.toInt(), originallyAvailableAt = o.str("originallyAvailableAt"),
            contentRating = o.str("contentRating"), duration = (o["duration"] as Number).toInt(),
            partKey = o.str("partKey"), container = o.str("container"), videoCodec = o.str("videoCodec"),
            audioCodec = o.str("audioCodec"), bitrate = (o["bitrate"] as? Number)?.toInt() ?: 0,
            genres = o.arr("genres")!!.map { it as String }, rating = (o["rating"] as? Number)?.toDouble() ?: 0.0,
            userRating = (o["userRating"] as? Number)?.toDouble() ?: 0.0, type = o.str("type")!!,
            viewCount = (o["viewCount"] as? Number)?.toInt() ?: 0, addedAt = (o["addedAt"] as? Number)?.toLong() ?: 0,
            studio = o.str("studio"), librarySource = o.str("librarySource")!!, tmdbID = o.str("tmdbID"),
            doviProfile = (o["doviProfile"] as? Number)?.toInt(), showKey = o.str("showKey"),
        ),
    )

    fun mediaBrowserScan(): GoldenSuite.Result {
        val r = GoldenSuite.Result("jellyfin/emby scan (upstream loader)")
        val stream = ServerChecks::class.java.getResourceAsStream("/vectors/mediabrowser.json")
        val v = Json.parse(stream!!.bufferedReader().use { it.readText() }).asObj()!!
        val fx = v.obj("fixture")!!
        val http = FixtureHttp(fx)
        val session = MediaBrowserSession(ServerKind.JELLYFIN, "http://server.invalid", fx.str("userId")!!, "t", "dev", "Lab", "me")
        val items = MediaBrowserLibrary(http, session).scan()
        val want = v.objs("items")
        r.check("item count", items.size == want.size) { "port ${items.size}, upstream ${want.size}" }
        for (i in 0 until minOf(items.size, want.size)) {
            val g = itemFields(items[i])
            val w = upstreamFields(want[i])
            r.check("item $i ${want[i].str("title")}", GoldenSuite.canonical(g) == GoldenSuite.canonical(w)) {
                val diff = g.keys.filter { GoldenSuite.canonical(g[it]) != GoldenSuite.canonical(w[it]) }
                diff.joinToString("; ") { "$it: port ${Json.stringify(g[it])} upstream ${Json.stringify(w[it])}" }
            }
        }
        return r
    }

    /** A small Plex server: sections, paging, a show's leaves, a music video library. */
    private class PlexFake : Http {
        val requests = ArrayList<String>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse {
            requests.add(url)
            val path = URI(url).path
            val q = query(url)
            fun mc(o: Map<String, Any?>) = HttpResponse(200, Json.stringify(mapOf("MediaContainer" to o)))
            return when {
                path == "/library/sections" -> mc(mapOf("Directory" to listOf(
                    mapOf("key" to "1", "title" to "Movies", "type" to "movie"),
                    mapOf("key" to "2", "title" to "TV Shows", "type" to "show"),
                    mapOf("key" to "3", "title" to "Music Videos", "type" to "movie"),
                )))
                path == "/library/sections/1/all" -> {
                    val all = (1..450).map { n ->
                        mapOf(
                            "ratingKey" to "${1000 + n}", "title" to "Film $n", "year" to 1980 + n % 40, "duration" to (90 * 60000 + n * 1000),
                            "Genre" to listOf(mapOf("tag" to "Drama")), "contentRating" to "PG", "addedAt" to 1600000000 + n,
                            "Guid" to listOf(mapOf("id" to "imdb://tt1"), mapOf("id" to "tmdb://00${n}")),
                            "Media" to listOf(
                                mapOf("bitrate" to 4000, "videoResolution" to "1080", "container" to "mkv", "videoCodec" to "h264", "audioCodec" to "ac3", "Part" to listOf(mapOf("key" to "/library/parts/$n/file.mkv"))),
                                mapOf("bitrate" to 9000, "videoResolution" to "4k", "container" to "mp4", "videoCodec" to "hevc", "audioCodec" to "eac3", "Part" to listOf(mapOf("key" to "/library/parts/${n}b/file.mp4"))),
                            ),
                        )
                    }
                    val start = q["X-Plex-Container-Start"]!!.toInt()
                    val size = q["X-Plex-Container-Size"]!!.toInt()
                    mc(mapOf("totalSize" to all.size, "Metadata" to all.drop(start).take(size)))
                }
                path == "/library/sections/2/all" -> mc(mapOf("totalSize" to 1, "Metadata" to listOf(
                    mapOf("ratingKey" to "50", "title" to "Cheers", "year" to 1982, "contentRating" to "TV-PG", "studio" to "NBC",
                        "Genre" to listOf(mapOf("tag" to "Comedy")), "Guid" to listOf(mapOf("id" to "tmdb://3943"))),
                )))
                path == "/library/metadata/50/allLeaves" -> mc(mapOf("Metadata" to listOf(
                    mapOf("ratingKey" to "51", "title" to "Give Me a Ring Sometime", "duration" to 1500000, "parentIndex" to 1, "index" to 1, "originallyAvailableAt" to "1982-09-30"),
                    mapOf("ratingKey" to "52", "title" to "Zero", "duration" to 59999),
                )))
                path == "/library/sections/3/all" -> mc(mapOf("totalSize" to 1, "Metadata" to listOf(
                    mapOf("ratingKey" to "70", "title" to "Nirvana - Smells Like Teen Spirit [Official Video]", "duration" to 301000),
                )))
                else -> HttpResponse(404, "{}")
            }
        }
    }

    fun plexScan(): GoldenSuite.Result {
        val r = GoldenSuite.Result("plex scan")
        val http = PlexFake()
        val server = SavedPlexServer("Lab", "mid", "http://plex.invalid:32400", "tok")
        val items = PlexLibrary(http, server, 0).scan()
        r.check("450 films over two pages", items.count { it.librarySource == "movie" } == 450)
        r.check("pages of 300", http.requests.count { it.contains("/library/sections/1/all") } == 2)
        val f = items.first()
        r.check("film fields", f.title == "Film 1" && f.duration == 90 && f.tmdbID == "1" && f.year == 1981 && f.contentRating == "PG") { f.toString() }
        r.check("best version picked", f.partKey == "/library/parts/1b/file.mp4" && f.videoCodec == "hevc" && f.videoHeight == 2160) { f.toString() }
        val ep = items.firstOrNull { it.type == "episode" }
        r.check("episode fields", ep != null && ep.title == "Cheers" && ep.episodeTitle == "Give Me a Ring Sometime" && ep.duration == 25 &&
            ep.tmdbID == "3943" && ep.studio == "NBC" && ep.contentRating == "TV-PG" && ep.librarySource == "tv" && ep.genres == listOf("Comedy") &&
            ep.originallyAvailableAt == "1982-09-30" && ep.showKey == "50") { ep.toString() }
        r.check("a zero-minute episode is dropped", items.none { it.ratingKey == "52" })
        val mv = items.firstOrNull { it.ratingKey == "70" }
        r.check("music video", mv != null && mv.librarySource == "musicVideo" && mv.artist == "Nirvana" && mv.title == "Smells Like Teen Spirit" &&
            mv.genres == listOf("Music Video")) { mv.toString() }
        r.check("token in a header, not the URL", http.requests.none { it.contains("X-Plex-Token") })
        return r
    }

    fun helpers(): GoldenSuite.Result {
        val r = GoldenSuite.Result("server helpers")
        r.check("address: bare host", MediaBrowser.addressCandidates("192.168.1.5") == listOf("http://192.168.1.5:8096", "http://192.168.1.5"))
        r.check("address: port", MediaBrowser.addressCandidates("nas.local:8920") == listOf("http://nas.local:8920"))
        r.check("address: https + web path", MediaBrowser.addressCandidates("https://jf.example.com/jellyfin/web/index.html#!/home") == listOf("https://jf.example.com/jellyfin"))
        r.check("address: spaces", MediaBrowser.addressCandidates(" http://10.0.0.2 ") == listOf("http://10.0.0.2:8096", "http://10.0.0.2"))
        r.check("emby dovi", MediaBrowser.embyDoviProfile("DoviProfile50") == 5 && MediaBrowser.embyDoviProfile("DoviProfile81") == 8 && MediaBrowser.embyDoviProfile("Hdr10") == null)
        r.check("plex default choice: owned", PlexAuth.defaultChoice(listOf(
            PlexServer("a", "1", "t", true, true, emptyList()), PlexServer("b", "2", "t", false, true, emptyList()),
        )) == setOf("1"))
        r.check("plex default choice: none owned", PlexAuth.defaultChoice(listOf(
            PlexServer("a", "1", "t", false, true, emptyList()), PlexServer("b", "2", "t", false, true, emptyList()),
        )) == setOf("1", "2"))

        val caps = DeviceCaps(hevc = true, ac3 = true, eac3 = false, maxWidth = 3840)
        val server = SavedPlexServer("Lab", "mid", "https://1-2-3-4.abc.plex.direct:32400", "tok")
        val item = MediaItem(ratingKey = "123", title = "T", duration = 100, partKey = "/library/parts/9/f.mkv", container = "mkv", videoCodec = "hevc", audioCodec = "truehd")
        r.check("truehd needs the server", !PlexStreams.canDirectPlay(item, caps))
        r.check("hevc+ac3 mkv plays direct", PlexStreams.canDirectPlay(item.copy(audioCodec = "ac3"), caps))
        r.check("no hevc decoder, no direct play", !PlexStreams.canDirectPlay(item.copy(audioCodec = "ac3"), caps.copy(hevc = false)))
        val url = PlexStreams.transcodeUrl(server, item, 1800, "sess", "cid", caps, capped = false)
        val q = query(url)
        r.check("transcode: offset and session", q["offset"] == "1800" && q["session"] == "sess" && q["path"] == "/library/metadata/123" && q["protocol"] == "hls")
        r.check("transcode: hevc and ac3 offered", q["X-Plex-Client-Profile-Extra"]!!.contains("videoCodec=hevc") && q["X-Plex-Client-Profile-Extra"]!!.contains("audioCodec=ac3") &&
            !q["X-Plex-Client-Profile-Extra"]!!.contains("audioCodec=eac3"))
        val capped = query(PlexStreams.transcodeUrl(server, item, 0, "s2", "cid", caps, capped = true))
        r.check("capped: 1080p, no hevc", capped["videoResolution"] == "1920x1080" && !capped["X-Plex-Client-Profile-Extra"]!!.contains("videoCodec=hevc"))
        r.check("stable session identifier", q["X-Plex-Session-Identifier"] == query(PlexStreams.transcodeUrl(server, item, 5, "other", "cid", caps, false))["X-Plex-Session-Identifier"])

        val mb = MediaBrowserSession(ServerKind.EMBY, "http://10.0.0.2:8096", "u", "tok", "dev", "Lab", "me")
        val hls = query(MediaBrowserStreams.hlsUrl(mb, item.copy(partKey = "ms1", bitrate = 30000), "ps", caps, false))
        r.check("mb hls", hls["MediaSourceId"] == "ms1" && hls["VideoCodec"] == "h264,hevc" && hls["AudioCodec"] == "aac,ac3" && hls["AllowVideoStreamCopy"] == "false" && hls["api_key"] == "tok")
        r.check("mb static mkv", MediaBrowserStreams.staticStreamUrl(mb, item.copy(partKey = "ms1")).startsWith("http://10.0.0.2:8096/Videos/123/stream.mkv?Static=true&MediaSourceId=ms1"))

        // The library save round-trips.
        val items = listOf(item, item.copy(ratingKey = "é\"x", genres = listOf("A", "B"), year = 1999, rating = 7.25, summary = "Line\nbreak"))
        val back = ItemCodec.decode(ItemCodec.encode(items, 42, "sig"))
        r.check("library save round trip", back != null && back.items == items && back.savedAtMs == 42L && back.signature == "sig")

        val plex = Session.Plex("cid", "acct", listOf(server.copy(connections = listOf(PlexConnection("http://192.168.1.2:32400", true, false, "http")))))
        r.check("session round trip (plex)", Session.fromJson(plex.toJson()) == plex)
        val mbs = Session.MediaBrowser(mb)
        r.check("session round trip (emby)", Session.fromJson(mbs.toJson()) == mbs)

        r.check("music: dash", MusicTitle.parse("a-ha - Take On Me [HD]") == ("a-ha" to "Take On Me"))
        r.check("music: by", MusicTitle.parse("Purple Rain by Prince") == ("Prince" to "Purple Rain"))
        r.check("music: featuring", MusicTitle.parse("Run-DMC ft. Aerosmith - Walk This Way") == ("Run-DMC" to "Walk This Way"))
        return r
    }
}
