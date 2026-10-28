package app.nostalgex.engine

import app.nostalgex.engine.filter.ChannelFilter
import app.nostalgex.engine.filter.FilterClock
import app.nostalgex.engine.filter.JsText
import app.nostalgex.engine.filter.PreparedChannel
import app.nostalgex.engine.filter.PreparedItem
import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.arr
import app.nostalgex.engine.json.asArr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.long
import app.nostalgex.engine.json.number
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.model.Channel
import app.nostalgex.engine.model.ChannelConfig
import app.nostalgex.engine.model.Enrichment
import app.nostalgex.engine.model.ExclusiveRule
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.model.MembershipManifest
import app.nostalgex.engine.schedule.ChannelSchedule
import app.nostalgex.engine.schedule.DailyManifest
import app.nostalgex.engine.schedule.LineupEntry
import app.nostalgex.engine.schedule.MemoryManifestStore
import app.nostalgex.engine.schedule.SchedChannel
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Differential vectors from upstream's own JS engines (`tools/gen-vectors.mjs`): the port must
 * give the JS answer for every channel and item, and every day of every channel.
 */
object EngineChecks {

    fun all(): List<GoldenSuite.Result> = listOf(filterVectors(), scheduleVectors(), jsonRoundTrip())

    private fun vectors(name: String): Map<String, Any?> {
        val stream = EngineChecks::class.java.getResourceAsStream("/vectors/$name")
        val text = stream?.bufferedReader()?.use { it.readText() }
            ?: java.io.File(findRoot(), "engine/src/test/resources/vectors/$name").readText()
        return Json.parse(text).asObj()!!
    }

    private fun findRoot(): java.io.File {
        var d: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (java.io.File(d, "engine/src/test/resources").exists()) return d
            d = d.parentFile
        }
        error("no project root")
    }

    private fun stringList(o: Map<String, Any?>, key: String): List<String>? =
        o.arr(key)?.map { it?.toString() ?: "null" }

    /** A generator item, read as the JS reads it: flat enrichment fields, string years. */
    fun vectorItem(o: Map<String, Any?>): MediaItem {
        val nested = o.obj("enrichment")
        fun list(key: String, flatFirst: Boolean = false): List<String> {
            val n = stringList(nested ?: emptyMap(), key)
            val f = stringList(o, key)
            return if (flatFirst) f ?: n ?: emptyList() else n ?: f ?: emptyList()
        }
        fun num(key: String): Double? = nested.number(key) ?: o.number(key)
        val keywords = stringList(o, "tmdbKeywords") ?: stringList(nested ?: emptyMap(), "keywords") ?: stringList(o, "keywords") ?: emptyList()
        val year = when (val y = o["year"]) {
            is Number -> y.toInt()
            is String -> JsText.parseIntJs(y)
            else -> null
        }
        return MediaItem(
            ratingKey = o.str("ratingKey")!!,
            title = o.str("title") ?: "",
            type = o.str("type") ?: "movie",
            duration = (o["duration"] as? Number)?.toInt() ?: 0,
            addedAt = o.long("addedAt") ?: 0,
            librarySource = o.str("librarySource")?.takeIf { it.isNotEmpty() } ?: "movie",
            artist = o.str("artist"),
            episodeTitle = o.str("episodeTitle"),
            summary = o.str("summary") ?: "",
            year = year,
            originallyAvailableAt = o["originallyAvailableAt"] as? String,
            genres = stringList(o, "genres") ?: emptyList(),
            contentRating = o["contentRating"] as? String,
            rating = o.number("rating") ?: 0.0,
            userRating = o.number("userRating") ?: 0.0,
            viewCount = o.int("viewCount") ?: 0,
            studio = o["studio"] as? String,
            tmdbID = o.str("tmdbID"),
            doviProfile = o.int("doviProfile"),
            enrichment = Enrichment(
                keywords = keywords,
                productionCompanies = list("productionCompanies"),
                networks = list("networks"),
                tmdbGenres = list("tmdbGenres"),
                imdbRating = num("imdbRating"),
                imdbVotes = num("imdbVotes"),
                rottenTomatoesScore = num("rottenTomatoesScore") ?: num("rtScore"),
                metacriticScore = num("metacriticScore") ?: num("metacritic"),
                awards = (nested?.get("awards") ?: o["awards"]) as? String,
            ),
        )
    }

    fun filterVectors(): GoldenSuite.Result {
        val r = GoldenSuite.Result("filter vectors (upstream JS engine)")
        val v = vectors("filter.json")
        val manifest = MembershipManifest.parse(GoldenSuite.asset("channels-memberships.json"))
        val exclusive = v.objs("exclusiveRules").map(ExclusiveRule::fromJson)
        val channels = v.objs("channels").map { c ->
            Channel.fromJson(c + mapOf("name" to "CH ${c.int("id")}"))
        }
        val items = v.objs("items").map { PreparedItem(vectorItem(it)) }
        val results = v.obj("results")!!
        for (clock in v.objs("clocks")) {
            val name = clock.str("name")!!
            val nowSec = clock.long("nowSec")!!
            val filter = ChannelFilter(manifest, exclusive, clock.str("backend")!!, FilterClock.fixed(nowSec * 1000, ZoneOffset.UTC))
            val want = results.arr(name)!!
            channels.forEachIndexed { i, ch ->
                val pc = PreparedChannel(ch)
                val got = items.mapNotNull { it2 -> filter.passes(it2, pc)?.let { via -> "${it2.item.ratingKey}:$via" } }.joinToString(",")
                val w = want[i] as String
                r.check("clock $name channel #$i id ${ch.id}", got == w) {
                    val gs = got.split(",").toSet()
                    val ws = w.split(",").toSet()
                    "port only: ${(gs - ws).take(8)}; JS only: ${(ws - gs).take(8)}"
                }
            }
        }
        return r
    }

    fun scheduleVectors(): GoldenSuite.Result {
        val r = GoldenSuite.Result("schedule vectors (upstream JS engine)")
        val v = vectors("schedule.json")
        val byKey = LinkedHashMap<String, MediaItem>()
        for (o in v.objs("items")) {
            val it = MediaItem(
                ratingKey = o.str("ratingKey")!!, title = o.str("title")!!, type = o.str("type")!!,
                duration = o.int("duration")!!, addedAt = o.long("addedAt") ?: 0,
            )
            byKey[it.ratingKey] = it
        }
        val pools = v.obj("pools")!!.mapValues { (_, keys) -> keys.asArr()!!.map { byKey.getValue(it as String) } }
        val lineup = v.objs("channels").map {
            LineupEntry(SchedChannel(it.int("id")!!, it.bool("premiere") == true), pools.getValue(it.str("pool")!!))
        }
        for (case in v.objs("cases")) {
            val zone = ZoneId.of(case.str("zone")!!)
            val m = DailyManifest(MemoryManifestStore()) { zone }
            for (day in case.objs("days")) {
                val t = day.long("t")!!
                val want = day.obj("ch")!!
                for ((channel, pool) in lineup) {
                    val got = m.getManifestBlocks(channel, pool, t * 1000, "vec").map { listOf(it.item.ratingKey, it.startSec, it.endSec) }
                    val w = want[channel.id.toString()]
                    r.check("${case.str("zone")} day $t ch ${channel.id}", GoldenSuite.canonical(got) == GoldenSuite.canonical(w)) {
                        "port ${GoldenSuite.canonical(got).take(160)}\n    js   ${GoldenSuite.canonical(w).take(160)}"
                    }
                }
            }
            for (a in case.objs("at")) {
                val t = (a["t"] as Number).toDouble()
                val raw = LinkedHashMap<Int, ChannelSchedule>()
                for ((channel, pool) in lineup) m.buildSchedule(channel, pool, t, "vec")?.let { raw[channel.id] = it }
                val shown = m.resolveConflicts(LinkedHashMap(raw))
                val want = a.obj("ch")!!
                for ((channel, pool) in lineup) {
                    val u = raw[channel.id]
                    val s = shown[channel.id]
                    val got: Any? = if (u == null || s == null) null else {
                        val tune = m.tuneIn(channel, pool, s, t, "vec")
                        val p = m.advancePick(channel, pool, tune?.item?.ratingKey, u.nowPlaying?.endSec?.toDouble() ?: t, lineup, "vec", true)
                        mapOf(
                            "now" to u.nowPlaying?.item?.ratingKey, "entries" to u.entries.size, "elapsed" to u.elapsedSeconds,
                            "shown" to s.nowPlaying?.item?.ratingKey,
                            "tune" to tune?.let { listOf(it.item.ratingKey, it.seekSeconds) },
                            "next" to p?.let { listOf(it.item.ratingKey, it.seekSeconds) },
                        )
                    }
                    val w = want[channel.id.toString()]
                    r.check("${case.str("zone")} at $t ch ${channel.id}", GoldenSuite.canonical(got) == GoldenSuite.canonical(w)) {
                        "port ${GoldenSuite.canonical(got)}\n    js   ${GoldenSuite.canonical(w)}"
                    }
                }
            }
        }
        return r
    }

    fun jsonRoundTrip(): GoldenSuite.Result {
        val r = GoldenSuite.Result("json")
        val text = GoldenSuite.asset("channels.json")
        val once = Json.stringify(Json.parse(text))
        r.check("channels.json survives a round trip", Json.stringify(Json.parse(once)) == once)
        val tricky = "{\"a\":\"\\u00e9\\n\\\"x\\\"\",\"b\":[1,2.5,-3e2,true,null],\"c\":{}}"
        val v = Json.parse(tricky).asObj()!!
        r.check("escapes", v.str("a") == "é\n\"x\"")
        r.check("numbers", v.arr("b") == listOf(1L, 2.5, -300.0, true, null))
        r.check("config parses", ChannelConfig.parse(text).channels.size > 100)
        return r
    }
}
