package com.shinpstudio.shogireviewoverlay

/**
 * Validate/reconstruct captured hands from a previous trusted board. A strict
 * unique legal-move match is required: if ambiguous or the review has jumped
 * multiple moves, fail rather than display a fake engine recommendation.
 */
object ReviewStateTracker {
    private val expected = mapOf(
        PieceType.PAWN to 18,
        PieceType.LANCE to 4,
        PieceType.KNIGHT to 4,
        PieceType.SILVER to 4,
        PieceType.GOLD to 4,
        PieceType.BISHOP to 2,
        PieceType.ROOK to 2,
        PieceType.KING to 2
    )

    fun inventoryIsValid(position: ShogiPosition): Boolean =
        PieceType.entries.all { type ->
            val boardCount = position.board.count { it?.type == type }
            val held = Side.entries.sumOf { position.handCount(it, type) }
            boardCount + held == expected.getValue(type)
        }

    fun resolve(previous: ShogiPosition, scannedBoard: List<Piece?>): ShogiPosition? {
        if (scannedBoard.size != 81) return null
        if (previous.board == scannedBoard) return previous
        val candidates = ShogiRules.legalMoves(previous).asSequence().map { move ->
            ShogiRules.apply(previous, move)
        }.filter { state ->
            state.board == scannedBoard && inventoryIsValid(state)
        }.take(2).toList()
        return candidates.singleOrNull()
    }

    fun format(position: ShogiPosition): String {
        require(inventoryIsValid(position)) { "駒の総数が不正です" }
        val board = (0..8).joinToString("/") { row ->
            val result = StringBuilder()
            var empty = 0
            for (col in 0..8) {
                val p = position.board[row * 9 + col]
                if (p == null) { empty++; continue }
                if (empty != 0) { result.append(empty); empty = 0 }
                if (p.promoted) result.append('+')
                result.append(if (p.side == Side.BLACK) p.type.sfen else p.type.sfen.lowercaseChar())
            }
            if (empty > 0) result.append(empty)
            result.toString()
        }
        val hands = StringBuilder()
        for (side in Side.entries) for (type in listOf(
            PieceType.ROOK, PieceType.BISHOP, PieceType.GOLD,
            PieceType.SILVER, PieceType.KNIGHT, PieceType.LANCE, PieceType.PAWN
        )) {
            val count = position.handCount(side, type)
            if (count <= 0) continue
            if (count > 1) hands.append(count)
            hands.append(if (side == Side.BLACK) type.sfen else type.sfen.lowercaseChar())
        }
        return board + (if (position.turn == Side.BLACK) " b " else " w ") +
            (if (hands.isEmpty()) "-" else hands.toString()) + " " + position.ply
    }
}
