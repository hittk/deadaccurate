package com.deadaccurate.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * FR-7 settings persistence. Takes the DataStore directly (not a Context)
 * so JVM unit tests can run against a temp-file store.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            gateTrimDb = prefs[KEY_GATE_TRIM] ?: 0f,
            bphOverride = prefs[KEY_BPH_OVERRIDE]?.takeIf { it > 0 },
            inputPreference = prefs[KEY_INPUT_PREFERENCE]
                ?.let { stored -> InputPreference.entries.find { it.name == stored } }
                ?: InputPreference.AUTO,
            onboardingDismissed = prefs[KEY_ONBOARDING_DISMISSED] ?: false,
        )
    }

    suspend fun setGateTrimDb(trimDb: Float) {
        dataStore.edit { it[KEY_GATE_TRIM] = trimDb }
    }

    suspend fun setBphOverride(bph: Int?) {
        dataStore.edit {
            if (bph == null) it.remove(KEY_BPH_OVERRIDE) else it[KEY_BPH_OVERRIDE] = bph
        }
    }

    suspend fun setInputPreference(preference: InputPreference) {
        dataStore.edit { it[KEY_INPUT_PREFERENCE] = preference.name }
    }

    suspend fun setOnboardingDismissed(dismissed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_DISMISSED] = dismissed }
    }

    private companion object {
        val KEY_GATE_TRIM = floatPreferencesKey("gate_trim_db")
        val KEY_BPH_OVERRIDE = intPreferencesKey("bph_override")
        val KEY_INPUT_PREFERENCE = stringPreferencesKey("input_preference")
        val KEY_ONBOARDING_DISMISSED = booleanPreferencesKey("onboarding_dismissed")
    }
}
