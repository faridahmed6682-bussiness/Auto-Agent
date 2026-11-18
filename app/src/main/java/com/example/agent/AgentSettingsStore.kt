package com.example.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tappilot_agent_settings"
)

class AgentSettingsStore(private val context: Context) {

    private object Keys {
        val MAX_STEPS = intPreferencesKey("max_steps")
        val USE_VISION = booleanPreferencesKey("use_vision_screenshot")
        val CONFIRM_SENSITIVE = booleanPreferencesKey("confirm_sensitive_actions")
        val GO_HOME_ON_LAUNCH = booleanPreferencesKey("go_home_on_launch")
        val STEP_DELAY_MS = longPreferencesKey("step_delay_ms")
    }

    val settingsFlow: Flow<AgentSettings> = context.agentSettingsDataStore.data.map { prefs ->
        AgentSettings(
            maxSteps = (prefs[Keys.MAX_STEPS] ?: 15).coerceIn(3, 40),
            useVisionScreenshot = prefs[Keys.USE_VISION] ?: true,
            confirmSensitiveActions = prefs[Keys.CONFIRM_SENSITIVE] ?: true,
            goHomeOnLaunch = prefs[Keys.GO_HOME_ON_LAUNCH] ?: true,
            stepDelayMs = (prefs[Keys.STEP_DELAY_MS] ?: 1200L).coerceIn(400L, 4000L)
        )
    }

    suspend fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        context.agentSettingsDataStore.edit { prefs ->
            val current = AgentSettings(
                maxSteps = prefs[Keys.MAX_STEPS] ?: 15,
                useVisionScreenshot = prefs[Keys.USE_VISION] ?: true,
                confirmSensitiveActions = prefs[Keys.CONFIRM_SENSITIVE] ?: true,
                goHomeOnLaunch = prefs[Keys.GO_HOME_ON_LAUNCH] ?: true,
                stepDelayMs = prefs[Keys.STEP_DELAY_MS] ?: 1200L
            )
            val updated = transform(current)
            prefs[Keys.MAX_STEPS] = updated.maxSteps.coerceIn(3, 40)
            prefs[Keys.USE_VISION] = updated.useVisionScreenshot
            prefs[Keys.CONFIRM_SENSITIVE] = updated.confirmSensitiveActions
            prefs[Keys.GO_HOME_ON_LAUNCH] = updated.goHomeOnLaunch
            prefs[Keys.STEP_DELAY_MS] = updated.stepDelayMs.coerceIn(400L, 4000L)
        }
    }
}
