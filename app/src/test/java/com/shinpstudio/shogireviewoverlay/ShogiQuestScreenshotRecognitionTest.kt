package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Regression against all 81 square masks sampled from the user-supplied
 * Shogi Quest 691x1536 review screenshot (frame: 0.012, 0.179, 0.977, 0.480).
 * Only 32x32 binary ink masks are retained, not the image or user metadata.
 * Uppercase = bottom/sente, lowercase = top/gote.
 */
class ShogiQuestScreenshotRecognitionTest {
    @Test fun recognizesAllPiecesAndTheirOwnerOrientation() {
        val expected = listOf(
            "l..g...nl",
            "..s.k.gs.",
            "p.nppp.p.",
            "......p..",
            "..P.....p",
            "P.r...P.P",
            "...PPPN..",
            ".G..KGS..",
            "LNS....RL"
        )
        val bytes = GZIPInputStream(ByteArrayInputStream(
            Base64.getDecoder().decode(
                File("src/test/resources/quest-review-81-masks.b64").readText().trim()
            )
        )).use { it.readBytes() }
        assertEquals(81 * 128, bytes.size)
        val classifier = PieceTemplateModel.create(
            File("src/main/assets/shogi_quest_templates_v1.txt").readText()
        )
        var matched = 0
        var empty = 0
        var minScore = 1.0
        var minMargin = 1.0
        val observed = CharArray(81)
        for (index in 0 until 81) {
            val expectedCode = expected[index / 9][index % 9]
            val start = index * 128
            val mask = BooleanArray(1024) { i ->
                ((bytes[start + i / 8].toInt() and 0xFF) and
                    (1 shl (7 - i % 8))) != 0
            }
            val actual = classifier.match(PieceTemplateModel.fromMask(mask))
            assertFalse("Uncertain square at row ${index / 9 + 1} col ${index % 9 + 1}", actual.unknown)
            assertEquals("Wrong type/owner at row ${index / 9 + 1} col ${index % 9 + 1}",
                if (expectedCode == '.') null else expectedCode, actual.code)
            observed[index] = actual.code ?: '.'
            if (expectedCode == '.') empty++ else {
                matched++
                minScore = minOf(minScore, actual.score)
                minMargin = minOf(minMargin, actual.margin)
            }
        }
        assertEquals(34, matched)
        assertEquals(47, empty)
        assertEquals(1, observed.count { it == 'K' })
        assertEquals(1, observed.count { it == 'k' })
        assertEquals('r', observed[5 * 9 + 2]) // Opponent's rook advanced into lower half
        assertEquals('R', observed[8 * 9 + 7]) // Sente's rook still on bottom row
        assertEquals('p', observed[4 * 9 + 8]) // Opponent's pawn in red-highlighted square
        assertTrue(minScore > 0.90)
        assertTrue(minMargin > 0.12)
    }
}
