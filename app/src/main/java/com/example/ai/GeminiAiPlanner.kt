package com.example.ai

import android.util.Log
import com.example.agent.AgentActionType
import com.example.agent.AgentStepLog
import com.example.agent.PlannedAction
import com.example.agent.UiSnapshot
import com.example.service.NodeTreeParser
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiAiPlanner"
private const val INFERENCE_TIMEOUT_MS = 60_000L

class GeminiAiPlanner {

    private val actionSchema = Schema.obj(
        properties = mapOf(
            "actionType" to Schema.enumeration(
                values = listOf(
                    "TAP",
                    "INPUT_TEXT",
                    "SCROLL_DOWN",
                    "SCROLL_UP",
                    "BACK",
                    "HOME",
                    "WAIT",
                    "COMPLETE",
                    "FAIL"
                ),
                description = "Next automation action to perform on the Android device."
            ),
            "targetNodeId" to Schema.integer(
                description = "Numeric node id from the UI Node Tree when targeting a specific element.",
                nullable = true
            ),
            "x" to Schema.integer(
                description = "Horizontal screen pixel coordinate for TAP when needed.",
                nullable = true
            ),
            "y" to Schema.integer(
                description = "Vertical screen pixel coordinate for TAP when needed.",
                nullable = true
            ),
            "textToType" to Schema.string(
                description = "Text payload when actionType is INPUT_TEXT.",
                nullable = true
            ),
            "reason" to Schema.string(
                description = "Concise explanation of why this step advances the user's goal."
            ),
            "requiresConfirmation" to Schema.boolean(
                description = "True if this step triggers payments, sending messages/emails, deleting data, uninstalling apps, or changing security settings."
            )
        ),
        optionalProperties = listOf("targetNodeId", "x", "y", "textToType")
    )

    private val config = generationConfig {
        responseMimeType = "application/json"
        responseSchema = actionSchema
        temperature = 0.2f
    }

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config,
            systemInstruction = content {
                text(
                    """
                    You are TapPilot, an Android native AccessibilityService automation agent.
                    You observe the current Android screen via a structured UI Node Tree (with node IDs and pixel bounds [left,top,right,bottom]) and an optional screenshot image.
                    Your job is to select the single best next action to accomplish the user's goal safely.
                    Rules:
                    1. Prefer specifying targetNodeId AND its center (x, y) coordinates from the UI Node Tree whenever a matching node exists.
                    2. Use INPUT_TEXT only on editable fields, and include textToType.
                    3. If the target app or option is off-screen, use SCROLL_DOWN or SCROLL_UP, or HOME to find the app launcher.
                    4. Set requiresConfirmation = true for any irreversible, financial, messaging/send, deletion, call, or security/permission action.
                    5. Return COMPLETE as soon as the user's goal is visibly satisfied. Return FAIL only if the task is impossible after reasonable attempts.
                    6. Never repeat the exact same failing action more than twice; try BACK, SCROLL_DOWN, or HOME instead.
                    """.trimIndent()
                )
            }
        )
    }

    suspend fun planNextAction(
        goal: String,
        snapshot: UiSnapshot,
        recentHistory: List<AgentStepLog>,
        stepNumber: Int,
        maxSteps: Int
    ): Result<PlannedAction> {
        return try {
            val promptText = buildPrompt(goal, snapshot, recentHistory, stepNumber, maxSteps)
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                model.generateContent(
                    content {
                        snapshot.screenshot?.let { bitmap ->
                            image(bitmap)
                        }
                        text(promptText)
                    }
                )
            }
            val rawJson = response.text
            if (rawJson.isNullOrBlank()) {
                Result.failure(IllegalStateException("Gemini returned an empty response."))
            } else {
                Result.success(parsePlannedAction(rawJson))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini planning timed out", e)
            Result.failure(IllegalStateException("AI reasoning timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini planning failed", e)
            Result.failure(e)
        }
    }

    internal fun parsePlannedAction(jsonString: String): PlannedAction {
        val json = JSONObject(jsonString)
        val actionType = AgentActionType.fromWire(json.getString("actionType"))
        val targetNodeId = if (json.has("targetNodeId") && !json.isNull("targetNodeId")) {
            json.optInt("targetNodeId", -1).takeIf { it > 0 }
        } else {
            null
        }
        val x = if (json.has("x") && !json.isNull("x")) {
            json.optInt("x", -1).takeIf { it >= 0 }
        } else {
            null
        }
        val y = if (json.has("y") && !json.isNull("y")) {
            json.optInt("y", -1).takeIf { it >= 0 }
        } else {
            null
        }
        val textToType = if (json.has("textToType") && !json.isNull("textToType")) {
            json.optString("textToType").takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val reason = json.optString("reason", "Proceeding with goal.")
        val requiresConfirmation = json.optBoolean("requiresConfirmation", false)

        return PlannedAction(
            actionType = actionType,
            targetNodeId = targetNodeId,
            x = x,
            y = y,
            textToType = textToType,
            reason = reason,
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun buildPrompt(
        goal: String,
        snapshot: UiSnapshot,
        recentHistory: List<AgentStepLog>,
        stepNumber: Int,
        maxSteps: Int
    ): String {
        val historyBlock = if (recentHistory.isEmpty()) {
            "None yet (first step)."
        } else {
            recentHistory.takeLast(6).joinToString("\n") { log ->
                "Step ${log.stepNumber}: ${log.actionType.name} -> ${log.targetSummary} [${log.status.name}] (${log.reason})"
            }
        }

        val nodeTreeText = NodeTreeParser.formatNodesForPrompt(snapshot.nodes)

        return """
            USER GOAL: $goal
            STEP: $stepNumber of $maxSteps
            FOREGROUND PACKAGE: ${snapshot.packageName}
            SCREEN RESOLUTION: ${snapshot.screenWidth}x${snapshot.screenHeight}
            CAPTURE STATUS: ${snapshot.captureNote}

            RECENT HISTORY:
            $historyBlock

            UI NODE TREE (${snapshot.nodes.size} elements):
            $nodeTreeText
        """.trimIndent()
    }
}
