package com.shinpstudio.shogireviewoverlay

import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Locates Shogi Wars' variable-position holding pieces without assuming
 * a specific left-to-right order. Uses cropped glyph masks only.
 *
 * Strict 40-piece inventory validation and actual badge digits are required.
 * Unknown pieces / digits fail closed; one-step history remains the fallback.
 */
internal class WarsHandRecognizer(iconsText: String, digitsText: String) {
    private data class Mask(val bits: BooleanArray, val axes: IntArray, val ink: Int)
    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class Candidate(val code: Char, val rect: Rect, val score: Double)
    private data class Match(val code: Char, val score: Double, val margin: Double)
    private val icons: Map<Char, List<Mask>> = load(iconsText, 32, 32)
    private val digits: Map<Char, List<Mask>> = load(digitsText, 16, 24)

    init {
        require("bpPLG".all { it in icons })
        require('2' in digits && '3' in digits)
    }

    private fun load(text: String, w: Int, h: Int): Map<Char, List<Mask>> {
        val entries = mutableMapOf<Char, MutableList<Mask>>()
        for (line in text.lineSequence().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            require(line.length > 2 && line[1] == ':')
            val bytes = Base64.getDecoder().decode(line.substring(2))
            require(bytes.size == w * h / 8)
            val bits = BooleanArray(w * h) { i ->
                ((bytes[i / 8].toInt() and 255) and (1 shl (7 - (i % 8)))) != 0
            }
            entries.getOrPut(line[0]) { mutableListOf() }.add(mask(bits, w, h))
        }
        return entries
    }

    private fun mask(bits: BooleanArray, w: Int, h: Int): Mask {
        val axes = IntArray(w + h)
        var ink = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!bits[y * w + x]) continue
            ink++
            axes[y]++
            axes[h + x]++
        }
        return Mask(bits, axes, ink)
    }

    private fun score(a: Mask, b: Mask): Double {
        if (a.ink == 0 || b.ink == 0) return 0.0
        var axesDot = 0.0
        var axesA = 0.0
        var axesB = 0.0
        for (i in a.axes.indices) {
            val av = a.axes[i].toDouble()
            val bv = b.axes[i].toDouble()
            axesDot += av * bv
            axesA += av * av
            axesB += bv * bv
        }
        var common = 0
        for (i in a.bits.indices) if (a.bits[i] && b.bits[i]) common++
        return .55 * axesDot / sqrt(axesA * axesB) +
            .45 * common / sqrt(a.ink.toDouble() * b.ink)
    }

    private fun match(actual: Mask, refs: Map<Char, List<Mask>>, choices: (Char) -> Boolean): Match? {
        val scored = refs.filterKeys(choices).map { (code, templates) ->
            code to templates.maxOf { score(actual, it) }
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        return Match(scored[0].first, scored[0].second,
            if (scored.size == 1) 1.0 else scored[0].second - scored[1].second)
    }

    private fun dark(color: Int): Boolean {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        return 77 * r + 150 * g + 29 * b < 108 * 256
    }

    private fun yellow(color: Int): Boolean {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        return r > 234 && g > 201 && b < 170 && r - b > 85 && g - b > 75
    }

    private fun sample(
        pixels: IntArray, width: Int, height: Int, rect: Rect
    ): Mask {
        val x0 = rect.x + .13f * rect.w
        val x1 = rect.x + .87f * rect.w
        val y0 = rect.y + .17f * rect.h
        val y1 = rect.y + .77f * rect.h
        val bits = BooleanArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            val sx = (x0 + (x1 - x0) * x / 31f).roundToInt().coerceIn(0, width - 1)
            val sy = (y0 + (y1 - y0) * y / 31f).roundToInt().coerceIn(0, height - 1)
            bits[y * 32 + x] = dark(pixels[sy * width + sx])
        }
        return mask(bits, 32, 32)
    }

    // The icon size is scaled from the calibrated board, not screen pixels.
    // Search covers all plausible horizontal slots; no canonical hand ordering.
    private fun locate(
        pixels: IntArray, width: Int, height: Int, left: Float, top: Float,
        boardW: Float, boardH: Float, side: Side
    ): List<Candidate>? {
        val cellW = boardW / 9f
        val cellH = boardH / 9f
        val xMin = (left + (if (side == Side.WHITE) .20f else .0f) * boardW)
            .roundToInt().coerceAtLeast(0)
        val xMax = (left + .73f * boardW).roundToInt().coerceAtMost(width - 1)
        val baseY = if (side == Side.WHITE) top else top + boardH
        val yMin = (baseY + (if (side == Side.WHITE) -1.48f else .22f) * cellH)
            .roundToInt().coerceAtLeast(0)
        val yMax = (baseY + (if (side == Side.WHITE) -1.20f else .56f) * cellH)
            .roundToInt().coerceAtMost(height - 1)
        val dx = max(2, (.05f * cellW).roundToInt())
        val dy = max(2, (.05f * cellH).roundToInt())
        val widths = listOf(.75f, .80f, .86f).map { (it * cellW).roundToInt() }
        val heights = listOf(.87f, .945f).map { (it * cellH).roundToInt() }
        val candidates = ArrayList<Candidate>()
        for (y in yMin..yMax step dy) for (x in xMin..xMax step dx) {
            for (w in widths) for (h in heights) {
                if (x + w > width || y + h > height) continue
                val rect = Rect(x, y, w, h)
                val signature = sample(pixels, width, height, rect)
                if (signature.ink < 90 || signature.ink > 600) continue
                val found = match(signature, icons) {
                    if (side == Side.WHITE) it.isLowerCase() else it.isUpperCase()
                } ?: continue
                if (found.score >= .84 && found.margin >= .075)
                    candidates += Candidate(found.code, rect, found.score)
            }
        }
        val chosen = ArrayList<Candidate>()
        for (item in candidates.sortedByDescending { it.score }) {
            if (chosen.any { kotlin.math.abs(it.rect.x - item.rect.x) < .63f * cellW })
                continue
            chosen += item
            if (chosen.size > 7) return null
        }
        return chosen.sortedBy { it.rect.x }
    }

    private fun badgeCount(
        pixels: IntArray, width: Int, height: Int, rect: Rect, side: Side
    ): Int? {
        // Wars has pale-yellow counts on the *right* of an icon, in contrast
        // to Quest's red counts. The upper count sits lower, the lower count
        // sits just above the icon's right shoulder.
        val x0 = (rect.x + .67f * rect.w).roundToInt().coerceIn(0, width)
        val x1 = (rect.x + 1.32f * rect.w).roundToInt().coerceIn(0, width)
        val y0 = (rect.y + (if (side == Side.WHITE) .33f else -.40f) * rect.h)
            .roundToInt().coerceIn(0, height)
        val y1 = (rect.y + (if (side == Side.WHITE) 1.20f else .45f) * rect.h)
            .roundToInt().coerceIn(0, height)
        if (x1 <= x0 || y1 <= y0) return null
        val regionW = x1 - x0
        val regionH = y1 - y0
        val visited = BooleanArray(regionW * regionH)
        var biggest: List<Int> = emptyList()
        for (y in 0 until regionH) for (x in 0 until regionW) {
            val at = y * regionW + x
            if (visited[at] || !yellow(pixels[(y0 + y) * width + x0 + x])) continue
            val stack = ArrayDeque<Int>()
            val component = ArrayList<Int>()
            stack.addLast(at)
            visited[at] = true
            while (stack.isNotEmpty()) {
                val now = stack.removeLast()
                component.add(now)
                val cy = now / regionW
                val cx = now % regionW
                for (offset in intArrayOf(-1, 1, -regionW, regionW)) {
                    val nx = cx + if (offset == -1) -1 else if (offset == 1) 1 else 0
                    val ny = cy + if (offset == -regionW) -1 else if (offset == regionW) 1 else 0
                    if (nx !in 0 until regionW || ny !in 0 until regionH) continue
                    val index = ny * regionW + nx
                    if (visited[index] ||
                        !yellow(pixels[(y0 + ny) * width + x0 + nx])) continue
                    visited[index] = true
                    stack.addLast(index)
                }
            }
            if (component.size > biggest.size) biggest = component
        }
        if (biggest.size < 80) return 1 // No multiplicity number visible.
        val left = biggest.minOf { it % regionW }
        val right = biggest.maxOf { it % regionW }
        val top = biggest.minOf { it / regionW }
        val bottom = biggest.maxOf { it / regionW }
        if (right - left < 7 || bottom - top < 14) return null
        val pixelsInGlyph = BooleanArray(regionW * regionH)
        for (n in biggest) pixelsInGlyph[n] = true
        val glyph = BooleanArray(16 * 24)
        for (y in 0 until 24) for (x in 0 until 16) {
            val sx = left + x * (right - left + 1) / 16
            val sy = top + y * (bottom - top + 1) / 24
            glyph[y * 16 + x] = pixelsInGlyph[sy * regionW + sx]
        }
        val found = match(mask(glyph, 16, 24), digits) { true } ?: return null
        if (found.score < .80 || found.margin < .09) return null
        return found.code.digitToInt()
    }

    /** Known mask sample, testable without Android or the screenshot. */
    internal fun recognizeReference(code: Char, base64: String): Char? {
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size == 128)
        val bits = BooleanArray(1024) { i ->
            ((bytes[i / 8].toInt() and 255) and (1 shl (7 - i % 8))) != 0
        }
        val ref = match(mask(bits, 32, 32), icons) { it.isUpperCase() == code.isUpperCase() }
        return ref?.takeIf { it.score >= .84 && it.margin >= .075 }?.code
    }

    /**
     * Exact inventory reconciliation. A single holder's count is verified by
     * the physical badge (or a missing badge for one); if two sides share a
     * type, both badges must be recognized before division.
     */
    internal fun reconcile(
        board: List<Piece?>, observed: Map<Side, Map<PieceType, Int>>
    ): Map<Side, Map<PieceType, Int>>? {
        if (board.size != 81) return null
        val expected = mapOf(
            PieceType.PAWN to 18, PieceType.LANCE to 4,
            PieceType.KNIGHT to 4, PieceType.SILVER to 4,
            PieceType.GOLD to 4, PieceType.BISHOP to 2,
            PieceType.ROOK to 2, PieceType.KING to 2
        )
        for ((type, total) in expected) {
            val remain = total - board.count { it?.type == type }
            if (remain < 0) return null
            val held = Side.entries.sumOf { observed[it]?.get(type) ?: 0 }
            if (held != remain) return null
            if (type == PieceType.KING && held != 0) return null
        }
        val result = observed.mapValues { (_, types) -> types.filterValues { it > 0 } }
        return result.takeIf {
            ReviewStateTracker.inventoryIsValid(ShogiPosition(board, Side.BLACK, it, 1))
        }
    }

    fun recognize(
        pixels: IntArray, width: Int, height: Int,
        boardLeft: Float, boardTop: Float, boardWidth: Float, boardHeight: Float,
        board: List<Piece?>
    ): Map<Side, Map<PieceType, Int>>? {
        if (width <= 0 || height <= 0 || pixels.size < width * height || board.size != 81)
            return null
        val observed = mutableMapOf(
            Side.WHITE to mutableMapOf<PieceType, Int>(),
            Side.BLACK to mutableMapOf<PieceType, Int>()
        )
        for (side in listOf(Side.WHITE, Side.BLACK)) {
            val found = locate(pixels, width, height, boardLeft, boardTop,
                boardWidth, boardHeight, side) ?: return null
            for (piece in found) {
                val type = PieceType.fromSfen(piece.code)
                val count = badgeCount(pixels, width, height, piece.rect, side) ?: return null
                // The UI renders one icon per held type, with a number if >1.
                if (type in observed.getValue(side)) return null
                observed.getValue(side)[type] = count
            }
        }
        return reconcile(board, observed)
    }
}
