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
            googleAiStudioApiKey = "g_key_12345",
            groqApiKey = "gsk_key_12345",
            openRouterApiKey = "  or_key_123  ",
            openRouterModel = "openrouter/free"
        )
        assertEquals("or_key_123", settings.activeApiKey())
        assertEquals("openrouter/free", settings.activeModelName())
    }

    @Test
    fun `parseApiKeys handles 25 plus keys across newlines commas and spaces without duplicates`() {
        val raw = (1..28).joinToString(separator = "\n") { idx ->
            when {
                idx % 3 == 0 -> "  AIzaSyKeyNumber_$idx, AIzaSyKeyNumber_$idx  "
                idx % 2 == 0 -> "AIzaSyKeyNumber_$idx;"
                else -> "AIzaSyKeyNumber_$idx"
            }
        }
        val parsed = AgentSettings.parseApiKeys(raw)
        assertEquals(28, parsed.size)
        assertEquals("AIzaSyKeyNumber_1", parsed.first())
        assertEquals("AIzaSyKeyNumber_28", parsed.last())
    }

    @Test
    fun `sticky failover skips failed keys and resumes from healthy key on subsequent steps`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "key_one_11111\nkey_two_22222\nkey_three_33333",
            autoCrossProviderFailover = false,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER
        )

        val step1Calls = mutableListOf<String>()
        val step1Result = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, apiKey, _ ->
            step1Calls.add(apiKey)
            if (apiKey == "key_one_11111") throw IllegalStateException("HTTP 429 rate limit")
            "ok_from_$apiKey"
        }

        assertEquals(listOf("key_one_11111", "key_two_22222"), step1Calls)
        assertEquals("ok_from_key_two_22222", step1Result.value)
        assertEquals(2, step1Result.keyIndexOneBased)
        assertEquals(1, step1Result.failoverCount)

        // Step 2 must start directly at key_two_22222 without wasting a call on failed key_one_11111
        val step2Calls = mutableListOf<String>()
        val step2Result = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, apiKey, _ ->
            step2Calls.add(apiKey)
            "step2_$apiKey"
        }

        assertEquals(listOf("key_two_22222"), step2Calls)
        assertEquals("step2_key_two_22222", step2Result.value)
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `smart hybrid round robin spreads one call per step across providers and keys`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "AIzaSyGoogleKey0001\nAIzaSyGoogleKey0002",
            groqApiKey = "gsk_GroqKey00000001\ngsk_GroqKey00000002",
            openRouterApiKey = "sk-or-v1-OpenRouter01\nsk-or-v1-OpenRouter02",
            autoCrossProviderFailover = true,
            routingStrategy = com.example.agent.AiRoutingStrategy.SMART_HYBRID
        )

        val usedRoutes = mutableListOf<String>()
        repeat(6) {
            val res = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
                settings = settings,
                firebaseConfigured = false,
                taskProfile = com.example.ai.AiTaskProfile.FAST_UI
            ) { provider, apiKey, _ ->
                "${provider.name}:$apiKey"
            }
            usedRoutes.add(res.value)
        }

        // All 6 distinct keys across the 3 providers must be used exactly once across 6 steps (1 call per step!)
        assertEquals(6, usedRoutes.distinct().size)
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `rate limited key enters 65s temporary cooldown and auto recovers while 401 stays invalid`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        var fakeClock = 1_000_000L
        com.example.ai.ApiKeyFailoverManager.nowMsProvider = { fakeClock }

        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "AIzaSyRateLimited01\nAIzaSyHealthyKey002",
            autoCrossProviderFailover = false,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER
        )

        // Step 1: Key 1 hits HTTP 429 -> falls back to Key 2 and places Key 1 in 65s cooldown
        val res1 = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, key, _ ->
            if (key == "AIzaSyRateLimited01") throw IllegalStateException("HTTP 429 quota exceeded")
            key
        }
        assertEquals("AIzaSyHealthyKey002", res1.value)
        assertEquals(1, com.example.ai.ApiKeyFailoverManager.getCooldownCount(AiProvider.GOOGLE_AI_STUDIO))
        assertEquals(0, com.example.ai.ApiKeyFailoverManager.getFailedCount(AiProvider.GOOGLE_AI_STUDIO))

        // After 66 seconds, cooldown expires automatically and Key 1 is healthy again!
        fakeClock += 66_000L
        assertEquals(0, com.example.ai.ApiKeyFailoverManager.getCooldownCount(AiProvider.GOOGLE_AI_STUDIO))

        // If Key 2 fails with HTTP 401, Key 1 immediately recovers and runs without manual reset
        val res2 = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { _, key, _ ->
            if (key == "AIzaSyHealthyKey002") throw IllegalStateException("HTTP 401 Unauthorized")
            key
        }
        assertEquals("AIzaSyRateLimited01", res2.value)
        assertEquals(1, com.example.ai.ApiKeyFailoverManager.getFailedCount(AiProvider.GOOGLE_AI_STUDIO))
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `failover manager crosses to groq and openrouter when primary provider keys exhaust`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "google_bad_1234",
            groqApiKey = "gsk_bad_123456",
            openRouterApiKey = "sk-or-v1-good12345",
            autoCrossProviderFailover = true,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER
        )

        val attemptedProviders = mutableListOf<AiProvider>()
        val result = com.example.ai.ApiKeyFailoverManager.executeWithFailover(
            settings = settings,
            firebaseConfigured = false
        ) { provider, apiKey, model ->
            attemptedProviders.add(provider)
            if (provider != AiProvider.OPENROUTER) throw IllegalStateException("Quota exhausted")
            "$provider:$model:$apiKey"
        }

        assertEquals(
            listOf(AiProvider.GOOGLE_AI_STUDIO, AiProvider.GROQ_CLOUD, AiProvider.OPENROUTER),
            attemptedProviders
        )
        assertEquals(AiProvider.OPENROUTER, result.providerUsed)
        assertEquals("openrouter/free", result.modelUsed)
        assertEquals(2, result.failoverCount)
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `bulk key importer parses mixed txt csv json and sorts by provider prefix`() {
        val mixedBulkText = """
            # Mixed keys backup
            GOOGLE_KEY_1="AIzaSyBulkGoogleKey00001"
            AIzaSyBulkGoogleKey00002, gsk_BulkGroqKey00000001
            {"openrouter": ["sk-or-v1-BulkOpenRouter001", "sk-or-v1-BulkOpenRouter002"]}
            gsk_BulkGroqKey00000002
            AIzaSyBulkGoogleKey00001
        """.trimIndent()

        val (updated, summary) = com.example.ai.BulkKeyImporter.importIntoSettings(
            rawContent = mixedBulkText,
            currentSettings = AgentSettings(),
            fallbackProvider = AiProvider.GOOGLE_AI_STUDIO
        )

        assertEquals(2, summary.googleCount)
        assertEquals(2, summary.groqCount)
        assertEquals(2, summary.openRouterCount)
        assertEquals(6, summary.newlyAddedCount)
        assertEquals(2, updated.apiKeysFor(AiProvider.GOOGLE_AI_STUDIO).size)
        assertEquals(2, updated.apiKeysFor(AiProvider.GROQ_CLOUD).size)
        assertEquals(2, updated.apiKeysFor(AiProvider.OPENROUTER).size)

        val (redistributed, sortedSummary) = com.example.ai.BulkKeyImporter.redistributeMixedBlock(
            mixedText = "AIzaSyOnlyGoogle00003\ngsk_OnlyGroq00000003",
            currentSettings = AgentSettings(googleAiStudioApiKey = "AIzaSyOnlyGoogle00003\ngsk_OnlyGroq00000003"),
            sourceProvider = AiProvider.GOOGLE_AI_STUDIO
        )
        assertEquals(1, sortedSummary.googleCount)
        assertEquals(1, sortedSummary.groqCount)
        assertEquals(listOf("AIzaSyOnlyGoogle00003"), redistributed.apiKeysFor(AiProvider.GOOGLE_AI_STUDIO))
        assertEquals(listOf("gsk_OnlyGroq00000003"), redistributed.apiKeysFor(AiProvider.GROQ_CLOUD))
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

    @Test
    fun `turbo dual key hedge uses single call when primary is fast and races secondary when primary is slow`() = kotlinx.coroutines.runBlocking {
        com.example.ai.ApiKeyFailoverManager.resetHealth()
        com.example.ai.ApiKeyFailoverManager.hedgeDelayMs = 80L

        val settings = AgentSettings(
            aiProvider = AiProvider.GOOGLE_AI_STUDIO,
            googleAiStudioApiKey = "AIzaSyPrimaryFast001",
            groqApiKey = "gsk_SecondaryFast002",
            autoCrossProviderFailover = true,
            routingStrategy = com.example.agent.AiRoutingStrategy.STICKY_FAILOVER,
            turboDualKeyGameMode = true
        )

        // Case A: Primary finishes before hedgeDelay -> secondary is cancelled without making any call
        val fastCalls = java.util.Collections.synchronizedList(mutableListOf<AiProvider>())
        val fastResult = com.example.ai.ApiKeyFailoverManager.executeWithHedgeOrFailover(
            settings = settings,
            firebaseConfigured = false,
            taskProfile = com.example.ai.AiTaskProfile.HIGH_ACCURACY,
            enableHedgeRace = true
        ) { provider, _, _ ->
            fastCalls.add(provider)
            "shot_from_${provider.name}"
        }
        assertEquals("shot_from_GOOGLE_AI_STUDIO", fastResult.value)
        assertEquals(listOf(AiProvider.GOOGLE_AI_STUDIO), fastCalls)

        // Case B: Primary is slow (> 80ms hedgeDelay) -> secondary starts in parallel and wins immediately
        val slowCalls = java.util.Collections.synchronizedList(mutableListOf<AiProvider>())
        val slowResult = com.example.ai.ApiKeyFailoverManager.executeWithHedgeOrFailover(
            settings = settings,
            firebaseConfigured = false,
            taskProfile = com.example.ai.AiTaskProfile.HIGH_ACCURACY,
            enableHedgeRace = true
        ) { provider, _, _ ->
            slowCalls.add(provider)
            if (provider == AiProvider.GOOGLE_AI_STUDIO) {
                kotlinx.coroutines.delay(900L)
                "late_google"
            } else {
                "fast_groq_win"
            }
        }
        assertEquals("fast_groq_win", slowResult.value)
        assertEquals(AiProvider.GROQ_CLOUD, slowResult.providerUsed)
        assertTrue(slowResult.wasHedged)
        assertTrue(slowCalls.containsAll(listOf(AiProvider.GOOGLE_AI_STUDIO, AiProvider.GROQ_CLOUD)))
        com.example.ai.ApiKeyFailoverManager.resetHealth()
    }

    @Test
    fun `action cache verifies screen transition matches shifted live node and blocks game boards`() {
        fun node(id: Int, label: String, viewId: String, left: Int, top: Int): ScreenNode = ScreenNode(
            id = id,
            className = "android.widget.Button",
            text = label,
            contentDesc = "",
            viewId = viewId,
            bounds = ScreenBounds(left, top, left + 180, top + 80),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val lobbyNodes = listOf(
            node(1, "Play Classic", "btn_play", 100, 300),
            node(2, "Disc Pool", "btn_pool", 100, 420),
            node(3, "Friends", "btn_friends", 100, 540),
            node(4, "Shop", "btn_shop", 100, 660)
        )
        val sigBefore = com.example.agent.ActionCacheStore.computeScreenSignature(lobbyNodes)
        val playAction = PlannedAction(
            actionType = AgentActionType.TAP,
            targetNodeId = 1,
            x = 190,
            y = 340,
            textToType = null,
            waitSeconds = null,
            memoryNote = null,
            reason = "Tap Play Classic",
            requiresConfirmation = false
        )
        val candidate = com.example.agent.PendingCacheCandidate(
            goal = "Open Carrom and play classic match",
            packageName = "com.miniclip.carrom",
            screenSignature = sigBefore,
            interactiveNodeCount = 4,
            action = playAction,
            targetNode = lobbyNodes[0],
            hadConfirmedSafety = false
        )

        // Screen didn't change -> ineligible
        val unchangedSnapshot = com.example.agent.UiSnapshot("com.miniclip.carrom", 1080, 2400, lobbyNodes, null, "")
        assertFalse(com.example.agent.ActionCacheStore.isEligibleForCache(candidate, unchangedSnapshot))

        // Screen transitioned to mode selection -> eligible!
        val nextNodes = listOf(
            node(1, "Matchmaking", "btn_match", 200, 500),
            node(2, "Cancel", "btn_cancel", 200, 700),
            node(3, "Paris", "btn_paris", 200, 900),
            node(4, "Delhi", "btn_delhi", 200, 1100)
        )
        val nextSnapshot = com.example.agent.UiSnapshot("com.miniclip.carrom", 1080, 2400, nextNodes, null, "")
        assertTrue(com.example.agent.ActionCacheStore.isEligibleForCache(candidate, nextSnapshot))

        // Sparse game board (< 4 clickable nodes) -> never eligible for cache
        val sparseCandidate = candidate.copy(interactiveNodeCount = 1)
        assertFalse(com.example.agent.ActionCacheStore.isEligibleForCache(sparseCandidate, nextSnapshot))

        // Cached entry resolves against shifted live node coordinates cleanly
        val goalKeys = com.example.agent.ActionCacheStore.normalizeGoalKeywords("Open Carrom and play classic match")
        val savedEntry = com.example.agent.CachedActionEntry(
            cacheKey = com.example.agent.ActionCacheStore.buildCacheKey(goalKeys, "com.miniclip.carrom", sigBefore),
            goalKeywords = goalKeys,
            packageName = "com.miniclip.carrom",
            screenSignature = sigBefore,
            actionType = AgentActionType.TAP,
            targetLabel = "Play Classic",
            targetViewId = "btn_play",
            tapX = 190,
            tapY = 340,
            textToType = null
        )

        val shiftedLobby = listOf(
            node(9, "Play Classic", "btn_play", 400, 800),
            node(2, "Disc Pool", "btn_pool", 100, 420),
            node(3, "Friends", "btn_friends", 100, 540),
            node(4, "Shop", "btn_shop", 100, 660)
        )
        val replaySnapshot = com.example.agent.UiSnapshot("com.miniclip.carrom", 1080, 2400, shiftedLobby, null, "")
        val match = com.example.agent.ActionCacheStore.matchEntryFromList(listOf(savedEntry), "Play Carrom classic", replaySnapshot)
        assertEquals(9, match?.action?.targetNodeId)
        assertEquals(490, match?.action?.x)
        assertEquals(840, match?.action?.y)
    }

    @Test
    fun `carrom solver computes slingshot ghost ball shot avoiding obstacles and foul pockets`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f), // top-left
                com.example.game.Point2D(960f, 560f), // top-right
                com.example.game.Point2D(120f, 1400f), // bottom-left
                com.example.game.Point2D(960f, 1400f)  // bottom-right
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        // White target coin near top-left; black obstacle blocks top-right pocket path
        val whiteTarget = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(310f, 750f),
            radiusPx = 28f
        )
        val blockingBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(635f, 655f), // directly between (310,750) and top-right (960,560)
            radiusPx = 28f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteTarget, blockingBlack),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400
        )

        org.junit.Assert.assertNotNull(solution)
        val sol = solution!!
        // Should aim for top-left pocket (120, 560), NOT blocked top-right
        assertEquals(120f, sol.targetPocket.x, 1f)
        assertEquals(560f, sol.targetPocket.y, 1f)
        // Slingshot pulls back opposite the upward shot (dragEnd.y > strikerPos.y)
        assertTrue(sol.dragEnd.y > sol.strikerPos.y)
        assertFalse(sol.isBankShot)

        // Straight-on follow-through into pocket must be flagged as a striker scratch foul
        val dirToTopLeft = (com.example.game.Point2D(120f, 560f) - whiteTarget.center).normalized()
        val scratchGhost = whiteTarget.center - dirToTopLeft * 63f
        assertTrue(
            com.example.game.CarromGeometrySolver.willStrikerScratchIntoPocket(
                strikerPos = com.example.game.Point2D(500f, 1320f),
                ghostBall = scratchGhost,
                dirSg = dirToTopLeft,
                dirCp = dirToTopLeft,
                cutAngleDeg = 0f,
                board = board
            )
        )
    }

    @Test
    fun `carrom solver falls back to one rail bank shot when direct pocket paths are blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val whiteCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(540f, 820f),
            radiusPx = 28f
        )
        // Place 4 blocker black coins between whiteCoin and each of the 4 pockets
        val blockers = board.pockets.map { pocket ->
            val mid = com.example.game.Point2D(
                (whiteCoin.center.x + pocket.x) * 0.5f,
                (whiteCoin.center.y + pocket.y) * 0.5f
            )
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, mid, 28f)
        }

        val bankShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteCoin) + blockers,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400
        )

        org.junit.Assert.assertNotNull(bankShot)
        assertTrue(bankShot!!.isBankShot)
    }
}
