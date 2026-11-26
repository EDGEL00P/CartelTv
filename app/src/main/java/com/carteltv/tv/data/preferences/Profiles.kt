package com.carteltv.tv.data.preferences

import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import com.carteltv.tv.data.network.HttpSupport

/**
 * HOUSEHOLD PROFILES + PARENTAL (/
 * bedtime + daily-limit — in-house clone).
 *
 * Laws adopted:
 *  · WHO'S WATCHING — named profiles with emoji faces; boot asks when set
 *  · KIDS profiles: daily minutes + bedtime window (overnight-safe)
 *  · parent authority = the Settings-door PIN (one code rules the house)
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val emoji: String = "🦁",
    val kids: Boolean = false,
    /** daily allowance minutes; 0 = unlimited */
    val dailyMinutes: Int = 0,
    /** lights-out minute-of-day (e.g. 1260 = 21:00); −1 = off */
    val bedtimeStartMin: Int = -1,
    /** bedtime window length in hours (overnight wraps) */
    val bedtimeHours: Int = 8,
    /** parent-vetted switch-in PIN (salted SHA-256); blank = open */
    val pinSha: String = "",
)

object Profiles {

    val FACES = listOf("🦁", "🐼", "🦊", "🐸", "🐙", "🦉", "🐺", "🐝", "🐨", "🦖")

    val state = mutableStateOf<List<Profile>>(emptyList())
    var activeId by mutableStateOf("")

    /** per-day usage minutes: key "yyyy-MM-dd:profileId" */
    var usage by mutableStateOf<Map<String, Int>>(emptyMap())

    /** UI bus: the WHO'S WATCHING picker (boot + hold-OK on HOME tab) */
    var pickerShown by mutableStateOf(false)

    private val json get() = HttpSupport.json

    fun load() {
        state.value = AppPreferences.profilesJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<Profile>>(it) }.getOrNull() }
            ?: emptyList()
        activeId = AppPreferences.activeProfileId
        usage = AppPreferences.usageJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }
            ?: emptyMap()
        if (activeId !in state.value.map { it.id }) {
            activeId = state.value.firstOrNull()?.id ?: ""
        }
    }

    fun persist() {
        AppPreferences.profilesJson = json.encodeToString(state.value)
        AppPreferences.activeProfileId = activeId
        AppPreferences.usageJson = json.encodeToString(usage)
    }

    fun add(name: String, emoji: String, kids: Boolean): Profile {
        val p = Profile(id = "p${System.currentTimeMillis()}", name = name, emoji = emoji, kids = kids)
        state.value = state.value + p
        if (activeId.isBlank()) activeId = p.id
        persist()
        return p
    }

    fun update(p: Profile) {
        state.value = state.value.map { if (it.id == p.id) p else it }
        persist()
    }

    fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
        if (activeId == id) activeId = state.value.firstOrNull()?.id ?: ""
        persist()
    }

    fun switch(id: String) {
        activeId = id
        persist()
    }

    fun active(): Profile? = state.value.firstOrNull { it.id == activeId }

    fun byId(id: String): Profile? = state.value.firstOrNull { it.id == id }

    /* ── switch-in PIN (profile gate) — same digest scheme as app pins ── */
    fun pinDigest(profileId: String, pin: String): String =
        HttpSupport.sha256Hex("$profileId:$pin:cartelprof")

    fun pinSet(id: String, pin: String) {
        byId(id)?.let { update(it.copy(pinSha = pinDigest(id, pin))) }
    }

    fun pinClear(id: String) {
        byId(id)?.let { update(it.copy(pinSha = "")) }
    }

    fun pinOk(id: String, pin: String): Boolean =
        byId(id)?.pinSha?.equals(pinDigest(id, pin), ignoreCase = true) == true

    sealed interface Block { data object Limit : Block; data object Bedtime : Block }

    fun usedToday(pid: String, now: java.util.Calendar): Int =
        usage[dayKey(now, pid)] ?: 0

    fun minutesLeft(p: Profile, now: java.util.Calendar): Int? =
        if (p.kids && p.dailyMinutes > 0)
            (p.dailyMinutes - usedToday(p.id, now)).coerceAtLeast(0) else null

    /** bedtime window check, overnight-safe (start > end wraps midnight) */
    fun bedtimeBlocked(p: Profile, nowMin: Int): Boolean {
        if (!p.kids || p.bedtimeStartMin < 0) return false
        val end = (p.bedtimeStartMin + p.bedtimeHours * 60) % (24 * 60)
        return if (p.bedtimeStartMin <= end) nowMin in p.bedtimeStartMin..end
        else nowMin >= p.bedtimeStartMin || nowMin <= end
    }

    /** the one gate query: WHY is this profile blocked right now? */
    fun evaluate(p: Profile?, now: java.util.Calendar): Block? {
        p ?: return null
        if (!p.kids) return null
        if (bedtimeBlocked(p, now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    now.get(java.util.Calendar.MINUTE))) return Block.Bedtime
        val left = minutesLeft(p, now)
        if (left != null && left <= 0) return Block.Limit
        return null
    }

    fun grantMore(minutes: Int = 15) {
        val p = active() ?: return
        val k = dayKey(java.util.Calendar.getInstance(), p.id)
        // granting = subtracting from today's usage (floor 0)
        usage = usage + (k to ((usage[k] ?: 0) - minutes).coerceAtLeast(0))
        persist()
    }

    fun dayKey(c: java.util.Calendar, pid: String): String =
        "%04d-%02d-%02d:%s".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            pid,
        )

    /* ── usage ticker: counts foreground minutes per active profile ── */
    private var resumed = 0

    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(object :
            android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { resumed++; ensureTicker(app) }
            override fun onActivityPaused(a: android.app.Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    private var ticking = false

    private fun ensureTicker(app: Application) {
        if (ticking) return
        ticking = true
        com.carteltv.tv.AppScope.io.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                if (resumed > 0) {
                    val p = active()
                    if (p != null) {
                        val k = dayKey(java.util.Calendar.getInstance(), p.id)
                        usage = usage + (k to (usage[k] ?: 0) + 1)
                        val cutoffPrefix = dayKey(
                            java.util.Calendar.getInstance().apply { add(java.util.Calendar.DATE, -3) }, "")
                        usage = usage.filterKeys { it >= cutoffPrefix }
                        persist()
                    }
                }
            }
        }
    }
}
