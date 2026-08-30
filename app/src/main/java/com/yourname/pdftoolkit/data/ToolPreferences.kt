package com.yourname.pdftoolkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * How the user has arranged the tool list: which tools they pinned, which they hid, and which
 * ones they reached for last.
 *
 * Lists are stored as ordered, separator-joined strings rather than string sets, because the
 * order is the point — pinned tools keep the order they were pinned in, recents are
 * most-recent-first.
 */
data class ToolPrefs(
    val favorites: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val recent: List<String> = emptyList()
)

object ToolPreferences {

    /** How many recently used tools to remember. */
    const val MAX_RECENT = 6

    private const val SEPARATOR = "|"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_preferences")

    private val FAVORITES_KEY = stringPreferencesKey("favorites")
    private val HIDDEN_KEY = stringPreferencesKey("hidden")
    private val RECENT_KEY = stringPreferencesKey("recent")

    fun flow(context: Context): Flow<ToolPrefs> = context.dataStore.data.map { preferences ->
        ToolPrefs(
            favorites = preferences[FAVORITES_KEY].decode(),
            hidden = preferences[HIDDEN_KEY].decode(),
            recent = preferences[RECENT_KEY].decode()
        )
    }

    /** Pin or unpin a tool. Pinning also brings it back from the hidden section. */
    suspend fun toggleFavorite(context: Context, toolId: String) {
        context.dataStore.edit { preferences ->
            val favorites = preferences[FAVORITES_KEY].decode()
            preferences[FAVORITES_KEY] = if (toolId in favorites) {
                (favorites - toolId).encode()
            } else {
                preferences[HIDDEN_KEY] = (preferences[HIDDEN_KEY].decode() - toolId).encode()
                (favorites + toolId).encode()
            }
        }
    }

    /** Move a tool into the hidden section, or bring it back. Hiding also unpins it. */
    suspend fun toggleHidden(context: Context, toolId: String) {
        context.dataStore.edit { preferences ->
            val hidden = preferences[HIDDEN_KEY].decode()
            preferences[HIDDEN_KEY] = if (toolId in hidden) {
                (hidden - toolId).encode()
            } else {
                preferences[FAVORITES_KEY] = (preferences[FAVORITES_KEY].decode() - toolId).encode()
                preferences[RECENT_KEY] = (preferences[RECENT_KEY].decode() - toolId).encode()
                (hidden + toolId).encode()
            }
        }
    }

    /** Remember that the user just opened this tool. */
    suspend fun recordUse(context: Context, toolId: String) {
        context.dataStore.edit { preferences ->
            val recent = preferences[RECENT_KEY].decode()
            preferences[RECENT_KEY] = (listOf(toolId) + (recent - toolId)).take(MAX_RECENT).encode()
        }
    }

    private fun String?.decode(): List<String> =
        this?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private fun List<String>.encode(): String = joinToString(SEPARATOR)
}
