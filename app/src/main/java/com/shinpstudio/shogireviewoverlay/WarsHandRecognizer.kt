package com.shinpstudio.shogireviewoverlay

import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Locates Shogi Wars' variable-position holding pieces without assuming
 * a specific left-to-right order. Uses cropped glyph masks only.
 *
 * Strict 40-piece inventory validation is required. An unreadable visible count
 * can be recovered only when the inventory makes it unique; ambiguous icons
 * or counts fail closed. One-step history remains the fallback.
 */
internal class WarsHandRecognizer(iconsText: String, digitsText: String) {
    private data class Mask(val bits: BooleanArray, val axes: IntArray, val ink: Int)
    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class Candidate(val code: Char, val rect: Rect, val score: Double)
    private data class Match<T>(val code: T, val score: Double, val margin: Double)
    private val icons = load(iconsText, 32, 32) { it.single() }
    private val digits = load(digitsText, 16, 24) { it.toInt().also { n -> require(n in 2..18) } }

    init {
        require("bpPLNSGBR".all { it in icons })
        require(2 in digits && 3 in digits)
    }

    private fun <T> load(text: String, w: Int, h: Int, key: (String) -> T): Map<T, List<Mask>> {
        val entries = mutableMapOf<T, MutableList<Mask>>()
        for (line in text.lineSequence().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.indexOf(':')
            require(separator > 0 && separator < line.lastIndex)
            val bytes = Base64.getDecoder().decode(line.substring(separator + 1))
            require(bytes.size == w * h / 8)
            val bits = BooleanArray(w * h) { i ->
                ((bytes[i / 8].toInt() and 255) and (1 shl (7 - (i % 8)))) != 0
            }
            entries.getOrPut(key(line.substring(0, separator))) { mutableListOf() }.add(mask(bits, w, h))
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

    private fun <T> match(actual: Mask, refs: Map<T, List<Mask>>, choices: (T) -> Boolean): Match<T>? {
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

    // Wars uses a seven-place holding tray. The upper tray is right-aligned
    // (its first place is next to the controls), the lower tray left-aligned.
    // The position of an occupied piece changes when the number of distinct
    // held types changes, but the *possible places* form a regular array.
    // Check only these 14 places for the orange piece silhouette. Avoid the
    // old full-width sliding-template search and background false positives.
    private fun slot(boardLeft: Float, boardTop: Float, boardW: Float,
                     boardH: Float, side: Side, index: Int): Rect {
        val cellW = boardW / 9f
        val cellH = boardH / 9f
        val startX = if (side == Side.WHITE) boardLeft + .62f * boardW
                     else boardLeft + .004f * boardW
        // The lower tray pitch is slightly narrower than a board cell.
        // The seventh icon otherwise overlaps the player's avatar.
        val pitch = if (side == Side.WHITE) cellW else .975f * cellW
        val x = startX + (if (side == Side.WHITE) -index else index) * pitch
        val y = if (side == Side.WHITE) boardTop - 1.32f * cellH
                else boardTop + boardH + .30f * cellH
        return Rect(
            x.roundToInt(), y.roundToInt(),
            (.80f * cellW).roundToInt(), (.94f * cellH).roundToInt()
        )
    }

    private fun orange(color: Int): Boolean {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        return r > 160 && g > 85 && b < 160 &&
            r > g + 20 && g > b + 15
    }

    /**
     * The orange-tan piece body must fill at least half of the central slot.
     * Extract a local orange bounding box and use it only to compensate for
     * small (<=4 px) differences in scaling or screenshot alignment.
     */
    private fun orangeRect(pixels: IntArray, width: Int, height: Int,
                           expected: Rect): Rect? {
        if (expected.x < 0 || expected.y < 0 ||
            expected.x + expected.w >= width ||
            expected.y + expected.h >= height ||
            expected.w < 30 || expected.h < 30) return null
        val x0 = expected.x + (.13f * expected.w).roundToInt()
        val x1 = expected.x + (.80f * expected.w).roundToInt()
        val y0 = expected.y + (.14f * expected.h).roundToInt()
        val y1 = expected.y + (.78f * expected.h).roundToInt()
        val area = (x1 - x0) * (y1 - y0)
        var occupied = 0
        var minX = x1
        var minY = y1
        var maxX = x0 - 1
        var maxY = y0 - 1
        for (y in y0 until y1) for (x in x0 until x1) {
            if (!orange(pixels[y * width + x])) continue
            occupied++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (occupied < .48f * area ||
            maxX - minX < .40f * (x1 - x0) ||
            maxY - minY < .40f * (y1 - y0)) return null
        val x = (minX - (.13f * expected.w).roundToInt())
            .coerceIn(expected.x - 4, expected.x + 4)
        val y = (minY - (.14f * expected.h).roundToInt())
            .coerceIn(expected.y - 4, expected.y + 4)
        return Rect(x, y, expected.w, expected.h)
    }

    /** Testable set of occupied slot indices, independent of glyph recognition. */
    internal fun occupiedSlotIndices(
        pixels: IntArray, width: Int, height: Int,
        boardLeft: Float, boardTop: Float, boardWidth: Float, boardHeight: Float,
        side: Side
    ): List<Int> = (0..6).filter { i ->
        orangeRect(pixels, width, height,
            slot(boardLeft, boardTop, boardWidth, boardHeight, side, i)) != null
    }

    private fun locate(
        pixels: IntArray, width: Int, height: Int, left: Float, top: Float,
        boardW: Float, boardH: Float, side: Side
    ): List<Candidate>? {
        val selected = ArrayList<Candidate>()
        for (index in 0..6) {
            val rect = orangeRect(pixels, width, height,
                slot(left, top, boardW, boardH, side, index)) ?: continue
            val signature = sample(pixels, width, height, rect)
            val found = match(signature, icons) {
                if (side == Side.WHITE) it.isLowerCase() else it.isUpperCase()
            } ?: return null
            // An occupied slot must have a verified piece glyph. Never treat a
            // mismatched orange panel as an empty position.
            if (signature.ink < 90 || signature.ink > 600 ||
                found.score < .84 || found.margin < .075) return null
            selected += Candidate(found.code, rect, found.score)
        }
        return selected.sortedBy { it.rect.x }
    }

    private fun badgeCount(
        pixels: IntArray, width: Int, height: Int, rect: Rect, side: Side
    ): Int? = readBadgeForIcon(pixels, width, height, rect.x, rect.y, rect.w, rect.h, side)

    /** Both trays use the same upright yellow glyphs and numeric templates.
     * Only the vertical search window is different. Never rotate the digits.
     * A clipped window is unknown rather than proof of a missing badge.
     */
    internal fun readBadgeForIcon(
        pixels: IntArray, width: Int, height: Int,
        x: Int, y: Int, w: Int, h: Int, side: Side
    ): Int? {
        if (w <= 0 || h <= 0) return null
        val x0 = (x + .43f * w).roundToInt()
        val x1 = (x + 1.32f * w).roundToInt()
        val y0 = (y + (if (side == Side.WHITE) .33f else -.30f) * h).roundToInt()
        val y1 = (y + (if (side == Side.WHITE) 1.20f else .33f) * h).roundToInt()
        return readBadgeInRegion(pixels, width, height, x0, x1, y0, y1)
    }

    /**
     * Shared production segmentation for unit tests based on anonymized,
     * real screenshot badge pixels. Unknown numerals are not guessed.
     */
    internal fun readBadgeInRegion(
        pixels: IntArray, width: Int, height: Int,
        x0: Int, x1: Int, y0: Int, y1: Int
    ): Int? {
        if (width <= 0 || height <= 0 || x0 < 0 || y0 < 0 ||
            x1 <= x0 || y1 <= y0 || x1 > width || y1 > height ||
            pixels.size < width * height) return null
        val regionW = x1 - x0
        val regionH = y1 - y0
        val visited = BooleanArray(regionW * regionH)
        val components = ArrayList<List<Int>>()
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
            if (component.size >= 80 &&
                component.maxOf { it / regionW } - component.minOf { it / regionW } >= 14)
                components.add(component)
        }
        if (components.isEmpty()) return 1 // No full-height multiplicity number visible.
        // 10 has two separate components; 11 can be joined at the baseline.
        // Merge only adjacent components on the same baseline, not the
        // yellow decoration underneath the lower tray.
        val biggest = components.maxBy { it.size }
        val primaryLeft = biggest.minOf { it % regionW }
        val primaryRight = biggest.maxOf { it % regionW }
        val primaryTop = biggest.minOf { it / regionW }
        val primaryBottom = biggest.maxOf { it / regionW }
        val tolerance = max(2, (primaryBottom - primaryTop + 1) / 6)
        val selected = components.filter { c ->
            val l = c.minOf { it % regionW }
            val r = c.maxOf { it % regionW }
            val t = c.minOf { it / regionW }
            val b = c.maxOf { it / regionW }
            kotlin.math.abs(t - primaryTop) <= tolerance &&
                kotlin.math.abs(b - primaryBottom) <= tolerance &&
                max(l - primaryRight - 1, primaryLeft - r - 1) <= tolerance + 1
        }
        if (selected.size != components.size || selected.size > 2) return null
        val combined = selected.flatten()
        val left = combined.minOf { it % regionW }
        val right = combined.maxOf { it % regionW }
        val top = combined.minOf { it / regionW }
        val bottom = combined.maxOf { it / regionW }
        if (right - left < 7 || bottom - top < 14) return null
        val aspect = (right - left + 1).toDouble() / (bottom - top + 1)
        val twoDigits = aspect > .95
        if (aspect > 1.9 || (selected.size > 1 && !twoDigits)) return null
        val pixelsInGlyph = BooleanArray(regionW * regionH)
        for (n in combined) pixelsInGlyph[n] = true
        val glyph = BooleanArray(16 * 24)
        for (y in 0 until 24) for (x in 0 until 16) {
            val sx = left + x * (right - left + 1) / 16
            val sy = top + y * (bottom - top + 1) / 24
            glyph[y * 16 + x] = pixelsInGlyph[sy * regionW + sx]
        }
        val found = match(mask(glyph, 16, 24), digits) { (it >= 10) == twoDigits } ?: return null
        if (found.score < (if (twoDigits) .94 else .80) || found.margin < .09) return null
        return found.code
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
     * Exact inventory reconciliation. A null value means an identified
     * holding icon with a visible but unreadable count, not a missing icon.
     * Recover at most one unknown holder per type using the physical inventory.
     * Never change a confidently read count or invent an owner.
     */
    internal fun reconcile(
        board: List<Piece?>, observed: Map<Side, Map<PieceType, Int?>>
    ): Map<Side, Map<PieceType, Int>>? {
        if (board.size != 81) return null
        val expected = mapOf(
            PieceType.PAWN to 18, PieceType.LANCE to 4,
            PieceType.KNIGHT to 4, PieceType.SILVER to 4,
            PieceType.GOLD to 4, PieceType.BISHOP to 2,
            PieceType.ROOK to 2, PieceType.KING to 2
        )
        val result = Side.entries.associateWith { mutableMapOf<PieceType, Int>() }
        for ((type, total) in expected) {
            val remain = total - board.count { it?.type == type }
            if (remain < 0) return null
            val owners = Side.entries.filter { observed[it]?.containsKey(type) == true }
            if (type == PieceType.KING && owners.isNotEmpty()) return null
            var known = 0
            val unknown = mutableListOf<Side>()
            for (owner in owners) {
                val count = observed.getValue(owner)[type]
                if (count == null) {
                    unknown.add(owner)
                } else {
                    if (count !in 1..total) return null
                    known += count
                    result.getValue(owner)[type] = count
                }
            }
            if (unknown.isEmpty()) {
                if (known != remain) return null
            } else if (unknown.size == 1) {
                val inferred = remain - known
                // A visible unreadable numeral must represent two or more.
                if (inferred !in 2..total) return null
                result.getValue(unknown.single())[type] = inferred
            } else return null
        }
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
            Side.WHITE to mutableMapOf<PieceType, Int?>(),
            Side.BLACK to mutableMapOf<PieceType, Int?>()
        )
        for (side in listOf(Side.WHITE, Side.BLACK)) {
            val found = locate(pixels, width, height, boardLeft, boardTop,
                boardWidth, boardHeight, side) ?: return null
            for (piece in found) {
                val type = PieceType.fromSfen(piece.code)
                val count = badgeCount(pixels, width, height, piece.rect, side)
                // The UI renders one icon per held type, with a number if >1.
                if (type in observed.getValue(side)) return null
                observed.getValue(side)[type] = count
            }
        }
        return reconcile(board, observed)
    }
}
