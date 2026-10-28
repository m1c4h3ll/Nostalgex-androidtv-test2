package app.nostalgex.engine.schedule

import app.nostalgex.engine.filter.JsText
import app.nostalgex.engine.model.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily 24-hour programme manifests and what airs: the running order of each channel's day, the
 * guide's 24-hour window, one title on two channels, what a tune plays and what plays next.
 *
 * A line-for-line port of upstream's `scripts/nostalgex-daily-manifest.js`, which upstream holds
 * bit-for-bit to the Apple TV's `SchedulePoolOrdering.swift`, `DailyManifestScheduler.swift`,
 * `ChannelSchedule.swift` and `AppState.advanceToNextItem` with
 * `scripts/schedule-parity-golden.json` and `scripts/schedule-dst-golden.json`. This port is
 * held to the same files (see the engine tests): same splitmix64 seeding, same xorshift64
 * stream, same Swift-stdlib shuffle, same grouping, premiere slots, sequel grouping and
 * golden-ratio filler offset, the same local days where the clocks change.
 *
 * The order depends on the pool's order, so pools must be in library order as the server lists
 * them, with minutes truncated.
 *
 * Not ported: the manifest cache's medium. The JS keeps it in localStorage; here it is a
 * [ManifestStore] (memory in tests, a file in the app), keyed and validated the same way.
 */
class DailyManifest(
    private val store: ManifestStore,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val zone: ZoneId get() = zoneProvider()

    // ── Local days ─────────────────────────────────────────────────────────

    /** `d.setHours(0, 0, 0, 0)`: local midnight, or the first instant after it where midnight is skipped. */
    fun startOfLocalDayMs(ms: Long): Long {
        val z = zone
        val date = Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
        return date.atStartOfDay(z).toInstant().toEpochMilli()
    }

    /** The start of the local day after the one containing `ms` (36 hours on, then its start). */
    fun startOfNextLocalDayMs(ms: Long): Long = startOfLocalDayMs(startOfLocalDayMs(ms) + 36 * 3_600_000L)

    fun localDayKey(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return "%d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /** The key of the day before: 12 hours before this day's start. */
    fun previousLocalDayKey(ms: Long): String = localDayKey(startOfLocalDayMs(ms) - 12 * 3_600_000L)

    fun localDayLengthSec(sec: Long): Long {
        val start = startOfLocalDayMs(sec * 1000)
        return (startOfNextLocalDayMs(start) - start) / 1000
    }

    fun localDayNumber(ms: Long): Long = Math.floorDiv(startOfLocalDayMs(ms), 86_400_000L)

    // ── Store ──────────────────────────────────────────────────────────────

    private fun storageKey(serverKey: String, channelId: Int, dayKey: String) =
        "$MANIFEST_PREFIX${serverKey}_${channelId}_$dayKey"

    private fun loadManifest(serverKey: String, channelId: Int, dayKey: String, poolFP: String): StoredManifest? {
        val m = store.get(storageKey(serverKey, channelId, dayKey)) ?: return null
        if (m.schemaVersion != SCHEMA_VERSION || m.poolFingerprint != poolFP) return null
        return m
    }

    private fun airedRatingKeys(serverKey: String, channelId: Int, dayKey: String): Set<String> =
        store.get(storageKey(serverKey, channelId, dayKey))?.blocks?.mapTo(HashSet()) { it.ratingKey } ?: emptySet()

    private fun lastAiredRatingKey(serverKey: String, channelId: Int, dayKey: String): String? =
        store.get(storageKey(serverKey, channelId, dayKey))?.blocks?.lastOrNull()?.ratingKey

    fun clearManifests(serverKey: String) = store.removePrefix("$MANIFEST_PREFIX${serverKey}_")

    // ── The day's running order ────────────────────────────────────────────

    fun packDay(
        pool: List<MediaItem>,
        dayStartSec: Long,
        dayNumber: Long,
        channelId: Int,
        yesterdayAired: Set<String>,
        isPremiereChannel: Boolean,
        yesterdayLastRatingKey: String?,
    ): List<Block> {
        val dayEndSec = dayStartSec + localDayLengthSec(dayStartSec)
        val totalPoolSec = pool.sumOf { it.duration.toLong() * 60 }
        if (totalPoolSec <= 0) return emptyList()

        val unplayed = pool.filter { !yesterdayAired.contains(it.ratingKey) }
        val interleaved = interleaveByShow(unplayed, channelId, dayNumber)
        val promoted = if (isPremiereChannel) promoteRecentlyAdded(interleaved, dayStartSec, channelId) else interleaved
        val unplayedOrdered = groupSequelParts(promoted).toMutableList()

        // Cross-day seam: never lead off with the title that ended just before midnight.
        if (yesterdayLastRatingKey != null && unplayedOrdered.size > 1 &&
            unplayedOrdered[0].ratingKey == yesterdayLastRatingKey
        ) {
            unplayedOrdered.add(unplayedOrdered.removeAt(0))
        }
        val blocks = ArrayList<Block>()
        var t = dayStartSec

        for (item in unplayedOrdered) {
            if (t >= dayEndSec) break
            val dur = item.duration.toLong() * 60
            if (dur <= 0) continue
            blocks.add(Block(item.ratingKey, t, t + dur))
            t += dur
        }

        if (t < dayEndSec) {
            // Filler loop: sequels only, no premiere promotion.
            val orderedFull = groupSequelParts(interleaveByShow(pool, channelId, dayNumber + 1))
            val goldenShift = Math.floor(totalPoolSec.toDouble() * 0.6180339887).toLong()
            val offsetSec = (dayNumber * goldenShift) % totalPoolSec

            var elapsed = 0L
            var cycleIndex = 0
            var offsetInItem = offsetSec
            for (i in orderedFull.indices) {
                val d = orderedFull[i].duration.toLong() * 60
                if (elapsed + d > offsetSec) {
                    cycleIndex = i
                    offsetInItem = offsetSec - elapsed
                    break
                }
                elapsed += d
            }

            // Wraparound seam: the cycle can land on the very item that just played.
            if (orderedFull.size > 1) {
                val lastPlayed = if (blocks.isNotEmpty()) blocks.last().ratingKey else yesterdayLastRatingKey
                if (lastPlayed != null && orderedFull[cycleIndex].ratingKey == lastPlayed) {
                    cycleIndex = (cycleIndex + 1) % orderedFull.size
                    offsetInItem = 0
                }
            }

            var idx = cycleIndex
            while (t < dayEndSec) {
                val item = orderedFull[idx % orderedFull.size]
                val fullDur = item.duration.toLong() * 60
                if (fullDur <= 0) {
                    idx++
                    offsetInItem = 0
                    continue
                }
                val remainingInItem = fullDur - offsetInItem
                val remainingDay = dayEndSec - t
                val playSec = minOf(remainingInItem, remainingDay)
                if (playSec <= 0) {
                    idx++
                    offsetInItem = 0
                    continue
                }
                blocks.add(Block(item.ratingKey, t, t + playSec))
                t += playSec
                if (offsetInItem + playSec >= fullDur) {
                    idx++
                    offsetInItem = 0
                } else {
                    offsetInItem += playSec
                }
            }
        }
        return blocks
    }

    private fun resolveBlocks(stored: List<Block>, pool: List<MediaItem>): List<ResolvedBlock> {
        val lookup = HashMap<String, MediaItem>(pool.size * 2)
        for (i in pool) lookup[i.ratingKey] = i
        return stored.mapNotNull { b -> lookup[b.ratingKey]?.let { ResolvedBlock(it, b.startSec, b.endSec) } }
    }

    /** One channel's blocks for the local day containing `atMs`, packed and stored on first ask. */
    fun getManifestBlocks(channel: SchedChannel, pool: List<MediaItem>, atMs: Long, serverKey: String): List<ResolvedBlock> {
        val validPool = pool.filter { it.duration > 0 }
        if (validPool.isEmpty()) return emptyList()

        val dayStartSec = Math.floorDiv(startOfLocalDayMs(atMs), 1000L)
        val dayKey = localDayKey(atMs)
        val poolFP = poolFingerprint(validPool)
        val channelId = channel.id

        loadManifest(serverKey, channelId, dayKey, poolFP)?.let { return resolveBlocks(it.blocks, validPool) }

        val dayNumber = localDayNumber(atMs)
        val yesterdayKey = previousLocalDayKey(atMs)
        val yesterdayAired = airedRatingKeys(serverKey, channelId, yesterdayKey)
        val yesterdayLast = lastAiredRatingKey(serverKey, channelId, yesterdayKey)
        val packed = packDay(validPool, dayStartSec, dayNumber, channelId, yesterdayAired, channel.isPremiereChannel, yesterdayLast)
        store.put(
            storageKey(serverKey, channelId, dayKey),
            StoredManifest(SCHEMA_VERSION, channelId, dayKey, poolFP, packed),
        )
        return resolveBlocks(packed, validPool)
    }

    // ── What airs ──────────────────────────────────────────────────────────

    /** Every block of each local day from `fromSec`'s to `toSec`'s that overlaps [fromSec, toSec), in start order. */
    fun blocksInRange(channel: SchedChannel, pool: List<MediaItem>, fromSec: Long, toSec: Long, serverKey: String): List<ResolvedBlock> {
        val result = ArrayList<ResolvedBlock>()
        var cursor = startOfLocalDayMs(fromSec * 1000)
        val endDay = startOfLocalDayMs(toSec * 1000)
        val z = zone
        while (cursor <= endDay) {
            for (b in getManifestBlocks(channel, pool, cursor + 3_600_000L, serverKey)) {
                if (b.endSec > fromSec && b.startSec < toSec) result.add(b)
            }
            // The same clock time a day on.
            cursor = ZonedDateTime.ofInstant(Instant.ofEpochMilli(cursor), z).plusDays(1).toInstant().toEpochMilli()
        }
        return result.sortedBy { it.startSec }
    }

    /**
     * The guide's view of a channel at `nowSec`: a 24-hour window from the current half hour, what
     * is on now and next and how far in. Mirrors ChannelScheduleBuilder.buildSchedule.
     */
    fun buildSchedule(channel: SchedChannel, pool: List<MediaItem>, nowSec: Double, serverKey: String): ChannelSchedule? {
        val nowUnix = Math.floor(nowSec).toLong()
        val windowStart = Math.floorDiv(nowUnix, 1800L) * 1800L
        val windowEnd = windowStart + 86400
        val dayBlocks = blocksInRange(channel, pool, windowStart, windowEnd, serverKey)
        if (dayBlocks.isEmpty()) return null
        val entries = ArrayList<ScheduleEntry>()
        var nowPlaying: ScheduleEntry? = null
        for (b in dayBlocks) {
            if (!(b.endSec > windowStart && b.startSec < windowEnd)) continue
            val isNow = b.startSec <= nowSec && b.endSec > nowSec
            val entry = ScheduleEntry("${b.item.ratingKey}_${b.startSec}", b.item, b.startSec, b.endSec, isNow, false)
            entries.add(entry)
            if (isNow) nowPlaying = entry
        }
        if (nowPlaying == null) nowPlaying = entries.firstOrNull { it.startSec <= nowSec && it.endSec > nowSec }
        val nowIdx = entries.indexOfFirst { it.isNowPlaying }
        val upNext = if (nowIdx >= 0 && nowIdx + 1 < entries.size) entries[nowIdx + 1] else null
        var progress = 0.0
        var elapsedSeconds = 0L
        if (nowPlaying != null) {
            val elapsed = nowSec - nowPlaying.startSec
            val dur = (nowPlaying.endSec - nowPlaying.startSec).toDouble()
            progress = if (dur > 0) elapsed / dur else 0.0
            elapsedSeconds = maxOf(0L, truncToLong(elapsed))
        }
        return ChannelSchedule(entries, nowPlaying, upNext, progress, elapsedSeconds)
    }

    /** ChannelSchedule.isCurrent. */
    fun scheduleIsCurrent(schedule: ChannelSchedule?, nowSec: Double): Boolean {
        val np = schedule?.nowPlaying ?: return false
        return np.startSec <= nowSec && nowSec < np.endSec
    }

    /** Seconds into what is on now, from the clock, capped at the programme's length. */
    fun liveElapsedSeconds(schedule: ChannelSchedule?, nowSec: Double): Long? {
        val np = schedule?.nowPlaying ?: return null
        val total = maxOf(1L, np.item.duration.toLong() * 60)
        return maxOf(0L, minOf(total, truncToLong(nowSec - np.startSec)))
    }

    /** What tuning to a channel plays, and from where. Mirrors AppState.selectChannel. */
    fun tuneIn(channel: SchedChannel, pool: List<MediaItem>, precomputed: ChannelSchedule?, nowSec: Double, serverKey: String): Pick? {
        val usable = if (precomputed != null && scheduleIsCurrent(precomputed, nowSec)) precomputed else null
        val schedule = usable ?: buildSchedule(channel, pool, nowSec, serverKey)
        val np = schedule?.nowPlaying ?: return null
        val live = liveElapsedSeconds(schedule, nowSec)
        return Pick(np.item, live ?: schedule.elapsedSeconds)
    }

    /**
     * One title on two channels at once: the lowest channel id keeps it, every other channel shows
     * its next programme as now playing. One pass. Mirrors ChannelScheduleBuilder.resolveConflicts.
     */
    fun resolveConflicts(schedules: LinkedHashMap<Int, ChannelSchedule>): LinkedHashMap<Int, ChannelSchedule> {
        val byItem = LinkedHashMap<String, MutableList<Int>>()
        for ((channelId, schedule) in schedules) {
            val np = schedule.nowPlaying ?: continue
            byItem.getOrPut(np.item.ratingKey) { ArrayList() }.add(channelId)
        }
        for (ids in byItem.values) {
            if (ids.size < 2) continue
            val losers = ids.sorted().drop(1)
            for (id in losers) {
                val old = schedules.getValue(id)
                val entries = old.entries.toMutableList()
                val i = entries.indexOfFirst { it.isNowPlaying }
                if (i >= 0) entries[i] = entries[i].copy(isNowPlaying = false, isConflict = true)
                val oldNext = old.upNext
                if (oldNext != null) {
                    val nowPlaying = oldNext.copy(isNowPlaying = true, isConflict = false)
                    val j = entries.indexOfFirst { it.id == oldNext.id }
                    val upNext = if (j >= 0 && j + 1 < entries.size) entries[j + 1] else null
                    schedules[id] = ChannelSchedule(entries, nowPlaying, upNext, 0.0, 0)
                } else {
                    schedules[id] = old.copy(entries = entries)
                }
            }
        }
        return schedules
    }

    /** The first other channel whose programme now is `item`. Mirrors ChannelScheduleBuilder.findConflict. */
    fun findConflict(item: MediaItem, excludingChannelId: Int, channels: List<LineupEntry>, nowSec: Double, serverKey: String): Int? {
        for ((channel, pool) in channels) {
            if (channel.id == excludingChannelId) continue
            val s = buildSchedule(channel, pool, nowSec, serverKey)
            if (s?.nowPlaying != null && s.nowPlaying.item.ratingKey == item.ratingKey) return channel.id
        }
        return null
    }

    /**
     * What a channel plays next when its programme ends. Mirrors AppState.advanceToNextItem: the
     * schedule's programme if it has moved on, otherwise the next one; never the title another
     * channel has on right now; from 0 on a natural end.
     */
    fun advancePick(
        channel: SchedChannel,
        pool: List<MediaItem>,
        prevRatingKey: String?,
        nowSec: Double,
        channels: List<LineupEntry>,
        serverKey: String,
        fromNaturalEnd: Boolean = true,
    ): Pick? {
        val schedule = buildSchedule(channel, pool, nowSec, serverKey) ?: return null
        val moved = schedule.nowPlaying?.item?.ratingKey != prevRatingKey
        var pick: MediaItem? = if (moved) schedule.nowPlaying?.item
        else schedule.upNext?.item ?: schedule.nowPlaying?.item
        var seekSeconds = if (moved && pick != null && !fromNaturalEnd) schedule.elapsedSeconds else 0L
        if (pick != null && findConflict(pick, channel.id, channels, nowSec, serverKey) != null) {
            val key = pick.ratingKey
            val idx = schedule.entries.indexOfFirst { it.item.ratingKey == key }
            if (idx >= 0 && idx + 1 < schedule.entries.size) {
                pick = schedule.entries[idx + 1].item
                seekSeconds = 0
            }
        }
        return pick?.let { Pick(it, seekSeconds) }
    }

    companion object {
        const val MANIFEST_PREFIX = "plex90_manifest_"
        const val SCHEMA_VERSION = 2

        private const val PREMIERE_WINDOW_SECONDS = 7L * 86400
        private const val PREMIERE_ANCHOR_SECONDS = 19L * 3600
        private const val PREMIERE_SPREAD_SECONDS = 5L * 3600
        private const val PREMIERE_SLOT_SECONDS = 15L * 60

        private fun truncToLong(x: Double): Long = if (x < 0) Math.ceil(x).toLong() else Math.floor(x).toLong()

        // ── Swift-identical RNG ───────────────────────────────────────────

        fun splitmix64(x: ULong): ULong {
            var z = x + 0x9E3779B97F4A7C15uL
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }

        /** High 64 bits of the 128-bit product. */
        fun umulHigh(a: ULong, b: ULong): ULong {
            val mask = 0xFFFFFFFFuL
            val aLo = a and mask
            val aHi = a shr 32
            val bLo = b and mask
            val bHi = b shr 32
            val loLo = aLo * bLo
            val hiLo = aHi * bLo
            val loHi = aLo * bHi
            val hiHi = aHi * bHi
            val cross = (loLo shr 32) + (hiLo and mask) + loHi
            return hiHi + (hiLo shr 32) + (cross shr 32)
        }

        /** Swift `SeededRNG`: splitmix64-seeded xorshift64, with the stdlib's bounded draw. */
        class SeededRNG(seed: ULong) {
            private var state: ULong = splitmix64(seed).let { if (it == 0uL) 1uL else it }

            fun next(): ULong {
                state = state xor (state shl 13)
                state = state xor (state shr 7)
                state = state xor (state shl 17)
                return state
            }

            /** `RandomNumberGenerator.next(upperBound:)`: Lemire nearly-divisionless. */
            fun boundedNext(upperBound: Int): Int {
                val u = upperBound.toULong()
                var r = next()
                var low = r * u
                if (low < u) {
                    val t = (0uL - u) % u
                    while (low < t) {
                        r = next()
                        low = r * u
                    }
                }
                return umulHigh(r, u).toInt()
            }
        }

        /** Swift `MutableCollection.shuffle(using:)`, in place. */
        fun <T> swiftShuffle(arr: MutableList<T>, rng: SeededRNG) {
            var amount = arr.size
            var currentIndex = 0
            while (amount > 1) {
                val random = rng.boundedNext(amount)
                amount -= 1
                val j = currentIndex + random
                val tmp = arr[currentIndex]
                arr[currentIndex] = arr[j]
                arr[j] = tmp
                currentIndex += 1
            }
        }

        fun interleaveByShow(items: List<MediaItem>, seed: Int, dayNumber: Long): List<MediaItem> {
            if (items.size <= 1) return items.toList()
            val groups = LinkedHashMap<String, MutableList<MediaItem>>()
            for (item in items) {
                // Episodes group on the show title (an episode's `title`); every film is its own.
                val key = if (item.type == "episode") item.title else "movie_${item.ratingKey}"
                groups.getOrPut(key) { ArrayList() }.add(item)
            }
            val rng = SeededRNG((Math.abs(seed.toLong()) + 1).toULong() + dayNumber.toULong())
            for (list in groups.values) swiftShuffle(list, rng)
            val orderedGroups: MutableList<MutableList<MediaItem>> = groups.values.toMutableList()
            swiftShuffle(orderedGroups, rng)

            val maxConsecutive = 2
            val result = ArrayList<MediaItem>(items.size)
            val positions = IntArray(orderedGroups.size)
            var groupIndex = 0
            var exhausted = 0
            while (exhausted < orderedGroups.size) {
                var attempts = 0
                while (positions[groupIndex] >= orderedGroups[groupIndex].size) {
                    groupIndex = (groupIndex + 1) % orderedGroups.size
                    attempts++
                    if (attempts > orderedGroups.size) break
                }
                if (attempts > orderedGroups.size) break
                val group = orderedGroups[groupIndex]
                val pos = positions[groupIndex]
                val take = minOf(maxConsecutive, group.size - pos)
                for (i in 0 until take) result.add(group[pos + i])
                positions[groupIndex] = pos + take
                if (positions[groupIndex] >= group.size) exhausted++
                groupIndex = (groupIndex + 1) % orderedGroups.size
            }
            return result
        }

        /** SchedulePoolOrdering.premiereOffset: FNV-1a over the ratingKey, murmur3 finalizer. */
        fun premiereOffset(channelId: Int, ratingKey: String): Long {
            val slots = maxOf(1L, PREMIERE_SPREAD_SECONDS / PREMIERE_SLOT_SECONDS).toULong()
            var x = channelId.toLong().toULong() + 0x9E3779B97F4A7C15uL
            for (byte in ratingKey.toByteArray(Charsets.UTF_8)) {
                x = (x xor (byte.toInt() and 0xFF).toULong()) * 0x00000100000001B3uL
            }
            x = x xor (x shr 33)
            x *= 0xFF51AFD7ED558CCDuL
            x = x xor (x shr 33)
            return PREMIERE_ANCHOR_SECONDS + (x % slots).toLong() * PREMIERE_SLOT_SECONDS
        }

        fun promoteRecentlyAdded(items: List<MediaItem>, dayStartUnix: Long, channelId: Int, windowSeconds: Long? = null): List<MediaItem> {
            if (items.size <= 1) return items
            val window = windowSeconds ?: PREMIERE_WINDOW_SECONDS
            val cutoff = dayStartUnix - window
            fun isRecent(i: MediaItem) = i.addedAt > 0 && i.addedAt >= cutoff
            val recent = items.filter(::isRecent)
            if (recent.isEmpty() || recent.size >= items.size) return items
            val rest = items.filter { !isRecent(it) }

            val targets = recent.map { it to premiereOffset(channelId, it.ratingKey) }
                .sortedWith { a, b ->
                    if (a.second != b.second) a.second.compareTo(b.second) else a.first.ratingKey.compareTo(b.first.ratingKey)
                }

            val out = rest.toMutableList()
            for ((item, offset) in targets) {
                var insertAt = out.size
                var cumulativeSeconds = 0L
                for (index in out.indices) {
                    if (cumulativeSeconds >= offset) { insertAt = index; break }
                    cumulativeSeconds += out[index].duration.toLong() * 60
                }
                out.add(insertAt, item)
            }
            return out
        }

        private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

        /** `/^(.*?)[\s:,-]+(?:part|pt\.?)\s*([0-9]{1,2})\s*$/i`, JS classes written out. */
        private val SEQUEL_MARKER = Regex(
            "^([^\\n\\r\\u2028\\u2029]*?)[$WS:,-]+(?:part|pt\\.?)[$WS]*([0-9]{1,2})[$WS]*\\z",
            RegexOption.IGNORE_CASE,
        )

        fun groupSequelParts(items: List<MediaItem>): List<MediaItem> {
            if (items.size <= 1) return items
            data class Mark(val index: Int, val base: String, val ordinal: Int)
            val marked = ArrayList<Mark>()
            for (index in items.indices) {
                val it = items[index]
                if (it.type != "movie") continue
                val m = SEQUEL_MARKER.find(it.title) ?: continue
                val ordinal = m.groupValues[2].toIntOrNull() ?: continue
                val base = JsText.trim(JsText.lower(m.groupValues[1]))
                if (base.isEmpty()) continue
                marked.add(Mark(index, base, ordinal))
            }
            val groups = LinkedHashMap<String, MutableList<Mark>>()
            for (mark in marked) groups.getOrPut(mark.base) { ArrayList() }.add(mark)
            val families = groups.values.filter { it.size > 1 }
            if (families.isEmpty()) return items

            val pulled = HashSet<Int>()
            val insertions = HashMap<Int, List<MediaItem>>()
            for (family in families) {
                val anchor = family.minOf { it.index }
                val ordered = family.sortedBy { it.ordinal }.map { items[it.index] }
                for (member in family) pulled.add(member.index)
                insertions[anchor] = ordered
            }
            val result = ArrayList<MediaItem>(items.size)
            for (index in items.indices) {
                val ins = insertions[index]
                if (ins != null) result.addAll(ins) else if (!pulled.contains(index)) result.add(items[index])
            }
            return result
        }

        fun poolFingerprint(pool: List<MediaItem>): String {
            val keys = pool.map { it.ratingKey }.sorted().joinToString(",")
            var h = 0
            for (c in keys) h = (h shl 5) - h + c.code
            return h.toString()
        }

        /** Minutes from a Plex duration in ms: integer division, truncated. */
        fun minutesFromPlexMs(ms: Long?): Int = if (ms != null && ms > 0) (ms / 60000).toInt() else 0

        /** Minutes from Jellyfin or Emby RunTimeTicks (100 ns). */
        fun minutesFromTicks(ticks: Long?): Int = if (ticks != null && ticks > 0) (ticks / 600_000_000L).toInt() else 0

        /** Unix seconds from a Jellyfin or Emby DateCreated; 0 when it doesn't parse. */
        fun unixSecondsFromISO(iso: String?): Long {
            if (iso.isNullOrEmpty()) return 0
            return try {
                Math.floorDiv(java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli(), 1000L)
            } catch (_: Exception) {
                try {
                    // Jellyfin writes up to seven fractional digits and a trailing Z.
                    Math.floorDiv(Instant.parse(iso).toEpochMilli(), 1000L)
                } catch (_: Exception) {
                    0
                }
            }
        }
    }
}

data class SchedChannel(val id: Int, val isPremiereChannel: Boolean = false)

data class LineupEntry(val channel: SchedChannel, val pool: List<MediaItem>)

data class Block(val ratingKey: String, val startSec: Long, val endSec: Long)

data class ResolvedBlock(val item: MediaItem, val startSec: Long, val endSec: Long)

data class StoredManifest(
    val schemaVersion: Int,
    val channelId: Int,
    val dayKey: String,
    val poolFingerprint: String,
    val blocks: List<Block>,
)

data class ScheduleEntry(
    val id: String,
    val item: MediaItem,
    val startSec: Long,
    val endSec: Long,
    val isNowPlaying: Boolean,
    val isConflict: Boolean,
)

data class ChannelSchedule(
    val entries: List<ScheduleEntry>,
    val nowPlaying: ScheduleEntry?,
    val upNext: ScheduleEntry?,
    val progress: Double,
    val elapsedSeconds: Long,
)

data class Pick(val item: MediaItem, val seekSeconds: Long)

/** Where day manifests are kept. Same keys as the JS localStorage. */
interface ManifestStore {
    fun get(key: String): StoredManifest?
    fun put(key: String, manifest: StoredManifest)
    fun removePrefix(prefix: String)
}

class MemoryManifestStore : ManifestStore {
    private val map = LinkedHashMap<String, StoredManifest>()

    @Synchronized override fun get(key: String) = map[key]

    @Synchronized override fun put(key: String, manifest: StoredManifest) {
        map[key] = manifest
    }

    @Synchronized override fun removePrefix(prefix: String) {
        map.keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized fun clear() = map.clear()

    @Synchronized fun snapshot(): Map<String, StoredManifest> = LinkedHashMap(map)

    @Synchronized fun putAll(entries: Map<String, StoredManifest>) = map.putAll(entries)
}
