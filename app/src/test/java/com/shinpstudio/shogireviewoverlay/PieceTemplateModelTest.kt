package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PieceTemplateModelTest {
    @Test fun outerGridLinesAreExcludedFromEverySquare() {
        // 100px-per-square board: paint a 6px black line on all four edges.
        // A correct 8% inset must sample none of those line pixels.
        val w = 900
        val pixels = IntArray(w * w) { 0xFFFFFFFF.toInt() }
        val offset = 4 * 100
        for (y in 0 until 100) for (x in 0 until 100) {
            if (x < 6 || x >= 94 || y < 6 || y >= 94)
                pixels[(offset + y) * w + offset + x] = 0xFF000000.toInt()
        }
        val result = PieceTemplateModel.sample(pixels, w, w, 0f, 0f, 900f, 900f, 40)
        assertEquals("Grid lines leaked into the inner binary mask", 0, result.ink)
        assertTrue(result.pixels.all { it == 0L })
        assertEquals(0, result.axes.sum())
    }

    @Test fun bitmaskAndAxisProjectionsHaveTheSameInkCount() {
        val mask = BooleanArray(1024)
        for (i in 0 until 32) {
            mask[i * 32 + i] = true
            mask[i * 32 + (31 - i)] = true
        }
        val signature = PieceTemplateModel.fromMask(mask)
        assertEquals(64, signature.ink)
        assertEquals(signature.ink, signature.axes.slice(0..31).sum())
        assertEquals(signature.ink, signature.axes.slice(32..63).sum())
        assertEquals(signature.ink, signature.pixels.sumOf { java.lang.Long.bitCount(it) })
    }

    @Test fun bundledTemplatesCanBeLoadedAndIdentified() {
        val file = File("src/main/assets/shogi_templates_v1.txt")
        assertTrue("Template assets not found: " + file.absolutePath, file.exists())
        val content = file.readText()
        val classifier = PieceTemplateModel.create(content)
        for (line in content.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val code = line[0]
            val bits = java.util.Base64.getDecoder().decode(line.substring(2))
            val mask = BooleanArray(1024) { i ->
                ((bits[i / 8].toInt() and 255) and (1 shl (7 - (i % 8)))) != 0
            }
            val match = classifier.match(PieceTemplateModel.fromMask(mask))
            assertEquals("Template $code was misclassified", code, match.code)
            assertTrue("Template confidence too low", match.score > 0.99)
        }
    }
}
