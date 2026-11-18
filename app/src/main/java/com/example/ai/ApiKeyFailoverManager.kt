package com.example.ai

import com.example.agent.AgentSettings
import com.example.agent.AiProvider
import com.example.agent.AiRoutingStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class AiTaskProfile {
    FAST_UI,
    BALANCED,
    HIGH_ACCURACY
}

data class KeyAttemptResult<T>(
    val value: T,
    val providerUsed: AiProvider,
    val modelUsed: String,
    val keyIndexOneBased: Int,
    val totalKeysInProvider: Int,
    val failoverCount: Int
)

object ApiKeyFailoverManager {

    internal const val RATE_LIMIT_COOLDOWN_MS = 65_000L

    private val activeIndexByProvider = ConcurrentHashMap<AiProvider, Int>()
    private val permanentFailedKeysByProvider = ConcurrentHashMap<AiProvider, MutableSet<String>>()
    private val cooldownUntilByProviderKey = ConcurrentHashMap<String, Long>()
    private val lastRouteSummary = ConcurrentHashMap<String, String>()
    private val roundRobinTurn = AtomicInteger(0)

    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    fun getLastRouteLabel(): String = lastRouteSummary["route"].orEmpty()

    fun getActiveKeyPosition(provider: AiProvider, totalKeys: Int): Int {
        if (totalKeys <= 0) return 0
        return (activeIndexByProvider[provider] ?: 0).coerceIn(0, totalKeys - 1)
    }

    fun getFailedCount(provider: AiProvider): Int {
        return permanentFailedKeysByProvider[provider]?.size ?: 0
    }

    fun getCooldownCount(provider: AiProvider): Int {
        val now = nowMsProvider()
        val prefix = "${provider.name}:"
        var count = 0
        cooldownUntilByProviderKey.entries.forEach { (composite, expiresAt) ->
            if (composite.startsWith(prefix)) {
                if (expiresAt > now) {
                    count++
                } else {
                    cooldownUntilByProviderKey.remove(composite, expiresAt)
                }
            }
        }
        return count
    }

    fun resetHealth() {
        activeIndexByProvider.clear()
        permanentFailedKeysByProvider.clear()
        cooldownUntilByProviderKey.clear()
        lastRouteSummary.clear()
        roundRobinTurn.set(0)
        nowMsProvider = { System.currentTimeMillis() }
    }

    fun orderedProviders(
        settings: AgentSettings,
        firebaseConfigured: Boolean,
        taskProfile: AiTaskProfile = AiTaskProfile.BALANCED
    ): List<AiProvider> {
        val configured = mutableListOf<AiProvider>()
        val baseOrder = when (taskProfile) {
            AiTaskProfile.FAST_UI -> listOf(
                AiProvider.GROQ_CLOUD,
                AiProvider.GOOGLE_AI_STUDIO,
                AiProvider.OPENROUTER,
                AiProvider.FIREBASE_AI
            )
            AiTaskProfile.HIGH_ACCURACY -> listOf(
                AiProvider.GOOGLE_AI_STUDIO,
                AiProvider.OPENROUTER,
                AiProvider.GROQ_CLOUD,
                AiProvider.FIREBASE_AI
            )
            AiTaskProfile.BALANCED -> listOf(
                settings.aiProvider,
                AiProvider.GOOGLE_AI_STUDIO,
                AiProvider.GROQ_CLOUD,
                AiProvider.OPENROUTER,
                AiProvider.FIREBASE_AI
            ).distinct()
        }

        if (!settings.autoCrossProviderFailover) {
            return listOf(settings.aiProvider)
        }

        for (p in baseOrder) {
            if (p == AiProvider.FIREBASE_AI && !firebaseConfigured) continue
            if (p.requiresApiKey && settings.apiKeysFor(p).isEmpty()) continue
            configured.add(p)
        }

        if (configured.isEmpty()) {
            return listOf(settings.aiProvider)
        }

        if (settings.routingStrategy == AiRoutingStrategy.STICKY_FAILOVER) {
            val stickyList = mutableListOf(settings.aiProvider)
            configured.filterTo(stickyList) { it != settings.aiProvider }
            return stickyList
        }

        // SMART_HYBRID: Rotate across configured providers on each call (Round-Robin)
        // so 1 call goes to Groq, next to Google AI Studio, next to OpenRouter, etc.
        val turn = roundRobinTurn.getAndIncrement()
        val shift = if (configured.size > 1) turn % configured.size else 0
        return (0 until configured.size).map { offset ->
            configured[(shift + offset) % configured.size]
        }
    }

    fun orderedKeyIndices(provider: AiProvider, keys: List<String>): List<Int> {
        if (keys.isEmpty()) return emptyList()
        val now = nowMsProvider()
        val start = getActiveKeyPosition(provider, keys.size)
        val permanentFailed = permanentFailedKeysByProvider[provider].orEmpty()

        val rotated = (0 until keys.size).map { offset -> (start + offset) % keys.size }
        val healthy = mutableListOf<Int>()
        val coolingDown = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        for (idx in rotated) {
            val key = keys[idx]
            val composite = "${provider.name}:$key"
            val cooldownUntil = cooldownUntilByProviderKey[composite]
            if (cooldownUntil != null && cooldownUntil <= now) {
                cooldownUntilByProviderKey.remove(composite, cooldownUntil)
            }
            when {
                key in permanentFailed -> failed.add(idx)
                cooldownUntil != null && cooldownUntil > now -> coolingDown.add(idx)
                else -> healthy.add(idx)
            }
        }

        // Priority: Ready healthy keys -> cooling-down keys (if all healthy busy) -> permanent failed as absolute last resort
        return healthy + coolingDown + failed
    }

    suspend fun <T> executeWithFailover(
        settings: AgentSettings,
        firebaseConfigured: Boolean,
        taskProfile: AiTaskProfile = AiTaskProfile.BALANCED,
        invokeForProvider: suspend (provider: AiProvider, apiKey: String, model: String) -> T
    ): KeyAttemptResult<T> {
        val providers = orderedProviders(settings, firebaseConfigured, taskProfile)
        val errors = mutableListOf<String>()
        var failovers = 0
        val isRoundRobin = settings.routingStrategy == AiRoutingStrategy.SMART_HYBRID

        for (provider in providers) {
            if (!provider.requiresApiKey) {
                if (!firebaseConfigured) {
                    errors.add("${provider.displayName}: Firebase not configured in this APK")
                    continue
                }
                val model = settings.modelNameFor(provider)
                try {
                    val result = invokeForProvider(provider, "", model)
                    lastRouteSummary["route"] = "${provider.shortBadge} ($model)"
                    return KeyAttemptResult(
                        value = result,
                        providerUsed = provider,
                        modelUsed = model,
                        keyIndexOneBased = 1,
                        totalKeysInProvider = 1,
                        failoverCount = failovers
                    )
                } catch (e: Exception) {
                    errors.add("${provider.displayName}: ${e.localizedMessage ?: "failed"}")
                    failovers++
                    continue
                }
            }

            val keys = settings.apiKeysFor(provider)
            if (keys.isEmpty()) {
                errors.add("${provider.displayName}: No API keys configured")
                continue
            }

            val primaryModel = settings.modelNameFor(provider)
            val fallbackModel = provider.defaultModel.takeIf { it != primaryModel }
            val keyIndices = orderedKeyIndices(provider, keys)

            for (idx in keyIndices) {
                val key = keys[idx]
                var usedModel = primaryModel
                try {
                    val result = try {
                        invokeForProvider(provider, key, primaryModel)
                    } catch (firstErr: Exception) {
                        val msg = firstErr.message.orEmpty()
                        val isModelNotFound = msg.contains("HTTP 404") ||
                            msg.contains("not found", ignoreCase = true) ||
                            msg.contains("is not supported", ignoreCase = true)
                        if (isModelNotFound && fallbackModel != null) {
                            usedModel = fallbackModel
                            invokeForProvider(provider, key, fallbackModel)
                        } else {
                            throw firstErr
                        }
                    }

                    // Key succeeded: clear any failure/cooldown mark.
                    permanentFailedKeysByProvider[provider]?.remove(key)
                    cooldownUntilByProviderKey.remove("${provider.name}:$key")
                    // In SMART_HYBRID Round-Robin, advance cursor to NEXT key so 1-minute RPM spreads across all keys!
                    activeIndexByProvider[provider] = if (isRoundRobin) {
                        (idx + 1) % keys.size
                    } else {
                        idx
                    }
                    lastRouteSummary["route"] = "${provider.shortBadge} #${idx + 1}/${keys.size}"
                    return KeyAttemptResult(
                        value = result,
                        providerUsed = provider,
                        modelUsed = usedModel,
                        keyIndexOneBased = idx + 1,
                        totalKeysInProvider = keys.size,
                        failoverCount = failovers
                    )
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    if (isPermanentAuthError(msg)) {
                        permanentFailedKeysByProvider
                            .getOrPut(provider) { ConcurrentHashMap.newKeySet() }
                            .add(key)
                    } else {
                        // Temporary 429 rate-limit / quota / transient network error -> 65s auto-cooldown!
                        cooldownUntilByProviderKey["${provider.name}:$key"] =
                            nowMsProvider() + RATE_LIMIT_COOLDOWN_MS
                    }
                    activeIndexByProvider[provider] = (idx + 1) % keys.size
                    failovers++
                    errors.add("${provider.shortBadge} Key #${idx + 1}: ${e.localizedMessage ?: "error"}")
                }
            }
        }

        throw IllegalStateException(
            "All configured keys/providers failed (${errors.size} attempt(s)). Last: ${errors.lastOrNull() ?: "Unknown error"}"
        )
    }

    private fun isPermanentAuthError(message: String): Boolean {
        return message.contains("HTTP 401") ||
            message.contains("HTTP 403") ||
            message.contains("API_KEY_INVALID", ignoreCase = true) ||
            message.contains("invalid api key", ignoreCase = true)
    }
}
