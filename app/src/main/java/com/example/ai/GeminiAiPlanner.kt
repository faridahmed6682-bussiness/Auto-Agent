package com.example.ai

import android.util.Log
import com.example.agent.AgentActionType
import com.example.agent.AgentExecutionMode
import com.example.agent.AgentSettings
import com.example.agent.AgentStepLog
import com.example.agent.AiProvider
import com.example.agent.NotificationSignal
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

    private val systemPromptText = """
        You are TapPilot, an Android native AccessibilityService automation agent.
        You observe the current Android screen via a structured UI Node Tree (with node IDs and pixel bounds [left,top,right,bottom]), an optional screenshot image, recent incoming notifications, and your running workingMemory note.
        Your job is to select the single best next action to accomplish the user's goal.
        Rules:
        1. Prefer specifying targetNodeId AND its center (x, y) coordinates from the UI Node Tree whenever a matching node exists.
        2. Use INPUT_TEXT only on editable fields, and include textToType.
        3. If the target app or option is off-screen, use SCROLL_DOWN or SCROLL_UP, or HOME to open the launcher.
        4. Social, search, posting, comment-reply, and Messenger chatting actions MUST have requiresConfirmation = false so they execute autonomously.
        5. Set requiresConfirmation = true ONLY when a tap/input involves money payment, financial transfer, final checkout/place-order confirmation, card/PIN/OTP entry, or factory reset.
        6. In CONTINUOUS_MONITOR mode:
           - Keep workingMemory updated via memoryNote so you never repeat the same post or reply twice.
           - When a recent incoming notification mentions a relevant chat/comment/message, switch to or open that app/notification to check and respond.
           - When nothing new needs action right now, return WAIT with waitSeconds (e.g. 20 to 90) so the phone conserves battery/quota until the next check or notification wakeup.
           - Only return COMPLETE if the user's end goal is completely finished, or FAIL if truly impossible.
        7. Never repeat the exact same failing TAP/INPUT_TEXT more than twice; try BACK, SCROLL_DOWN, or HOME instead.
    """.trimIndent()

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
            "waitSeconds" to Schema.integer(
                description = "Seconds to wait before next check when actionType is WAIT (e.g., 15 to 120 in monitor mode).",
                nullable = true
            ),
            "memoryNote" to Schema.string(
                description = "Concise updated working-memory summary of progress, posted items, replies handled, or prices found.",
                nullable = true
            ),
            "reason" to Schema.string(
                description = "Concise explanation of why this step advances the user's goal."
            ),
            "requiresConfirmation" to Schema.boolean(
                description = "True ONLY for payments, money transfer, final order/checkout confirmation, card/PIN/OTP entry, or destructive resets. Keep false for normal posting, commenting, chatting, or browsing."
            )
        ),
        optionalProperties = listOf("targetNodeId", "x", "y", "textToType", "waitSeconds", "memoryNote")
    )

    private val config = generationConfig {
        responseMimeType = "application/json"
        responseSchema = actionSchema
        temperature = 0.2f
    }

    private val firebaseModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config,
            systemInstruction = content { text(systemPromptText) }
        )
    }

    suspend fun planNextAction(
        goal: String,
        mode: AgentExecutionMode,
        settings: AgentSettings,
        snapshot: UiSnapshot,
        recentHistory: List<AgentStepLog>,
        recentNotifications: List<NotificationSignal>,
        workingMemory: String,
        stepNumber: Int,
        maxSteps: Int?
    ): Result<PlannedAction> {
        return try {
            val promptText = buildPrompt(
                goal = goal,
                mode = mode,
                snapshot = snapshot,
                recentHistory = recentHistory,
                recentNotifications = recentNotifications,
                workingMemory = workingMemory,
                stepNumber = stepNumber,
                maxSteps = maxSteps
            )

            val rawJson = withTimeout(INFERENCE_TIMEOUT_MS) {
                if (settings.aiProvider == AiProvider.FIREBASE_AI) {
                    val isFirebaseReady = runCatching {
                        com.google.firebase.FirebaseApp.getInstance()
                    }.isSuccess
                    if (!isFirebaseReady) {
                        throw IllegalStateException(
                            "Firebase is not configured in this APK. Select Google AI Studio, Grok, or OpenRouter in Settings, or add GOOGLE_SERVICES_JSON secret in GitHub."
                        )
                    }
                    val response = firebaseModel.generateContent(
                        content {
                            snapshot.screenshot?.let { bitmap ->
                                image(bitmap)
                            }
                            text(promptText)
                        }
                    )
                    response.text.orEmpty()
                } else {
                    val apiKey = settings.activeApiKey()
                    if (apiKey.isBlank()) {
                        throw IllegalStateException("Missing API key for ${settings.aiProvider.displayName}. Add it in Settings.")
                    }
                    RestAiClients.callProvider(
                        provider = settings.aiProvider,
                        apiKey = apiKey,
                        modelName = settings.activeModelName(),
                        systemInstruction = systemPromptText,
                        promptText = promptText,
                        screenshot = snapshot.screenshot
                    )
                }
            }

            if (rawJson.isBlank()) {
                Result.failure(IllegalStateException("${settings.aiProvider.displayName} returned an empty response."))
            } else {
                Result.success(parsePlannedAction(rawJson))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI planning timed out", e)
            Result.failure(IllegalStateException("AI reasoning timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI planning failed", e)
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
            json.optString("textToType").takeIf { it.isNotEmpty() && it != "null" }
        } else {
            null
        }
        val waitSeconds = if (json.has("waitSeconds") && !json.isNull("waitSeconds")) {
            json.optInt("waitSeconds", -1).takeIf { it > 0 }?.coerceIn(3, 300)
        } else {
            null
        }
        val memoryNote = if (json.has("memoryNote") && !json.isNull("memoryNote")) {
            json.optString("memoryNote").trim().takeIf { it.isNotEmpty() && it != "null" }
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
            waitSeconds = waitSeconds,
            memoryNote = memoryNote,
            reason = reason,
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun buildPrompt(
        goal: String,
        mode: AgentExecutionMode,
        snapshot: UiSnapshot,
        recentHistory: List<AgentStepLog>,
        recentNotifications: List<NotificationSignal>,
        workingMemory: String,
        stepNumber: Int,
        maxSteps: Int?
    ): String {
        val historyBlock = if (recentHistory.isEmpty()) {
            "None yet (first step)."
        } else {
            recentHistory.takeLast(8).joinToString("\n") { log ->
                "Step ${log.stepNumber}: ${log.actionType.name} -> ${log.targetSummary} [${log.status.name}] (${log.reason})"
            }
        }

        val notifBlock = if (recentNotifications.isEmpty()) {
            "No new notifications."
        } else {
            recentNotifications.takeLast(5).joinToString("\n") { sig ->
                "[${sig.packageName}] ${sig.text}"
            }
        }

        val stepLabel = if (maxSteps != null) "$stepNumber of $maxSteps" else "$stepNumber (Continuous Monitor)"
        val memoryBlock = workingMemory.ifBlank { "No prior working memory yet." }
        val nodeTreeText = NodeTreeParser.formatNodesForPrompt(snapshot.nodes)

        return """
            USER GOAL: $goal
            EXECUTION MODE: ${mode.name}
            STEP: $stepLabel
            FOREGROUND PACKAGE: ${snapshot.packageName}
            SCREEN RESOLUTION: ${snapshot.screenWidth}x${snapshot.screenHeight}
            CAPTURE STATUS: ${snapshot.captureNote}

            WORKING MEMORY:
            $memoryBlock

            RECENT INCOMING NOTIFICATIONS:
            $notifBlock

            RECENT HISTORY:
            $historyBlock

            UI NODE TREE (${snapshot.nodes.size} elements):
            $nodeTreeText
        """.trimIndent()
    }
}
