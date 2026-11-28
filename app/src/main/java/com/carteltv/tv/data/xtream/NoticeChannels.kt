package com.carteltv.tv.data.xtream

/**
 * Some providers list notice entries as channels ("Account Information", "Subscription Status"). They are not
 * something to watch and have no guide, so the channel lists leave them out. Favourites and recents are not
 * filtered, because a viewer who put one there chose it.
 */
object NoticeChannels {
    private val NOTICE = Regex(
        """^\W*(account|subscription|expiry|expiration)\s+(info|information|status|details)\W*$""",
        RegexOption.IGNORE_CASE,
    )

    fun isNotice(channelName: String): Boolean = NOTICE.containsMatchIn(channelName.trim())
}
