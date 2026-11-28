package com.carteltv.tv.ui.player

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ChannelDuplicates
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.data.xtream.XtreamClient

/** Digits typed on the remote while a live channel plays: 4, 8, 2 is channel 482, taken once the viewer pauses. */
internal class NumberEntry(private val maxDigits: Int = 4) {
    var text: String = ""
        private set

    val active: Boolean get() = text.isNotEmpty()

    /** True when the number is as long as it can be, so there is nothing left to wait for. */
    fun press(digit: Int): Boolean {
        require(digit in 0..9) { "not a digit: $digit" }
        text = if (text.length >= maxDigits) digit.toString() else text + digit
        return text.length == maxDigits
    }

    /** The number typed so far, and the entry starts over. */
    fun take(): Int? = text.toIntOrNull().also { text = "" }
}

/** The remote's live-channel intents, all answered from one cached read of the pool. */
internal object ChannelTuning {

    /** The list the viewer last tuned from in the channel panel or guide; channel up/down walks it. */
    @Volatile var zapList: List<XtreamClient.LiveStream> = emptyList()

    /** One read of the live-channel pool: each channel's display name and place, and its provider number. */
    data class Index(
        val pool: List<XtreamClient.LiveStream>,
        val names: List<String>,
        val positions: Map<Long, Int>,
        val providerNumbers: Boolean,
        val byNumber: Map<Int, XtreamClient.LiveStream>,
    )

    @Volatile private var cached: Index? = null

    /** Rebuilds only when LiveChannels publishes a new filtered list, not on every remote press. */
    fun index(pool: List<XtreamClient.LiveStream>): Index {
        val cur = cached
        if (cur != null && cur.pool === pool) return cur
        val providerNumbers = pool.any { it.num > 0 }
        return Index(
            pool = pool,
            names = pool.map { GuideModel.displayName(it.name) },
            positions = buildMap {
                pool.forEachIndexed { i, s -> putIfAbsent(s.streamId, i) }
            },
            providerNumbers = providerNumbers,
            byNumber = if (providerNumbers) {
                buildMap {
                    for (s in pool) if (s.num > 0) putIfAbsent(s.num, s)
                }
            } else {
                emptyMap()
            },
        ).also { cached = it }
    }

    /**
     * The entry [direction] places from [index] in [names], wrapping at the ends. A provider lists a
     * channel once per quality copy, and a copy is not another channel, so the walk skips every entry
     * named like the one playing. Null when the list is empty, [index] is out of range, or nothing
     * else is in it.
     */
    fun neighbour(names: List<String>, index: Int, direction: Int): Int? {
        if (index !in names.indices) return null
        val fromIdentity = ChannelDuplicates.identity(names[index])
        var at = index
        repeat(names.size - 1) {
            at = Math.floorMod(at + direction, names.size)
            if (ChannelDuplicates.identity(names[at]) != fromIdentity) return at
        }
        return null
    }
}

/**
 * The number a channel goes by: the provider's own, or its place in the list when the provider numbers
 * nothing (some portals send 0 for every channel).
 */
internal fun channelNumberOf(pool: List<XtreamClient.LiveStream>, stream: XtreamClient.LiveStream): Int {
    val index = ChannelTuning.index(pool)
    return if (index.providerNumbers) stream.num else index.positions[stream.streamId]?.plus(1) ?: 0
}

/** The channel the remote's number names, in the same terms [channelNumberOf] counts by. */
internal fun channelByNumber(pool: List<XtreamClient.LiveStream>, number: Int): XtreamClient.LiveStream? {
    val index = ChannelTuning.index(pool)
    return if (index.providerNumbers) index.byNumber[number] else pool.getOrNull(number - 1)
}

private fun slotFor(stream: XtreamClient.LiveStream): AppPreferences.MultiViewSlot? {
    val client = ProviderSession.current ?: return null
    return AppPreferences.MultiViewSlot("live:${stream.streamId}", stream.name, client.liveUrl(stream.streamId))
}

/** The channel typed as [number] on the remote, null when the list has none by that number. */
internal fun channelSlotByNumber(number: Int): AppPreferences.MultiViewSlot? =
    channelByNumber(LiveChannels.cached(), number)?.let(::slotFor)

/** The channel watched before the one playing as [refId]; a recent in a hidden group still counts. */
internal fun lastChannelSlot(refId: String): AppPreferences.MultiViewSlot? {
    val current = refId.removePrefix("live:").toLongOrNull() ?: return null
    val id = previousLiveId(AppPreferences.recentLive(), current) ?: return null
    return LiveChannels.byId(id)?.let(::slotFor)
}

/**
 * The channel [direction] places from the one playing as [refId], within the list it was tuned from
 * when that holds it, else the whole provider list. Null when the channel list is not loaded yet or holds no other channel.
 */
internal fun neighbourChannel(refId: String, direction: Int): AppPreferences.MultiViewSlot? {
    val id = refId.removePrefix("live:").toLongOrNull() ?: return null
    val pool = ChannelTuning.zapList.takeIf { list -> list.any { it.streamId == id } } ?: LiveChannels.cached()
    val index = ChannelTuning.index(pool)
    val at = ChannelTuning.neighbour(index.names, index.positions[id] ?: return null, direction) ?: return null
    return slotFor(pool[at])
}

/** The channel watched before [current]: the newest recent that is not it. */
internal fun previousLiveId(recents: List<Long>, current: Long): Long? = recents.firstOrNull { it != current }

/** How far through a programme [nowMs] is, 0 to 1; 0 when the times are missing or reversed. */
internal fun programmeProgress(nowMs: Long, startMs: Long, stopMs: Long): Float {
    if (startMs <= 0L || stopMs <= startMs) return 0f
    return ((nowMs - startMs).toFloat() / (stopMs - startMs).toFloat()).coerceIn(0f, 1f)
}
