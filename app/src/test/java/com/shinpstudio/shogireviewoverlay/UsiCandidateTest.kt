package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test

class UsiCandidateTest {
    private val initial = Sfen.parse(Sfen.INITIAL)

    @Test fun acceptsBareMateScores() {
        assertEquals(30000, UsiInfoParser.score(requireNotNull(
            UsiInfoParser.parse("info depth 2 score mate + pv 7g7f"))))
        assertEquals(-30000, UsiInfoParser.score(requireNotNull(
            UsiInfoParser.parse("info depth 2 score mate - pv 7g7f"))))
        assertEquals(-29997, UsiInfoParser.score(requireNotNull(
            UsiInfoParser.parse("info depth 2 score mate -3 pv 7g7f"))))
    }

    @Test fun legalBestmoveWithoutPvHasNoFabricatedScore() {
        val c = UsiInfoParser.candidates(initial, emptyList(), "7g7f", 3).single()
        assertEquals("7g7f", c.move.usi())
        assertEquals(UNKNOWN_ENGINE_SCORE, c.score)
        assertEquals("評価なし", formatEngineScore(c.score))
    }

    @Test fun terminalOrIllegalBestmoveDoesNotProduceCandidate() {
        for (move in listOf("resign", "win", "7g7e", null))
            assertTrue(UsiInfoParser.candidates(initial, emptyList(), move, 3).isEmpty())
    }

    @Test fun scoredPvRemainsPreferred() {
        val pv = requireNotNull(UsiInfoParser.parse("info depth 3 score cp 42 pv 7g7f"))
        val c = UsiInfoParser.candidates(initial, listOf(pv), "2g2f", 3).single()
        assertEquals("7g7f", c.move.usi())
        assertEquals(42, c.score)
    }
}
