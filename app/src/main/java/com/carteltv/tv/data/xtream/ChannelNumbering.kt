package com.carteltv.tv.data.xtream

/**
 * Sticky channel numbers. Each stream keeps the number it was first given across refreshes
 * and reorders; a stream the provider stops listing loses its claim, and a new stream takes
 * the lowest free number. Pure logic so assignment survives on the JVM without a device.
 */
object ChannelNumbering {

    /**
     * Carry [previous] numbers forward onto [streams] in list order: streams still present keep
     * their number, streams that vanished release theirs, and new streams take the lowest free
     * number, walking the list from the top. Returns the full map, every stream numbered.
     */
    fun assign(previous: Map<Long, Int>, streams: List<Long>): Map<Long, Int> {
        val claimed = mutableMapOf<Long, Int>()
        val taken = mutableSetOf<Int>()
        // First stream wins a duplicate claim from an old map; later ones renumber.
        streams.forEach { id ->
            val old = previous[id]
            if (old != null && old > 0 && taken.add(old)) claimed[id] = old
        }
        var candidate = 1
        streams.forEach { id ->
            if (id !in claimed) {
                while (candidate in taken) candidate++
                taken += candidate
                claimed[id] = candidate
            }
        }
        return claimed
    }

    /** The stream that owns [number], or null; a number two streams hold resolves to the first in [numbers]' order is undefined, so pick the lowest stream id. */
    fun resolve(numbers: Map<Long, Int>, number: Int): Long? =
        numbers.entries.filter { it.value == number }.minByOrNull { it.key }?.key

    /** The lowest number no stream has — where a user-chosen number can go. */
    fun nextFree(numbers: Map<Long, Int>, except: Long? = null): Int {
        val taken = numbers.filterKeys { it != except }.values.toSet()
        var n = 1
        while (n in taken) n++
        return n
    }

    /** Compact wire/storage form: `12=5;305=1` — ids ascending, so the map's order is stable. */
    fun encode(numbers: Map<Long, Int>): String =
        numbers.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** Reads [encode]'s form; junk entries are dropped, not fatal. */
    fun decode(raw: String?): Map<Long, Int> =
        raw.orEmpty().split(';').mapNotNull {
            val p = it.split('=')
            val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val n = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (id > 0 && n > 0) id to n else null
        }.toMap()
}
