package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Inventory-derived counts are safe only when each missing piece kind has at
 * most one holder with an unreadable red multiplicity badge.
 */
class QuestHandCountReconciliationTest {
    private val recognizer = QuestHandRecognizer(
        File("src/main/assets/shogi_quest_hand_icons_v1.txt").readText(),
        File("src/main/assets/shogi_quest_hand_digits_v1.txt").readText()
    )

    private fun boardWithout(vararg pieces: Pair<PieceType, Int>): List<Piece?> {
        val board = Sfen.parse(Sfen.INITIAL).board.toMutableList()
        for ((type, amount) in pieces) {
            var removed = 0
            for (i in board.indices) {
                if (board[i]?.type == type && removed < amount) {
                    board[i] = null
                    removed++
                }
            }
            assertEquals(amount, removed)
        }
        return board
    }

    @Test fun oneUnreadableCountIsUnambiguouslyRecovered() {
        val board = boardWithout(PieceType.PAWN to 3,
            PieceType.BISHOP to 1, PieceType.LANCE to 1)
        val expected = mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to 2, PieceType.BISHOP to 1),
            Side.BLACK to mapOf(PieceType.PAWN to 1, PieceType.LANCE to 1)
        )
        val observed: Map<Side, Map<PieceType, Int?>> = mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to 2, PieceType.BISHOP to null),
            Side.BLACK to mapOf(PieceType.PAWN to null, PieceType.LANCE to null)
        )
        assertEquals(expected, recognizer.reconcileCounts(board, observed))
    }

    @Test fun twoUnreadableNumbersForSamePieceKindAreAmbiguous() {
        val board = boardWithout(PieceType.PAWN to 3)
        val observed: Map<Side, Map<PieceType, Int?>> = mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to null),
            Side.BLACK to mapOf(PieceType.PAWN to null)
        )
        assertNull(recognizer.reconcileCounts(board, observed))
    }

    @Test fun falselyReadNumberCannotBeSilentlyOverridden() {
        val board = boardWithout(PieceType.PAWN to 3)
        val observed: Map<Side, Map<PieceType, Int?>> = mapOf(
            Side.WHITE to mapOf(PieceType.PAWN to 3),
            Side.BLACK to mapOf(PieceType.PAWN to null)
        )
        assertNull(recognizer.reconcileCounts(board, observed))
    }

    @Test fun handIconWithoutAnyMissingPieceIsRejected() {
        val board = Sfen.parse(Sfen.INITIAL).board
        val observed: Map<Side, Map<PieceType, Int?>> = mapOf(
            Side.WHITE to mapOf(PieceType.ROOK to null),
            Side.BLACK to emptyMap()
        )
        assertNull(recognizer.reconcileCounts(board, observed))
    }
}
