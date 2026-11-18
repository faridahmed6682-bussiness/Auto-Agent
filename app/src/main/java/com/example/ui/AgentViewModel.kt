package com.example.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentSettings
import com.example.agent.AgentUiState
import com.example.service.TapPilotAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GoalPreset(
    val title: String,
    val prompt: String,
    val suggestedMode: AgentExecutionMode = AgentExecutionMode.SINGLE_SESSION
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    init {
        AgentCoordinator.initialize(application.applicationContext)
    }

    val agentState: StateFlow<AgentUiState> = AgentCoordinator.uiState
    val isServiceConnected: StateFlow<Boolean> = AgentCoordinator.isServiceConnected
    val settings: StateFlow<AgentSettings> = AgentCoordinator.settings

    private val _goalText = MutableStateFlow("")
    val goalText: StateFlow<String> = _goalText.asStateFlow()

    private val _executionMode = MutableStateFlow(AgentExecutionMode.CONTINUOUS_MONITOR)
    val executionMode: StateFlow<AgentExecutionMode> = _executionMode.asStateFlow()

    private val _isAccessibilityEnabledInSettings = MutableStateFlow(
        TapPilotAccessibilityService.isServiceEnabled(application.applicationContext)
    )
    val isAccessibilityEnabledInSettings: StateFlow<Boolean> =
        _isAccessibilityEnabledInSettings.asStateFlow()

    val quickPresets: List<GoalPreset> = listOf(
        GoalPreset(
            title = "FB & Messenger Watch",
            prompt = "Open Facebook, search for Gillette Guard around 180 taka, post in a buy/sell group if needed, monitor comments and Messenger replies, negotiate to 180 taka, and pause only if payment/checkout confirmation appears.",
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "Auto-Reply Messenger",
            prompt = "Monitor incoming Messenger notifications, open new chats, reply politely about product availability and price, and continue watching.",
            suggestedMode = AgentExecutionMode.CONTINUOUS_MONITOR
        ),
        GoalPreset(
            title = "Toggle Wi-Fi",
            prompt = "Open Settings, navigate to Network & internet or Connections, and open Wi-Fi settings.",
            suggestedMode = AgentExecutionMode.SINGLE_SESSION
        ),
        GoalPreset(
            title = "Check Battery",
            prompt = "Open Settings and check current Battery percentage and usage details.",
            suggestedMode = AgentExecutionMode.SINGLE_SESSION
        )
    )

    fun onGoalTextChanged(newText: String) {
        _goalText.value = newText
    }

    fun onExecutionModeChanged(mode: AgentExecutionMode) {
        _executionMode.value = mode
    }

    fun selectPreset(preset: GoalPreset) {
        _goalText.value = preset.prompt
        _executionMode.value = preset.suggestedMode
    }

    fun refreshAccessibilityStatus() {
        _isAccessibilityEnabledInSettings.value =
            TapPilotAccessibilityService.isServiceEnabled(getApplication())
    }

    fun buildOpenAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchAgent() {
        refreshAccessibilityStatus()
        AgentCoordinator.startTask(_goalText.value, _executionMode.value)
    }

    fun pauseAgent() {
        AgentCoordinator.pauseTask()
    }

    fun resumeAgent() {
        AgentCoordinator.resumeTask()
    }

    fun stopAgent() {
        AgentCoordinator.stopTask()
    }

    fun confirmPendingAction(approved: Boolean) {
        AgentCoordinator.confirmPendingAction(approved)
    }

    fun clearTrace() {
        AgentCoordinator.dismissFinishedState()
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        AgentCoordinator.updateSettings(transform)
    }
}
