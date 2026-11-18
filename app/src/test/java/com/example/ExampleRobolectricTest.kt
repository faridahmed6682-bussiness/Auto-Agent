package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentActionType
import com.example.ai.GeminiAiPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TapPilot Agent", appName)
    }

    @Test
    fun `gemini planner parses structured JSON action with waitSeconds and memoryNote`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "actionType": "WAIT",
              "targetNodeId": null,
              "x": null,
              "y": null,
              "textToType": null,
              "waitSeconds": 45,
              "memoryNote": "Posted in group; awaiting Messenger reply.",
              "reason": "Waiting for seller reply",
              "requiresConfirmation": false
            }
        """.trimIndent()

        val parsed = planner.parsePlannedAction(json)
        assertEquals(AgentActionType.WAIT, parsed.actionType)
        assertEquals(45, parsed.waitSeconds)
        assertEquals("Posted in group; awaiting Messenger reply.", parsed.memoryNote)
        assertFalse(parsed.requiresConfirmation)
    }

    @Test
    fun `gemini planner parses DRAG shot with learnedRule and foulOrMistakeLesson`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "actionType": "DRAG",
              "targetNodeId": null,
              "x": 540,
              "y": 1620,
              "endX": 460,
              "endY": 1820,
              "durationMs": 420,
              "textToType": null,
              "waitSeconds": null,
              "memoryNote": "Aiming striker for top-right white coin",
              "learnedRule": "Pull striker slightly left-down to sink top-right pocket coin.",
              "foulOrMistakeLesson": "Avoid pulling straight down when center coin is near pocket edge.",
              "reason": "Pull striker opposite target vector to pocket white coin",
              "requiresConfirmation": false
            }
        """.trimIndent()

        val parsed = planner.parsePlannedAction(json)
        assertEquals(AgentActionType.DRAG, parsed.actionType)
        assertEquals(540, parsed.x)
        assertEquals(1620, parsed.y)
        assertEquals(460, parsed.endX)
        assertEquals(1820, parsed.endY)
        assertEquals(420L, parsed.durationMs)
        assertEquals("Pull striker slightly left-down to sink top-right pocket coin.", parsed.learnedRule)
        assertEquals(
            "Avoid pulling straight down when center coin is near pocket edge.",
            parsed.foulOrMistakeLesson
        )
        assertFalse(parsed.requiresConfirmation)
    }

    @Test
    fun `research playbook parses and serializes cleanly`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "topicKey": "carrom_board_game",
              "topicTitle": "Carrom Board Game",
              "rulesSummary": "Position striker on baseline and drag opposite to shoot coins into 4 corner pockets.",
              "winningStrategies": [
                "Align striker center opposite target coin center toward corner pocket."
              ],
              "foulLessons": [
                "Never pocket the striker alone; causes foul and returns one pocketed coin."
              ]
            }
        """.trimIndent()

        val pb = planner.parseResearchPlaybook(json, "Play Carrom game")
        val encoded = com.example.agent.AgentKnowledgeStore.serializePlaybooksJson(listOf(pb))
        val decoded = com.example.agent.AgentKnowledgeStore.parsePlaybooksJson(encoded)

        assertEquals(1, decoded.size)
        assertEquals("carrom_board_game", decoded[0].topicKey)
        assertEquals("Carrom Board Game", decoded[0].topicTitle)
        assertEquals(1, decoded[0].winningStrategies.size)
        assertEquals(1, decoded[0].foulLessons.size)
    }
}
