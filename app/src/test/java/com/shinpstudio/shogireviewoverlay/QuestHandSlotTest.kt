package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Real Quest screenshots, but only anonymized 16x16 gold-presence masks of
 * each of the 18 candidate hand positions, not the source photos or names.
 */
class QuestHandSlotTest {
    private val width = 691
    private val height = 1536
    private val boardLeft = 8f
    private val boardWidth = 675f
    private val gold = 0xFFD69B36.toInt()
    private val background = 0xFF092844.toInt()
    private val recognizer = QuestHandRecognizer(
        File("src/main/assets/shogi_quest_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_quest_hand_digits_v1.txt").readText()
    )

    private fun frame(filename: String, boardTop: Float, boardBottom: Float): IntArray {
        val encoded = File("src/test/resources/${filename}").readText().trim()
        val packed = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
            .use { it.readBytes() }
        assertEquals(576, packed.size) // 2 sides x 9 slots x 16 x 16 bits
        val pixels = IntArray(width * height) { background }
        val cellW = boardWidth / 9f
        val cellH = (boardBottom - boardTop) / 9f
        for (side in listOf(Side.WHITE, Side.BLACK)) for (index in 0..8) {
            val slotIndex = (if (side == Side.WHITE) 0 else 9) + index
            val x0 = (boardLeft + (index + .08f) * cellW).roundToInt()
            val y0 = (if (side == Side.WHITE) boardTop - 1.17f * cellH
                      else boardBottom + .07f * cellH).roundToInt()
            val sw = (.84f * cellW).roundToInt()
            val sh = (1.10f * cellH).roundToInt()
            val cx0 = x0 + (.17f * sw).roundToInt()
            val cx1 = x0 + (.83f * sw).roundToInt()
            val cy0 = y0 + (.22f * sh).roundToInt()
            val cy1 = y0 + (.79f * sh).roundToInt()
            for (gy in 0 until 16) for (gx in 0 until 16) {
                val bitIndex = slotIndex * 256 + gy * 16 + gx
                val isGold = ((packed[bitIndex / 8].toInt() and 255)
                    and (1 shl (7 - bitIndex % 8))) != 0
                if (!isGold) continue
                val ya = cy0 + gy * (cy1 - cy0) / 16
                val yb = cy0 + (gy + 1) * (cy1 - cy0) / 16
                val xa = cx0 + gx * (cx1 - cx0) / 16
                val xb = cx0 + (gx + 1) * (cx1 - cx0) / 16
                for (y in ya until yb) for (x in xa until xb) {
                    if (x in 0 until width && y in 0 until height)
                        pixels[y * width + x] = gold
                }
            }
        }
        return pixels
    }

    private fun check(
        filename: String, top: Float, bottom: Float,
        expectedUpper: List<Int>, expectedLower: List<Int>
    ) {
        val img = frame(filename, top, bottom)
        val ch = bottom - top
        val upper = recognizer.occupiedSlotIndices(
            img, width, height, boardLeft, top, boardWidth, ch, Side.WHITE
        )
        val lower = recognizer.occupiedSlotIndices(
            img, width, height, boardLeft, top, boardWidth, ch, Side.BLACK
        )
        assertEquals("Upper hand from ${filename}", expectedUpper, upper)
        assertEquals("Lower hand from ${filename}", expectedLower, lower)
    }

    @Test fun screenshotOneHasSeparatedTopAndBottomPieces() {
        check("quest-hand-slots-3436.b64", 275f, 1014f, listOf(7), listOf(0, 5))
    }

    @Test fun screenshotTwoHasThreeNoncontiguousUpperPositions() {
        check("quest-hand-slots-3438.b64", 314f, 1060f,
            listOf(2, 3, 8), listOf(0, 4))
    }

    @Test fun screenshotThreeHasSevenAdjacentLowerPositionsAndTwoUpperGaps() {
        check("quest-hand-slots-3440.b64", 315f, 1061f,
            listOf(2, 5), (0..6).toList())
    }

    @Test fun emptyPositionsRemainEmpty() {
        val img = IntArray(width * height) { background }
        for (side in Side.entries) {
            assertTrue(recognizer.occupiedSlotIndices(
                img, width, height, boardLeft, 315f, boardWidth, 746f, side
            ).isEmpty())
        }
    }
}
