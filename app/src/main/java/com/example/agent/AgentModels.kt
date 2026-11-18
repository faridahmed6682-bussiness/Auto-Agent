package com.example.agent

import android.graphics.Bitmap

enum class AiProvider(
    val displayName: String,
    val shortBadge: String,
    val defaultModel: String,
    val requiresApiKey: Boolean
) {
    FIREBASE_AI(
        displayName = "Firebase AI (Built-in)",
        shortBadge = "Firebase Gemini",
        defaultModel = "gemini-3.8-flash",
        requiresApiKey = false
    ),
    GOOGLE_AI_STUDIO(
        displayName = "Google AI Studio",
        shortBadge = "AI Studio Key",
        defaultModel = "gemini-2.5-flash",
        requiresApiKey = true
    ),
    XAI_GROK(
        displayName = "Grok (xAI)",
        shortBadge = "Grok API",
        defaultModel = "grok-2-vision-1212",
        requiresApiKey = true
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        shortBadge = "OpenRouter",
        defaultModel = "google/gemini-2.0-flash-exp:free",
        requiresApiKey = true
    )
}

enum class AgentExecutionMode {
    SINGLE_SESSION,
    CONTINUOUS_MONITOR
}

enum class SafetyPolicyMode {
    PAYMENT_ONLY,
    STRICT_ALL
}

enum class AgentActionType {
    TAP,
    INPUT_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    BACK,
    HOME,
    WAIT,
    COMPLETE,
    FAIL;

    companion object {
        fun fromWire(value: String): AgentActionType {
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: FAIL
        }
    }
}

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun toCompactString(): String = "[$left,$top,$right,$bottom]"
}

data class ScreenNode(
    val id: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val bounds: ScreenBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean
) {
    fun displayLabel(): String {
        return when {
            text.isNotBlank() -> text
            contentDesc.isNotBlank() -> contentDesc
            viewId.isNotBlank() -> viewId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }
    }
}

data class UiSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<ScreenNode>,
    val screenshot: Bitmap?,
    val captureNote: String
)

data class NotificationSignal(
    val packageName: String,
    val text: String,
    val timestampMs: Long
)

data class PlannedAction(
    val actionType: AgentActionType,
    val targetNodeId: Int?,
    val x: Int?,
    val y: Int?,
    val textToType: String?,
    val waitSeconds: Int?,
    val memoryNote: String?,
    val reason: String,
    val requiresConfirmation: Boolean
)

enum class StepStatus {
    EXECUTED,
    WAITING,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED
}

data class AgentStepLog(
    val stepNumber: Int,
    val timestampMs: Long,
    val actionType: AgentActionType,
    val targetSummary: String,
    val reason: String,
    val status: StepStatus,
    val requiresConfirmation: Boolean
)

enum class AgentPhase(val label: String) {
    OBSERVING("Observing screen & UI tree..."),
    THINKING("AI reasoning next action..."),
    ACTING("Dispatching accessibility gesture..."),
    WAITING_DELAY("Waiting for UI transition..."),
    MONITOR_SLEEPING("Monitoring in background (wakes on notification)...")
}

sealed interface AgentUiState {
    data object Idle : AgentUiState

    data class Running(
        val goal: String,
        val mode: AgentExecutionMode,
        val currentStep: Int,
        val maxSteps: Int?,
        val phase: AgentPhase,
        val lastThought: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Paused(
        val goal: String,
        val mode: AgentExecutionMode,
        val currentStep: Int,
        val maxSteps: Int?,
        val pauseReason: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class AwaitingConfirmation(
        val goal: String,
        val mode: AgentExecutionMode,
        val currentStep: Int,
        val maxSteps: Int?,
        val pendingAction: PlannedAction,
        val targetDescription: String,
        val safetyReason: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Completed(
        val goal: String,
        val summary: String,
        val totalSteps: Int,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Error(
        val goal: String,
        val message: String,
        val workingMemory: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState
}

data class AgentSettings(
    val aiProvider: AiProvider = AiProvider.FIREBASE_AI,
    val googleAiStudioApiKey: String = "",
    val googleAiStudioModel: String = AiProvider.GOOGLE_AI_STUDIO.defaultModel,
    val grokApiKey: String = "",
    val grokModel: String = AiProvider.XAI_GROK.defaultModel,
    val openRouterApiKey: String = "",
    val openRouterModel: String = AiProvider.OPENROUTER.defaultModel,
    val maxSteps: Int = 20,
    val monitorIntervalSec: Int = 30,
    val useVisionScreenshot: Boolean = true,
    val safetyPolicyMode: SafetyPolicyMode = SafetyPolicyMode.PAYMENT_ONLY,
    val goHomeOnLaunch: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val stepDelayMs: Long = 1200L
) {
    fun activeApiKey(): String = when (aiProvider) {
        AiProvider.FIREBASE_AI -> ""
        AiProvider.GOOGLE_AI_STUDIO -> googleAiStudioApiKey.trim()
        AiProvider.XAI_GROK -> grokApiKey.trim()
        AiProvider.OPENROUTER -> openRouterApiKey.trim()
    }

    fun activeModelName(): String = when (aiProvider) {
        AiProvider.FIREBASE_AI -> AiProvider.FIREBASE_AI.defaultModel
        AiProvider.GOOGLE_AI_STUDIO -> googleAiStudioModel.trim().ifBlank { AiProvider.GOOGLE_AI_STUDIO.defaultModel }
        AiProvider.XAI_GROK -> grokModel.trim().ifBlank { AiProvider.XAI_GROK.defaultModel }
        AiProvider.OPENROUTER -> openRouterModel.trim().ifBlank { AiProvider.OPENROUTER.defaultModel }
    }
}
