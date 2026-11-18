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
    fun `gemini planner parses structured JSON action accurately`() {
        val planner = GeminiAiPlanner()
        val json = """
            {
              "actionType": "INPUT_TEXT",
              "targetNodeId": 7,
              "x": 540,
              "y": 310,
              "textToType": "hello world",
              "reason": "Type search query into box",
              "requiresConfirmation": false
            }
        """.trimIndent()

        val parsed = planner.parsePlannedAction(json)
        assertEquals(AgentActionType.INPUT_TEXT, parsed.actionType)
        assertEquals(7, parsed.targetNodeId)
        assertEquals(540, parsed.x)
        assertEquals(310, parsed.y)
        assertEquals("hello world", parsed.textToType)
        assertFalse(parsed.requiresConfirmation)
    }
}
