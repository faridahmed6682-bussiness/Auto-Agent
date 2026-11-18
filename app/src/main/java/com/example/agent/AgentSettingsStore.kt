package com.example.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tappilot_agent_settings"
)

class AgentSettingsStore(private val context: Context) {

    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val GOOGLE_AI_STUDIO_KEY = stringPreferencesKey("google_ai_studio_key")
        val GOOGLE_AI_STUDIO_MODEL = stringPreferencesKey("google_ai_studio_model")
        val GROK_KEY = stringPreferencesKey("grok_key")
        val GROK_MODEL = stringPreferencesKey("grok_model")
        val OPENROUTER_KEY = stringPreferencesKey("openrouter_key")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val MONITOR_INTERVAL_SEC = intPreferencesKey("monitor_interval_sec")
        val USE_VISION = booleanPreferencesKey("use_vision_screenshot")
        val SAFETY_POLICY_MODE = stringPreferencesKey("safety_policy_mode")
        val GO_HOME_ON_LAUNCH = booleanPreferencesKey("go_home_on_launch")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val STEP_DELAY_MS = longPreferencesKey("step_delay_ms")
    }

    val settingsFlow: Flow<AgentSettings> = context.agentSettingsDataStore.data.map { prefs ->
        readSettings(prefs)
    }

    suspend fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        context.agentSettingsDataStore.edit { prefs ->
            val current = readSettings(prefs)
            val updated = transform(current)
            prefs[Keys.AI_PROVIDER] = updated.aiProvider.name
            prefs[Keys.GOOGLE_AI_STUDIO_KEY] = updated.googleAiStudioApiKey.trim()
            prefs[Keys.GOOGLE_AI_STUDIO_MODEL] = updated.googleAiStudioModel.trim()
                .ifBlank { AiProvider.GOOGLE_AI_STUDIO.defaultModel }
            prefs[Keys.GROK_KEY] = updated.grokApiKey.trim()
            prefs[Keys.GROK_MODEL] = updated.grokModel.trim()
                .ifBlank { AiProvider.XAI_GROK.defaultModel }
            prefs[Keys.OPENROUTER_KEY] = updated.openRouterApiKey.trim()
            prefs[Keys.OPENROUTER_MODEL] = updated.openRouterModel.trim()
                .ifBlank { AiProvider.OPENROUTER.defaultModel }
            prefs[Keys.MAX_STEPS] = updated.maxSteps.coerceIn(5, 50)
            prefs[Keys.MONITOR_INTERVAL_SEC] = updated.monitorIntervalSec.coerceIn(10, 300)
            prefs[Keys.USE_VISION] = updated.useVisionScreenshot
            prefs[Keys.SAFETY_POLICY_MODE] = updated.safetyPolicyMode.name
            prefs[Keys.GO_HOME_ON_LAUNCH] = updated.goHomeOnLaunch
            prefs[Keys.KEEP_SCREEN_AWAKE] = updated.keepScreenAwake
            prefs[Keys.STEP_DELAY_MS] = updated.stepDelayMs.coerceIn(400L, 4000L)
        }
    }

    private fun readSettings(prefs: Preferences): AgentSettings {
        val provider = prefs[Keys.AI_PROVIDER]
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: AiProvider.FIREBASE_AI
        val safetyMode = prefs[Keys.SAFETY_POLICY_MODE]
            ?.let { runCatching { SafetyPolicyMode.valueOf(it) }.getOrNull() }
            ?: SafetyPolicyMode.PAYMENT_ONLY

        return AgentSettings(
            aiProvider = provider,
            googleAiStudioApiKey = prefs[Keys.GOOGLE_AI_STUDIO_KEY].orEmpty(),
            googleAiStudioModel = prefs[Keys.GOOGLE_AI_STUDIO_MODEL]
                ?.takeIf { it.isNotBlank() } ?: AiProvider.GOOGLE_AI_STUDIO.defaultModel,
            grokApiKey = prefs[Keys.GROK_KEY].orEmpty(),
            grokModel = prefs[Keys.GROK_MODEL]
                ?.takeIf { it.isNotBlank() } ?: AiProvider.XAI_GROK.defaultModel,
            openRouterApiKey = prefs[Keys.OPENROUTER_KEY].orEmpty(),
            openRouterModel = prefs[Keys.OPENROUTER_MODEL]
                ?.takeIf { it.isNotBlank() } ?: AiProvider.OPENROUTER.defaultModel,
            maxSteps = (prefs[Keys.MAX_STEPS] ?: 20).coerceIn(5, 50),
            monitorIntervalSec = (prefs[Keys.MONITOR_INTERVAL_SEC] ?: 30).coerceIn(10, 300),
            useVisionScreenshot = prefs[Keys.USE_VISION] ?: true,
            safetyPolicyMode = safetyMode,
            goHomeOnLaunch = prefs[Keys.GO_HOME_ON_LAUNCH] ?: true,
            keepScreenAwake = prefs[Keys.KEEP_SCREEN_AWAKE] ?: false,
            stepDelayMs = (prefs[Keys.STEP_DELAY_MS] ?: 1200L).coerceIn(400L, 4000L)
        )
    }
}
