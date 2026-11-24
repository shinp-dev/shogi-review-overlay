package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Real screenshots, reduced to three 40x67 binary masks of *yellow pixels
 * only* (no board, player names, ads or full images). Sources: upper tray
 * bishop x2 and pawn x4/x6 in the two supplied 691x1536 Wars replays.
 *
 * Runs the same connected component and scoring function as production,
 * rather than testing only template self-similarity.
 */
class WarsHandDigitScreenshotTest {
    private val recognizer = WarsHandRecognizer(
        File("src/main/assets/shogi_wars_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_wars_hand_digits_v1.txt").readText()
    )

    @Test fun readsYellowTwoFourSixFromActualBadgeShapes() {
        val masks = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(
            File("src/test/resources/wars-yellow-count-246.b64").readText().trim()
        ))).use { it.readBytes() }
        val width = 40
        val height = 67
        assertEquals(3 * width * height / 8, masks.size)
        for ((index, number) in listOf(2, 4, 6).withIndex()) {
            val pixels = IntArray(width * height) { i ->
                val bit = index * width * height + i
                val on = ((masks[bit / 8].toInt() and 255) and
                    (1 shl (7 - bit % 8))) != 0
                if (on) 0xFFFFEA78.toInt() else 0xFF121212.toInt()
            }
            assertEquals(
                "Misread screenshot yellow badge $number",
                number, recognizer.readBadgeInRegion(
                    pixels, width, height, 0, width, 0, height
                )
            )
        }
    }

    @Test fun readsLowerTraySixThroughElevenAndTwoWithRealBackgroundPixels() {
        val masks = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(
            File("src/test/resources/wars-yellow-count-6to11.b64").readText().trim()
        ))).use { it.readBytes() }
        val width = 56
        val height = 44
        val expected = listOf(6, 7, 8, 9, 10, 11, 2)
        assertEquals(expected.size * width * height / 8, masks.size)
        for ((index, number) in expected.withIndex()) {
            val pixels = IntArray(width * height) { i ->
                val bit = index * width * height + i
                val on = ((masks[bit / 8].toInt() and 255) and
                    (1 shl (7 - bit % 8))) != 0
                if (on) 0xFFFFEA78.toInt() else 0xFF121212.toInt()
            }
            assertEquals("Misread lower-tray badge $number", number,
                recognizer.readBadgeInRegion(pixels, width, height, 0, width, 0, height))
        }
    }

    @Test fun noBadgeMeansOneButOutOfFrameIsRejected() {
        val width = 40
        val height = 67
        val pixels = IntArray(width * height) { 0xFF121212.toInt() }
        assertEquals(1, recognizer.readBadgeInRegion(pixels, width, height,
            0, width, 0, height))
        assertNull(recognizer.readBadgeInRegion(pixels, width, height,
            -1, width, 0, height))
    }

    @Test fun onlyScreenshotConfirmedDigitsAreListed() {
        val rows = File("src/main/assets/shogi_wars_hand_digits_v1.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(setOf(2, 3, 4, 6, 7, 8, 9, 10, 11), rows.map { it.substringBefore(':').toInt() }.toSet())
        for (row in rows) {
            assertEquals(48, Base64.getDecoder().decode(row.substringAfter(':')).size)
        }
    }
}
