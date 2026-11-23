package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Anonymous amber-only occupancy grids sampled from the user's two Wars review
 * screenshots. Each of 14 possible tray slots is represented by 15x19 bits
 * (a 4x4 block majority filter of the original 60x76 slot). There are no
 * screenshots, glyphs, names or player identities in the fixtures.
 */
class WarsHandSlotTest {
    private val width = 691
    private val height = 1536
    private val left = 8f
    private val top = 438f
    private val boardW = 675f
    private val boardH = 729f
    private val recognizer = WarsHandRecognizer(
        File("src/main/assets/shogi_wars_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_wars_hand_digits_v1.txt").readText()
    )

    private fun screenshotOrangeSlots(filename: String): IntArray {
        val bytes = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(
            File("src/test/resources/${filename}").readText().trim()
        ))).use { it.readBytes() }
        assertEquals(499, bytes.size) // 14 slots x 15x19 bits, packed
        val image = IntArray(width * height) { 0xFF888888.toInt() }
        val cellW = boardW / 9f
        val cellH = boardH / 9f
        for (side in listOf(Side.WHITE, Side.BLACK)) {
            val slotOffset = if (side == Side.WHITE) 0 else 7
            for (index in 0..6) {
                val x0 = (left +
                    (if (side == Side.WHITE) .62f * boardW else .01f * boardW) +
                    (if (side == Side.WHITE) -index else index) * cellW).roundToInt()
                val y0 = (if (side == Side.WHITE) top - 1.32f * cellH
                          else top + boardH + .30f * cellH).roundToInt()
                for (row in 0 until 19) for (col in 0 until 15) {
                    val bit = (slotOffset + index) * (15 * 19) + row * 15 + col
                    val gold = (bytes[bit / 8].toInt() and
                        (1 shl (7 - bit % 8))) != 0
                    if (!gold) continue
                    for (dy in 0..3) for (dx in 0..3) {
                        val x = x0 + col * 4 + dx
                        val y = y0 + row * 4 + dy
                        if (x in 0 until width && y in 0 until height)
                            image[y * width + x] = 0xFFDC9B37.toInt()
                    }
                }
            }
        }
        return image
    }

    @Test fun onePieceBelowTwoAboveFromFirstRealScreenshot() {
        val image = screenshotOrangeSlots("wars-hand-orange-3441.b64")
        assertEquals(listOf(0, 1), recognizer.occupiedSlotIndices(
            image, width, height, left, top, boardW, boardH, Side.WHITE
        ))
        assertEquals(listOf(0), recognizer.occupiedSlotIndices(
            image, width, height, left, top, boardW, boardH, Side.BLACK
        ))
    }

    @Test fun threePiecesBelowTwoAboveFromSecondRealScreenshot() {
        val image = screenshotOrangeSlots("wars-hand-orange-3442.b64")
        assertEquals(listOf(0, 1), recognizer.occupiedSlotIndices(
            image, width, height, left, top, boardW, boardH, Side.WHITE
        ))
        assertEquals(listOf(0, 1, 2), recognizer.occupiedSlotIndices(
            image, width, height, left, top, boardW, boardH, Side.BLACK
        ))
    }

    @Test fun emptyTraysDontCreateInventedPieces() {
        val image = IntArray(width * height) { 0xFF888888.toInt() }
        for (side in Side.entries) {
            assertTrue(recognizer.occupiedSlotIndices(
                image, width, height, left, top, boardW, boardH, side
            ).isEmpty())
        }
    }
}
