package app.nostalgex.engine.schedule

import app.nostalgex.engine.model.Bundle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Seasonal packages: when one is in season, which one the invite above channel one offers,
 * which channels lead the guide, and what the invite and the Settings row say.
 *
 * A port of upstream's `scripts/nostalgex-season.js` (Swift: `ChannelBundle.swift`,
 * `SeasonalPrompt.swift`, `GuideChannelOrder.swift`), held to `scripts/season-golden.json`.
 * Dates are local wall-clock times, as on the device.
 *
 * A seasonal package is never switched on for anyone and, once on, stays on in every month.
 * Its season only decides the invite and the guide lead.
 */
object Season {
    const val US_THANKSGIVING = "usThanksgiving"
    private val MONTH_ABBR = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** The fourth Thursday of November. */
    fun usThanksgiving(year: Int): LocalDate {
        val nov1 = LocalDate.of(year, 11, 1)
        // JS getDay(): Sunday 0 .. Saturday 6
        val dow = nov1.dayOfWeek.value % 7
        val firstThursday = 1 + ((4 - dow + 7) % 7)
        return LocalDate.of(year, 11, firstThursday + 21)
    }

    fun seasonStartDate(bundle: Bundle, year: Int): LocalDate? {
        if (!bundle.isSeasonal) return null
        val start = bundle.seasonStart ?: return null
        if (start.relativeTo != US_THANKSGIVING) return null
        return usThanksgiving(year).plusDays(start.days.toLong())
    }

    /** ChannelBundle.isInSeason(on:). A package without activeMonths always is. */
    fun isInSeason(bundle: Bundle, now: LocalDateTime): Boolean {
        val months = bundle.activeMonths ?: return true
        val month = now.monthValue
        if (months.contains(month)) {
            // Late September gate: if September is in the list, only from the 15th onward.
            return month != 9 || now.dayOfMonth >= 15
        }
        val start = seasonStartDate(bundle, now.year) ?: return false
        return !now.isBefore(start.atStartOfDay()) && now.monthValue == start.monthValue
    }

    /** SeasonalPrompt.bundleToOffer: the first seasonal package in season, off and not silenced. */
    fun bundleToOffer(bundles: List<Bundle>, enabled: Set<String>, now: LocalDateTime, isSilenced: (String) -> Boolean): Bundle? =
        bundles.firstOrNull { it.isSeasonal && isInSeason(it, now) && !enabled.contains(it.id) && !isSilenced(it.id) }

    /** GuideChannelOrder.seasonalLeadIDs: channels of every seasonal package on and in season. */
    fun seasonalLeadIDs(bundles: List<Bundle>, enabled: Set<String>, now: LocalDateTime): List<Int> =
        bundles.filter { it.isSeasonal && enabled.contains(it.id) && isInSeason(it, now) }.flatMap { it.channelIDs }

    /** SeasonalPrompt.headline. */
    fun inviteHeadline(bundle: Bundle, now: LocalDateTime): String = when (bundle.id) {
        "seasonal" -> "IT'S OCTOBER. ARE YOU READY TO SCREAM?"
        "tis-the-season" -> if (now.monthValue == 12) "IT'S DECEMBER. DECK THE CHANNELS." else "THE HOLIDAYS ARE HERE. DECK THE CHANNELS."
        else -> "${bundle.name} IS IN SEASON."
    }

    /** ChannelBundle.spotlightLabel: "SPOTLIGHTED OCT", or "SPOTLIGHTED NOV 19 TO DEC 31". */
    fun spotlightLabel(bundle: Bundle, now: LocalDateTime): String {
        val months = bundle.activeMonths
        if (months.isNullOrEmpty()) return ""
        val start = seasonStartDate(bundle, now.year)
        val last = months.last()
        if (start != null && last in 1..12) {
            val endYear = if (last < start.monthValue) start.year + 1 else start.year
            val lastDay = YearMonth.of(endYear, last).lengthOfMonth()
            return "SPOTLIGHTED ${MONTH_ABBR[start.monthValue - 1]} ${start.dayOfMonth} TO ${MONTH_ABBR[last - 1]} $lastDay"
        }
        val names = months.mapNotNull { MONTH_ABBR.getOrNull(it - 1) }
        return if (names.isNotEmpty()) "SPOTLIGHTED ${names.joinToString(", ")}" else ""
    }
}

/**
 * NOT THIS YEAR silences until the year turns; DON'T ASK AGAIN silences for good.
 * Mirrors the web tuner's `isSeasonalPromptSilenced` / `answerSeasonalPrompt`.
 */
data class SeasonalAnswer(val never: Boolean = false, val notNowYear: Int? = null) {
    fun silences(year: Int): Boolean = never || notNowYear == year
}
