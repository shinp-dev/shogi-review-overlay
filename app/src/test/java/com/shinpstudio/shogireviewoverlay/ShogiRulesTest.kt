package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test

class ShogiRulesTest {
    @Test fun initialPositionAndPawnAdvance() {
        val state = Sfen.parse(Sfen.INITIAL)
        assertEquals(Side.BLACK, state.turn)
        assertEquals(PieceType.KING, state.board[Squares.fromUsi("5i")]?.type)
        val legal = ShogiRules.legalMoves(state).map { it.usi() }
        assertTrue(legal.contains("7g7f"))
        assertTrue(legal.contains("2g2f"))
        assertFalse(legal.contains("7g7e"))
    }

    @Test fun captureAndHands() {
        val state = Sfen.parse("4k4/9/9/9/4p4/4P4/9/9/4K4 b - 1")
        val move = MoveParser.fromUsi("5f5e")
        val next = ShogiRules.apply(state, move)
        assertEquals(1, next.handCount(Side.BLACK, PieceType.PAWN))
        assertEquals(Side.WHITE, next.turn)
    }

    @Test fun nifuBlocksPawnDrops() {
        val state = Sfen.parse("4k4/9/9/9/9/9/4P4/9/4K4 b P 1")
        val drops = ShogiRules.legalMoves(state).filter { it.drop == PieceType.PAWN }
        assertTrue(drops.isNotEmpty())
        assertTrue(drops.none { Squares.file(it.to) == 5 })
    }

    @Test fun coordinatesForOverlay() {
        assertEquals(2, Squares.fromUsi("7a") % 9)
        assertEquals(6, Squares.fromUsi("7g") / 9)
        assertEquals("7g7f", MoveParser.fromUsi("7g7f").usi())
        assertEquals("P*5e", MoveParser.fromUsi("P*5e").usi())
    }

    @Test fun usiMultiPvParsing() {
        val line = "info depth 16 seldepth 24 multipv 2 score cp 123 nodes 3200 pv 7g7f 3c3d"
        val pv = UsiInfoParser.parse(line)!!
        assertEquals(16, pv.depth)
        assertEquals(2, pv.rank)
        assertEquals(123, pv.scoreCp)
        assertEquals(listOf("7g7f", "3c3d"), pv.moves)
    }

    @Test fun invalidInputIsRejected() {
        try {
            Sfen.parse("bad b - 1")
            fail("Invalid board was accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
