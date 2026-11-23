package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test

class ReviewStateTrackerTest {
    @Test fun initialInventoryAndSfenRoundtrip() {
        val start = Sfen.parse(Sfen.INITIAL)
        assertTrue(ReviewStateTracker.inventoryIsValid(start))
        assertEquals(Sfen.INITIAL, ReviewStateTracker.format(start))
    }

    @Test fun oneMoveTracking() {
        val initial = Sfen.parse(Sfen.INITIAL)
        val advanced = ShogiRules.apply(initial, MoveParser.fromUsi("7g7f"))
        val recovered = ReviewStateTracker.resolve(initial, advanced.board)
        assertNotNull(recovered)
        assertEquals(advanced.board, recovered?.board)
        assertEquals(Side.WHITE, recovered?.turn)
        assertTrue(ReviewStateTracker.inventoryIsValid(requireNotNull(recovered)))
    }

    @Test fun captureReconstructsHand() {
        var state = Sfen.parse(Sfen.INITIAL)
        // 7-file pawn advances to take the opponent pawn at 7c.
        for (usi in listOf("7g7f", "3c3d", "7f7e", "3d3e", "7e7d", "3e3f")) {
            val move = MoveParser.fromUsi(usi)
            assertTrue("Expected legal: " + usi, ShogiRules.legalMoves(state).any { it.usi() == usi })
            state = ShogiRules.apply(state, move)
        }
        val capture = MoveParser.fromUsi("7d7c+")
        assertTrue(ShogiRules.legalMoves(state).any { it.usi() == capture.usi() })
        val after = ShogiRules.apply(state, capture)
        assertEquals(1, after.handCount(Side.BLACK, PieceType.PAWN))
        val matched = ReviewStateTracker.resolve(state, after.board)
        assertNotNull(matched)
        assertEquals(1, requireNotNull(matched).handCount(Side.BLACK, PieceType.PAWN))
        val encoded = ReviewStateTracker.format(matched)
        assertEquals(matched, Sfen.parse(encoded))
    }

    @Test fun noInventedCapturesWhenBoardChangesIllegally() {
        val start = Sfen.parse(Sfen.INITIAL)
        val corrupted = start.board.toMutableList()
        corrupted[Squares.fromUsi("5i")] = null
        assertNull(ReviewStateTracker.resolve(start, corrupted))
    }
}
