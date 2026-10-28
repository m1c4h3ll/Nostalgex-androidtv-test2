package app.nostalgex.engine.lineup

import app.nostalgex.engine.filter.ChannelFilter
import app.nostalgex.engine.filter.PreparedItem
import app.nostalgex.engine.model.Bundle
import app.nostalgex.engine.model.Channel
import app.nostalgex.engine.model.ChannelConfig
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.schedule.Season
import java.time.LocalDateTime

/**
 * Which channels exist, which have enough to air, and in what order the guide lists them.
 *
 * Follows the web tuner (`repopulateChannelsFromLibrary`, `applyBundleFilter`,
 * `sortChannelsForGuide`, `getTimeFilteredPool`) and its helpers in
 * `scripts/nostalgex-library-load.js`, which mirror the Apple TV's `AppState+Channels.swift`.
 */
object Lineup {

    /**
     * `staticChannelIDsForPoolBuild`: every enabled package builds its pools, in any month. A
     * first load builds every channel, so a lineup with nothing on can switch packages on.
     */
    fun channelIDsForPoolBuild(buildAll: Boolean, config: ChannelConfig, enabledBundles: Set<String>): Set<Int> {
        if (buildAll || config.bundles.isEmpty()) return config.channels.mapTo(LinkedHashSet()) { it.id }
        val ids = LinkedHashSet<Int>()
        for (b in config.bundles) if (enabledBundles.contains(b.id)) ids.addAll(b.channelIDs)
        return ids
    }

    /** Builds the pools of `channelIds`, in channels.json order. */
    fun buildPools(
        items: List<PreparedItem>,
        config: ChannelConfig,
        channelIds: Set<Int>,
        filter: ChannelFilter,
        onChannel: (index: Int, total: Int, channel: Channel) -> Unit = { _, _, _ -> },
    ): Map<Int, List<MediaItem>> {
        val targets = config.channels.filter { channelIds.contains(it.id) }
        val out = LinkedHashMap<Int, List<MediaItem>>()
        targets.forEachIndexed { i, ch ->
            onChannel(i + 1, targets.size, ch)
            out[ch.id] = filter.filterPool(items, ch)
        }
        return out
    }

    /** `autoEnableBundlesWithContent` / `bundlesToAutoEnable`. */
    fun bundlesToAutoEnable(bundles: List<Bundle>, enabled: Set<String>, availableChannelIDs: Set<Int>): List<String> {
        val ids = ArrayList<String>()
        for (b in bundles) {
            if (b.id.startsWith("collections-")) continue
            if (b.isSeasonal) continue
            if (enabled.contains(b.id)) continue
            if (b.channelIDs.any { availableChannelIDs.contains(it) }) ids.add(b.id)
        }
        return ids
    }

    /** Channels whose pool reaches `minItems`. */
    fun availableChannelIDs(config: ChannelConfig, pools: Map<Int, List<MediaItem>>): Set<Int> =
        config.channels.filter { (pools[it.id]?.size ?: 0) >= it.minItems }.mapTo(HashSet()) { it.id }

    /**
     * The guide: the channels of every enabled package that have enough to air, with the seasonal
     * lead (packages on and in season, in their bundle order) above channel one, then by number.
     */
    fun guideChannels(
        config: ChannelConfig,
        enabledBundles: Set<String>,
        pools: Map<Int, List<MediaItem>>,
        now: LocalDateTime,
    ): List<Channel> {
        val enabledIds = HashSet<Int>()
        for (b in config.bundles) if (enabledBundles.contains(b.id)) enabledIds.addAll(b.channelIDs)
        val list = config.channels.filter {
            enabledIds.contains(it.id) && (pools[it.id]?.size ?: 0) >= it.minItems
        }
        val lead = Season.seasonalLeadIDs(config.bundles, enabledBundles, now)
        val rank = HashMap<Int, Int>()
        lead.forEachIndexed { i, id -> if (!rank.containsKey(id)) rank[id] = i }
        val leading = list.filter { rank.containsKey(it.id) }.sortedBy { rank.getValue(it.id) }
        val rest = list.filter { !rank.containsKey(it.id) }.sortedBy { it.number }
        return leading + rest
    }

    /**
     * Channel.filteredPool() / `getTimeFilteredPool`: rating blocks by hour, episodes only before
     * an hour, or nothing before an hour, each keeping at least two items.
     */
    fun timeFilteredPool(channel: Channel, pool: List<MediaItem>, hour: Int): List<MediaItem> {
        val tr = channel.timeRestrictions ?: return pool
        var p = pool
        val ratings = tr.blockRatings
        if (ratings != null) {
            val isBlocked = (tr.blockBefore != null && hour < tr.blockBefore) || (tr.blockAfter != null && hour >= tr.blockAfter)
            if (isBlocked) {
                val filtered = p.filter { !ratings.contains(ChannelFilter.usContentRating(it.contentRating, it.type)) }
                if (filtered.size >= 2) p = filtered
            }
        }
        if (tr.tvOnlyBefore != null && hour < tr.tvOnlyBefore) {
            val episodes = p.filter { it.type == "episode" }
            if (episodes.size >= 2) p = episodes
        }
        if (tr.onlyAfterHour != null && hour < tr.onlyAfterHour) return emptyList()
        return p
    }
}
