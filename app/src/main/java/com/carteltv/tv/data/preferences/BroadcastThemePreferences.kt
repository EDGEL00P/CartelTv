package com.carteltv.tv.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.broadcastThemeDataStore by preferencesDataStore(name = "broadcast_theme")

object BroadcastThemePreferences {
    private val selectedThemeId = stringPreferencesKey("selected_theme_id")

    fun selectedId(context: Context): Flow<String> =
        context.applicationContext.broadcastThemeDataStore.data.map { preferences ->
            preferences[selectedThemeId] ?: "espn"
        }

    suspend fun setSelectedId(context: Context, id: String) {
        context.applicationContext.broadcastThemeDataStore.edit { preferences ->
            preferences[selectedThemeId] = id
        }
    }
}