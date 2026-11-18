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
}
