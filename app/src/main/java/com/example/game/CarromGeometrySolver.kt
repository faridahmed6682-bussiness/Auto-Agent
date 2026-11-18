package com.example.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class CoinColor {
    WHITE,
    BLACK,
    QUEEN,
    STRIKER
}

data class Point2D(
    val x: Float,
    val y: Float
) {
    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Float): Point2D = Point2D(x * scalar, y * scalar)

    fun length(): Float = hypot(x, y)

    fun normalized(): Point2D {
        val len = length()
        return if (len > 1e-4f) Point2D(x / len, y / len) else Point2D(0f, 0f)
    }

    fun dot(other: Point2D): Float = x * other.x + y * other.y
}

data class CarromPiece(
    val color: CoinColor,
    val center: Point2D,
    val radiusPx: Float
)

data class CarromBoardLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pockets: List<Point2D>,
    val baselineY: Float,
    val coinRadiusPx: Float,
    val strikerRadiusPx: Float,
    val pocketRadiusPx: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

data class CarromShotSolution(
    val strikerPos: Point2D,
    val dragEnd: Point2D,
    val ghostBall: Point2D,
    val targetCoin: CarromPiece,
    val targetPocket: Point2D,
    val cutAngleDeg: Float,
    val isBankShot: Boolean,
    val confidenceScore: Float,
    val reason: String
)

/**
 * Pure Kotlin Ghost-Ball & Vector Physics Carrom Shot Solver.
 * Computes exact striker placement, cut angles, obstacle collision checks,
 * striker scratch-foul guards, and 1-cushion bank/rebound alternatives.
 */
object CarromGeometrySolver {

    fun solveBestShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece? = null
    ): CarromShotSolution? {
        if (board.pockets.size < 4) return null

        val targetPieces = pieces.filter {
            it.color == myCoinColor || it.color == CoinColor.QUEEN
        }
        if (targetPieces.isEmpty()) return null

        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val strikerCandidates = buildStrikerCandidates(board, obstacles, existingStriker)
        if (strikerCandidates.isEmpty()) return null

        var bestDirect: CarromShotSolution? = null

        for (coin in targetPieces) {
            for (pocket in board.pockets) {
                val coinToPocket = (pocket - coin.center)
                val distCoinPocket = coinToPocket.length()
                if (distCoinPocket < board.coinRadiusPx * 1.2f) continue

                val dirCp = coinToPocket.normalized()
                // Ghost-ball position behind the target coin along pocket line
                val impactDist = board.coinRadiusPx + board.strikerRadiusPx
                val ghostBall = coin.center - dirCp * impactDist

                if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.6f)) {
                    continue
                }

                // Verify coin -> pocket path is clear of blocking coins
                if (!isSegmentClear(
                        start = coin.center,
                        end = pocket,
                        obstacles = obstacles,
                        excluded = coin,
                        clearanceRadius = board.coinRadiusPx * 1.85f
                    )
                ) {
                    continue
                }

                for (strikerPos in strikerCandidates) {
                    val strikerToGhost = ghostBall - strikerPos
                    val distStrikerGhost = strikerToGhost.length()
                    if (distStrikerGhost < board.strikerRadiusPx * 1.1f) continue

                    val dirSg = strikerToGhost.normalized()
                    val cosAngle = dirSg.dot(dirCp).coerceIn(-1f, 1f)
                    val cutAngleDeg = (acos(cosAngle) * (180.0 / PI)).toFloat()

                    // Physically impossible or ultra-thin unreliable cut shots (> 62 degrees)
                    if (cutAngleDeg > 62f) continue

                    // Verify striker -> ghostBall path does not clip another coin first
                    if (!isSegmentClear(
                            start = strikerPos,
                            end = ghostBall,
                            obstacles = obstacles,
                            excluded = coin,
                            clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                        )
                    ) {
                        continue
                    }

                    // Foul guard: avoid shots where striker follows straight into target or adjacent pocket
                    if (willStrikerScratchIntoPocket(
                            strikerPos = strikerPos,
                            ghostBall = ghostBall,
                            dirSg = dirSg,
                            dirCp = dirCp,
                            cutAngleDeg = cutAngleDeg,
                            board = board
                        )
                    ) {
                        continue
                    }

                    val queenBonus = if (coin.color == CoinColor.QUEEN) 0.08f else 0f
                    val angleScore = 1f - (cutAngleDeg / 65f) * 0.55f
                    val distScore = 1f - ((distCoinPocket + distStrikerGhost) / (board.width * 2.2f)).coerceIn(0f, 0.35f)
                    val existingBonus = if (existingStriker != null && abs(strikerPos.x - existingStriker.center.x) < 12f) 0.06f else 0f
                    val score = (angleScore * 0.65f + distScore * 0.35f + queenBonus + existingBonus).coerceIn(0f, 1f)

                    if (bestDirect == null || score > bestDirect.confidenceScore) {
                        val dragEnd = computeSlingshotDragEnd(
                            strikerPos = strikerPos,
                            shotDir = dirSg,
                            totalDistance = distStrikerGhost + distCoinPocket,
                            boardWidth = board.width,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight
                        )
                        bestDirect = CarromShotSolution(
                            strikerPos = strikerPos,
                            dragEnd = dragEnd,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            cutAngleDeg = cutAngleDeg,
                            isBankShot = false,
                            confidenceScore = score,
                            reason = "Direct ${coin.color.name} cut=${cutAngleDeg.toInt()}° -> pocket (${pocket.x.toInt()},${pocket.y.toInt()})"
                        )
                    }
                }
            }
        }

        if (bestDirect != null && bestDirect.confidenceScore >= 0.58f) {
            return bestDirect
        }

        // Fallback: check 1-cushion bank/rebound shot off left/right/top rails if no good direct shot
        val bestBank = solveOneRailBankShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        return when {
            bestDirect != null && bestBank != null ->
                if (bestDirect.confidenceScore >= bestBank.confidenceScore) bestDirect else bestBank
            else -> bestDirect ?: bestBank
        }
    }

    private fun solveOneRailBankShot(
        board: CarromBoardLayout,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        screenWidth: Int,
        screenHeight: Int
    ): CarromShotSolution? {
        var bestBank: CarromShotSolution? = null
        val r = board.coinRadiusPx

        // Mirror top, left, and right rails for 1-cushion bank geometry
        val railPlanes = listOf(
            "top" to { p: Point2D -> Point2D(p.x, 2f * (board.top + r) - p.y) },
            "left" to { p: Point2D -> Point2D(2f * (board.left + r) - p.x, p.y) },
            "right" to { p: Point2D -> Point2D(2f * (board.right - r) - p.x, p.y) }
        )

        for (coin in targetPieces) {
            for (pocket in board.pockets) {
                for ((railName, mirrorFn) in railPlanes) {
                    val mirroredPocket = mirrorFn(pocket)
                    val toMirror = (mirroredPocket - coin.center)
                    if (toMirror.length() < r * 2f) continue
                    val dirBank = toMirror.normalized()
                    val ghostBall = coin.center - dirBank * (board.coinRadiusPx + board.strikerRadiusPx)
                    if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx)) continue

                    for (strikerPos in strikerCandidates) {
                        val sg = ghostBall - strikerPos
                        if (sg.length() < r * 1.5f) continue
                        val dirSg = sg.normalized()
                        val cutDeg = (acos(dirSg.dot(dirBank).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                        if (cutDeg > 35f) continue

                        if (!isSegmentClear(strikerPos, ghostBall, obstacles, coin, r + board.strikerRadiusPx * 0.9f)) {
                            continue
                        }

                        val score = (0.64f - (cutDeg / 60f) * 0.25f).coerceIn(0f, 0.72f)
                        if (bestBank == null || score > bestBank.confidenceScore) {
                            val dragEnd = computeSlingshotDragEnd(
                                strikerPos = strikerPos,
                                shotDir = dirSg,
                                totalDistance = sg.length() + toMirror.length(),
                                boardWidth = board.width,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            bestBank = CarromShotSolution(
                                strikerPos = strikerPos,
                                dragEnd = dragEnd,
                                ghostBall = ghostBall,
                                targetCoin = coin,
                                targetPocket = pocket,
                                cutAngleDeg = cutDeg,
                                isBankShot = true,
                                confidenceScore = score,
                                reason = "1-rail ($railName) bank ${coin.color.name} cut=${cutDeg.toInt()}°"
                            )
                        }
                    }
                }
            }
        }
        return bestBank
    }

    private fun buildStrikerCandidates(
        board: CarromBoardLayout,
        obstacles: List<CarromPiece>,
        existingStriker: CarromPiece?
    ): List<Point2D> {
        val margin = board.strikerRadiusPx * 2.1f
        val minX = board.left + margin
        val maxX = board.right - margin
        if (maxX <= minX) return emptyList()

        val candidates = mutableListOf<Point2D>()
        existingStriker?.let {
            val clampedX = it.center.x.coerceIn(minX, maxX)
            candidates.add(Point2D(clampedX, board.baselineY))
        }

        val steps = 10
        val stepSize = (maxX - minX) / steps
        for (i in 0..steps) {
            val pos = Point2D(minX + i * stepSize, board.baselineY)
            if (isPositionFree(pos, obstacles, board.coinRadiusPx + board.strikerRadiusPx * 0.95f)) {
                candidates.add(pos)
            }
        }
        return candidates
    }

    private fun isPositionFree(
        point: Point2D,
        obstacles: List<CarromPiece>,
        minDistance: Float
    ): Boolean {
        return obstacles.none { obs -> (obs.center - point).length() < minDistance }
    }

    internal fun isSegmentClear(
        start: Point2D,
        end: Point2D,
        obstacles: List<CarromPiece>,
        excluded: CarromPiece,
        clearanceRadius: Float
    ): Boolean {
        val seg = end - start
        val segLenSq = seg.x * seg.x + seg.y * seg.y
        if (segLenSq < 1e-3f) return true

        for (obs in obstacles) {
            if (obs === excluded) continue
            val toObs = obs.center - start
            val t = (toObs.dot(seg) / segLenSq).coerceIn(0f, 1f)
            // Ignore exact endpoints near start/end
            if (t <= 0.03f || t >= 0.97f) continue
            val closest = Point2D(start.x + seg.x * t, start.y + seg.y * t)
            val dist = (obs.center - closest).length()
            if (dist < clearanceRadius) {
                return false
            }
        }
        return true
    }

    internal fun willStrikerScratchIntoPocket(
        strikerPos: Point2D,
        ghostBall: Point2D,
        dirSg: Point2D,
        dirCp: Point2D,
        cutAngleDeg: Float,
        board: CarromBoardLayout
    ): Boolean {
        // 1. Nearly straight follow-through (< 11 deg): striker continues roughly along dirSg
        if (cutAngleDeg < 11f) {
            val followEnd = ghostBall + dirSg * (board.width * 0.95f)
            for (pocket in board.pockets) {
                if (distancePointToSegment(pocket, ghostBall, followEnd) < board.pocketRadiusPx * 1.25f) {
                    return true
                }
            }
        }

        // 2. Deflection tangent ray: post-collision striker moves perpendicular to dirCp
        // Tangent sign points in the same half-plane as dirSg
        val tangent1 = Point2D(-dirCp.y, dirCp.x)
        val tangent = if (tangent1.dot(dirSg) >= 0f) tangent1 else Point2D(dirCp.y, -dirCp.x)
        val deflectEnd = ghostBall + tangent * (board.width * 0.7f)
        for (pocket in board.pockets) {
            if (distancePointToSegment(pocket, ghostBall, deflectEnd) < board.pocketRadiusPx * 1.15f) {
                return true
            }
        }
        return false
    }

    private fun distancePointToSegment(p: Point2D, a: Point2D, b: Point2D): Float {
        val ab = b - a
        val abLenSq = ab.x * ab.x + ab.y * ab.y
        if (abLenSq < 1e-4f) return (p - a).length()
        val t = ((p - a).dot(ab) / abLenSq).coerceIn(0f, 1f)
        val proj = Point2D(a.x + ab.x * t, a.y + ab.y * t)
        return (p - proj).length()
    }

    private fun isInsidePlayableBoard(p: Point2D, board: CarromBoardLayout, margin: Float): Boolean {
        return p.x in (board.left + margin)..(board.right - margin) &&
            p.y in (board.top + margin)..(board.bottom - margin)
    }

    private fun computeSlingshotDragEnd(
        strikerPos: Point2D,
        shotDir: Point2D,
        totalDistance: Float,
        boardWidth: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Point2D {
        val powerRatio = (totalDistance / boardWidth).coerceIn(0.35f, 1.35f)
        val pullLength = (boardWidth * 0.28f * powerRatio).coerceIn(115f, 320f)
        val rawEnd = strikerPos - shotDir * pullLength
        return Point2D(
            x = rawEnd.x.coerceIn(16f, max(17f, screenWidth - 16f)),
            y = rawEnd.y.coerceIn(16f, max(17f, screenHeight - 16f))
        )
    }
}
