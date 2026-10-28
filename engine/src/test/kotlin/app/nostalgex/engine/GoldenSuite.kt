package app.nostalgex.engine

import app.nostalgex.engine.filter.ChannelFilter
import app.nostalgex.engine.filter.FilterClock
import app.nostalgex.engine.filter.PreparedChannel
import app.nostalgex.engine.filter.PreparedItem
import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.arr
import app.nostalgex.engine.json.asArr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.long
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.json.strings
import app.nostalgex.engine.model.Bundle
import app.nostalgex.engine.model.ChannelConfig
import app.nostalgex.engine.model.Enrichment
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.model.MembershipManifest
import app.nostalgex.engine.model.SeasonStart
import app.nostalgex.engine.schedule.ChannelSchedule
import app.nostalgex.engine.schedule.DailyManifest
import app.nostalgex.engine.schedule.LineupEntry
import app.nostalgex.engine.schedule.MemoryManifestStore
import app.nostalgex.engine.schedule.SchedChannel
import app.nostalgex.engine.schedule.Season
import app.nostalgex.engine.schedule.SeasonalAnswer
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Upstream's golden vectors, vendored unchanged from chadMueller/Nostalgex `scripts/`, run
 * against this port the way upstream's own smoke tests run them against the JS engines:
 *
 *  - filter-golden.json          as nostalgex-filter-golden-smoke.mjs
 *  - rating-golden.json          as nostalgex-rating-smoke.mjs
 *  - schedule-parity-golden.json as nostalgex-schedule-parity-smoke.mjs / schedule-parity-dump.mjs
 *    (made by the Apple TV's own code: ScheduleParityTests.swift), byte for byte
 *  - schedule-dst-golden.json    as nostalgex-schedule-dst-smoke.mjs
 *  - season-golden.json          as nostalgex-season-smoke.mjs
 *
 * A divergence is a bug in the port, never a reason to edit the vectors.
 */
object GoldenSuite {

    class Result(val name: String) {
        var passed = 0
        val failures = ArrayList<String>()
        fun check(label: String, ok: Boolean, detail: () -> String = { "" }) {
            if (ok) passed++ else failures.add(label + detail().let { if (it.isEmpty()) "" else "\n    $it" })
        }
        override fun toString() =
            if (failures.isEmpty()) "$name: $passed passed" else "$name: ${failures.size} FAILED, $passed passed\n  " +
                failures.take(25).joinToString("\n  ")
    }

    fun all(): List<Result> = listOf(rating(), filter(), scheduleParity(), scheduleDst(), season())

    // ── Files ────────────────────────────────────────────────────────────────

    private fun golden(name: String): Any? {
        val stream = GoldenSuite::class.java.getResourceAsStream("/golden/$name")
        val text = stream?.bufferedReader()?.use { it.readText() }
            ?: findUp("engine/src/test/resources/golden/$name").readText()
        return Json.parse(text)
    }

    /** The app's shipped data (app/src/main/assets), found from wherever the tests run. */
    fun asset(name: String): String = findUp("app/src/main/assets/$name").readText()

    private fun findUp(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("can't find $rel above ${System.getProperty("user.dir")}")
    }

    // ── Ratings ──────────────────────────────────────────────────────────────

    fun rating(): Result {
        val r = Result("rating golden")
        for (c in golden("rating-golden.json").asObj().objs("cases")) {
            val raw = c["raw"] as String?
            val type = c.str("type")
            val want = c["us"] as String?
            val got = ChannelFilter.usContentRating(raw, type)
            r.check("usContentRating(${Json.stringify(raw)}, $type)", got == want) { "got ${Json.stringify(got)}, want ${Json.stringify(want)}" }
        }
        return r
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    fun filter(): Result {
        val r = Result("filter golden")
        val config = ChannelConfig.parse(asset("channels.json"))
        val manifest = MembershipManifest.parse(asset("channels-memberships.json"))
        val g = golden("filter-golden.json").asObj()
        val filters = HashMap<String, ChannelFilter>()
        for (c in g.objs("cases")) {
            val gi = c.obj("item")!!
            val backend = gi.str("backend") ?: "plex"
            val filter = filters.getOrPut(backend) {
                ChannelFilter(manifest, config.exclusiveRules, backend, FilterClock.system())
            }
            val type = gi.str("type") ?: "movie"
            val en = gi.obj("enrichment")
            val item = MediaItem(
                ratingKey = "golden",
                title = gi.str("title") ?: "",
                artist = gi.str("artist"),
                episodeTitle = gi.str("episodeTitle"),
                summary = gi.str("summary") ?: "",
                originallyAvailableAt = gi.str("originallyAvailableAt"),
                year = gi.int("year"),
                type = type,
                librarySource = gi.str("librarySource") ?: if (type == "episode") "tv" else "movie",
                genres = gi.strings("genres"),
                contentRating = gi["contentRating"] as String?,
                duration = gi.int("duration") ?: 100,
                rating = (gi["rating"] as? Number)?.toDouble() ?: 0.0,
                studio = gi.str("studio"),
                tmdbID = gi.str("tmdbID"),
                doviProfile = gi.int("doviProfile"),
                enrichment = en?.let {
                    Enrichment(
                        keywords = it.strings("keywords"),
                        productionCompanies = it.strings("productionCompanies"),
                        networks = it.strings("networks"),
                        tmdbGenres = it.strings("tmdbGenres"),
                    )
                },
            )
            val pi = PreparedItem(item)
            val problems = ArrayList<String>()
            fun pass(id: Int): Boolean {
                val ch = config.channelsById[id] ?: error("channel id $id is not in channels.json")
                return filter.passes(pi, PreparedChannel(ch)) != null
            }
            try {
                for (id in c.arr("in").orEmpty()) {
                    val i = (id as Number).toInt()
                    if (!pass(i)) problems.add("not on $i ${config.channelsById[i]?.name}")
                }
                for (id in c.arr("out").orEmpty()) {
                    val i = (id as Number).toInt()
                    if (pass(i)) problems.add("on $i ${config.channelsById[i]?.name}")
                }
            } catch (e: Exception) {
                problems.add(e.message ?: e.toString())
            }
            r.check(c.str("name") ?: "?", problems.isEmpty()) { problems.joinToString("; ") }
        }
        return r
    }

    // ── Schedule parity (the Apple TV's own answers) ────────────────────────

    private fun makeItem(spec: Map<String, Any?>): Pair<String, MediaItem?> {
        val compact = spec.arr("compact")
        if (compact != null) {
            val key = compact[0] as String
            return key to MediaItem(
                ratingKey = key,
                title = compact[1] as String,
                type = compact[2] as String,
                duration = (compact[3] as Number).toInt(),
                addedAt = (compact[4] as Number).toLong(),
            )
        }
        val backend = spec.str("backend")
        val kind = spec.str("kind") ?: "movie"
        val raw = spec.obj("raw")
        val show = spec.obj("show") ?: emptyMap()
        return when (backend) {
            "plex" -> {
                val key = raw.str("ratingKey")!!
                val minutes = DailyManifest.minutesFromPlexMs(raw.long("duration"))
                if (minutes <= 0) return key to null
                val title = if (kind == "episode") show.str("title") ?: "" else raw.str("title") ?: ""
                key to MediaItem(ratingKey = key, title = title, type = kind, duration = minutes, addedAt = raw.long("addedAt") ?: 0)
            }
            "jellyfin", "emby" -> {
                val key = raw.str("Id")!!
                val minutes = DailyManifest.minutesFromTicks(raw.long("RunTimeTicks"))
                if (minutes <= 0) return key to null
                val title = if (kind == "episode") show.str("Name") ?: raw.str("SeriesName") ?: "" else raw.str("Name") ?: ""
                key to MediaItem(
                    ratingKey = key, title = title, type = kind, duration = minutes,
                    addedAt = DailyManifest.unixSecondsFromISO(raw.str("DateCreated")),
                )
            }
            else -> error("unknown item $backend $kind")
        }
    }

    /** schedule-parity-dump.mjs `dump`, field for field. */
    fun dumpParity(fx: Map<String, Any?>): Map<String, Any?> {
        val zone = ZoneId.of(fx.str("zone")!!)
        val store = MemoryManifestStore()
        val m = DailyManifest(store) { zone }
        val sk = fx.str("fingerprint")!!
        val byKey = HashMap<String, MediaItem>()
        val items = LinkedHashMap<String, Any?>()
        for (spec in fx.objs("items")) {
            val (key, item) = makeItem(spec)
            if (item != null) byKey[item.ratingKey] = item
            if (spec.arr("compact") == null) {
                items[key] = item?.let { listOf(it.duration.toLong(), maxOf(0L, it.addedAt), it.title, it.type) }
            }
        }
        val lineup = fx.objs("channels").map { row ->
            LineupEntry(
                SchedChannel(row.int("id")!!, row.bool("premiere") == true),
                row.arr("pool").orEmpty().mapNotNull { byKey[it as String] },
            )
        }
        for (t in fx.arr("prime").orEmpty()) {
            val ms = (t as Number).toLong() * 1000
            for ((channel, pool) in lineup) m.getManifestBlocks(channel, pool, ms, sk)
        }
        val days = LinkedHashMap<String, Any?>()
        for (t in fx.arr("days").orEmpty()) {
            val sec = (t as Number).toLong()
            val byChannel = LinkedHashMap<String, Any?>()
            for ((channel, pool) in lineup) {
                byChannel[channel.id.toString()] = m.getManifestBlocks(channel, pool, sec * 1000, sk)
                    .map { listOf(it.item.ratingKey, it.startSec, it.endSec) }
            }
            days[sec.toString()] = byChannel
        }
        val at = ArrayList<Any?>()
        for (tv in fx.arr("at").orEmpty()) {
            val tl = (tv as Number).toLong()
            val t = tl.toDouble()
            val raw = LinkedHashMap<Int, ChannelSchedule>()
            for ((channel, pool) in lineup) m.buildSchedule(channel, pool, t, sk)?.let { raw[channel.id] = it }
            val shown = m.resolveConflicts(LinkedHashMap(raw))
            val byChannel = LinkedHashMap<String, Any?>()
            for ((channel, pool) in lineup) {
                val u = raw[channel.id]
                val r = shown[channel.id]
                if (u == null || r == null) {
                    byChannel[channel.id.toString()] = null
                    continue
                }
                val tune = m.tuneIn(channel, pool, r, t, sk)
                val prev = tune?.item?.ratingKey
                val endOfNow = u.nowPlaying?.endSec?.toDouble() ?: t
                fun adv(now: Double, natural: Boolean): Any? =
                    m.advancePick(channel, pool, prev, now, lineup, sk, natural)?.let { listOf(it.item.ratingKey, it.seekSeconds) }
                val gaveWay = r.entries.firstOrNull { it.isConflict }
                byChannel[channel.id.toString()] = mapOf(
                    "now" to u.nowPlaying?.let { listOf(it.item.ratingKey, it.startSec, it.endSec) },
                    "elapsed" to u.elapsedSeconds,
                    "next" to u.upNext?.item?.ratingKey,
                    "span" to listOf(
                        u.entries.size.toLong(),
                        if (u.entries.isNotEmpty()) u.entries.first().startSec else 0L,
                        if (u.entries.isNotEmpty()) u.entries.last().endSec else 0L,
                    ),
                    "shown" to r.nowPlaying?.let { listOf(it.item.ratingKey, it.startSec) },
                    "shownNext" to r.upNext?.item?.ratingKey,
                    "gaveWay" to gaveWay?.item?.ratingKey,
                    "tune" to tune?.let { listOf(it.item.ratingKey, it.seekSeconds) },
                    "early" to adv(t, true),
                    "end" to adv(endOfNow, true),
                    "resync" to adv(endOfNow + 600, false),
                )
            }
            at.add(mapOf("t" to tl, "ch" to byChannel))
        }
        return mapOf("items" to items, "days" to days, "at" to at)
    }

    /** JSON with every object's keys sorted, numbers in one form: what both sides compare. */
    fun canonical(v: Any?): String {
        val sb = StringBuilder()
        fun w(x: Any?) {
            when (x) {
                is Map<*, *> -> {
                    sb.append('{')
                    x.keys.map { it.toString() }.sorted().forEachIndexed { i, k ->
                        if (i > 0) sb.append(',')
                        Json.write(sb, k)
                        sb.append(':')
                        w(x[k])
                    }
                    sb.append('}')
                }
                is List<*> -> {
                    sb.append('[')
                    x.forEachIndexed { i, e -> if (i > 0) sb.append(','); w(e) }
                    sb.append(']')
                }
                is Int -> sb.append(x.toLong())
                is Double -> if (x == Math.floor(x) && Math.abs(x) < 9e15) sb.append(x.toLong()) else sb.append(x)
                else -> Json.write(sb, x)
            }
        }
        w(v)
        return sb.toString()
    }

    fun scheduleParity(): Result {
        val r = Result("schedule parity golden")
        for (c in golden("schedule-parity-golden.json").asObj().objs("cases")) {
            val got = canonical(dumpParity(c.obj("fixture")!!))
            val want = canonical(c["expect"])
            var at = 0
            while (at < got.length && at < want.length && got[at] == want[at]) at++
            r.check("${c.str("name")}: byte for byte what the Apple TV airs", got == want) {
                "first difference at byte $at:\n    port  …${got.substring(maxOf(0, at - 80), minOf(got.length, at + 80))}…\n" +
                    "    apple …${want.substring(maxOf(0, at - 80), minOf(want.length, at + 80))}…"
            }
        }
        return r
    }

    // ── Days the clocks change ──────────────────────────────────────────────

    fun scheduleDst(): Result {
        val r = Result("schedule DST golden")
        val g = golden("schedule-dst-golden.json").asObj()
        fun pool(key: String) = g.obj("pools").arr(key)!!.map {
            val row = it.asArr()!!
            MediaItem(ratingKey = row[0] as String, title = row[1] as String, type = row[2] as String, duration = (row[3] as Number).toInt())
        }
        val small = SchedChannel(201)
        val smallPool = pool("small")
        val big = SchedChannel(203)
        val bigPool = pool("big")
        val days = g.objs("days")
        for (d in days) {
            val zone = ZoneId.of(d.str("zone")!!)
            val m = DailyManifest(MemoryManifestStore()) { zone }
            for (atv in d.arr("at").orEmpty()) {
                val ms = (atv as Number).toLong() * 1000
                val got = listOf(m.localDayKey(ms), m.startOfLocalDayMs(ms) / 1000, m.startOfNextLocalDayMs(ms) / 1000, m.previousLocalDayKey(ms))
                val want = listOf(d.str("date"), d.long("start"), d.long("nextStart"), d.str("previous"))
                r.check("days ${d.str("zone")} ${d.str("date")} at ${atv}", got == want) { "got $got, want $want" }
            }
            val store = MemoryManifestStore()
            val m2 = DailyManifest(store) { zone }
            val at2 = (d.arr("at")!![2] as Number).toLong() * 1000
            val blocks = m2.getManifestBlocks(small, smallPool, at2, "dst")
            val contiguous = blocks.indices.all { i -> i == 0 || blocks[i].startSec == blocks[i - 1].endSec }
            r.check("fill ${d.str("zone")} ${d.str("date")}", blocks.isNotEmpty() && blocks.first().startSec == d.long("start") &&
                blocks.last().endSec == d.long("nextStart") && contiguous) {
                "blocks ${blocks.size}, ${blocks.firstOrNull()?.startSec}..${blocks.lastOrNull()?.endSec}, contiguous $contiguous"
            }
        }
        for (y in g.objs("yesterday")) {
            val zoneName = y.str("zone")!!
            val zone = ZoneId.of(zoneName)
            fun dayOf(date: String?) = days.first { it.str("zone") == zoneName && it.str("date") == date }
            val m = DailyManifest(MemoryManifestStore()) { zone }
            val before = m.getManifestBlocks(big, bigPool, (dayOf(y.str("previous")).arr("at")!![2] as Number).toLong() * 1000, "dst")
            val after = m.getManifestBlocks(big, bigPool, (dayOf(y.str("date")).arr("at")!![2] as Number).toLong() * 1000, "dst")
            val aired = before.map { it.item.ratingKey }.toSet()
            r.check("yesterday $zoneName ${y.str("date")}", after.none { aired.contains(it.item.ratingKey) })
        }
        return r
    }

    // ── Seasons ─────────────────────────────────────────────────────────────

    private fun local(at: String): LocalDateTime {
        val parts = at.split("T")
        val (y, mo, d) = parts[0].split("-").map { it.toInt() }
        val (hh, mm) = (parts.getOrNull(1) ?: "00:00").split(":").map { it.toInt() }
        return LocalDateTime.of(y, mo, d, hh, mm)
    }

    fun season(): Result {
        val r = Result("season golden")
        val g = golden("season-golden.json").asObj()
        val bundles = ChannelConfig.parse(asset("channels.json")).bundles
        fun bundleFor(c: Map<String, Any?>): Bundle {
            val def = c.obj("def")
            if (def != null) {
                val ss = def.obj("seasonStart")
                return Bundle(
                    id = def.str("id") ?: "def",
                    name = def.str("name") ?: "DEF",
                    description = "",
                    channelIDs = listOf(1),
                    activeMonths = def.arr("activeMonths")?.map { (it as Number).toInt() },
                    seasonStart = ss?.let { SeasonStart(it.str("relativeTo") ?: "", it.int("days") ?: 0) },
                )
            }
            return bundles.first { it.id == c.str("bundle") }
        }
        val tis = bundles.first { it.id == "tis-the-season" }
        for (t in g.objs("thanksgiving")) {
            val year = t.int("year")!!
            r.check("thanksgiving $year", Season.usThanksgiving(year).toString() == t.str("thanksgiving"))
            r.check("TIS THE SEASON start $year", Season.seasonStartDate(tis, year)?.toString() == t.str("tisTheSeasonStart"))
        }
        for (c in g.objs("inSeason")) {
            r.check("inSeason: ${c.str("name")}", Season.isInSeason(bundleFor(c), local(c.str("at")!!)) == c.bool("want"))
        }
        for (c in g.objs("offer")) {
            val offer = Season.bundleToOffer(bundles, c.strings("on").toSet(), local(c.str("at")!!)) { false }
            r.check("offer: ${c.str("name")}", offer?.id == c.str("want")) { "got ${offer?.id}" }
        }
        for (c in g.objs("lead")) {
            val want = c.strings("want").flatMap { id -> bundles.first { it.id == id }.channelIDs }
            val got = Season.seasonalLeadIDs(bundles, c.strings("on").toSet(), local(c.str("at")!!))
            r.check("lead: ${c.str("name")}", got == want) { "got $got, want $want" }
        }
        for (c in g.objs("headline")) {
            val got = Season.inviteHeadline(bundleFor(c), local(c.str("at")!!))
            r.check("headline ${c.str("want")}", got == c.str("want")) { "got $got" }
        }
        for (c in g.objs("label")) {
            val got = Season.spotlightLabel(bundleFor(c), local(c.str("at")!!)).uppercase()
            r.check("label ${c.str("want")} at ${c.str("at")}", got == c.str("want")) { "got $got" }
        }
        for (c in g.objs("answers")) {
            val answers = HashMap<String, SeasonalAnswer>()
            val enabled = HashSet<String>()
            for (step in c.objs("steps")) {
                val at = local(step.str("at")!!)
                val silenced = { id: String -> answers[id]?.silences(at.year) == true }
                when {
                    step.containsKey("answer") -> {
                        val id = step.str("bundle")!!
                        when (step.str("answer")) {
                            "yes" -> { answers.remove(id); enabled.add(id) }
                            "never" -> answers[id] = SeasonalAnswer(never = true)
                            else -> answers[id] = SeasonalAnswer(notNowYear = at.year)
                        }
                    }
                    step.containsKey("silenced") -> {
                        r.check("${c.str("name")}: silenced ${step.str("silenced")} at ${step.str("at")}",
                            silenced(step.str("silenced")!!) == step.bool("want"))
                    }
                    step.containsKey("offer") -> {
                        val offer = Season.bundleToOffer(bundles, enabled, at, silenced)
                        r.check("${c.str("name")}: offer at ${step.str("at")}", offer?.id == step.str("offer")) { "got ${offer?.id}" }
                    }
                }
            }
        }
        return r
    }
}
