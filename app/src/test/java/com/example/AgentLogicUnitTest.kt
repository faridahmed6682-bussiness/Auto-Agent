package com.example

import com.example.agent.AgentActionType
import com.example.agent.AgentSettings
import com.example.agent.AgentStepLog
import com.example.agent.AiProvider
import com.example.agent.PlannedAction
import com.example.agent.SafetyGuard
import com.example.agent.SafetyPolicyMode
import com.example.agent.ScreenBounds
import com.example.agent.ScreenNode
import com.example.agent.StepStatus
import com.example.ai.RestAiClients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLogicUnitTest {

    @Test
    fun `payment only mode flags payment keyword on target node`() {
        val action = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 4,
            x = 500,
            y = 1200,
            textToType = null,
            waitSeconds = null,
            memoryNote = null,
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

        val result = SafetyGuard.evaluate(action, node, SafetyPolicyMode.PAYMENT_ONLY)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `payment only mode allows facebook post and messenger send autonomously`() {
        val action = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 6,
            x = 900,
            y = 1800,
            textToType = null,
            waitSeconds = null,
            memoryNote = "Posted in group",
            reason = "Tap Send button to reply on Messenger",
            requiresConfirmation = false
        )
        val node = ScreenNode(
            id = 6,
            className = "android.widget.ImageButton",
            text = "Send",
            contentDesc = "Send message",
            viewId = "btn_send",
            bounds = ScreenBounds(850, 1750, 950, 1850),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val paymentOnlyResult = SafetyGuard.evaluate(action, node, SafetyPolicyMode.PAYMENT_ONLY)
        assertFalse(paymentOnlyResult.needsConfirmation)

        val strictResult = SafetyGuard.evaluate(action, node, SafetyPolicyMode.STRICT_ALL)
        assertTrue(strictResult.needsConfirmation)
    }

    @Test
    fun `stuck loop ignores repeated monitor WAIT steps`() {
        val waitLog = AgentStepLog(
            stepNumber = 1,
            timestampMs = 100L,
            actionType = AgentActionType.WAIT,
            targetSummary = "Wait 30s",
            reason = "Waiting for reply",
            status = StepStatus.WAITING,
            requiresConfirmation = false
        )
        val candidateWait = PlannedAction(
            actionType = AgentActionType.WAIT,
            targetNodeId = null,
            x = null,
            y = null,
            textToType = null,
            waitSeconds = 30,
            memoryNote = null,
            reason = "Still waiting",
            requiresConfirmation = false
        )

        assertFalse(SafetyGuard.isStuckLoop(listOf(waitLog, waitLog, waitLog), candidateWait, "Wait 30s"))
    }

    @Test
    fun `stuck loop detects three identical executed tap steps`() {
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
            waitSeconds = null,
            memoryNote = null,
            reason = "Open Settings",
            requiresConfirmation = false
        )

        assertTrue(SafetyGuard.isStuckLoop(listOf(log, log, log), candidate, "#3 Settings (400,600)"))
    }

    @Test
    fun `rest client extracts raw json from fenced third party model output`() {
        val fenced = """
            ```json
            {"actionType":"TAP","x":240,"y":680,"reason":"Tap Post","requiresConfirmation":false}
            ```
        """.trimIndent()
        val extracted = RestAiClients.extractJsonObjectString(fenced)
        assertEquals(
            "{\"actionType\":\"TAP\",\"x\":240,\"y\":680,\"reason\":\"Tap Post\",\"requiresConfirmation\":false}",
            extracted
        )
    }

    @Test
    fun `agent settings resolves active key and model per provider`() {
        val settings = AgentSettings(
            aiProvider = AiProvider.OPENROUTER,
            googleAiStudioApiKey = "g_key",
            grokApiKey = "x_key",
            openRouterApiKey = "  or_key_123  ",
            openRouterModel = "meta-llama/llama-4-maverick:free"
        )
        assertEquals("or_key_123", settings.activeApiKey())
        assertEquals("meta-llama/llama-4-maverick:free", settings.activeModelName())
    }

    @Test
    fun `knowledge store matches saved carrom playbook by goal and merges lessons without duplicates`() {
        val carrom = com.example.agent.TaskPlaybook(
            topicKey = "carrom_board_game",
            topicTitle = "Carrom Board Game",
            rulesSummary = "Drag striker opposite target direction.",
            winningStrategies = listOf("Cut shots at 30 degrees work well for corner pockets."),
            foulLessons = listOf("Pocketing striker causes a foul.")
        )
        val matched = com.example.agent.AgentKnowledgeStore.findBestMatchingPlaybook(
            listOf(carrom),
            "Open Carrom Board and practice pocketing coins"
        )
        assertEquals("carrom_board_game", matched?.topicKey)

        val merged = com.example.agent.AgentKnowledgeStore.mergeLessons(
            carrom.foulLessons,
            listOf("pocketing striker causes a foul.", "Avoid hitting opponent black coin first.")
        )
        assertEquals(2, merged.size)
    }
}
