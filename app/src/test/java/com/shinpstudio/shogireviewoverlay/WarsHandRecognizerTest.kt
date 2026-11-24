package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Screenshot-derived glyphs only (no user identities).
 * Hand slot order varies in Wars; the inventory rather than slot position
 * determines whether a reconstructed midgame SFEN is trustworthy.
 */
class WarsHandRecognizerTest {
    private val model = WarsHandRecognizer(
        File("src/main/assets/shogi_wars_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_wars_hand_digits_v1.txt").readText()
    )

    @Test fun heldOutScreenshotGlyphsRemainDistinguishable() {
        val rows = File("src/test/resources/shogi_wars_hand_heldout_v1.txt")
            .readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(3, rows.size)
        for (line in rows) {
            assertEquals("Second screenshot glyph or owner flipped for ${line[0]}",
                line[0], model.recognizeReference(line[0], line.substring(2)))
        }
    }

    @Test fun allSevenSenteHandGlyphsFromAnotherReplayFrame() {
        val rows = File("src/test/resources/shogi_wars_hand_all_sente_heldout_v1.txt")
            .readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("PLNSGBR".toSet(), rows.map { it[0] }.toSet())
        for (line in rows) {
            assertEquals(line[0], model.recognizeReference(line[0], line.substring(2)))
        }
    }

    @Test fun infersUnreadableFiveOrLargePawnBadgeOnlyWhenOwnerIsKnown() {
        for (n in listOf(5, 12, 18)) {
            val board = makeBoard(mapOf(PieceType.PAWN to n))
            val observed = mapOf(
                Side.BLACK to mapOf<PieceType, Int?>(PieceType.PAWN to null)
            )
            assertEquals(n, model.reconcile(board, observed)?.get(Side.BLACK)?.get(PieceType.PAWN))
        }
    }

    @Test fun rejectsAmbiguousCountsAndNeverOverridesKnownDigits() {
        val board = makeBoard(mapOf(PieceType.PAWN to 8))
        assertNull(model.reconcile(board, mapOf(
            Side.BLACK to mapOf<PieceType, Int?>(PieceType.PAWN to null),
            Side.WHITE to mapOf<PieceType, Int?>(PieceType.PAWN to null)
        )))
        val known = mapOf(
            Side.BLACK to mapOf<PieceType, Int?>(PieceType.PAWN to null),
            Side.WHITE to mapOf<PieceType, Int?>(PieceType.PAWN to 3)
        )
        assertEquals(5, model.reconcile(board, known)?.get(Side.BLACK)?.get(PieceType.PAWN))
        assertNull(model.reconcile(board, mapOf(Side.BLACK to mapOf(PieceType.PAWN to 7))))
        val one = makeBoard(mapOf(PieceType.PAWN to 1))
        assertNull(model.reconcile(one, mapOf(
            Side.BLACK to mapOf<PieceType, Int?>(PieceType.PAWN to null)
        )))
    }

    @Test fun acceptsFirstScreenshotEvenWhenUpperHandOrderIsBishopThenPawn() {
        // Screenshot #1: Gote B2 P3. Sente P3 L1 G1.
        val board = makeBoard(
            mapOf(PieceType.BISHOP to 2, PieceType.PAWN to 6,
                PieceType.LANCE to 1, PieceType.GOLD to 1)
        )
        val hands = mapOf(
            Side.WHITE to linkedMapOf(
                PieceType.BISHOP to 2, PieceType.PAWN to 3
            ),
            Side.BLACK to linkedMapOf(
                PieceType.PAWN to 3, PieceType.LANCE to 1, PieceType.GOLD to 1
            )
        )
        assertEquals(30, board.count { it != null })
        assertEquals(hands, model.reconcile(board, hands))
        val position = ShogiPosition(board, Side.BLACK, hands, 1)
        assertEquals(position, Sfen.parse(ReviewStateTracker.format(position)))
    }

    @Test fun singlePawnHandAndBishopPawnTopHandAlsoWork() {
        // Screenshot #2: Gote B1 P1. Sente P1.
        val board = makeBoard(
            mapOf(PieceType.BISHOP to 1, PieceType.PAWN to 2)
        )
        val hands = mapOf(
            Side.WHITE to linkedMapOf(
                PieceType.BISHOP to 1, PieceType.PAWN to 1
            ),
            Side.BLACK to linkedMapOf(PieceType.PAWN to 1)
        )
        assertEquals(37, board.count { it != null })
        assertEquals(hands, model.reconcile(board, hands))
        assertNull(model.reconcile(board, mapOf(
            Side.WHITE to mapOf(PieceType.BISHOP to 1, PieceType.PAWN to 2),
            Side.BLACK to mapOf(PieceType.PAWN to 1)
        )))
    }

    @Test fun rejectsInventedHoldsAndInvalidTotalWithoutGuessing() {
        val board = makeBoard(mapOf(PieceType.PAWN to 6, PieceType.BISHOP to 2))
        assertNull(model.reconcile(board, mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to 3, PieceType.BISHOP to 2),
            Side.BLACK to mapOf(PieceType.PAWN to 2)
        )))
        assertNull(model.reconcile(board, mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to 3, PieceType.BISHOP to 2),
            Side.BLACK to mapOf(PieceType.PAWN to 3, PieceType.KING to 1)
        )))
    }

    private fun makeBoard(removed: Map<PieceType, Int>): List<Piece?> {
        val board = Sfen.parse(Sfen.INITIAL).board.toMutableList()
        for ((type, amount) in removed) {
            var left = amount
            for (i in board.indices) {
                if (left == 0) break
                if (board[i]?.type == type) {
                    board[i] = null
                    left--
                }
            }
            assertEquals("Not enough pieces available: $"+ "type", 0, left)
        }
        return board
    }
}
