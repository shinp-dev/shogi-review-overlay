package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Reproduces the *real red-pixel segmentation path* for five numbered holding
 * pieces from the user's five Quest review screenshots. No identifying text,
 * actual screenshot, player details or advertisements are stored.
 *
 * Each frame is a 60x60 bitmap of RED PIXELS ONLY, cropped at the same bottom
 * hand slot. Digit order in fixture: 4, 5, 7, 8, 9.
 */
class QuestHandDigitScreenshotTest {
    private val recognizer = QuestHandRecognizer(
        File("src/main/assets/shogi_quest_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_quest_hand_digits_v1.txt").readText()
    )

    @Test fun readsFivePreviouslyUnverifiedRealBadgesIncludingNine() {
        val source = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(
            File("src/test/resources/quest-red-digits-45789.b64").readText().trim()
        ))).use { it.readBytes() }
        assertEquals(5 * 60 * 60 / 8, source.size)
        val actual = ArrayList<Int?>()
        for (index in 0 until 5) {
            val patch = IntArray(60 * 60) { i ->
                val bitIndex = index * 60 * 60 + i
                val red = ((source[bitIndex / 8].toInt() and 255) and
                    (1 shl (7 - bitIndex % 8))) != 0
                if (red) 0xFFC3231E.toInt() else 0xFF092844.toInt()
            }
            actual += recognizer.readRedBadgeInRegion(
                patch, 60, 60, 0, 60, 0, 60
            )
        }
        assertEquals(listOf(4, 5, 7, 8, 9), actual)
    }

    @Test fun noRedBadgeMeansExactlyOnePiece() {
        val empty = IntArray(60 * 60) { 0xFF092844.toInt() }
        assertEquals(1, recognizer.readRedBadgeInRegion(empty, 60, 60, 0, 60, 0, 60))
    }

    @Test fun invalidBadgeRegionFailsClosed() {
        val pixels = IntArray(60 * 60)
        assertNull(recognizer.readRedBadgeInRegion(pixels, 60, 60, -1, 30, 0, 60))
        assertNull(recognizer.readRedBadgeInRegion(pixels, 60, 60, 0, 61, 0, 60))
    }

    @Test fun everyStoredDigitHasAFull48BytePackedMask() {
        val entries = File("src/main/assets/shogi_quest_hand_digits_v1.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(10, entries.size)
        for (entry in entries) {
            assertEquals("Digit ${entry[0]} corrupt", 48,
                Base64.getDecoder().decode(entry.substring(2)).size)
        }
    }
}
