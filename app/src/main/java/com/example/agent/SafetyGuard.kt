package com.example.agent

object SafetyGuard {

    private val sensitiveKeywords = listOf(
        "pay", "checkout", "purchase", "buy now", "place order", "confirm order",
        "transfer", "send money", "upi", "wallet", "card", "cvv",
        "delete", "remove", "erase", "uninstall", "factory reset", "format",
        "send", "submit", "post", "publish", "call", "dial",
        "password", "passcode", "pin", "otp", "permission", "allow",
        "পেমেন্ট", "পরিশোধ", "ডিলিট", "মুছুন", "পাঠান", "কল"
    )

    data class Evaluation(
        val needsConfirmation: Boolean,
        val reason: String
    )

    fun evaluate(
        action: PlannedAction,
        targetNode: ScreenNode?,
        confirmSensitiveEnabled: Boolean
    ): Evaluation {
        if (!confirmSensitiveEnabled) {
            return Evaluation(needsConfirmation = false, reason = "")
        }

        if (action.actionType in listOf(
                AgentActionType.COMPLETE,
                AgentActionType.FAIL,
                AgentActionType.WAIT,
                AgentActionType.BACK,
                AgentActionType.HOME,
                AgentActionType.SCROLL_DOWN,
                AgentActionType.SCROLL_UP
            )
        ) {
            return Evaluation(needsConfirmation = false, reason = "")
        }

        if (action.requiresConfirmation) {
            return Evaluation(
                needsConfirmation = true,
                reason = "AI flagged this ${action.actionType.name} step as sensitive: ${action.reason}"
            )
        }

        val combinedText = buildString {
            append(action.reason.lowercase())
            append(' ')
            append(action.textToType?.lowercase().orEmpty())
            if (targetNode != null) {
                append(' ')
                append(targetNode.text.lowercase())
                append(' ')
                append(targetNode.contentDesc.lowercase())
                append(' ')
                append(targetNode.viewId.lowercase())
            }
        }

        val matchedKeyword = sensitiveKeywords.firstOrNull { keyword ->
            combinedText.contains(keyword)
        }

        return if (matchedKeyword != null) {
            Evaluation(
                needsConfirmation = true,
                reason = "Detected sensitive action keyword '$matchedKeyword' on target UI control."
            )
        } else {
            Evaluation(needsConfirmation = false, reason = "")
        }
    }

    fun isStuckLoop(recentLogs: List<AgentStepLog>, candidate: PlannedAction, targetSummary: String): Boolean {
        if (recentLogs.size < 3) return false
        val lastThree = recentLogs.takeLast(3)
        return lastThree.all {
            it.actionType == candidate.actionType &&
                it.targetSummary == targetSummary &&
                it.status == StepStatus.EXECUTED
        }
    }
}
