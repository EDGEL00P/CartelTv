package com.carteltv.tv.data.xtream

import kotlinx.serialization.Serializable

/** What the user did to one provider's live groups: which are hidden, what they are called, their order, and which ask for the parent PIN. */
@Serializable
data class ChannelGroupConfig(
    val hidden: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val order: List<String> = emptyList(),
    val locked: Set<String> = emptySet(),
) {
    val isDefault: Boolean get() = hidden.isEmpty() && names.isEmpty() && order.isEmpty() && locked.isEmpty()
}

object ChannelGroups {
    /** Hidden groups dropped, renames applied, and the user's order first; unlisted groups keep their place after. */
    fun apply(groups: List<XtreamClient.Category>, cfg: ChannelGroupConfig): List<XtreamClient.Category> {
        if (cfg.isDefault) return groups
        val rank = cfg.order.withIndex().associate { it.value to it.index }
        return groups.filter { it.category_id !in cfg.hidden }
            .map { g -> cfg.names[g.category_id]?.let { g.copy(category_name = it) } ?: g }
            .sortedBy { rank[it.category_id] ?: Int.MAX_VALUE } // stable: unlisted groups keep provider order
    }

    fun visible(streams: List<XtreamClient.LiveStream>, cfg: ChannelGroupConfig): List<XtreamClient.LiveStream> =
        if (cfg.hidden.isEmpty()) streams else streams.filter { it.categoryId !in cfg.hidden }

    fun toggleHidden(cfg: ChannelGroupConfig, id: String): ChannelGroupConfig =
        cfg.copy(hidden = if (id in cfg.hidden) cfg.hidden - id else cfg.hidden + id)

    /** A locked group stays visible but its channels ask for the parent PIN first (XCIPTV-style parental lock). */
    fun toggleLocked(cfg: ChannelGroupConfig, id: String): ChannelGroupConfig =
        cfg.copy(locked = if (id in cfg.locked) cfg.locked - id else cfg.locked + id)

    /** An empty or unchanged name removes the rename. */
    fun rename(cfg: ChannelGroupConfig, id: String, original: String, name: String): ChannelGroupConfig {
        val n = name.trim()
        return cfg.copy(names = if (n.isEmpty() || n == original) cfg.names - id else cfg.names + (id to n))
    }

    /**
     * Moves [id] one place up or down within [shown] (the groups as the user currently sees them) and returns the
     * full order, so groups that are hidden keep a stable slot.
     */
    fun move(cfg: ChannelGroupConfig, shown: List<String>, id: String, delta: Int): ChannelGroupConfig {
        val i = shown.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in shown.indices) return cfg
        val next = shown.toMutableList().apply { removeAt(i); add(j, id) }
        val rest = cfg.order.filter { it !in next }
        return cfg.copy(order = next + rest)
    }
}
