package com.example

import com.example.agent.AgentActionType
import com.example.agent.AgentStepLog
import com.example.agent.PlannedAction
import com.example.agent.SafetyGuard
import com.example.agent.ScreenBounds
import com.example.agent.ScreenNode
import com.example.agent.StepStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLogicUnitTest {

    @Test
    fun `safety guard flags payment keyword on target node`() {
        val action = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 4,
            x = 500,
            y = 1200,
            textToType = null,
            reason = "Tap confirmation button",
            requiresConfirmation = false
        )
        val node = ScreenNode(
            id = 4,
            className = "android.widget.Button",
            text = "Pay Now",
            contentDesc = "",
            viewId = "btn_pay",
            bounds = ScreenBounds(300, 1150, 700, 1250),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val result = SafetyGuard.evaluate(action, node, confirmSensitiveEnabled = true)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `safety guard skips benign navigation tap`() {
        val action = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 2,
            x = 200,
            y = 400,
            textToType = null,
            reason = "Open Wi-Fi menu",
            requiresConfirmation = false
        )
        val node = ScreenNode(
            id = 2,
            className = "android.widget.TextView",
            text = "Wi-Fi",
            contentDesc = "",
            viewId = "title",
            bounds = ScreenBounds(100, 350, 300, 450),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val result = SafetyGuard.evaluate(action, node, confirmSensitiveEnabled = true)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun `stuck loop detects three identical executed steps`() {
        val log = AgentStepLog(
            stepNumber = 1,
            timestampMs = 100L,
            actionType = AgentActionType.TAP,
            targetSummary = "#3 Settings (400,600)",
            reason = "Open Settings",
            status = StepStatus.EXECUTED,
            requiresConfirmation = false
        )
        val candidate = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 3,
            x = 400,
            y = 600,
            textToType = null,
            reason = "Open Settings",
            requiresConfirmation = false
        )

        assertTrue(SafetyGuard.isStuckLoop(listOf(log, log, log), candidate, "#3 Settings (400,600)"))
    }
}
