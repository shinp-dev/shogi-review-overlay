package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * All 81 cropped cells from the supplied 691x1536 Shogi Wars screenshot.
 * The fixture is compressed binary ink masks only: it contains neither
 * user names nor the source screenshot.
 *
 * Codes T/U/V/W/X/Y are promoted P/L/N/S/B/R respectively.
 */
class ShogiWarsPromotedRecognitionTest {
    private fun loadModel() = PieceTemplateModel.create(
        listOf(
            "shogi_templates_v1.txt",
            "shogi_wars_variants_v1.txt",
            "shogi_wars_promoted_v1.txt"
        ).joinToString("\n") { File("src/main/assets/${it}").readText() }
    )

    @Test fun all81SquaresIdentifyPiecePromotionAndSide() {
        val expected = listOf(
            "..TW...s.",
            "...X....l",
            "UGY.pk..n",
            "...V...p.",
            ".....p.Pp",
            "P..p..p..",
            ".P.PPPP.P",
            ".....S...",
            "L...KG.NL"
        )
        assertTrue(expected.all { it.length == 9 })
        val binary = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(
            File("src/test/resources/wars-promoted-81-masks.b64").readText().trim()
        ))).use { it.readBytes() }
        assertEquals(81 * 128, binary.size)

        val classifier = loadModel()
        var pieces = 0
        var empty = 0
        var promoted = 0
        var smallestScore = 1.0
        var smallestMargin = 1.0
        val board = ArrayList<Piece?>(81)
        for (i in 0 until 81) {
            val start = i * 128
            val mask = BooleanArray(1024) { j ->
                ((binary[start + j / 8].toInt() and 0xFF) and
                    (1 shl (7 - j % 8))) != 0
            }
            val result = classifier.match(PieceTemplateModel.fromMask(mask))
            val expectedCode = expected[i / 9][i % 9]
            assertFalse("Uncertain at row ${i / 9 + 1}, col ${i % 9 + 1}", result.unknown)
            assertEquals("Wrong piece/promotion/owner at row ${i / 9 + 1}, col ${i % 9 + 1}",
                if (expectedCode == '.') null else expectedCode, result.code)
            board += result.code?.let(PieceTemplateModel::pieceForCode)
            if (expectedCode == '.') {
                empty++
            } else {
                pieces++
                smallestScore = minOf(smallestScore, result.score)
                smallestMargin = minOf(smallestMargin, result.margin)
                if (expectedCode in "TUVWXYtuvwxy") promoted++
            }
        }
        assertEquals(31, pieces)
        assertEquals(50, empty)
        assertEquals(6, promoted)
        assertTrue("Score threshold too low: $smallestScore", smallestScore > 0.90)
        assertTrue("Top-2 ambiguity too high: $smallestMargin", smallestMargin > 0.15)

        assertEquals(1, board.count { it?.type == PieceType.KING && it.side == Side.BLACK })
        assertEquals(1, board.count { it?.type == PieceType.KING && it.side == Side.WHITE })
        // The promoted bishop (horse) and rook (dragon) retain the player's ownership.
        assertEquals(Piece(PieceType.BISHOP, Side.BLACK, true), board[1 * 9 + 3])
        assertEquals(Piece(PieceType.ROOK, Side.BLACK, true), board[2 * 9 + 2])
        assertEquals(Piece(PieceType.LANCE, Side.BLACK, true), board[2 * 9])
        assertEquals(Piece(PieceType.KNIGHT, Side.BLACK, true), board[3 * 9 + 3])

        // Visible held pieces in the source screenshot: sente holds
        // pawn x3, knight x1, silver x1, gold x2, bishop x1, rook x1.
        val hands = mapOf(
            Side.BLACK to mapOf(
                PieceType.PAWN to 3,
                PieceType.KNIGHT to 1,
                PieceType.SILVER to 1,
                PieceType.GOLD to 2,
                PieceType.BISHOP to 1,
                PieceType.ROOK to 1
            ),
            Side.WHITE to emptyMap()
        )
        val state = ShogiPosition(board, Side.BLACK, hands, 1)
        assertTrue("Detected piece inventory does not add up to 40", ReviewStateTracker.inventoryIsValid(state))
        val sfen = ReviewStateTracker.format(state)
        assertEquals(state.board, Sfen.parse(sfen).board)
        assertEquals(state.hands, Sfen.parse(sfen).hands)
    }

    @Test fun originalBasicPiecesStillRecognizeAfterAddingNewReferences() {
        val model = loadModel()
        val rows = File("src/main/assets/shogi_templates_v1.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(16, rows.size)
        for (row in rows) {
            val actual = model.match(fromPacked(row.substring(2)))
            assertEquals("Old template ${row[0]} regressed", row[0], actual.code)
        }
    }

    @Test fun provisionalGotePromotionAliasesKeepTheirPieceAndDirection() {
        val model = loadModel()
        val promoted = File("src/main/assets/shogi_wars_promoted_v1.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(12, promoted.size)
        val types = listOf(
            PieceType.PAWN, PieceType.LANCE, PieceType.KNIGHT,
            PieceType.SILVER, PieceType.BISHOP, PieceType.ROOK
        )
        for ((index, letter) in "TUVWXY".withIndex()) {
            for (code in listOf(letter, letter.lowercaseChar())) {
                val row = promoted.single { it[0] == code }
                assertEquals(code, model.match(fromPacked(row.substring(2))).code)
                assertEquals(
                    Piece(types[index], if (code.isUpperCase()) Side.BLACK else Side.WHITE, true),
                    PieceTemplateModel.pieceForCode(code)
                )
            }
        }
    }

    private fun fromPacked(packed: String): PieceTemplateModel.Signature {
        val bytes = Base64.getDecoder().decode(packed)
        assertEquals(128, bytes.size)
        return PieceTemplateModel.fromMask(BooleanArray(1024) { i ->
            ((bytes[i / 8].toInt() and 0xFF) and (1 shl (7 - i % 8))) != 0
        })
    }
}
