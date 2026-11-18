package com.example.agent

import android.content.Context
import android.util.Log
import com.example.ai.GeminiAiPlanner
import com.example.service.AgentKeepAliveService
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
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

private const val TAG = "AgentCoordinator"
private const val MAX_TRACE_ENTRIES = 80
private const val MAX_NOTIF_BUFFER = 10

object AgentCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var settingsStore: AgentSettingsStore? = null
    private var settingsCollectJob: Job? = null
    private val aiPlanner = GeminiAiPlanner()

    private var serviceRef: WeakReference<TapPilotAccessibilityService>? = null
    private var activeRunJob: Job? = null
    private var pauseGate: CompletableDeferred<Unit>? = null
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private var notificationWakeSignal: CompletableDeferred<Unit>? = null

    private val recentNotifications = ArrayDeque<NotificationSignal>()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _settings = MutableStateFlow(AgentSettings())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    fun initialize(context: Context) {
        if (settingsStore != null) return
        val app = context.applicationContext
        appContext = app
        val store = AgentSettingsStore(app)
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

    fun onNotificationReceived(signal: NotificationSignal) {
        synchronized(recentNotifications) {
            if (recentNotifications.size >= MAX_NOTIF_BUFFER) {
                recentNotifications.removeFirst()
            }
            recentNotifications.addLast(signal)
        }
        // Wake up background monitor sleep early so it can inspect new message/comment!
        notificationWakeSignal?.complete(Unit)
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val store = settingsStore ?: return
        scope.launch {
            store.updateSettings(transform)
        }
    }

    fun startTask(
        goalInput: String,
        mode: AgentExecutionMode = AgentExecutionMode.SINGLE_SESSION
    ) {
        val goal = goalInput.trim()
        if (goal.isEmpty()) {
            _uiState.value = AgentUiState.Error(
                goal = "",
                message = "Please enter an automation goal first.",
                workingMemory = "",
                trace = emptyList()
            )
            return
        }

        val service = serviceRef?.get()
        if (service == null) {
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = "Accessibility Service is not enabled. Enable TapPilot in Android Accessibility Settings first.",
                workingMemory = "",
                trace = emptyList()
            )
            return
        }

        val initialSettings = _settings.value
        if (initialSettings.aiProvider.requiresApiKey && initialSettings.activeApiKey().isBlank()) {
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = "Missing API key for ${initialSettings.aiProvider.displayName}. Open Settings to enter your key.",
                workingMemory = "",
                trace = emptyList()
            )
            return
        }

        activeRunJob?.cancel()
        pauseGate = null
        confirmationDeferred = null
        notificationWakeSignal = null
        synchronized(recentNotifications) {
            recentNotifications.clear()
        }

        val trace = mutableListOf<AgentStepLog>()
        var workingMemory = ""
        val maxStepsLimit: Int? = if (mode == AgentExecutionMode.SINGLE_SESSION) {
            initialSettings.maxSteps
        } else {
            null
        }

        if (mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
            appContext?.let { AgentKeepAliveService.start(it) }
        }

        activeRunJob = scope.launch {
            try {
                if (initialSettings.goHomeOnLaunch) {
                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        mode = mode,
                        currentStep = 0,
                        maxSteps = maxStepsLimit,
                        phase = AgentPhase.ACTING,
                        lastThought = "Navigating to Home screen...",
                        workingMemory = workingMemory,
                        trace = trace.toList()
                    )
                    service.navigateHome()
                    delay(900)
                }

                var step = 1
                while (maxStepsLimit == null || step <= maxStepsLimit) {
                    val currentSettings = _settings.value
                    awaitIfPaused(goal, mode, step, maxStepsLimit, workingMemory, trace)

                    // Wait cleanly if device screen is currently locked during continuous monitoring
                    while (service.isDeviceLocked()) {
                        _uiState.value = AgentUiState.Running(
                            goal = goal,
                            mode = mode,
                            currentStep = step,
                            maxSteps = maxStepsLimit,
                            phase = AgentPhase.MONITOR_SLEEPING,
                            lastThought = "Screen locked — waiting for device unlock...",
                            workingMemory = workingMemory,
                            trace = trace.toList()
                        )
                        delay(4_000L)
                        awaitIfPaused(goal, mode, step, maxStepsLimit, workingMemory, trace)
                    }

                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        mode = mode,
                        currentStep = step,
                        maxSteps = maxStepsLimit,
                        phase = AgentPhase.OBSERVING,
                        lastThought = trace.lastOrNull()?.reason.orEmpty(),
                        workingMemory = workingMemory,
                        trace = trace.toList()
                    )

                    val snapshot = service.captureSnapshot(currentSettings.useVisionScreenshot)
                    val notificationsSnapshot = synchronized(recentNotifications) {
                        recentNotifications.toList()
                    }

                    val plannedAction: PlannedAction = try {
                        _uiState.value = AgentUiState.Running(
                            goal = goal,
                            mode = mode,
                            currentStep = step,
                            maxSteps = maxStepsLimit,
                            phase = AgentPhase.THINKING,
                            lastThought = snapshot.captureNote,
                            workingMemory = workingMemory,
                            trace = trace.toList()
                        )

                        val planResult = withContext(Dispatchers.IO) {
                            aiPlanner.planNextAction(
                                goal = goal,
                                mode = mode,
                                settings = currentSettings,
                                snapshot = snapshot,
                                recentHistory = trace,
                                recentNotifications = notificationsSnapshot,
                                workingMemory = workingMemory,
                                stepNumber = step,
                                maxSteps = maxStepsLimit
                            )
                        }

                        planResult.getOrElse { err ->
                            if (mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                                // Recoverable network/transient hiccup in all-day monitor: wait and retry
                                appendTrace(
                                    trace,
                                    AgentStepLog(
                                        stepNumber = step,
                                        timestampMs = System.currentTimeMillis(),
                                        actionType = AgentActionType.WAIT,
                                        targetSummary = "Transient AI error",
                                        reason = err.localizedMessage ?: "Retrying after monitor interval...",
                                        status = StepStatus.WAITING,
                                        requiresConfirmation = false
                                    )
                                )
                                sleepMonitorInterval(
                                    goal = goal,
                                    mode = mode,
                                    step = step,
                                    maxSteps = maxStepsLimit,
                                    waitSec = currentSettings.monitorIntervalSec,
                                    reason = "Retrying after transient error: ${err.localizedMessage ?: "network"}",
                                    workingMemory = workingMemory,
                                    trace = trace
                                )
                                step++
                                continue
                            } else {
                                _uiState.value = AgentUiState.Error(
                                    goal = goal,
                                    message = err.localizedMessage ?: "Gemini planning failed.",
                                    workingMemory = workingMemory,
                                    trace = trace.toList()
                                )
                                return@launch
                            }
                        }
                    } finally {
                        snapshot.screenshot?.recycle()
                    }

                    if (!plannedAction.memoryNote.isNullOrBlank()) {
                        workingMemory = plannedAction.memoryNote
                    }

                    val targetNode = plannedAction.targetNodeId?.let { id ->
                        snapshot.nodes.firstOrNull { it.id == id }
                    }
                    val targetSummary = buildTargetSummary(plannedAction, targetNode)

                    if (plannedAction.actionType == AgentActionType.COMPLETE) {
                        appendTrace(
                            trace,
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
                            workingMemory = workingMemory,
                            trace = trace.toList()
                        )
                        return@launch
                    }

                    if (plannedAction.actionType == AgentActionType.FAIL) {
                        appendTrace(
                            trace,
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
                            workingMemory = workingMemory,
                            trace = trace.toList()
                        )
                        return@launch
                    }

                    if (plannedAction.actionType == AgentActionType.WAIT) {
                        val waitSec = plannedAction.waitSeconds
                            ?: if (mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                                currentSettings.monitorIntervalSec
                            } else {
                                3
                            }
                        appendTrace(
                            trace,
                            AgentStepLog(
                                stepNumber = step,
                                timestampMs = System.currentTimeMillis(),
                                actionType = AgentActionType.WAIT,
                                targetSummary = "Wait ${waitSec}s (wakes on notification)",
                                reason = plannedAction.reason,
                                status = StepStatus.WAITING,
                                requiresConfirmation = false
                            )
                        )
                        sleepMonitorInterval(
                            goal = goal,
                            mode = mode,
                            step = step,
                            maxSteps = maxStepsLimit,
                            waitSec = waitSec,
                            reason = plannedAction.reason,
                            workingMemory = workingMemory,
                            trace = trace
                        )
                        step++
                        continue
                    }

                    if (SafetyGuard.isStuckLoop(trace, plannedAction, targetSummary)) {
                        _uiState.value = AgentUiState.Error(
                            goal = goal,
                            message = "Stopped to prevent an infinite loop repeating: $targetSummary",
                            workingMemory = workingMemory,
                            trace = trace.toList()
                        )
                        return@launch
                    }

                    val safetyCheck = SafetyGuard.evaluate(
                        action = plannedAction,
                        targetNode = targetNode,
                        policyMode = currentSettings.safetyPolicyMode
                    )

                    var finalStatus = StepStatus.EXECUTED
                    if (safetyCheck.needsConfirmation) {
                        val deferred = CompletableDeferred<Boolean>()
                        confirmationDeferred = deferred
                        _uiState.value = AgentUiState.AwaitingConfirmation(
                            goal = goal,
                            mode = mode,
                            currentStep = step,
                            maxSteps = maxStepsLimit,
                            pendingAction = plannedAction,
                            targetDescription = targetSummary,
                            safetyReason = safetyCheck.reason,
                            workingMemory = workingMemory,
                            trace = trace.toList()
                        )
                        val approved = deferred.await()
                        confirmationDeferred = null
                        if (!approved) {
                            appendTrace(
                                trace,
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
                                summary = "Stopped safely: payment/sensitive action declined by user.",
                                totalSteps = step,
                                workingMemory = workingMemory,
                                trace = trace.toList()
                            )
                            return@launch
                        }
                        finalStatus = StepStatus.APPROVED
                    }

                    awaitIfPaused(goal, mode, step, maxStepsLimit, workingMemory, trace)

                    _uiState.value = AgentUiState.Running(
                        goal = goal,
                        mode = mode,
                        currentStep = step,
                        maxSteps = maxStepsLimit,
                        phase = AgentPhase.ACTING,
                        lastThought = plannedAction.reason,
                        workingMemory = workingMemory,
                        trace = trace.toList()
                    )

                    val execResult = service.executeAction(plannedAction, snapshot)
                    appendTrace(
                        trace,
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
                        mode = mode,
                        currentStep = step,
                        maxSteps = maxStepsLimit,
                        phase = AgentPhase.WAITING_DELAY,
                        lastThought = plannedAction.reason,
                        workingMemory = workingMemory,
                        trace = trace.toList()
                    )
                    delay(currentSettings.stepDelayMs)
                    step++
                }

                _uiState.value = AgentUiState.Completed(
                    goal = goal,
                    summary = "Reached single-task step limit (${maxStepsLimit ?: 0}) without explicit completion.",
                    totalSteps = maxStepsLimit ?: trace.size,
                    workingMemory = workingMemory,
                    trace = trace.toList()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected automation error", e)
                _uiState.value = AgentUiState.Error(
                    goal = goal,
                    message = e.localizedMessage ?: "Unexpected automation failure.",
                    workingMemory = workingMemory,
                    trace = trace.toList()
                )
            } finally {
                notificationWakeSignal = null
                appContext?.let { AgentKeepAliveService.stop(it) }
            }
        }
    }

    fun pauseTask(reason: String = "Automation paused. Tap Resume or Stop.") {
        val current = _uiState.value
        if (current is AgentUiState.Running && pauseGate == null) {
            pauseGate = CompletableDeferred()
            _uiState.value = AgentUiState.Paused(
                goal = current.goal,
                mode = current.mode,
                currentStep = current.currentStep,
                maxSteps = current.maxSteps,
                pauseReason = reason,
                workingMemory = current.workingMemory,
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
        notificationWakeSignal?.cancel()
        notificationWakeSignal = null
        appContext?.let { AgentKeepAliveService.stop(it) }

        val currentTrace = extractTrace(_uiState.value)
        val currentGoal = extractGoal(_uiState.value)
        val currentMemory = extractMemory(_uiState.value)
        _uiState.value = if (reason != null) {
            AgentUiState.Error(
                goal = currentGoal,
                message = reason,
                workingMemory = currentMemory,
                trace = currentTrace
            )
        } else if (currentTrace.isNotEmpty()) {
            AgentUiState.Completed(
                goal = currentGoal,
                summary = "Automation stopped by user.",
                totalSteps = currentTrace.size,
                workingMemory = currentMemory,
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

    private suspend fun sleepMonitorInterval(
        goal: String,
        mode: AgentExecutionMode,
        step: Int,
        maxSteps: Int?,
        waitSec: Int,
        reason: String,
        workingMemory: String,
        trace: List<AgentStepLog>
    ) {
        val wakeSignal = CompletableDeferred<Unit>()
        notificationWakeSignal = wakeSignal
        _uiState.value = AgentUiState.Running(
            goal = goal,
            mode = mode,
            currentStep = step,
            maxSteps = maxSteps,
            phase = AgentPhase.MONITOR_SLEEPING,
            lastThought = "Waiting up to ${waitSec}s • $reason",
            workingMemory = workingMemory,
            trace = trace.toList()
        )
        try {
            withTimeoutOrNull(waitSec * 1000L) {
                wakeSignal.await()
            }
        } finally {
            if (notificationWakeSignal === wakeSignal) {
                notificationWakeSignal = null
            }
        }
    }

    private suspend fun awaitIfPaused(
        goal: String,
        mode: AgentExecutionMode,
        step: Int,
        maxSteps: Int?,
        workingMemory: String,
        trace: List<AgentStepLog>
    ) {
        val gate = pauseGate ?: return
        _uiState.value = AgentUiState.Paused(
            goal = goal,
            mode = mode,
            currentStep = step,
            maxSteps = maxSteps,
            pauseReason = "Automation paused. Tap Resume or Stop.",
            workingMemory = workingMemory,
            trace = trace.toList()
        )
        gate.await()
    }

    private fun appendTrace(trace: MutableList<AgentStepLog>, entry: AgentStepLog) {
        if (trace.size >= MAX_TRACE_ENTRIES) {
            trace.removeAt(0)
        }
        trace.add(entry)
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

    private fun extractMemory(state: AgentUiState): String = when (state) {
        is AgentUiState.Idle -> ""
        is AgentUiState.Running -> state.workingMemory
        is AgentUiState.Paused -> state.workingMemory
        is AgentUiState.AwaitingConfirmation -> state.workingMemory
        is AgentUiState.Completed -> state.workingMemory
        is AgentUiState.Error -> state.workingMemory
    }
}
