package com.shinpstudio.shogireviewoverlay

/**
 * Deliberately shallow fallback used for UI wiring and manual testing only.
 * Not a replacement for YaneuraOu (MultiPV). Values are rough material/position scores.
 */
object SimpleAnalyzer {
    fun analyze(position: ShogiPosition, count: Int = 3): List<Candidate> {
        val side = position.turn
        return ShogiRules.legalMoves(position).map { move ->
            val after = ShogiRules.apply(position, move)
            val replies = ShogiRules.legalMoves(after)
            val score = when {
                replies.isEmpty() && ShogiRules.isInCheck(after, after.turn) -> 30000
                replies.isEmpty() -> ShogiRules.evaluate(after, side)
                else -> replies.minOf { reply ->
                    ShogiRules.evaluate(ShogiRules.apply(after, reply), side)
                }
            }
            Candidate(move, score, move.japanese(position))
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.move.usi() })
            .take(count.coerceIn(1, 3))
    }
}

/** Parser adapter for future USI MultiPV engine integration. */
data class UsiPrincipalVariation(
    val depth: Int,
    val rank: Int,
    val scoreCp: Int?,
    val mate: String?,
    val moves: List<String>
)

object UsiInfoParser {
    fun score(pv: UsiPrincipalVariation): Int? {
        pv.scoreCp?.let { return it }
        val mate = pv.mate ?: return null
        // USI permits bare +/- when mate distance is unspecified.
        return when (mate) {
            "+" -> 30000
            "-" -> -30000
            else -> mate.toIntOrNull()?.let {
                val distance = kotlin.math.abs(it.toLong()).coerceAtMost(29999).toInt()
                if (mate.startsWith("-")) -30000 + distance else 30000 - distance
            }
        }
    }

    /** Never invent scores or accept a move not in the current legal set. */
    fun candidates(
        position: ShogiPosition, variations: List<UsiPrincipalVariation>,
        bestmove: String?, count: Int
    ): List<Candidate> {
        val legal = ShogiRules.legalMoves(position).associateBy { it.usi() }
        val scored = variations.sortedBy { it.rank }.mapNotNull { pv ->
            val move = legal[pv.moves.firstOrNull()] ?: return@mapNotNull null
            val score = score(pv) ?: return@mapNotNull null
            Candidate(move, score, move.japanese(position))
        }.distinctBy { it.move.usi() }
        if (scored.isNotEmpty()) return scored.take(count.coerceIn(1, 3))
        val move = legal[bestmove] ?: return emptyList()
        return listOf(Candidate(move, UNKNOWN_ENGINE_SCORE, move.japanese(position)))
    }

    fun parse(line: String): UsiPrincipalVariation? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.firstOrNull() != "info" || "pv" !in tokens) return null
        fun token(key: String): String? =
            tokens.indexOf(key).takeIf { it >= 0 && it + 1 < tokens.size }?.let { tokens[it + 1] }
        val depth = token("depth")?.toIntOrNull() ?: return null
        val rank = token("multipv")?.toIntOrNull() ?: 1
        val pvStart = tokens.indexOf("pv")
        if (pvStart + 1 >= tokens.size) return null
        val scoreIndex = tokens.indexOf("score")
        val scoreType = if (scoreIndex >= 0) tokens.getOrNull(scoreIndex + 1) else null
        val scoreValue = if (scoreIndex >= 0) tokens.getOrNull(scoreIndex + 2) else null
        return UsiPrincipalVariation(
            depth = depth,
            rank = rank,
            scoreCp = if (scoreType == "cp") scoreValue?.toIntOrNull() else null,
            mate = if (scoreType == "mate") scoreValue else null,
            moves = tokens.subList(pvStart + 1, tokens.size)
        )
    }
}
