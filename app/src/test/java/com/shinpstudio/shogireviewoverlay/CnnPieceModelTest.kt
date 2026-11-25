package com.shinpstudio.shogireviewoverlay

import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CnnPieceModelTest {
    private val model = CnnPieceModel(File("src/main/assets/shogi_cnn_v1.txt").readText())

    @Test fun matchesPyTorchReferenceLogits() {
        val logits = model.logits(FloatArray(1024) { .25f })
        val expected = floatArrayOf(18.809498f, -9.237596f, -8.508749f, -4.312319f, -9.454449f)
        expected.forEachIndexed { i, value -> assertEquals(value, logits[i], .002f) }
        val result = model.match(FloatArray(1024) { .25f })
        assertFalse(result.unknown)
        assertNull(result.code)
    }

    @Test fun trainedTemplatesClassifyCorrectlyOrFailClosed() {
        val names = listOf("shogi_quest_templates_v1.txt", "shogi_quest_variants_v1.txt",
            "shogi_quest_promoted_v1.txt", "shogi_templates_v1.txt",
            "shogi_wars_variants_v1.txt", "shogi_wars_promoted_v1.txt")
        var accepted = 0
        var total = 0
        for (name in names) for (line in File("src/main/assets/$name").readLines()) {
            if (line.length < 2 || line[1] != ':') continue
            val bytes = Base64.getDecoder().decode(line.substring(2))
            val input = FloatArray(1024) { i ->
                if ((bytes[i/8].toInt() and (1 shl (7-i%8))) != 0) .85f else .2f
            }
            val result = model.match(input)
            total++
            if (!result.unknown) {
                assertEquals("Wrong accepted code in $name", line[0], result.code)
                accepted++
            }
        }
        assertEquals(85, total)
        assertTrue(accepted >= 84)
    }
}
