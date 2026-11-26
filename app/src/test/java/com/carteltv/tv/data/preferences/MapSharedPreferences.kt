package com.carteltv.tv.data.preferences

import android.content.SharedPreferences

/** Map-backed store for preference tests. */
internal class MapSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    private inner class Editor : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(k: String?, v: String?) = apply { staged[k!!] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { staged[k!!] = v?.toSet() }
        override fun putInt(k: String?, v: Int) = apply { staged[k!!] = v }
        override fun putLong(k: String?, v: Long) = apply { staged[k!!] = v }
        override fun putFloat(k: String?, v: Float) = apply { staged[k!!] = v }
        override fun putBoolean(k: String?, v: Boolean) = apply { staged[k!!] = v }
        override fun remove(k: String?) = apply { removed += k!! }
        override fun clear() = apply { removed += values.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            removed.forEach { values.remove(it) }
            values.putAll(staged)
        }
    }

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(k: String?, def: String?): String? = values[k] as String? ?: def
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String?, def: MutableSet<String>?): MutableSet<String>? =
        (values[k] as Set<String>?)?.toMutableSet() ?: def
    override fun getInt(k: String?, def: Int): Int = values[k] as Int? ?: def
    override fun getLong(k: String?, def: Long): Long = values[k] as Long? ?: def
    override fun getFloat(k: String?, def: Float): Float = values[k] as Float? ?: def
    override fun getBoolean(k: String?, def: Boolean): Boolean = values[k] as Boolean? ?: def
    override fun contains(k: String?): Boolean = values.containsKey(k)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
