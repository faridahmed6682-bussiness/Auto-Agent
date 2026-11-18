package com.example.agent

import android.content.Context
import android.util.Log
import com.example.ai.GeminiAiPlanner
import com.example.service.TapPilotAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

private const val TAG = "AgentCoordinator"

object AgentCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var settingsStore: AgentSettingsStore? = null
    private var settingsCollectJob: Job? = null
    private val aiPlanner = GeminiAiPlanner()

    private var serviceRef: WeakReference<TapPilotAccessibilityService>? = null
    private var activeRunJob: Job? = null
    private var pauseGate: CompletableDeferred<Unit>? = null
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _settings = MutableStateFlow(AgentSettings())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    fun initialize(context: Context) {
        if (settingsStore != null) return
        val store = AgentSettingsStore(context.applicationContext)
        settingsStore = store
        settingsCollectJob = scope.launch {
            store.settingsFlow.collect { latest ->
                _settings.value = latest
            }
        }
    }

    fun attachService(service: TapPilotAccessibilityService) {
        serviceRef = WeakReference(service)
        _isServiceConnected.value = true
        Log.d(TAG, "AccessibilityService attached.")
    }

    fun detachService(service: TapPilotAccessibilityService) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            _isServiceConnected.value = false
            stopTask("Accessibility Service disconnected.")
        }
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val store = settingsStore ?: return
        scope.launch {
            store.updateSettings(transform)
        }
    }

    fun startTask(goalInput: String) {
        val goal = goalInput.trim()
        if (goal.isEmpty()) {
            _uiState.value = AgentUiState.Error(goal = "", message = "Please enter an automation goal first.", trace = emptyList())
            return
        }

        val service = serviceRef?.get()
        if (service == null) {
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = "Accessibility Service is not enabled. Enable TapPilot in Android Accessibility Settings first.",
                trace = emptyList()
            )
            return
        }

        activeRunJob?.cancel()
        pauseGate = null
        confirmationDeferred = null

        val currentSettings = _settings.value
        val trace = mutableListOf<AgentStepLog>()

        activeRunJob = scope.launch {
            try {
                if (currentSettings.goHomeOnLaunch) {
                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        currentStep = 0,
                        maxSteps = currentSettings.maxSteps,
                        phase = AgentPhase.ACTING,
                        lastThought = "Navigating to Home screen...",
                        trace = trace.toList()
                    )
                    service.navigateHome()
                    delay(900)
                }

                for (step in 1..currentSettings.maxSteps) {
                    awaitIfPaused(goal, step, currentSettings.maxSteps, trace)

                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        currentStep = step,
                        maxSteps = currentSettings.maxSteps,
                        phase = AgentPhase.OBSERVING,
                        lastThought = trace.lastOrNull()?.reason.orEmpty(),
                        trace = trace.toList()
                    )

                    val snapshot = service.captureSnapshot(currentSettings.useVisionScreenshot)
                    val plannedAction: PlannedAction = try {
                        _uiState.value = AgentUiState.Running(
                            goal = goal,
                            currentStep = step,
                            maxSteps = currentSettings.maxSteps,
                            phase = AgentPhase.THINKING,
                            lastThought = snapshot.captureNote,
                            trace = trace.toList()
                        )

                        val planResult = withContext(Dispatchers.IO) {
                            aiPlanner.planNextAction(
                                goal = goal,
                                snapshot = snapshot,
                                recentHistory = trace,
                                stepNumber = step,
                                maxSteps = currentSettings.maxSteps
                            )
                        }

                        planResult.getOrElse { err ->
                            _uiState.value = AgentUiState.Error(
                                goal = goal,
                                message = err.localizedMessage ?: "Gemini planning failed.",
                                trace = trace.toList()
                            )
                            return@launch
                        }
                    } finally {
                        snapshot.screenshot?.recycle()
                    }

                    val targetNode = plannedAction.targetNodeId?.let { id ->
                        snapshot.nodes.firstOrNull { it.id == id }
                    }
                    val targetSummary = buildTargetSummary(plannedAction, targetNode)

                    if (plannedAction.actionType == AgentActionType.COMPLETE) {
                        trace.add(
                            AgentStepLog(
                                stepNumber = step,
                                timestampMs = System.currentTimeMillis(),
                                actionType = AgentActionType.COMPLETE,
                                targetSummary = "Goal satisfied",
                                reason = plannedAction.reason,
                                status = StepStatus.COMPLETED,
                                requiresConfirmation = false
                            )
                        )
                        _uiState.value = AgentUiState.Completed(
                            goal = goal,
                            summary = plannedAction.reason,
                            totalSteps = step,
                            trace = trace.toList()
                        )
                        return@launch
                    }

                    if (plannedAction.actionType == AgentActionType.FAIL) {
                        trace.add(
                            AgentStepLog(
                                stepNumber = step,
                                timestampMs = System.currentTimeMillis(),
                                actionType = AgentActionType.FAIL,
                                targetSummary = targetSummary,
                                reason = plannedAction.reason,
                                status = StepStatus.FAILED,
                                requiresConfirmation = false
                            )
                        )
                        _uiState.value = AgentUiState.Error(
                            goal = goal,
                            message = plannedAction.reason,
                            trace = trace.toList()
                        )
                        return@launch
                    }

                    if (SafetyGuard.isStuckLoop(trace, plannedAction, targetSummary)) {
                        _uiState.value = AgentUiState.Error(
                            goal = goal,
                            message = "Stopped to prevent an infinite loop repeating: $targetSummary",
                            trace = trace.toList()
                        )
                        return@launch
                    }

                    val safetyCheck = SafetyGuard.evaluate(
                        action = plannedAction,
                        targetNode = targetNode,
                        confirmSensitiveEnabled = currentSettings.confirmSensitiveActions
                    )

                    var finalStatus = StepStatus.EXECUTED
                    if (safetyCheck.needsConfirmation) {
                        val deferred = CompletableDeferred<Boolean>()
                        confirmationDeferred = deferred
                        _uiState.value = AgentUiState.AwaitingConfirmation(
                            goal = goal,
                            currentStep = step,
                            maxSteps = currentSettings.maxSteps,
                            pendingAction = plannedAction,
                            targetDescription = targetSummary,
                            safetyReason = safetyCheck.reason,
                            trace = trace.toList()
                        )
                        val approved = deferred.await()
                        confirmationDeferred = null
                        if (!approved) {
                            trace.add(
                                AgentStepLog(
                                    stepNumber = step,
                                    timestampMs = System.currentTimeMillis(),
                                    actionType = plannedAction.actionType,
                                    targetSummary = targetSummary,
                                    reason = plannedAction.reason,
                                    status = StepStatus.REJECTED,
                                    requiresConfirmation = true
                                )
                            )
                            _uiState.value = AgentUiState.Completed(
                                goal = goal,
                                summary = "Stopped safely: sensitive action cancelled by user.",
                                totalSteps = step,
                                trace = trace.toList()
                            )
                            return@launch
                        }
                        finalStatus = StepStatus.APPROVED
                    }

                    awaitIfPaused(goal, step, currentSettings.maxSteps, trace)

                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        currentStep = step,
                        maxSteps = currentSettings.maxSteps,
                        phase = AgentPhase.ACTING,
                        lastThought = plannedAction.reason,
                        trace = trace.toList()
                    )

                    val execResult = service.executeAction(plannedAction, snapshot)
                    trace.add(
                        AgentStepLog(
                            stepNumber = step,
                            timestampMs = System.currentTimeMillis(),
                            actionType = plannedAction.actionType,
                            targetSummary = execResult.summary.ifBlank { targetSummary },
                            reason = plannedAction.reason,
                            status = if (execResult.success) finalStatus else StepStatus.FAILED,
                            requiresConfirmation = safetyCheck.needsConfirmation
                        )
                    )

                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        currentStep = step,
                        maxSteps = currentSettings.maxSteps,
                        phase = AgentPhase.WAITING_DELAY,
                        lastThought = plannedAction.reason,
                        trace = trace.toList()
                    )
                    delay(currentSettings.stepDelayMs)
                }

                _uiState.value = AgentUiState.Completed(
                    goal = goal,
                    summary = "Reached maximum step limit (${currentSettings.maxSteps}) without explicit completion.",
                    totalSteps = currentSettings.maxSteps,
                    trace = trace.toList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected automation error", e)
                _uiState.value = AgentUiState.Error(
                    goal = goal,
                    message = e.localizedMessage ?: "Unexpected automation failure.",
                    trace = trace.toList()
                )
            }
        }
    }

    fun pauseTask() {
        val current = _uiState.value
        if (current is AgentUiState.Running && pauseGate == null) {
            pauseGate = CompletableDeferred()
            _uiState.value = AgentUiState.Paused(
                goal = current.goal,
                currentStep = current.currentStep,
                maxSteps = current.maxSteps,
                trace = current.trace
            )
        }
    }

    fun resumeTask() {
        pauseGate?.complete(Unit)
        pauseGate = null
    }

    fun confirmPendingAction(approved: Boolean) {
        confirmationDeferred?.complete(approved)
    }

    fun stopTask(reason: String? = null) {
        activeRunJob?.cancel()
        activeRunJob = null
        pauseGate?.cancel()
        pauseGate = null
        confirmationDeferred?.cancel()
        confirmationDeferred = null

        val currentTrace = extractTrace(_uiState.value)
        val currentGoal = extractGoal(_uiState.value)
        _uiState.value = if (reason != null) {
            AgentUiState.Error(goal = currentGoal, message = reason, trace = currentTrace)
        } else if (currentTrace.isNotEmpty()) {
            AgentUiState.Completed(
                goal = currentGoal,
                summary = "Automation stopped by user.",
                totalSteps = currentTrace.size,
                trace = currentTrace
            )
        } else {
            AgentUiState.Idle
        }
    }

    fun dismissFinishedState() {
        val current = _uiState.value
        if (current is AgentUiState.Completed || current is AgentUiState.Error) {
            _uiState.value = AgentUiState.Idle
        }
    }

    private suspend fun awaitIfPaused(
        goal: String,
        step: Int,
        maxSteps: Int,
        trace: List<AgentStepLog>
    ) {
        val gate = pauseGate ?: return
        _uiState.value = AgentUiState.Paused(
            goal = goal,
            currentStep = step,
            maxSteps = maxSteps,
            trace = trace.toList()
        )
        gate.await()
    }

    private fun buildTargetSummary(action: PlannedAction, node: ScreenNode?): String {
        return when {
            node != null -> "#${node.id} ${node.displayLabel()} (${node.bounds.centerX},${node.bounds.centerY})"
            action.x != null && action.y != null -> "(${action.x}, ${action.y})"
            !action.textToType.isNullOrBlank() -> "\"${action.textToType}\""
            else -> action.actionType.name
        }
    }

    private fun extractTrace(state: AgentUiState): List<AgentStepLog> = when (state) {
        is AgentUiState.Idle -> emptyList()
        is AgentUiState.Running -> state.trace
        is AgentUiState.Paused -> state.trace
        is AgentUiState.AwaitingConfirmation -> state.trace
        is AgentUiState.Completed -> state.trace
        is AgentUiState.Error -> state.trace
    }

    private fun extractGoal(state: AgentUiState): String = when (state) {
        is AgentUiState.Idle -> ""
        is AgentUiState.Running -> state.goal
        is AgentUiState.Paused -> state.goal
        is AgentUiState.AwaitingConfirmation -> state.goal
        is AgentUiState.Completed -> state.goal
        is AgentUiState.Error -> state.goal
    }
}
