package com.example.game

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

sealed interface CarromVisionOutcome {
    data class WaitStillnessOrTurn(val reason: String) : CarromVisionOutcome
    data class SolvedShot(
        val solution: CarromShotSolution,
        val detectedStriker: CarromPiece?,
        val board: CarromBoardLayout
    ) : CarromVisionOutcome
    data class NeedsAiVision(val diagnostic: String) : CarromVisionOutcome
}

/**
 * Pure Kotlin on-device Carrom screenshot analyzer + offline board-motion detector.
 * Zero native OpenCV bloat (~15-35ms per frame). Falls back cleanly to AI Vision
 * whenever board confidence is low or screen is a lobby/menu.
 */
object CarromVisionDetector {

    private const val GRID_SIZE = 16
    private const val SAMPLE_STEP = 4
    private const val MOTION_THRESHOLD = 9.5f

    private var previousBoardLuma: IntArray? = null

    fun resetMotionHistory() {
        previousBoardLuma = null
    }

    fun analyzeAndSolve(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        myCoinColor: CoinColor,
        consecutiveOfflineWaits: Int
    ): CarromVisionOutcome {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) {
            return CarromVisionOutcome.NeedsAiVision("No screenshot available for Carrom solver.")
        }

        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()

        // Estimate square Carrom board region in portrait mobile view
        val boardBmLeft = (screenshot.width * 0.06f).toInt()
        val boardBmRight = (screenshot.width * 0.94f).toInt()
        val boardBmWidth = (boardBmRight - boardBmLeft).coerceAtLeast(40)
        val boardBmTop = ((screenshot.height - boardBmWidth) * 0.48f).toInt().coerceIn(0, screenshot.height - 40)
        val boardBmBottom = (boardBmTop + boardBmWidth).coerceAtMost(screenshot.height - 1)

        // 1. Offline Motion / Stillness check (avoids wasting API while coins are still rolling!)
        val currentLuma = sampleBoardLumaGrid(
            bitmap = screenshot,
            left = boardBmLeft,
            top = boardBmTop,
            right = boardBmRight,
            bottom = boardBmBottom
        )
        val prevLuma = previousBoardLuma
        previousBoardLuma = currentLuma

        if (prevLuma != null && consecutiveOfflineWaits < 2) {
            val meanDiff = computeMeanAbsDiff(prevLuma, currentLuma)
            if (meanDiff > MOTION_THRESHOLD) {
                return CarromVisionOutcome.WaitStillnessOrTurn(
                    "Board motion detected (diff=${meanDiff.toInt()}) — waiting offline for coins to settle (0 API)."
                )
            }
        }

        // 2. Scan board region into clustered pieces
        val boardPieces = detectBoardPieces(
            bitmap = screenshot,
            left = boardBmLeft,
            top = boardBmTop,
            right = boardBmRight,
            bottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY
        )

        if (boardPieces.size < 2) {
            return CarromVisionOutcome.NeedsAiVision(
                "Detected ${boardPieces.size} Carrom piece(s); delegating to AI Vision."
            )
        }

        val boardLeft = boardBmLeft * scaleX
        val boardTop = boardBmTop * scaleY
        val boardRight = boardBmRight * scaleX
        val boardBottom = boardBmBottom * scaleY
        val boardWidthPx = (boardRight - boardLeft).coerceAtLeast(100f)

        val coinRadius = boardWidthPx * 0.029f
        val strikerRadius = boardWidthPx * 0.036f
        val pocketRadius = boardWidthPx * 0.052f
        val pocketInset = boardWidthPx * 0.065f

        val pockets = listOf(
            Point2D(boardLeft + pocketInset, boardTop + pocketInset),
            Point2D(boardRight - pocketInset, boardTop + pocketInset),
            Point2D(boardLeft + pocketInset, boardBottom - pocketInset),
            Point2D(boardRight - pocketInset, boardBottom - pocketInset)
        )
        val baselineY = boardTop + (boardBottom - boardTop) * 0.855f

        val boardLayout = CarromBoardLayout(
            left = boardLeft,
            top = boardTop,
            right = boardRight,
            bottom = boardBottom,
            pockets = pockets,
            baselineY = baselineY,
            coinRadiusPx = coinRadius,
            strikerRadiusPx = strikerRadius,
            pocketRadiusPx = pocketRadius
        )

        val strikerPiece = boardPieces.firstOrNull { it.color == CoinColor.STRIKER }
        if (strikerPiece == null && consecutiveOfflineWaits < 2) {
            // Baseline striker not visible -> likely opponent's turn! Wait cleanly without burning API.
            return CarromVisionOutcome.WaitStillnessOrTurn(
                "Striker not on baseline (likely opponent turn) — waiting offline (0 API)."
            )
        }

        val solution = CarromGeometrySolver.solveBestShot(
            board = boardLayout,
            pieces = boardPieces,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = strikerPiece
        )

        return if (solution != null && solution.confidenceScore >= 0.62f) {
            CarromVisionOutcome.SolvedShot(
                solution = solution,
                detectedStriker = strikerPiece,
                board = boardLayout
            )
        } else {
            CarromVisionOutcome.NeedsAiVision(
                "No high-confidence offline Carrom shot found; using AI Vision."
            )
        }
    }

    private fun detectBoardPieces(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        scaleX: Float,
        scaleY: Float
    ): List<CarromPiece> {
        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        val cols = (bw / SAMPLE_STEP).coerceAtLeast(1)
        val rows = (bh / SAMPLE_STEP).coerceAtLeast(1)

        val labels = Array(rows) { arrayOfNulls<CoinColor>(cols) }
        val hsv = FloatArray(3)
        val pocketMargin = (bw * 0.11f).toInt()

        for (r in 0 until rows) {
            val py = (top + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (left + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)

                // Skip dark corner pocket holes
                val dx = px - left
                val dy = py - top
                val nearCorner = (dx < pocketMargin || dx > bw - pocketMargin) &&
                    (dy < pocketMargin || dy > bh - pocketMargin)
                if (nearCorner) continue

                val pixel = bitmap.getPixel(px, py)
                rgbToHsv(pixel, hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]

                val isBaselineBand = abs(dy - bh * 0.855f) < bh * 0.055f

                labels[r][c] = when {
                    // Queen: saturated red/crimson
                    (h >= 338f || h <= 16f) && s >= 0.48f && v >= 0.35f -> CoinColor.QUEEN
                    // Striker: distinct cyan/teal/gold disc on baseline
                    isBaselineBand && ((h in 165f..225f && s >= 0.38f && v >= 0.45f) ||
                        (h in 36f..58f && s >= 0.55f && v >= 0.72f)) -> CoinColor.STRIKER
                    // White coin: bright ivory/white with low saturation
                    s <= 0.24f && v >= 0.76f -> CoinColor.WHITE
                    // Black coin: dark grey/charcoal disc inside wooden board
                    v in 0.07f..0.28f && s <= 0.40f -> CoinColor.BLACK
                    else -> null
                }
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val detected = mutableListOf<CarromPiece>()
        val queue = ArrayDeque<Pair<Int, Int>>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = labels[r][c] ?: continue
                if (visited[r][c]) continue

                var sumX = 0f
                var sumY = 0f
                var count = 0
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (left + cc * SAMPLE_STEP) * scaleX
                    sumY += (top + cr * SAMPLE_STEP) * scaleY
                    count++

                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols &&
                                !visited[nr][nc] && labels[nr][nc] == color
                            ) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                // Filter out noise specks and huge board UI elements
                if (count in 5..95) {
                    val cx = sumX / count
                    val cy = sumY / count
                    val radiusApprox = (kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX)
                        .coerceIn(14f, 65f)
                    detected.add(CarromPiece(color, Point2D(cx, cy), radiusApprox))
                }
            }
        }

        return detected
    }

    private fun sampleBoardLumaGrid(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray {
        val out = IntArray(GRID_SIZE * GRID_SIZE)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        var idx = 0
        for (gy in 0 until GRID_SIZE) {
            val py = (top + (gy + 0.5f) * h / GRID_SIZE).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until GRID_SIZE) {
                val px = (left + (gx + 0.5f) * w / GRID_SIZE).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val luma = (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                out[idx++] = luma
            }
        }
        return out
    }

    private fun computeMeanAbsDiff(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var total = 0L
        for (i in a.indices) {
            total += abs(a[i] - b[i])
        }
        return total.toFloat() / a.size
    }

    private fun rgbToHsv(pixel: Int, out: FloatArray) {
        val r = ((pixel ushr 16) and 0xFF) / 255f
        val g = ((pixel ushr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC

        var hue = when {
            delta < 1e-5f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        val sat = if (maxC < 1e-5f) 0f else delta / maxC

        out[0] = hue
        out[1] = sat
        out[2] = maxC
    }
}
