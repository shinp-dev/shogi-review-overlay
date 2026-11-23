package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Shogi Quest promoted position: all 81 sampled squares, not the original screenshot. */
class ShogiQuestPromotedRecognitionTest {
    @Test fun promotedPiecesAndOwnerOrientationAreRecognized() {
        // T/U/V/W/X/Y = +P/+L/+N/+S/+B/+R; lowercase x is the real
        // gote-promoted bishop (horse) seen near the lower-left of the board.
        val rows = listOf(
            "XT.T.k...",
            ".V.U...s.",
            "WYV...n..",
            "....p..pl",
            ".....pp..",
            "p.......P",
            "xP.PPPPP.",
            "..S......",
            "...GKGSNL"
        )
        assertEquals(9, rows.size)
        assertTrue(rows.all { it.length == 9 })
        val masks = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(
            File("src/test/resources/quest-promoted-81-masks.b64").readText().trim()
        ))).use { it.readBytes() }
        assertEquals(81 * 128, masks.size)
        val classifier = PieceTemplateModel.create(
            listOf(
                "shogi_quest_templates_v1.txt",
                "shogi_quest_variants_v1.txt",
                "shogi_quest_promoted_v1.txt"
            ).joinToString("\n") { File("src/main/assets/${it}").readText() }
        )
        var occupied = 0
        var promoted = 0
        val board = ArrayList<Piece?>(81)
        for (i in 0 until 81) {
            val start = i * 128
            val signature = PieceTemplateModel.fromMask(BooleanArray(1024) { j ->
                ((masks[start + j / 8].toInt() and 0xFF) and
                    (1 shl (7 - j % 8))) != 0
            })
            val result = classifier.match(signature)
            val expected = rows[i / 9][i % 9]
            assertFalse("Uncertain at row ${i / 9 + 1}, col ${i % 9 + 1}, score=${result.score}, margin=${result.margin}", result.unknown)
            assertEquals("Wrong piece or orientation at row ${i / 9 + 1}, col ${i % 9 + 1}",
                if (expected == '.') null else expected, result.code)
            val piece = result.code?.let(PieceTemplateModel::pieceForCode)
            board += piece
            if (piece != null) {
                occupied++
                if (piece.promoted) promoted++
            }
        }
        assertEquals(32, occupied)
        assertEquals(9, promoted)
        assertEquals(49, board.count { it == null })
        assertEquals(Piece(PieceType.BISHOP, Side.BLACK, true), board[0]) // Sente horse
        assertEquals(Piece(PieceType.BISHOP, Side.WHITE, true), board[6 * 9]) // Actual gote horse
        assertEquals(Piece(PieceType.KNIGHT, Side.BLACK, true), board[1 * 9 + 1])
        assertEquals(Piece(PieceType.LANCE, Side.BLACK, true), board[1 * 9 + 3])
        assertEquals(Piece(PieceType.SILVER, Side.BLACK, true), board[2 * 9])
        assertEquals(Piece(PieceType.ROOK, Side.BLACK, true), board[2 * 9 + 1])
        assertEquals(1, board.count { it?.type == PieceType.KING && it.side == Side.BLACK })
        assertEquals(1, board.count { it?.type == PieceType.KING && it.side == Side.WHITE })

        // Hands visible in this screenshot: gote pawn/lance/rook,
        // sente three pawns and two golds.
        val hands = mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to 1, PieceType.LANCE to 1, PieceType.ROOK to 1),
            Side.BLACK to mapOf(PieceType.PAWN to 3, PieceType.GOLD to 2)
        )
        val position = ShogiPosition(board, Side.BLACK, hands, 1)
        assertTrue("Board plus hands must total forty correctly typed pieces",
            ReviewStateTracker.inventoryIsValid(position))
        assertEquals(position, Sfen.parse(ReviewStateTracker.format(position)))
    }

    @Test fun bothOrientationsUseCorrectPromotionCodes() {
        val model = PieceTemplateModel.create(
            listOf(
                "shogi_quest_templates_v1.txt",
                "shogi_quest_variants_v1.txt",
                "shogi_quest_promoted_v1.txt"
            ).joinToString("\n") { File("src/main/assets/${it}").readText() }
        )
        val lines = File("src/main/assets/shogi_quest_promoted_v1.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(14, lines.size)
        assertEquals("TUVWXYtuvwxy".toSet(), lines.map { it[0] }.toSet())
        for (line in lines) {
            val bytes = Base64.getDecoder().decode(line.substring(2))
            assertEquals(128, bytes.size)
            val mask = BooleanArray(1024) { i ->
                (bytes[i / 8].toInt() and 0xFF and (1 shl (7 - i % 8))) != 0
            }
            val result = model.match(PieceTemplateModel.fromMask(mask))
            assertEquals("Promotion code ${line[0]} not matched", line[0], result.code)
            assertTrue(PieceTemplateModel.pieceForCode(line[0]).promoted)
        }
    }
}
