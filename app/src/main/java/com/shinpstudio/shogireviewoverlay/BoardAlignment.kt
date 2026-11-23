package com.shinpstudio.shogireviewoverlay

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min

/**
 * Corrects a small global translation error in a manually aligned 9x9 grid.
 *
 * Search radius is bounded to +/-4 physical pixels (never stretches the grid).
 * 25 coarse candidates at 2px steps followed by 1px refinement around the
 * winning coarse candidate. Only in-memory 32x32 binary masks are compared.
 *
 * The correction is not applied to the saved manual grid; a validated result
 * can be passed to the overlay so arrows are drawn over the *actual* squares.
 */
internal object BoardAlignment {
    private const val LIMIT_PX = 4
    data class Scan(
        val board: List<Piece?>,
        val dx: Int,
        val dy: Int,
        val uncertain: Int,
        val occupied: Int,
        val kings: Boolean,
        val inventory: Boolean,
        val score: Double,
        val lowestScore: Double
    ) {
        val reliable: Boolean
            get() = uncertain == 0 && kings && (occupied != 40 || inventory)
        val distance: Int
            get() = abs(dx) + abs(dy)
    }

    private fun evaluate(
        pixels: IntArray,
        width: Int,
        height: Int,
        rect: RectF,
        inset: Float,
        classifier: PieceTemplateModel,
        dx: Int,
        dy: Int
    ): Scan {
        val board = MutableList<Piece?>(81) { null }
        var missing = 0
        var occupied = 0
        var total = 0.0
        var minScore = 1.0
        for (square in 0..80) {
            val signature = PieceTemplateModel.sample(
                pixels, width, height,
                rect.left + dx, rect.top + dy, rect.width(), rect.height(),
                square, inset
            )
            val result = classifier.match(signature)
            if (result.unknown) { missing++; continue }
            val code = result.code ?: continue
            board[square] = Piece(
                PieceType.fromSfen(code),
                if (code.isUpperCase()) Side.BLACK else Side.WHITE
            )
            occupied++
            total += result.score
            minScore = min(minScore, result.score)
        }
        val kings = Side.entries.all { player ->
            board.count { it?.type == PieceType.KING && it.side == player } == 1
        }
        val inventory = if (occupied == 40) {
            ReviewStateTracker.inventoryIsValid(
                ShogiPosition(
                    board, Side.BLACK,
                    mapOf(Side.BLACK to emptyMap(), Side.WHITE to emptyMap()), 1
                )
            )
        } else false
        return Scan(board, dx, dy, missing, occupied, kings, inventory,
            if (occupied == 0) 0.0 else total / occupied, minScore)
    }

    /**
     * Prioritize globally consistent positions, then few uncertain cells,
     * then confidence. Do not take a high similarity average over a broken
     * 40-piece position if another offset satisfies the full inventory.
     */
    internal fun better(a: Scan, b: Scan): Boolean {
        fun level(c: Scan): Int = when {
            c.reliable && c.occupied == 40 && c.inventory -> 3
            c.reliable -> 2
            c.uncertain == 0 && c.kings -> 1
            else -> 0
        }
        if (level(a) != level(b)) return level(a) > level(b)
        if (a.uncertain != b.uncertain) return a.uncertain < b.uncertain
        if (a.occupied != b.occupied) return a.occupied > b.occupied
        if (abs(a.score - b.score) > 0.0001) return a.score > b.score
        return a.distance < b.distance
    }

    fun find(
        pixels: IntArray,
        width: Int,
        height: Int,
        rect: RectF,
        inset: Float,
        classifier: PieceTemplateModel
    ): Scan {
        require(rect.width() > 0 && rect.height() > 0)
        val maxShift = min(LIMIT_PX,
            (min(rect.width(), rect.height()) / 9f * .065f).toInt().coerceAtLeast(1))
        fun inside(x: Int, y: Int): Boolean =
            rect.left + x >= 0f && rect.top + y >= 0f &&
                rect.right + x <= width && rect.bottom + y <= height
        return searchOffsets(maxShift, ::inside) { dx, dy ->
            evaluate(pixels, width, height, rect, inset, classifier, dx, dy)
        }
    }

    /** Pure search, testable without Android graphics. */
    internal fun searchOffsets(
        maxShift: Int,
        inside: (Int, Int) -> Boolean,
        evaluate: (Int, Int) -> Scan
    ): Scan {
        require(maxShift in 1..LIMIT_PX)
        var winner: Scan? = null
        val visited = HashSet<Pair<Int, Int>>()
        fun tryAt(dx: Int, dy: Int) {
            if (dx !in -maxShift..maxShift || dy !in -maxShift..maxShift ||
                !inside(dx, dy) || !visited.add(dx to dy)) return
            val scan = evaluate(dx, dy)
            if (winner == null || better(scan, requireNotNull(winner))) winner = scan
        }
        tryAt(0, 0)
        for (dy in -maxShift..maxShift step 2)
            for (dx in -maxShift..maxShift step 2) tryAt(dx, dy)
        val coarse = requireNotNull(winner)
        for (dy in coarse.dy - 1..coarse.dy + 1)
            for (dx in coarse.dx - 1..coarse.dx + 1) tryAt(dx, dy)
        return requireNotNull(winner)

    }
}
