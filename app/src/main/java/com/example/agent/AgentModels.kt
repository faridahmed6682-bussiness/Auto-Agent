package com.example.agent

import android.graphics.Bitmap

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

data class PlannedAction(
    val actionType: AgentActionType,
    val targetNodeId: Int?,
    val x: Int?,
    val y: Int?,
    val textToType: String?,
    val reason: String,
    val requiresConfirmation: Boolean
)

enum class StepStatus {
    EXECUTED,
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
    THINKING("Gemini reasoning next action..."),
    ACTING("Dispatching accessibility gesture..."),
    WAITING_DELAY("Waiting for UI transition...")
}

sealed interface AgentUiState {
    data object Idle : AgentUiState

    data class Running(
        val goal: String,
        val currentStep: Int,
        val maxSteps: Int,
        val phase: AgentPhase,
        val lastThought: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Paused(
        val goal: String,
        val currentStep: Int,
        val maxSteps: Int,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class AwaitingConfirmation(
        val goal: String,
        val currentStep: Int,
        val maxSteps: Int,
        val pendingAction: PlannedAction,
        val targetDescription: String,
        val safetyReason: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Completed(
        val goal: String,
        val summary: String,
        val totalSteps: Int,
        val trace: List<AgentStepLog>
    ) : AgentUiState

    data class Error(
        val goal: String,
        val message: String,
        val trace: List<AgentStepLog>
    ) : AgentUiState
}

data class AgentSettings(
    val maxSteps: Int = 15,
    val useVisionScreenshot: Boolean = true,
    val confirmSensitiveActions: Boolean = true,
    val goHomeOnLaunch: Boolean = true,
    val stepDelayMs: Long = 1200L
)
