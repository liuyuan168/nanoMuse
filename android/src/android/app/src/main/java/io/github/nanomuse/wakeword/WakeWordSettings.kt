package io.github.nanomuse.wakeword

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * nanoMuse: persistence for the voice wake-word toggle (Settings › Background).
 * A dedicated DataStore file keeps this self-contained — upstream's
 * BackgroundSettingsRepository stays untouched.
 */
private val Context.wakeWordDataStore by preferencesDataStore(name = "nanomuse_wake_word")

class WakeWordSettings(private val context: Context) {

    val enabled: Flow<Boolean> =
        context.wakeWordDataStore.data.map { prefs -> prefs[KEY_ENABLED] ?: false }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.wakeWordDataStore.edit { prefs -> prefs[KEY_ENABLED] = value }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")

        /** The phrase the spotter listens for. Changing it means editing the
         *  packaged keywords asset (pinyin tokens) too — they move together. */
        const val DEFAULT_PHRASE = "小刘同学"
    }
}
