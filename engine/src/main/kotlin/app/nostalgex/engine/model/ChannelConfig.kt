package app.nostalgex.engine.model

import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.arr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.number
import app.nostalgex.engine.json.obj
import app.nostalgex.engine.json.objs
import app.nostalgex.engine.json.str
import app.nostalgex.engine.json.strings

/**
 * Upstream's channels.json, read unchanged: the stock channels, the packages (bundles) and the
 * exclusive rules. It is the same file the Apple TV app, the web tuner and the Roku port read.
 */
class ChannelConfig(
    val version: Int,
    val channels: List<Channel>,
    val bundles: List<Bundle>,
    val exclusiveRules: List<ExclusiveRule>,
) {
    val channelsById: Map<Int, Channel> = channels.associateBy { it.id }

    companion object {
        fun parse(text: String): ChannelConfig {
            val root = Json.parse(text).asObj() ?: error("channels.json is not an object")
            return ChannelConfig(
                version = root.int("version") ?: 0,
                channels = root.objs("channels").map(Channel::fromJson),
                bundles = root.objs("bundles").map(Bundle::fromJson),
                // Same normalisation as plex-tuner.html loadStockChannelsFromJSON.
                exclusiveRules = root.objs("exclusiveRules").map(ExclusiveRule::fromJson),
            )
        }
    }
}

data class Channel(
    val id: Int,
    val number: Int,
    val name: String,
    val colorHex: String,
    val category: String?,
    /** `def.minItems || 50`, as the web tuner reads it. */
    val minItems: Int,
    val rules: ChannelRules,
    val timeRestrictions: TimeRestrictions?,
    val isPremiereChannel: Boolean,
) {
    companion object {
        fun fromJson(o: Map<String, Any?>): Channel {
            val id = o.int("id") ?: 0
            val min = o.int("minItems") ?: 0
            return Channel(
                id = id,
                number = o.int("number")?.takeIf { it != 0 } ?: id,
                name = o.str("name") ?: "CH $id",
                colorHex = o.str("colorHex") ?: "#FFE500",
                category = o.str("category"),
                minItems = if (min != 0) min else 50,
                rules = ChannelRules.fromJson(o.obj("rules")),
                timeRestrictions = o.obj("timeRestrictions")?.let(TimeRestrictions::fromJson),
                isPremiereChannel = o.bool("isPremiereChannel") == true,
            )
        }
    }
}

/** A channel's rules, as channels.json writes them. Absent lists read as empty, as `|| []` does. */
class ChannelRules(
    val type: String? = null,
    val source: String? = null,
    val genresInclude: List<String> = emptyList(),
    val genresExclude: List<String> = emptyList(),
    val genresRequireAll: List<String> = emptyList(),
    val titleContains: List<String> = emptyList(),
    val titleExcludes: List<String> = emptyList(),
    val editorialOverrides: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val productionCompanies: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val keywordsRequireAnyGenre: List<String> = emptyList(),
    val keywordsExclude: List<String> = emptyList(),
    val keywordGatedGenres: List<String> = emptyList(),
    val hasYearRange: Boolean = false,
    val yearMin: Double? = null,
    val yearMax: Double? = null,
    val contentRatings: List<String> = emptyList(),
    val allowUnrated: Boolean = false,
    val hasDurationRange: Boolean = false,
    val durationMin: Double? = null,
    val durationMax: Double? = null,
    val watchedOnly: Boolean = false,
    val unwatchedOnly: Boolean = false,
    val rewatched: Boolean = false,
    val manifestOnly: Boolean = false,
    val matchTmdbGenres: Boolean = false,
    val wonOscar: Boolean = false,
    val imdbRatingMin: Double? = null,
    val imdbVotesMin: Double? = null,
    val rtScoreMin: Double? = null,
    val metacriticMin: Double? = null,
    val ratingMin: Double? = null,
    val addedWithinDays: Double? = null,
    val releasedWithinMonths: Double? = null,
    val episodeTitleContains: List<String> = emptyList(),
    val episodeSummaryContains: List<String> = emptyList(),
    val episodeSeasonalTitleContains: List<String> = emptyList(),
    val episodeTitleExcludes: List<String> = emptyList(),
    val episodeAirWindowFrom: String? = null,
    val episodeAirWindowTo: String? = null,
) {
    companion object {
        fun fromJson(r: Map<String, Any?>?): ChannelRules {
            if (r == null) return ChannelRules()
            val genres = r.obj("genres")
            val yr = r.obj("yearRange")
            val dr = r.obj("durationRange")
            val win = r.obj("episodeAirWindow")
            fun truthy(key: String): Boolean = when (val v = r[key]) {
                is Boolean -> v
                null -> false
                is String -> v.isNotEmpty()
                is Long -> v != 0L
                is Double -> v != 0.0
                else -> true
            }
            return ChannelRules(
                type = r.str("type")?.takeIf { it.isNotEmpty() },
                source = r.str("source")?.takeIf { it.isNotEmpty() },
                genresInclude = genres.strings("include"),
                genresExclude = genres.strings("exclude"),
                genresRequireAll = genres.strings("requireAll"),
                titleContains = r.strings("titleContains"),
                titleExcludes = r.strings("titleExcludes"),
                editorialOverrides = r.strings("editorialOverrides"),
                studios = r.strings("studios"),
                productionCompanies = r.strings("productionCompanies"),
                networks = r.strings("networks"),
                keywords = r.strings("keywords"),
                keywordsRequireAnyGenre = r.strings("keywordsRequireAnyGenre"),
                keywordsExclude = r.strings("keywordsExclude"),
                keywordGatedGenres = r.strings("keywordGatedGenres"),
                hasYearRange = yr != null,
                yearMin = yr.number("min"),
                yearMax = yr.number("max"),
                contentRatings = r.strings("contentRatings"),
                allowUnrated = truthy("allowUnrated"),
                hasDurationRange = dr != null,
                durationMin = dr.number("min") ?: dr.number("minMinutes"),
                durationMax = dr.number("max") ?: dr.number("maxMinutes"),
                watchedOnly = truthy("watchedOnly"),
                unwatchedOnly = truthy("unwatchedOnly"),
                rewatched = truthy("rewatched"),
                manifestOnly = truthy("manifestOnly"),
                matchTmdbGenres = truthy("matchTmdbGenres"),
                wonOscar = truthy("wonOscar"),
                imdbRatingMin = r.number("imdbRatingMin"),
                imdbVotesMin = r.number("imdbVotesMin"),
                rtScoreMin = r.number("rtScoreMin"),
                metacriticMin = r.number("metacriticMin"),
                ratingMin = r.number("ratingMin"),
                addedWithinDays = r.number("addedWithinDays"),
                releasedWithinMonths = r.number("releasedWithinMonths"),
                episodeTitleContains = r.strings("episodeTitleContains"),
                episodeSummaryContains = r.strings("episodeSummaryContains"),
                episodeSeasonalTitleContains = r.strings("episodeSeasonalTitleContains"),
                episodeTitleExcludes = r.strings("episodeTitleExcludes"),
                episodeAirWindowFrom = win?.get("from")?.toString(),
                episodeAirWindowTo = win?.get("to")?.toString(),
            )
        }
    }
}

/** Mirrors Channel.filteredPool()'s inputs (Models/Channel.swift) and the web's getTimeFilteredPool. */
data class TimeRestrictions(
    val blockRatings: List<String>?,
    val blockBefore: Int?,
    val blockAfter: Int?,
    val tvOnlyBefore: Int?,
    val onlyAfterHour: Int?,
) {
    companion object {
        fun fromJson(o: Map<String, Any?>): TimeRestrictions {
            val br = o.obj("blockRatings")
            return TimeRestrictions(
                blockRatings = br?.strings("ratings"),
                blockBefore = br.int("blockBefore"),
                blockAfter = br.int("blockAfter"),
                tvOnlyBefore = o.int("tvOnlyBefore"),
                onlyAfterHour = o.int("onlyAfterHour"),
            )
        }
    }
}

data class SeasonStart(val relativeTo: String, val days: Int)

data class Bundle(
    val id: String,
    val name: String,
    val description: String,
    val channelIDs: List<Int>,
    /** Set for a seasonal package (SCREAM, TIS THE SEASON). */
    val activeMonths: List<Int>?,
    val seasonStart: SeasonStart?,
) {
    val isSeasonal: Boolean get() = activeMonths != null

    companion object {
        fun fromJson(o: Map<String, Any?>): Bundle {
            val ss = o.obj("seasonStart")
            val days = ss?.get("days")
            return Bundle(
                id = o.str("id") ?: "",
                name = o.str("name") ?: "",
                description = o.str("description") ?: "",
                channelIDs = o.arr("channelIDs")?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
                activeMonths = o.arr("activeMonths")?.mapNotNull { (it as? Number)?.toInt() },
                seasonStart = ss?.let {
                    SeasonStart(
                        relativeTo = it.str("relativeTo") ?: "",
                        // Number.isInteger(start.days) ? start.days : 0
                        days = when (days) {
                            is Long -> days.toInt()
                            is Double -> if (days == Math.floor(days)) days.toInt() else 0
                            else -> 0
                        },
                    )
                },
            )
        }
    }
}

data class ExclusiveRule(
    val channelIDs: List<Int>,
    val genres: List<String>,
    val titleContains: List<String>,
    val editorialTitles: List<String>,
    val manifestExclusive: Boolean,
    val type: String?,
    val source: String?,
) {
    companion object {
        fun fromJson(o: Map<String, Any?>): ExclusiveRule {
            val ids = o.arr("channelIDs")?.mapNotNull { (it as? Number)?.toInt() }
                ?: o.int("channelID")?.let { listOf(it) } ?: emptyList()
            return ExclusiveRule(
                channelIDs = ids,
                genres = o.strings("genres"),
                titleContains = o.strings("titleContains"),
                editorialTitles = o.strings("editorialTitles"),
                manifestExclusive = o.bool("manifestExclusive") == true,
                type = o.str("type")?.takeIf { it.isNotEmpty() },
                source = o.str("source")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * channels-memberships.json: TMDB ids ("movie:603", "tv:1399") claimed for channels, built
 * upstream from TMDB "similar" titles around exemplars, and the items locked to one channel.
 */
class MembershipManifest(private val entries: Map<String, Entry>) {
    class Entry(val channels: IntArray, val exclusive: Int?)

    operator fun get(key: String): Entry? = entries[key]

    val size: Int get() = entries.size

    companion object {
        fun parse(text: String): MembershipManifest {
            val root = Json.parse(text).asObj() ?: return MembershipManifest(emptyMap())
            val items = root.obj("items") ?: return MembershipManifest(emptyMap())
            val map = HashMap<String, Entry>(items.size * 2)
            for ((k, v) in items) {
                val o = v.asObj() ?: continue
                val chans = o.arr("channels")?.mapNotNull { (it as? Number)?.toInt() }?.toIntArray() ?: IntArray(0)
                val ex = o["exclusive"]
                map[k] = Entry(chans, (ex as? Number)?.toInt())
            }
            return MembershipManifest(map)
        }
    }
}
