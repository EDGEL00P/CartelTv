package com.carteltv.tv.data.xtream

/**
 * The provider's guide ids as a plain text file, one per line, for `tools/clean_epg.py --only-ids`.
 * It holds channel ids only: no server address, username or password, and no channel names.
 */
object EpgIdExport {

    /** Distinct ids (case-insensitive, first spelling kept), sorted, one per line with a trailing newline. */
    fun format(ids: Collection<String?>): String =
        ids.mapNotNull { it?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .joinToString("") { it + "\n" }

    /** Number of ids [format] would write, for the confirmation message. */
    fun count(ids: Collection<String?>): Int = format(ids).count { it == '\n' }
}
