package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Synthetic full-screen replay using the exact binary glyph/digit samples
 * extracted from the provided Quest 78-move screenshot. The coordinates,
 * hand order and count badges match that screenshot; no screenshot/avatar or
 * participant identifiers are committed.
 */
class QuestHandRecognizerTest {
    private val width = 691
    private val height = 1536
    private val frame = IntArray(width * height) { 0xFF0B2545.toInt() }
    private val gold = 0xFFDC9B37.toInt()
    private val black = 0xFF101010.toInt()
    private val red = 0xFFC3231E.toInt()
    private val iconMasks = load("shogi_quest_hand_icons_v1.txt", 32, 32)
    private val digitMasks = load("shogi_quest_hand_digits_v1.txt", 16, 24)
    private val recognizer = QuestHandRecognizer(
        File("src/main/assets/shogi_quest_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_quest_hand_digits_v1.txt").readText()
    )

    private fun load(file: String, w: Int, h: Int): Map<Char, BooleanArray> =
        File("src/main/assets/${file}").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val bytes = Base64.getDecoder().decode(line.substring(2))
                line[0] to BooleanArray(w * h) { i ->
                    ((bytes[i / 8].toInt() and 255) and (1 shl (7 - i % 8))) != 0
                }
            }

    private data class Slot(val left: Int, val right: Int, val top: Int, val bottom: Int)

    private val topIcons = listOf(
        Slot(174, 222, 233, 305),
        Slot(390, 452, 236, 307)
    )
    private val bottomIcons = listOf(
        Slot(24, 68, 1073, 1146),
        Slot(96, 147, 1073, 1139),
        Slot(169, 223, 1072, 1140),
        Slot(241, 302, 1072, 1142),
        Slot(316, 377, 1072, 1142),
        Slot(391, 448, 1070, 1147),
        Slot(466, 528, 1070, 1143)
    )

    private fun put(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) frame[y * width + x] = color
    }

    private fun paintIcon(slot: Slot, code: Char) {
        for (y in slot.top..slot.bottom) for (x in slot.left until slot.right)
            put(x, y, gold)
        val glyph = requireNotNull(iconMasks[code])
        val x0 = slot.left + .145f * (slot.right - slot.left)
        val x1 = slot.right - .145f * (slot.right - slot.left)
        val y0 = slot.top + .14f * (slot.bottom - slot.top)
        val y1 = slot.bottom - .12f * (slot.bottom - slot.top)
        for (y in 0..31) for (x in 0..31) {
            if (glyph[y * 32 + x]) {
                put((x0 + (x1 - x0) * x / 31f).roundToInt(),
                    (y0 + (y1 - y0) * y / 31f).roundToInt(), black)
            }
        }
    }

    private fun paintDigit(code: Char, x0: Int, y0: Int, boxW: Int = 22, boxH: Int = 34) {
        val mask = requireNotNull(digitMasks[code])
        for (y in 0 until boxH) for (x in 0 until boxW) {
            val cy = ((y + .5f) * 24 / boxH).toInt().coerceIn(0, 23)
            val cx = ((x + .5f) * 16 / boxW).toInt().coerceIn(0, 15)
            if (mask[cy * 16 + cx]) put(x0 + x, y0 + y, red)
        }
    }

    private val expectedBoard = listOf(
        "l.......Y",
        ".g.......",
        "ns.......",
        ".k.p.....",
        "....P....",
        "p........",
        "P..P.PP.P",
        "..TG.K...",
        "LN...GSNL"
    ).flatMap { row -> row.map { char ->
        if (char == '.') null else PieceTemplateModel.pieceForCode(char)
    } }

    private val correctHands = mapOf(
        Side.WHITE to mapOf(PieceType.PAWN to 3, PieceType.SILVER to 1),
        Side.BLACK to mapOf(
            PieceType.PAWN to 6, PieceType.LANCE to 1, PieceType.KNIGHT to 1,
            PieceType.SILVER to 1, PieceType.GOLD to 1,
            PieceType.BISHOP to 2, PieceType.ROOK to 1
        )
    )

    private fun drawReplay(pawnNumber: Char = '6') {
        for ((slot, code) in topIcons.zip("ps".toList())) paintIcon(slot, code)
        for ((slot, code) in bottomIcons.zip("PLNSGBR".toList())) paintIcon(slot, code)
        paintDigit('3', 160, 232, 22, 34) // Gote pawn x3
        paintDigit(pawnNumber, 58, 1114, 22, 33) // Sente pawn x6
        paintDigit('2', 433, 1114, 23, 33) // Sente bishop x2
    }

    @Test fun readsBothHoldersAndCountsAtMove78AndBuildsAnSfen() {
        assertEquals(23, expectedBoard.count { it != null })
        drawReplay()
        val hands = recognizer.recognize(
            frame, width, height, 8f, 322f, 675f, 737f, expectedBoard
        )
        assertEquals(correctHands, hands)
        val state = ShogiPosition(expectedBoard, Side.BLACK, requireNotNull(hands), 1)
        assertTrue(ReviewStateTracker.inventoryIsValid(state))
        assertEquals(state, Sfen.parse(ReviewStateTracker.format(state)))
    }

    @Test fun rejectsCountsThatConflictWithTheFortyPieceInventory() {
        drawReplay(pawnNumber = '2')
        val hands = recognizer.recognize(
            frame, width, height, 8f, 322f, 675f, 737f, expectedBoard
        )
        assertNull(hands)
    }

    @Test fun refusesAnIncompleteBoardOrMissingTray() {
        drawReplay()
        assertNull(recognizer.recognize(
            frame, width, height, 8f, 322f, 675f, 737f,
            expectedBoard.toMutableList().also { it[0] = null }
        ))
        assertNull(recognizer.recognize(
            frame, width, height, 8f, 0f, 675f, 737f, expectedBoard
        ))
    }

    @Test fun fixedSlotsRecoverHandsEvenWhenPieceGlyphsAreUnreadable() {
        drawReplay()
        // Simulate a glyph appearance that fails the original template
        // classifier: replace the glyph interior with gold without moving
        // any icon or changing its red count badge.
        for (slot in topIcons + bottomIcons) {
            for (y in slot.top..slot.bottom) for (x in slot.left until slot.right) {
                if (frame[y * width + x] == black) put(x, y, gold)
            }
        }
        assertEquals(correctHands, recognizer.recognize(
            frame, width, height, 8f, 322f, 675f, 737f, expectedBoard
        ))
    }

}
