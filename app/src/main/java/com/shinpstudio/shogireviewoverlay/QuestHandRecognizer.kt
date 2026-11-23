package com.shinpstudio.shogireviewoverlay

import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shogi Quest's review UI draws each distinct held piece as a gold icon in a
 * tray immediately above or below the calibrated 9x9 board. A small red number
 * indicates multiplicity; an unnumbered icon means one.
 *
 * Recognize both trays from screenshot pixels, then demand the standard 40-piece
 * inventory. This deliberately fails closed for unknown digits / themes rather
 * than guessing at an SFEN with incorrect hands.
 *
 * No OCR/network dependency. Assets contain only cropped binary glyph masks.
 */
internal class QuestHandRecognizer(iconText: String, digitText: String) {
    private data class Glyph(
        val width: Int,
        val height: Int,
        val bits: BooleanArray,
        val projection: IntArray,
        val ink: Int
    )
    private data class Icon(val left: Int, val right: Int, val top: Int, val bottom: Int)
    private data class Best(val code: Char, val score: Double, val margin: Double)

    private val icons = load(iconText, 32, 32)
    private val digits = load(digitText, 16, 24)
    private val order = listOf(
        PieceType.PAWN, PieceType.LANCE, PieceType.KNIGHT, PieceType.SILVER,
        PieceType.GOLD, PieceType.BISHOP, PieceType.ROOK
    )

    init {
        require("PLNSGBRplnsgbr".all { it in icons }) { "Missing Quest hand templates" }
        require("0123456789".all { it in digits }) { "Missing hand-count glyphs" }
    }

    private fun load(text: String, width: Int, height: Int): Map<Char, Glyph> {
        val result = mutableMapOf<Char, Glyph>()
        for (source in text.lineSequence()) {
            val line = source.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            require(line.length > 2 && line[1] == ':') { "Bad hand mask row" }
            val packed = Base64.getDecoder().decode(line.substring(2))
            require(packed.size == width * height / 8) { "Invalid hand mask size" }
            val bits = BooleanArray(width * height) { i ->
                ((packed[i ushr 3].toInt() and 255) and (1 shl (7 - (i and 7)))) != 0
            }
            require(result.put(line[0], makeGlyph(width, height, bits)) == null) {
                "Duplicate hand template code"
            }
        }
        return result
    }

    private fun makeGlyph(w: Int, h: Int, bits: BooleanArray): Glyph {
        val axes = IntArray(w + h)
        var ink = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!bits[y * w + x]) continue
            axes[y]++
            axes[h + x]++
            ink++
        }
        return Glyph(w, h, bits, axes, ink)
    }

    private fun similarity(a: Glyph, b: Glyph): Double {
        if (a.ink == 0 || b.ink == 0) return 0.0
        var axesDot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.projection.indices) {
            val v = a.projection[i].toDouble()
            val u = b.projection[i].toDouble()
            axesDot += v * u
            aa += v * v
            bb += u * u
        }
        var common = 0
        for (i in a.bits.indices) if (a.bits[i] && b.bits[i]) common++
        return 0.55 * axesDot / sqrt(aa * bb) +
            0.45 * common / sqrt(a.ink.toDouble() * b.ink)
    }

    private fun match(signature: Glyph, references: Map<Char, Glyph>): Best? {
        val scored = references.map { (code, reference) ->
            Best(code, similarity(signature, reference), 0.0)
        }.sortedByDescending { it.score }
        if (scored.size < 2) return null
        val best = scored[0]
        return Best(best.code, best.score, best.score - scored[1].score)
    }

    private fun isGold(pixel: Int): Boolean {
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        return r > 130 && g > 70 && b < 180 &&
            r * 100 > g * 116 && g * 100 > b * 112
    }

    private fun isRed(pixel: Int): Boolean {
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        return r > 130 && r > g + 70 && r > b + 65 && g < 115 && b < 125
    }

    private fun isInk(pixel: Int): Boolean {
        if (isRed(pixel)) return false // Don't let the count obscure the piece glyph.
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        return 77 * r + 150 * g + 29 * b < 108 * 256
    }

    // In Quest, holding pieces sit on the same nine-column grid as the board,
    // even when the upper tray contains gaps between icons. Check *all nine*
    // candidate cells in both trays; do not scan or merge arbitrary columns.
    private data class Slot(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun slot(
        boardLeft: Float, boardTop: Float, boardW: Float, boardH: Float,
        side: Side, index: Int
    ): Slot {
        require(index in 0..8)
        val cellW = boardW / 9f
        val cellH = boardH / 9f
        val x = boardLeft + (index + .08f) * cellW
        // Upper tray is anchored immediately above the board; lower tray
        // immediately beneath it. Both retain the board's horizontal grid.
        val y = if (side == Side.WHITE) boardTop - 1.17f * cellH
                else boardTop + boardH + .07f * cellH
        return Slot(
            x.roundToInt(), y.roundToInt(),
            (.84f * cellW).roundToInt(), (1.10f * cellH).roundToInt()
        )
    }

    /**
     * Require a substantial gold region in the *center* of a candidate slot
     * so portraits, timers and the backdrop don't become phantom held pieces.
     * Then obtain the actual gold outline from within that single slot. The
     * resulting bounding box is used for glyph and badge sampling.
     */
    private fun goldRect(pixels: IntArray, w: Int, h: Int, slot: Slot): Icon? {
        if (slot.x < 0 || slot.y < 0 || slot.w < 24 || slot.h < 24 ||
            slot.x + slot.w > w || slot.y + slot.h > h) return null

        val cx0 = slot.x + (.17f * slot.w).roundToInt()
        val cx1 = slot.x + (.83f * slot.w).roundToInt()
        val cy0 = slot.y + (.22f * slot.h).roundToInt()
        val cy1 = slot.y + (.79f * slot.h).roundToInt()
        var present = 0
        for (y in cy0 until cy1) for (x in cx0 until cx1) {
            if (isGold(pixels[y * w + x])) present++
        }
        val area = (cx1 - cx0) * (cy1 - cy0)
        if (area <= 0 || present < .35f * area) return null

        var left = slot.x + slot.w
        var top = slot.y + slot.h
        var right = slot.x - 1
        var bottom = slot.y - 1
        for (y in slot.y until slot.y + slot.h) {
            for (x in slot.x until slot.x + slot.w) {
                if (!isGold(pixels[y * w + x])) continue
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        if (right - left < .45f * slot.w ||
            bottom - top < .40f * slot.h) return null
        // Keep the existing glyph template crop convention: x-end exclusive,
        // y-end inclusive. Changing it by one pixel shifts 32x32 masks.
        return Icon(left, right + 1, top, bottom)
    }

    internal fun occupiedSlotIndices(
        pixels: IntArray, width: Int, height: Int,
        boardLeft: Float, boardTop: Float, boardW: Float, boardH: Float,
        side: Side
    ): List<Int> = (0..8).filter { index ->
        goldRect(
            pixels, width, height,
            slot(boardLeft, boardTop, boardW, boardH, side, index)
        ) != null
    }

    private fun locateIcons(
        pixels: IntArray, w: Int, h: Int, left: Float, top: Float,
        boardW: Float, boardH: Float, side: Side
    ): List<Icon> {
        return (0..8).mapNotNull { index ->
            goldRect(pixels, w, h, slot(left, top, boardW, boardH, side, index))
        }
    }

    private fun sampleIcon(pixels: IntArray, w: Int, h: Int, icon: Icon): Glyph {
        val x0 = icon.left + .145f * (icon.right - icon.left)
        val x1 = icon.right - .145f * (icon.right - icon.left)
        val y0 = icon.top + .14f * (icon.bottom - icon.top)
        val y1 = icon.bottom - .12f * (icon.bottom - icon.top)
        val bits = BooleanArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            val sx = (x0 + (x1 - x0) * x / 31f).roundToInt().coerceIn(0, w - 1)
            val sy = (y0 + (y1 - y0) * y / 31f).roundToInt().coerceIn(0, h - 1)
            bits[y * 32 + x] = isInk(pixels[sy * w + sx])
        }
        return makeGlyph(32, 32, bits)
    }

    private fun count(
        pixels: IntArray, w: Int, h: Int, icon: Icon, side: Side,
        cellW: Float, cellH: Float
    ): Int? {
        val left = if (side == Side.WHITE) icon.left - .42f * cellW
                   else icon.right - .25f * cellW
        val right = if (side == Side.WHITE) icon.left + .26f * cellW
                    else icon.right + .40f * cellW
        val top = if (side == Side.WHITE) icon.top - .16f * cellH
                  else icon.top + .35f * cellH
        val bottom = if (side == Side.WHITE) icon.top + .52f * cellH
                     else icon.bottom + .14f * cellH
        val x0 = left.roundToInt().coerceIn(0, w)
        val x1 = right.roundToInt().coerceIn(0, w)
        val y0 = top.roundToInt().coerceIn(0, h)
        val y1 = bottom.roundToInt().coerceIn(0, h)
        if (x1 <= x0 || y1 <= y0) return null

        val redPerColumn = IntArray(x1 - x0)
        for (y in y0 until y1) for (x in x0 until x1)
            if (isRed(pixels[y * w + x])) redPerColumn[x - x0]++

        val detected = StringBuilder()
        var cursor = 0
        var redTotal = redPerColumn.sum()
        while (cursor < redPerColumn.size) {
            if (redPerColumn[cursor] == 0) { cursor++; continue }
            val start = cursor
            while (cursor < redPerColumn.size && redPerColumn[cursor] > 0) cursor++
            val finish = cursor
            var minY = y1
            var maxY = -1
            var inkCount = 0
            for (x in x0 + start until x0 + finish) for (y in y0 until y1) {
                if (isRed(pixels[y * w + x])) {
                    inkCount++
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
            if (finish - start < 4 || inkCount < 24) continue
            if (detected.length >= 2) return null
            val srcW = finish - start
            val srcH = maxY - minY + 1
            val bits = BooleanArray(16 * 24)
            for (y in 0 until 24) for (x in 0 until 16) {
                val sx = x0 + start + min(srcW - 1, ((x + 0.5f) * srcW / 16).toInt())
                val sy = minY + min(srcH - 1, ((y + 0.5f) * srcH / 24).toInt())
                bits[y * 16 + x] = isRed(pixels[sy * w + sx])
            }
            val guessed = match(makeGlyph(16, 24, bits), digits) ?: return null
            if (guessed.score < .78 || guessed.margin < .075) return null
            detected.append(guessed.code)
        }
        if (detected.isEmpty()) return if (redTotal < 24) 1 else null
        val raw = detected.toString()
        if (raw.startsWith('0')) return null
        return raw.toIntOrNull()?.takeIf { it in 2..18 }
    }

    /**
     * A known board tells us how many pieces of each kind are off-board.
     * An icon always proves *which owner* holds that kind, even if an unusual
     * red digit could not be classified. Recover at most one unknown count per
     * kind from its remaining inventory. Two unreadable counts for the same
     * type are ambiguous and must never be guessed.
     *
     * A positively recognized digit must still agree with the inventory.
     */
    internal fun reconcileCounts(
        board: List<Piece?>,
        observed: Map<Side, Map<PieceType, Int?>>
    ): Map<Side, Map<PieceType, Int>>? {
        if (board.size != 81) return null
        val totals = mapOf(
            PieceType.PAWN to 18, PieceType.LANCE to 4,
            PieceType.KNIGHT to 4, PieceType.SILVER to 4,
            PieceType.GOLD to 4, PieceType.BISHOP to 2,
            PieceType.ROOK to 2, PieceType.KING to 2
        )
        val result = mutableMapOf(
            Side.BLACK to mutableMapOf<PieceType, Int>(),
            Side.WHITE to mutableMapOf<PieceType, Int>()
        )
        for ((type, total) in totals) {
            val missing = total - board.count { it?.type == type }
            if (missing < 0) return null
            val owners = Side.entries.filter { observed[it]?.containsKey(type) == true }
            if (type == PieceType.KING && owners.isNotEmpty()) return null
            val known = owners.mapNotNull { side ->
                observed.getValue(side)[type]?.let { side to it }
            }
            if (known.any { (_, amount) -> amount !in 1..18 }) return null
            val knownTotal = known.sumOf { it.second }
            val unknown = owners.filter { observed.getValue(it)[type] == null }
            if (unknown.size > 1 || knownTotal > missing) return null
            if (unknown.isEmpty() && knownTotal != missing) return null
            for ((side, number) in known) result.getValue(side)[type] = number
            if (unknown.size == 1) {
                val remainder = missing - knownTotal
                if (remainder !in 1..18) return null
                result.getValue(unknown[0])[type] = remainder
            }
        }
        val finalized = result.mapValues { it.value.toMap() }
        return finalized.takeIf {
            ReviewStateTracker.inventoryIsValid(
                ShogiPosition(board, Side.BLACK, it, 1)
            )
        }
    }

    /** Returns null unless all seven standard inventories match exactly. */
    fun recognize(
        pixels: IntArray, width: Int, height: Int,
        boardLeft: Float, boardTop: Float, boardWidth: Float, boardHeight: Float,
        board: List<Piece?>
    ): Map<Side, Map<PieceType, Int>>? {
        if (pixels.size < width * height || board.size != 81 || width <= 0 || height <= 0)
            return null
        val result = mutableMapOf(
            Side.BLACK to mutableMapOf<PieceType, Int>(),
            Side.WHITE to mutableMapOf<PieceType, Int>()
        )
        val cellW = boardWidth / 9f
        val cellH = boardHeight / 9f
        for (side in listOf(Side.WHITE, Side.BLACK)) {
            val found = locateIcons(
                pixels, width, height, boardLeft, boardTop, boardWidth, boardHeight, side
            ) ?: return null
            var previousIndex = -1
            for (icon in found) {
                val chosen = match(sampleIcon(pixels, width, height, icon), icons) ?: return null
                if (chosen.score < .78 || chosen.margin < .075) return null
                if (side == Side.BLACK && !chosen.code.isUpperCase()) return null
                if (side == Side.WHITE && !chosen.code.isLowerCase()) return null
                val type = PieceType.fromSfen(chosen.code)
                val index = order.indexOf(type)
                // Quest sorts each tray P, L, N, S, G, B, R; a duplicate or
                // out-of-order result is far more likely a template mismatch.
                if (index <= previousIndex) return null
                previousIndex = index
                // A digit that cannot be read remains unknown. Reconcile it
                // only when the board and other holding icons uniquely imply
                // the count. Never assign a default "one" to visible digits.
                observed.getValue(side)[type] =
                    count(pixels, width, height, icon, side, cellW, cellH)
            }
        }
        return reconcileCounts(board, observed.mapValues { it.value.toMap() })
    }
}
