package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.AgentActionType
import com.example.agent.PlannedAction
import com.example.agent.ScreenNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "GestureExecutor"

object GestureExecutor {

    data class ExecutionResult(
        val success: Boolean,
        val summary: String
    )

    suspend fun execute(
        service: AccessibilityService,
        action: PlannedAction,
        nodes: List<ScreenNode>,
        screenWidth: Int,
        screenHeight: Int
    ): ExecutionResult {
        val targetNode = action.targetNodeId?.let { id -> nodes.firstOrNull { it.id == id } }

        return when (action.actionType) {
            AgentActionType.TAP -> {
                val tapX = (action.x ?: targetNode?.bounds?.centerX)?.coerceIn(1, screenWidth - 1)
                val tapY = (action.y ?: targetNode?.bounds?.centerY)?.coerceIn(1, screenHeight - 1)

                if (tapX == null || tapY == null) {
                    return ExecutionResult(false, "No valid coordinates or node bounds for TAP.")
                }

                val gestureOk = dispatchTap(service, tapX.toFloat(), tapY.toFloat())
                if (gestureOk) {
                    ExecutionResult(true, "Tapped ($tapX, $tapY) ${targetNode?.displayLabel()?.let { "[$it]" } ?: ""}".trim())
                } else if (targetNode != null) {
                    val root = service.rootInActiveWindow
                    val liveNode = NodeTreeParser.findAccessibilityNodeByBounds(
                        root,
                        targetNode.bounds,
                        screenWidth,
                        screenHeight
                    )
                    val clicked = liveNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    ExecutionResult(clicked, if (clicked) "Clicked node #${targetNode.id}" else "Tap failed at ($tapX, $tapY)")
                } else {
                    ExecutionResult(false, "Gesture dispatch rejected at ($tapX, $tapY)")
                }
            }

            AgentActionType.INPUT_TEXT -> {
                val text = action.textToType.orEmpty()
                if (text.isBlank()) {
                    return ExecutionResult(false, "INPUT_TEXT requested with empty text.")
                }
                val root = service.rootInActiveWindow
                var liveNode = targetNode?.let {
                    NodeTreeParser.findAccessibilityNodeByBounds(root, it.bounds, screenWidth, screenHeight)
                }
                if (liveNode == null) {
                    liveNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }
                if (liveNode == null && action.x != null && action.y != null) {
                    dispatchTap(service, action.x.toFloat(), action.y.toFloat())
                    delay(250)
                    liveNode = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }

                if (liveNode != null) {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    val ok = liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    ExecutionResult(ok, if (ok) "Entered \"$text\"" else "ACTION_SET_TEXT rejected by field")
                } else {
                    ExecutionResult(false, "No editable node found to input text.")
                }
            }

            AgentActionType.SCROLL_DOWN -> {
                val cx = (screenWidth * 0.5f)
                val startY = (screenHeight * 0.72f)
                val endY = (screenHeight * 0.28f)
                val ok = dispatchSwipe(service, cx, startY, cx, endY)
                ExecutionResult(ok, if (ok) "Scrolled down" else "Scroll down failed")
            }

            AgentActionType.SCROLL_UP -> {
                val cx = (screenWidth * 0.5f)
                val startY = (screenHeight * 0.28f)
                val endY = (screenHeight * 0.72f)
                val ok = dispatchSwipe(service, cx, startY, cx, endY)
                ExecutionResult(ok, if (ok) "Scrolled up" else "Scroll up failed")
            }

            AgentActionType.BACK -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ExecutionResult(ok, "Pressed Back")
            }

            AgentActionType.HOME -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                ExecutionResult(ok, "Navigated Home")
            }

            AgentActionType.WAIT -> {
                delay(1500)
                ExecutionResult(true, "Waited for screen update")
            }

            AgentActionType.COMPLETE -> ExecutionResult(true, action.reason)
            AgentActionType.FAIL -> ExecutionResult(false, action.reason)
        }
    }

    private suspend fun dispatchTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(service, gesture)
    }

    private suspend fun dispatchSwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 380L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(service, gesture)
    }

    private suspend fun dispatchAndAwait(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean {
        return withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture cancelled by system")
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }
}
