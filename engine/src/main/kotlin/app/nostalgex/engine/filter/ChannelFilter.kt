package app.nostalgex.engine.filter

import app.nostalgex.engine.model.Channel
import app.nostalgex.engine.model.ChannelRules
import app.nostalgex.engine.model.ExclusiveRule
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.model.MembershipManifest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The channel pool filter: which library items a channel may air.
 *
 * A line-for-line port of upstream's `scripts/nostalgex-channel-filter.cjs` (`itemPasses`,
 * `filterPool`, `usContentRating`), which upstream holds identical to the Apple TV's
 * `AppState+Channels.swift` `filterItems` with `scripts/filter-golden.json`. This port is held
 * to the same golden file (see the engine tests). The order of the checks is the JS order and
 * must stay so; several of them return early.
 *
 * Two changes that don't affect results, as in the Roku port: the clock is an argument
 * ([FilterClock]), and rule lists and item fields are lowercased once ([PreparedChannel],
 * [PreparedItem]) rather than on every call.
 */
class ChannelFilter(
    private val manifest: MembershipManifest?,
    exclusiveRules: List<ExclusiveRule>,
    /** "plex", "jellyfin" or "emby". */
    private val backend: String,
    private val clock: FilterClock = FilterClock.system(),
) {
    private val exclusive = exclusiveRules.map(::PreparedExclusiveRule)

    /** Upstream `filterPool(...).items`: the items `channel` may air, in library order. */
    fun filterPool(items: List<PreparedItem>, channel: Channel): List<MediaItem> {
        val pc = PreparedChannel(channel)
        val nowSec = clock.nowSec()
        val year = clock.currentYear()
        val out = ArrayList<MediaItem>()
        for (it in items) if (passes(it, pc, nowSec, year) != null) out.add(it.item)
        return out
    }

    fun passes(item: MediaItem, channel: Channel): String? = passes(PreparedItem(item), PreparedChannel(channel))

    /**
     * Upstream `itemPasses`: `null` where the JS returns false, otherwise how it passed
     * (`rules`, `manifest`, `editorial`, `episode`, `title`, `keyword`, `network`, `prodco`).
     */
    fun passes(item: PreparedItem, ch: PreparedChannel): String? =
        passes(item, ch, clock.nowSec(), clock.currentYear())

    private fun passes(item: PreparedItem, ch: PreparedChannel, nowSec: Double, currentYear: Int): String? {
        val it = item.item
        // Emby Dolby Vision profile 5: on no channel, whatever the rules say.
        if (isLeftOffChannels(it, backend)) return null
        val r = ch.rules
        val titleLower = item.titleLower
        val plexGenres = item.plexGenres
        val itemGenres = if (r.matchTmdbGenres) item.genresWithTmdb else plexGenres

        val isEpisodeCurated = ch.isEpisodeCurated
        val matchesEpisodeRules = isEpisodeCurated && episodeRulesMatch(item, ch)

        val matchesTitleRule = if (ch.titleContains.isEmpty()) true
        else ch.titleContains.any { JsText.containsLoweredWord(titleLower, it) }

        val itemSource = it.librarySource.ifEmpty { "movie" }
        if (r.source != null) {
            if (itemSource != r.source) return null
        } else if (itemSource == "musicVideo") {
            return null
        }

        val isKids = ch.category == "kids"
        val hasFamilyGenre = ch.genresIncludeLower.any { g -> g == "family" }
        val isFamilySafe = isKids || hasFamilyGenre
        if (isFamilySafe && !it.contentRating.isNullOrEmpty() && ADULT_RATINGS.contains(item.usRating)) return null

        val hasGenreInclude = r.genresInclude.isNotEmpty()
        val hasStudioRule = r.studios.isNotEmpty()
        val isStudioChannel = hasStudioRule && !hasGenreInclude

        val hasTitleCurated = r.titleContains.isNotEmpty() || isEpisodeCurated
        val allowsAnimation = isStudioChannel || ch.includesGenre(ANIMATION) || isKids || hasTitleCurated
        val allowsHorror = isStudioChannel || ch.includesGenre(HORROR)
        val allowsReality = isStudioChannel || ch.includesGenre(REALITY)
        val allowsDocumentary = isStudioChannel || ch.includesGenre(DOCUMENTARY)
        val allowsWar = isStudioChannel || ch.includesGenre(WAR)
        val allowsWestern = isStudioChannel || ch.includesGenre(WESTERN)
        val allowsTalkShow = isStudioChannel || ch.includesGenre(TALKSHOW)

        if (itemGenres.any { HORROR.contains(it) } && !allowsHorror) return null
        if (itemGenres.any { REALITY.contains(it) } && !allowsReality) return null
        if (itemGenres.any { ANIMATION.contains(it) } && !allowsAnimation) return null
        if (itemGenres.any { DOCUMENTARY.contains(it) } && !allowsDocumentary) return null
        if (itemGenres.any { WAR.contains(it) } && !allowsWar) return null
        if (itemGenres.any { WESTERN.contains(it) } && !allowsWestern) return null
        if (itemGenres.any { TALKSHOW.contains(it) } && !allowsTalkShow) return null

        val isExplicitReality = ch.includesGenre(REALITY)
        if (isExplicitReality && !itemGenres.any { REALITY.contains(it) }) return null

        if (ch.editorialOverridesLower.isNotEmpty()) {
            if (ch.editorialOverridesLower.any { t -> titleLower == t }) return "editorial"
        }

        // Manifest-level exclusivity.
        val manifestKey = item.manifestKey
        if (manifest != null && manifestKey != null) {
            val entry = manifest[manifestKey]
            if (entry?.exclusive != null && entry.exclusive != ch.id) return null
        }

        if (manifest != null && manifestClaims(item, ch.id)) {
            if (r.type != null && it.type != r.type) return null
            if (!passesYearRange(it, r)) return null
            if (r.titleContains.isNotEmpty() && !matchesTitleRule) return null
            if (isEpisodeCurated && !matchesEpisodeRules) return null
            if (ch.titleExcludes.isNotEmpty() && ch.titleExcludes.any { JsText.containsLoweredWord(titleLower, it) }) return null
            if (ch.genresExcludeLower.isNotEmpty()) {
                val blocked = ch.genresExcludeLower.any { e -> itemGenres.any { g -> g.contains(e) } }
                if (blocked) return null
            }
            return "manifest"
        }

        if (r.manifestOnly) return null

        if (ch.titleExcludes.isNotEmpty()) {
            if (ch.titleExcludes.any { JsText.containsLoweredWord(titleLower, it) }) return null
        }

        for (rule in exclusive) {
            val ids = rule.rule.channelIDs
            if (ids.contains(ch.id)) continue
            if (!rule.applies(it)) continue
            // Server genres only, as Swift's ExclusiveRule.matches reads item.genres.
            if (rule.matches(item, plexGenres)) return null
            if (rule.rule.manifestExclusive && manifest != null && ids.any { id -> manifestClaims(item, id) }) return null
        }

        if (r.type != null && it.type != r.type) return null

        if (!passesYearRange(it, r)) return null

        if (r.contentRatings.isNotEmpty()) {
            val rating = item.usRating.let { v -> if (v.isNullOrEmpty()) "" else v }
            if (rating == "" && r.allowUnrated) {
                // pass
            } else if (!r.contentRatings.contains(rating)) {
                return null
            }
        }

        if (r.hasDurationRange) {
            val min = r.durationMin
            val max = r.durationMax
            if (min != null && it.duration < min) return null
            if (max != null && it.duration > max) return null
        }

        val itemYear = parseYear(it) ?: 0
        val isNewRelease = itemYear >= currentYear - 1
        if (r.watchedOnly && it.viewCount < 1 && !isNewRelease) return null
        if (r.unwatchedOnly && (it.viewCount > 0 || isNewRelease)) return null
        if (r.rewatched && it.viewCount < 3) return null

        if (isEpisodeCurated && !matchesEpisodeRules) return null

        val titleContains = matchesTitleRule
        val itemTmdbKeywords = item.keywords

        if (ch.genresExcludeLower.isNotEmpty()) {
            val blocked = ch.genresExcludeLower.any { e -> itemGenres.any { g -> g.contains(e) } }
            if (blocked) return null
        }

        // Soft exclude: unenriched items pass. Exact equality, as Swift.
        if (ch.keywordsExcludeLower.isNotEmpty() && itemTmdbKeywords.isNotEmpty()) {
            if (ch.keywordsExcludeLower.any { kw -> itemTmdbKeywords.contains(kw) }) return null
        }

        // OMDb gates: HARD. A missing value rejects, as Swift's `guard let` does.
        val e = it.enrichment
        r.imdbRatingMin?.let { min -> val v = e?.imdbRating; if (v == null || v < min) return null }
        r.imdbVotesMin?.let { min -> val v = e?.imdbVotes; if (v == null || v < min) return null }
        r.rtScoreMin?.let { min -> val v = e?.rottenTomatoesScore; if (v == null || v < min) return null }
        r.metacriticMin?.let { min -> val v = e?.metacriticScore; if (v == null || v < min) return null }
        if (r.wonOscar) {
            val a = e?.awards?.let(JsText::lower) ?: return null
            if (!(a.contains("won") && a.contains("oscar"))) return null
        }

        r.addedWithinDays?.let { days ->
            if (days > 0) {
                val cutoff = nowSec - days * 86400
                if (it.addedAt < cutoff) return null
            }
        }

        r.releasedWithinMonths?.let { months ->
            if (months > 0) {
                val d = JsText.leadingDate(it.originallyAvailableAt)
                if (d != null) {
                    if (clock.releasedBeforeMonthsAgo(d.first, d.second, d.third, months)) return null
                } else {
                    val year = parseYear(it)
                    if (year != null) {
                        val yearsBack = Math.floor((months + 11) / 12).toInt() - 1
                        if (year < currentYear - yearsBack) return null
                    }
                }
            }
        }

        r.ratingMin?.let { min ->
            if (it.rating < min && it.userRating < min) return null
        }

        var matchesGenre = false
        if (ch.genresIncludeLower.isNotEmpty()) {
            matchesGenre = itemGenres.any { g -> ch.genresIncludeLower.any { m -> g.contains(m) } }
        }
        if (ch.genresRequireAllLower.isNotEmpty()) {
            val allHit = ch.genresRequireAllLower.all { m -> itemGenres.any { g -> g.contains(m) } }
            if (!allHit) return null
        }

        var matchesStudio = false
        if (hasStudioRule) {
            val lowerStudios = ch.studiosLower
            val itemStudio = item.studioLower
            matchesStudio = (itemStudio.isNotEmpty() && lowerStudios.any { s -> itemStudio.contains(s) }) ||
                item.productionCompanies.any { pc -> lowerStudios.any { s -> pc.contains(s) } } ||
                item.networks.any { n -> lowerStudios.any { s -> n.contains(s) } }
        }

        val hasProdCoRule = r.productionCompanies.isNotEmpty()
        val prodCoHaystack = item.prodCoHaystack
        var matchesProdCo = false
        if (hasProdCoRule && prodCoHaystack.isNotEmpty()) {
            matchesProdCo = ch.productionCompaniesLower.any { c -> prodCoHaystack.any { pc -> pc.contains(c) } }
        }

        val hasTitleRule = r.titleContains.isNotEmpty()
        val hasKeywordRule = r.keywords.isNotEmpty()
        val hasNetworkRule = r.networks.isNotEmpty()
        val isTitleCuratedChannel =
            hasTitleRule && !hasKeywordRule && !hasNetworkRule && !hasProdCoRule && !hasStudioRule

        if (isEpisodeCurated) return "episode"

        if (isTitleCuratedChannel) {
            if (!titleContains) return null
            return "rules"
        }

        var matchesKeyword = false
        if (hasKeywordRule && itemTmdbKeywords.isNotEmpty()) {
            val kwHit = ch.keywordsLower.any { kw -> itemTmdbKeywords.contains(kw) }
            if (kwHit) {
                val gate = ch.keywordsRequireAnyGenreLower
                matchesKeyword = gate.isEmpty() || gate.any { g -> itemGenres.any { ig -> ig.contains(g) } }
            }
        }

        val itemNetworks = item.networks
        var matchesNetwork = false
        if (hasNetworkRule && itemNetworks.isNotEmpty()) {
            matchesNetwork = ch.networksLower.any { n -> itemNetworks.any { inw -> inw.contains(n) } }
        }

        val hasAnyContentRule = hasTitleRule || hasStudioRule || hasGenreInclude ||
            hasProdCoRule || hasKeywordRule || hasNetworkRule
        if (hasAnyContentRule) {
            if (hasTitleRule && titleContains) return "title"
            if (matchesKeyword) return "keyword"
            if (matchesNetwork) return "network"
            if (matchesProdCo) return "prodco"
            if (hasStudioRule && hasGenreInclude) {
                if (!matchesStudio || !matchesGenre) return null
            } else if (hasStudioRule) {
                if (!matchesStudio) return null
            } else if (hasGenreInclude) {
                if (!matchesGenre) return null
                if (ch.keywordGatedGenresLower.isNotEmpty()) {
                    // Exact genre match, not substring.
                    val isGated = ch.keywordGatedGenresLower.any { g -> itemGenres.any { ig -> ig == g } }
                    if (isGated && !matchesKeyword) return null
                }
            } else {
                return null
            }
            return "rules"
        }
        return "rules"
    }

    private fun manifestClaims(item: PreparedItem, channelID: Int): Boolean {
        val m = manifest ?: return false
        val key = item.manifestKey ?: return false
        return m[key]?.channels?.contains(channelID) == true
    }

    private fun episodeRulesMatch(item: PreparedItem, ch: PreparedChannel): Boolean {
        val own = item.ownTitleLower
        fun has(list: List<String>, hay: String) = list.isNotEmpty() && hay.isNotEmpty() &&
            list.any { JsText.containsLoweredWord(hay, it) }
        val r = ch.rules
        if (has(ch.episodeTitleExcludes, own)) return false
        if (has(ch.episodeTitleContains, own)) return true
        if (ch.episodeSummaryContains.isNotEmpty() && has(ch.episodeSummaryContains, item.summaryLower)) return true
        if (item.item.type == "episode" && ch.episodeSeasonalTitleContains.isNotEmpty() &&
            inAirWindow(item.item, r.episodeAirWindowFrom, r.episodeAirWindowTo) &&
            has(ch.episodeSeasonalTitleContains, own)
        ) return true
        return false
    }

    companion object {
        val HORROR = setOf("horror")
        val REALITY = setOf("reality", "game show", "game-show", "reality-tv")
        val ANIMATION = setOf("animation", "animated", "cartoon")
        val DOCUMENTARY = setOf("documentary", "docuseries")
        val WAR = setOf("war", "war & politics")
        val WESTERN = setOf("western")
        val TALKSHOW = setOf("talk show", "talk", "news")
        val ADULT_RATINGS = setOf("R", "NC-17", "TV-MA", "18", "18+", "X", "NR")

        private val RATING_BANDS = mapOf(
            "all" to listOf("G", "TV-G"),
            "pg" to listOf("PG", "TV-PG"),
            "teen" to listOf("PG-13", "TV-14"),
            "mature" to listOf("R", "TV-MA"),
            "adult" to listOf("NC-17", "TV-MA"),
        )
        private val US_RATINGS = setOf(
            "G", "PG", "PG-13", "R", "NC-17", "NR", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14", "TV-MA",
        )
        private val RATING_WORDS: Map<String, Map<String, String>> = mapOf(
            "GB" to mapOf("U" to "all", "UC" to "all", "PG" to "pg", "12A" to "teen", "12PG" to "teen", "R18" to "adult"),
            "IE" to mapOf("G" to "all", "PG" to "pg", "12A" to "teen", "12PG" to "teen", "15A" to "mature", "15PG" to "mature"),
            "AU" to mapOf(
                "P" to "all", "C" to "all", "G" to "all", "PG" to "pg", "M" to "mature", "MA15+" to "mature",
                "AV15+" to "mature", "R18+" to "mature", "X18+" to "adult",
            ),
            "NZ" to mapOf(
                "G" to "all", "PG" to "pg", "R13" to "teen", "RP13" to "teen", "M" to "mature", "R15" to "mature",
                "R16" to "mature", "RP16" to "mature", "R18" to "mature", "RP18" to "mature", "R" to "adult",
            ),
            "CA" to mapOf("C" to "all", "G" to "all", "C8" to "pg", "PG" to "pg", "14A" to "teen", "18A" to "mature", "R" to "adult", "A" to "adult"),
            "FR" to mapOf("U" to "all", "TP" to "all"),
            "NL" to mapOf("AL" to "all", "MG6" to "pg"),
        )
        private val RATING_BARE = mapOf(
            "U" to "all", "UC" to "all", "12A" to "teen", "12PG" to "teen", "14A" to "teen", "15A" to "mature", "18A" to "mature",
            "MA15+" to "mature", "R18+" to "mature", "R18" to "adult", "X18+" to "adult",
        )

        /** `/^(\d{1,2})\+?$/`: "0" is everyone, up to 10 PG, up to 14 PG-13 / TV-14, older R / TV-MA. */
        private fun ratingAgeBand(label: String): String? {
            var end = label.length
            if (end > 0 && label[end - 1] == '+') end--
            if (end !in 1..2) return null
            for (k in 0 until end) if (label[k] !in '0'..'9') return null
            val age = label.substring(0, end).toInt()
            return when {
                age == 0 -> "all"
                age <= 10 -> "pg"
                age <= 14 -> "teen"
                else -> "mature"
            }
        }

        /** Upstream `usContentRating`: the US label channel rules read for a rating from anywhere. */
        fun usContentRating(raw: String?, type: String?): String? {
            if (raw == null) return null
            val key = JsText.removeSpaces(JsText.upper(raw))
            if (key == "" || US_RATINGS.contains(key)) return raw
            var country: String? = null
            var label = key
            if (key.startsWith("FSK")) {
                country = "DE"
                label = key.substring(3).let { if (it.startsWith("-")) it.substring(1) else it }
            } else if (key.length >= 4 && key[0] in 'A'..'Z' && key[1] in 'A'..'Z' && (key[2] == '-' || key[2] == '/')) {
                val rest = key.substring(3)
                // `.+` stops at a line terminator.
                if (rest.isNotEmpty() && rest.none { it == '\n' || it == '\r' || it == ' ' || it == ' ' }) {
                    val cc = key.substring(0, 2)
                    if (cc != "TV") country = if (cc == "UK") "GB" else cc
                    label = rest
                }
            }
            var band = ratingAgeBand(label)
            if (band == null) {
                band = if (country != null) RATING_WORDS[country]?.get(label) ?: (if (label == "X") "adult" else null)
                else RATING_BARE[label]
            }
            if (band == null) return raw
            return RATING_BANDS.getValue(band)[if (type == "episode") 1 else 0]
        }

        /** Emby Dolby Vision profile 5 is on no channel (upstream `isLeftOffChannels`). */
        fun isLeftOffChannels(item: MediaItem, backend: String): Boolean = backend == "emby" && item.doviProfile == 5

        fun parseYear(item: MediaItem): Int? = item.year

        /** Swift `itemReleaseYear`: the year, else the year of `originallyAvailableAt`. */
        fun releaseYear(item: MediaItem): Int? = parseYear(item) ?: JsText.leadingDate(item.originallyAvailableAt)?.first

        fun passesYearRange(item: MediaItem, rules: ChannelRules): Boolean {
            if (!rules.hasYearRange) return true
            val year = releaseYear(item) ?: return true
            if (rules.yearMin != null && year < rules.yearMin) return false
            if (rules.yearMax != null && year > rules.yearMax) return false
            return true
        }

        /** `/^(\d{1,2})-(\d{1,2})$/` on the trimmed string: month * 100 + day. */
        private fun parseMonthDay(s: String?): Int? {
            val t = JsText.trim(s ?: "")
            val dash = t.indexOf('-')
            if (dash < 1 || dash > 2) return null
            val m = t.substring(0, dash)
            val d = t.substring(dash + 1)
            if (d.length !in 1..2) return null
            if (!m.all { it in '0'..'9' } || !d.all { it in '0'..'9' }) return null
            return m.toInt() * 100 + d.toInt()
        }

        fun inAirWindow(item: MediaItem, from: String?, to: String?): Boolean {
            val f = parseMonthDay(from) ?: return false
            val t = parseMonthDay(to) ?: return false
            val d = JsText.leadingDate(item.originallyAvailableAt) ?: return false
            val md = d.second * 100 + d.third
            return if (f <= t) md in f..t else md >= f || md <= t
        }
    }
}

/** The clock the filter reads. Upstream reads `new Date()`; tests pin it. */
class FilterClock(private val nowMillis: () -> Long, private val zone: () -> ZoneId) {
    fun nowSec(): Double = Math.floor(nowMillis() / 1000.0)

    fun currentYear(): Int = Instant.ofEpochMilli(nowMillis()).atZone(zone()).year

    /**
     * `released < cutoff`, where `released` is local midnight of y-m-d and `cutoff` is now with
     * `setMonth(getMonth() - months)`, JS month arithmetic included: the day of the month is
     * kept and overflows into the next month (Mar 31 minus one month is Mar 3).
     */
    fun releasedBeforeMonthsAgo(y: Int, m: Int, d: Int, months: Double): Boolean {
        val z = zone()
        val now = Instant.ofEpochMilli(nowMillis()).atZone(z).toLocalDateTime()
        val totalMonths = now.year * 12L + (now.monthValue - 1) - months.toLong()
        val cy = Math.floorDiv(totalMonths, 12L).toInt()
        val cm = Math.floorMod(totalMonths, 12L).toInt() + 1
        val cutoffDate = LocalDate.of(cy, cm, 1).plusDays((now.dayOfMonth - 1).toLong())
        val cutoff = ZonedDateTime.of(LocalDateTime.of(cutoffDate, now.toLocalTime()), z).toInstant()
        // new Date(y, m - 1, d): out-of-range months and days roll over too.
        val relDate = LocalDate.of(y, 1, 1).plusMonths((m - 1).toLong()).plusDays((d - 1).toLong())
        val released = relDate.atStartOfDay(z).toInstant()
        return released.isBefore(cutoff)
    }

    companion object {
        fun system() = FilterClock({ System.currentTimeMillis() }, { ZoneId.systemDefault() })
        fun fixed(millis: Long, zone: ZoneId) = FilterClock({ millis }, { zone })
    }
}

/** A channel with its rule lists lowercased once. */
class PreparedChannel(val channel: Channel) {
    val id = channel.id
    val category = channel.category
    val rules = channel.rules
    /** Lowercased once; matched with [JsText.containsLoweredWord]. */
    val titleContains = rules.titleContains.map(JsText::lower)
    val titleExcludes = rules.titleExcludes.map(JsText::lower)
    val episodeTitleContains = rules.episodeTitleContains.map(JsText::lower)
    val episodeSummaryContains = rules.episodeSummaryContains.map(JsText::lower)
    val episodeSeasonalTitleContains = rules.episodeSeasonalTitleContains.map(JsText::lower)
    val episodeTitleExcludes = rules.episodeTitleExcludes.map(JsText::lower)
    val genresIncludeLower = rules.genresInclude.map(JsText::lower)
    val genresExcludeLower = rules.genresExclude.map(JsText::lower)
    val genresRequireAllLower = rules.genresRequireAll.map(JsText::lower)
    val editorialOverridesLower = rules.editorialOverrides.map(JsText::lower)
    val studiosLower = rules.studios.map(JsText::lower)
    val productionCompaniesLower = rules.productionCompanies.map(JsText::lower)
    val networksLower = rules.networks.map(JsText::lower)
    val keywordsLower = rules.keywords.map(JsText::lower)
    val keywordsRequireAnyGenreLower = rules.keywordsRequireAnyGenre.map(JsText::lower)
    val keywordsExcludeLower = rules.keywordsExclude.map(JsText::lower)
    val keywordGatedGenresLower = rules.keywordGatedGenres.map(JsText::lower)
    val isEpisodeCurated = rules.episodeTitleContains.isNotEmpty() ||
        rules.episodeSummaryContains.isNotEmpty() || rules.episodeSeasonalTitleContains.isNotEmpty()

    /** `channelIncludesGenre`: an include genre that is exactly one of `set`. */
    fun includesGenre(set: Set<String>): Boolean = genresIncludeLower.any { set.contains(it) }
}

/** An item with the fields the filter reads computed once. */
class PreparedItem(val item: MediaItem) {
    /** Artist first, then title, joined with a space, lowercased, " (YYYY)" stripped. */
    val titleLower: String = JsText.stripYearSuffix(
        listOf(item.artist ?: "", item.title).filter { it.isNotEmpty() }.joinToString(" "),
    )
    val plexGenres: List<String> = item.genres.map(JsText::lower)
    private val e = item.enrichment
    val keywords: List<String> = e?.keywords?.map(JsText::lower) ?: emptyList()
    val productionCompanies: List<String> = e?.productionCompanies?.map(JsText::lower) ?: emptyList()
    val networks: List<String> = e?.networks?.map(JsText::lower) ?: emptyList()
    val genresWithTmdb: List<String> by lazy {
        LinkedHashSet<String>().apply {
            addAll(plexGenres)
            e?.tmdbGenres?.forEach { add(JsText.lower(it)) }
        }.toList()
    }
    val studioLower: String = item.studio?.let(JsText::lower) ?: ""
    val prodCoHaystack: List<String> =
        if (!item.studio.isNullOrEmpty()) productionCompanies + studioLower else productionCompanies
    val ownTitleLower: String =
        if (item.type == "episode") JsText.lower(item.episodeTitle ?: "") else JsText.stripYearSuffix(item.title)
    val summaryLower: String by lazy { JsText.lower(item.summary) }
    val rawTitleLower: String = JsText.lower(item.title)
    /** usContentRating(contentRating, type): read by the family gate and contentRatings. */
    val usRating: String? = ChannelFilter.usContentRating(item.contentRating, item.type)
    val exclusiveTitle: String = JsText.trim(JsText.stripYearSuffix(rawTitleLower))
    val manifestKey: String? = item.tmdbID?.takeIf { it.isNotEmpty() && it != "0" }?.let {
        (if (item.type == "episode") "tv:" else "movie:") + it
    }
}

/** Swift `ExclusiveRule.matches`, with its scope. */
class PreparedExclusiveRule(val rule: ExclusiveRule) {
    private val editorialLower = rule.editorialTitles.map(JsText::lower)
    private val genresLower = rule.genres.map(JsText::lower)
    private val titleContainsLower = rule.titleContains.map(JsText::lower)

    fun applies(item: MediaItem): Boolean {
        if (rule.type != null && item.type.ifEmpty { "movie" } != rule.type) return false
        if (rule.source != null && item.librarySource.ifEmpty { "movie" } != rule.source) return false
        return true
    }

    fun matches(item: PreparedItem, itemGenres: List<String>): Boolean {
        if (!applies(item.item)) return false
        val raw = item.rawTitleLower
        val title = item.exclusiveTitle
        if (editorialLower.any { c -> title == c || raw == c }) return true
        if (titleContainsLower.any { JsText.containsLoweredWord(title, it) }) return true
        if (genresLower.isNotEmpty() && itemGenres.any { ig -> genresLower.any { rg -> ig.contains(rg) } }) return true
        return false
    }
}
