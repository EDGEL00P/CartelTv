package com.carteltv.tv.data.remap

/**
 * KEY REMAP ENGINE (~60 string keys ⇒ in-house
 * behavior clone): bind physical remote keys — plain AND long-press — to
 * launcher/global actions via our accessibility sink.
 *
 * Storage grammar, one binding per line:  "flavor:keycode=action"
 *   flavor ∈ {p, l}   (plain | long) — grammar reserves "d" for future
 *   double-press without migration.
 *
 * Ownership law: a key with ANY binding belongs to CartelTV system-wide
 * (consumed by the sink). Reserved keys never bind (system articulation):
 * HOME(3) · BACK(4) · MENU(82).
 */
object KeyRemap {

    enum class Flavor(val tag: String) { PLAIN("p"), LONG("l") }

    data class Bind(val flavor: Flavor, val keyCode: Int, val action: String) {
        fun encode(): String = "${flavor.tag}:$keyCode=$action"
    }

    /* ── reserved keys (key_remap_blocked) ── */
    private val BIND_LINE = Regex("^([pl]):(\\d+)=(\\w+)$")

    val RESERVED = setOf(3, 4, 82)   // HOME · BACK · MENU

    val KEYS: Map<Int, String> = mapOf(
        183 to "Red", 184 to "Green", 185 to "Yellow", 186 to "Blue",
        85 to "Media Play", 86 to "Stop", 89 to "Rewind", 90 to "Fast Forward",
        126 to "Play / Pause", 127 to "Pause",
        166 to "Channel Up", 167 to "Channel Down",
        172 to "Guide", 165 to "Info", 175 to "Captions", 170 to "Tools",
        84 to "Search", 219 to "Assistant", 231 to "Assistant Hold",
        80 to "Power", 164 to "Mute", 24 to "Volume Up", 25 to "Volume Down",
        96 to "Numpad Enter", 7 to "Digit 0", 8 to "Digit 1", 9 to "Digit 2", 10 to "Digit 3",
        11 to "Digit 4", 12 to "Digit 5", 13 to "Digit 6", 14 to "Digit 7", 15 to "Digit 8", 16 to "Digit 9",
    )

    fun keyLabel(code: Int): String = KEYS[code] ?: "Key $code"

    data class ActionDef(val id: String, val label: String)

    val ACTIONS: List<ActionDef> = listOf(
        ActionDef("none", "Disable Key"),
        ActionDef("dock", "Toggle Navigation Bar"),
        ActionDef("home", "Open Home"),
        ActionDef("live", "Open Live TV"),
        ActionDef("movies", "Open Movies"),
        ActionDef("series", "Open TV Shows"),
        ActionDef("scores", "Open Sports"),
        ActionDef("weather", "Open Weather"),
        ActionDef("addons", "Open Extensions"),
        ActionDef("youtube", "Open YouTube"),
        ActionDef("search", "Open Search"),
        ActionDef("settings", "Open System"),
        ActionDef("notifications_shade", "Notification Shade"),
        ActionDef("recents", "Recent Tasks"),
        ActionDef("screenshot", "Capture Screen"),
        ActionDef("lock_screen", "Lock Display"),
        ActionDef("power_menu", "Power Options"),
        ActionDef("sleep_timer", "Arm Standby Timer"),
        ActionDef("night_veil", "Engage Standby"),
        ActionDef("media_stop", "Arm Auto-Pause"),
        ActionDef("system_settings", "Device Settings"),
    )

    fun actionById(id: String): ActionDef? = ACTIONS.firstOrNull { it.id == id }

    fun parse(raw: String): List<Bind> =
        raw.split("\n").mapNotNull { line ->
            val m = BIND_LINE.find(line.trim()) ?: return@mapNotNull null
            val (fl, kc, ac) = m.destructured
            val flavor = if (fl == "p") Flavor.PLAIN else Flavor.LONG
            val code = kc.toIntOrNull() ?: return@mapNotNull null
            if (code in RESERVED) return@mapNotNull null
            Bind(flavor, code, ac)
        }

    fun serialize(bindings: List<Bind>): String =
        bindings.joinToString("\n") { it.encode() }
}
