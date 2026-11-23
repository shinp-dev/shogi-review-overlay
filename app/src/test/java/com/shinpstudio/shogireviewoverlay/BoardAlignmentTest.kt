package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.math.roundToInt

class BoardAlignmentTest {
    private fun make(
        dx: Int, dy: Int, missing: Int = 0, occupied: Int = 39,
        kings: Boolean = true, inventory: Boolean = false, score: Double = 0.95
    ) = BoardAlignment.Scan(
        List(81) { null }, dx, dy, missing, occupied, kings, inventory, score, score
    )

    @Test fun correctsOnePixelMisalignmentAfterCoarseSweep() {
        val tested = mutableSetOf<Pair<Int, Int>>()
        val winner = BoardAlignment.searchOffsets(4, { _, _ -> true }) { dx, dy ->
            tested += dx to dy
            val error = (dx - 2) * (dx - 2) + (dy + 1) * (dy + 1)
            make(dx, dy, score = 1.0 - error * 0.008)
        }
        assertEquals(2, winner.dx)
        assertEquals(-1, winner.dy)
        assertTrue(tested.size < 40)
        assertTrue(tested.contains(0 to 0))
        assertTrue(tested.contains(2 to -1))
    }

    @Test fun fullInventoryBeatsHigherButInvalidSimilarity() {
        val valid = make(1, 1, occupied = 40, inventory = true, score = .86)
        val invalid = make(0, 0, occupied = 40, inventory = false, score = .99)
        assertTrue(BoardAlignment.better(valid, invalid))
        assertFalse(BoardAlignment.better(invalid, valid))
    }

    @Test fun lowConfidenceWithUncertainSquaresDoesNotBeatValidBoard() {
        assertTrue(BoardAlignment.better(
            make(2, -1, score = .81),
            make(0, 0, missing = 1, score = .99)
        ))
    }

    @Test fun actualBinaryPieceSamplingPicksShiftedPixels() {
        val content = File("src/main/assets/shogi_templates_v1.txt").readText()
        val model = PieceTemplateModel.create(content)
        val row = content.lineSequence().first { it.startsWith("P:") }
        val packed = Base64.getDecoder().decode(row.substring(2))
        val mask = BooleanArray(1024) { i ->
            (packed[i / 8].toInt() and 255 and (1 shl (7 - i % 8))) != 0
        }

        val w = 760
        val image = IntArray(w * w) { 0xFFFFFFFF.toInt() }
        val baseX = 12f
        val baseY = 12f
        val size = 720f
        val cell = 80f
        val margin = 0.08f
        val truthDx = 2
        val truthDy = -1
        // Simulate the glyph in a central square at the true shifted offset.
        for (y in 0..31) for (x in 0..31) {
            if (!mask[y * 32 + x]) continue
            val px = (baseX + truthDx + (4 + margin) * cell +
                (x + .5f) / 32 * cell * (1 - 2 * margin) - .5f).roundToInt()
            val py = (baseY + truthDy + (4 + margin) * cell +
                (y + .5f) / 32 * cell * (1 - 2 * margin) - .5f).roundToInt()
            image[py * w + px] = 0xFF000000.toInt()
        }
        val correct = PieceTemplateModel.sample(
            image, w, w, baseX + truthDx, baseY + truthDy, size, size, 40
        )
        val shifted = PieceTemplateModel.sample(
            image, w, w, baseX, baseY, size, size, 40
        )
        assertArrayEquals(PieceTemplateModel.fromMask(mask).pixels, correct.pixels)
        assertFalse(correct.pixels.contentEquals(shifted.pixels))

        val winner = BoardAlignment.searchOffsets(4, { _, _ -> true }) { dx, dy ->
            val sample = PieceTemplateModel.sample(
                image, w, w, baseX + dx, baseY + dy, size, size, 40
            )
            val scored = model.match(sample)
            make(dx, dy, missing = if (scored.unknown) 1 else 0,
                occupied = if (scored.code == null) 0 else 1, score = scored.score)
        }
        assertEquals(truthDx, winner.dx)
        assertEquals(truthDy, winner.dy)
    }
}
