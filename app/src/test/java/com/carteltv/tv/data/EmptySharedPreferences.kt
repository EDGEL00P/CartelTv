package com.carteltv.tv.data

import android.content.SharedPreferences

/** Read-only empty store for tests: prefs reads yield defaults, writes no-op. */
object EmptySharedPreferences : SharedPreferences {
    private val editor = object : SharedPreferences.Editor {
        override fun putString(k: String?, v: String?) = this
        override fun putStringSet(k: String?, v: MutableSet<String>?) = this
        override fun putInt(k: String?, v: Int) = this
        override fun putLong(k: String?, v: Long) = this
        override fun putFloat(k: String?, v: Float) = this
        override fun putBoolean(k: String?, v: Boolean) = this
        override fun remove(k: String?) = this
        override fun clear() = this
        override fun commit() = true
        override fun apply() {}
    }
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
    override fun getString(k: String?, def: String?) = def
    override fun getStringSet(k: String?, def: MutableSet<String>?) = def
    override fun getInt(k: String?, def: Int) = def
    override fun getLong(k: String?, def: Long) = def
    override fun getFloat(k: String?, def: Float) = def
    override fun getBoolean(k: String?, def: Boolean) = def
    override fun contains(k: String?) = false
    override fun edit(): SharedPreferences.Editor = editor
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
