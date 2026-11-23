package com.shinpstudio.shogireviewoverlay

import kotlin.math.abs

/**
 * Lightweight legal-move layer for the UI prototype.
 * Pawn-drop mate and repetition adjudication are not yet implemented.
 */
object ShogiRules {
    private fun promotionZone(side: Side, row: Int): Boolean =
        if (side == Side.BLACK) row <= 2 else row >= 6

    private fun forcedPromotion(type: PieceType, side: Side, row: Int): Boolean {
        val last = if (side == Side.BLACK) 0 else 8
        return (type == PieceType.PAWN || type == PieceType.LANCE) && row == last ||
            type == PieceType.KNIGHT && abs(row - last) <= 1
    }

    fun pseudoMoves(position: ShogiPosition, includeDrops: Boolean = true): List<ShogiMove> {
        val moves = ArrayList<ShogiMove>(128)
        val owner = position.turn
        val forward = if (owner == Side.BLACK) -1 else 1

        fun add(from: Int, to: Int, piece: Piece) {
            if (piece.promoted || !piece.type.canPromote) {
                moves += ShogiMove(from, to)
                return
            }
            val can = promotionZone(owner, Squares.rank(from)) ||
                promotionZone(owner, Squares.rank(to))
            if (!forcedPromotion(piece.type, owner, Squares.rank(to))) {
                moves += ShogiMove(from, to)
            }
            if (can) moves += ShogiMove(from, to, promote = true)
        }

        position.board.forEachIndexed { from, piece ->
            if (piece == null || piece.side != owner) return@forEachIndexed
            val row = from / 9
            val col = from % 9
            fun explore(dy: Int, dx: Int, slide: Boolean = false) {
                var r = row + dy
                var c = col + dx
                while (r in 0..8 && c in 0..8) {
                    val to = r * 9 + c
                    val target = position.board[to]
                    if (target?.side == owner) break
                    add(from, to, piece)
                    if (!slide || target != null) break
                    r += dy
                    c += dx
                }
            }
            fun gold() {
                explore(forward, 0)
                explore(forward, -1)
                explore(forward, 1)
                explore(0, -1)
                explore(0, 1)
                explore(-forward, 0)
            }
            if (piece.promoted && piece.type in setOf(
                    PieceType.PAWN, PieceType.LANCE, PieceType.KNIGHT, PieceType.SILVER
                )
            ) {
                gold()
            } else when (piece.type) {
                PieceType.PAWN -> explore(forward, 0)
                PieceType.LANCE -> explore(forward, 0, slide = true)
                PieceType.KNIGHT -> {
                    explore(2 * forward, -1)
                    explore(2 * forward, 1)
                }
                PieceType.SILVER -> {
                    explore(forward, 0)
                    explore(forward, -1)
                    explore(forward, 1)
                    explore(-forward, -1)
                    explore(-forward, 1)
                }
                PieceType.GOLD -> gold()
                PieceType.KING -> {
                    for (dy in -1..1) for (dx in -1..1)
                        if (dy != 0 || dx != 0) explore(dy, dx)
                }
                PieceType.ROOK -> {
                    explore(1, 0, slide = true)
                    explore(-1, 0, slide = true)
                    explore(0, 1, slide = true)
                    explore(0, -1, slide = true)
                    if (piece.promoted) {
                        for (dy in listOf(-1, 1)) for (dx in listOf(-1, 1)) explore(dy, dx)
                    }
                }
                PieceType.BISHOP -> {
                    for (dy in listOf(-1, 1)) for (dx in listOf(-1, 1))
                        explore(dy, dx, slide = true)
                    if (piece.promoted) {
                        explore(1, 0)
                        explore(-1, 0)
                        explore(0, 1)
                        explore(0, -1)
                    }
                }
            }
        }

        if (includeDrops) {
            for ((type, number) in (position.hands[owner] ?: emptyMap())) {
                if (number <= 0 || type == PieceType.KING) continue
                for (to in 0..80) {
                    if (position.board[to] != null) continue
                    val row = Squares.rank(to)
                    if (forcedPromotion(type, owner, row)) continue
                    if (type == PieceType.PAWN) {
                        val col = to % 9
                        if ((0..8).any { r ->
                                val pawn = position.board[r * 9 + col]
                                pawn?.side == owner && pawn.type == PieceType.PAWN && !pawn.promoted
                            }
                        ) continue
                    }
                    moves += ShogiMove(null, to, drop = type)
                }
            }
        }
        return moves
    }

    fun apply(position: ShogiPosition, move: ShogiMove): ShogiPosition {
        val board = position.board.toMutableList()
        val hands = mutableMapOf(
            Side.BLACK to (position.hands[Side.BLACK] ?: emptyMap()).toMutableMap(),
            Side.WHITE to (position.hands[Side.WHITE] ?: emptyMap()).toMutableMap()
        )
        val old = if (move.from == null) {
            val type = requireNotNull(move.drop)
            val count = hands.getValue(position.turn)[type] ?: 0
            require(count > 0) { "持ち駒がありません" }
            require(board[move.to] == null)
            hands.getValue(position.turn)[type] = count - 1
            Piece(type, position.turn)
        } else {
            val moving = requireNotNull(board[move.from])
            require(moving.side == position.turn)
            board[move.from] = null
            moving.copy(promoted = moving.promoted || move.promote)
        }
        val captured = board[move.to]
        if (captured != null) {
            require(captured.side != position.turn && captured.type != PieceType.KING)
            val previous = hands.getValue(position.turn)[captured.type] ?: 0
            hands.getValue(position.turn)[captured.type] = previous + 1
        }
        board[move.to] = old
        return ShogiPosition(board, position.turn.opposite, hands, position.ply + 1)
    }

    fun isInCheck(position: ShogiPosition, player: Side): Boolean {
        val king = position.board.indexOfFirst {
            it?.side == player && it.type == PieceType.KING
        }
        if (king < 0) return true
        return pseudoMoves(position.copy(turn = player.opposite), includeDrops = false)
            .any { it.to == king }
    }

    fun legalMoves(position: ShogiPosition): List<ShogiMove> =
        pseudoMoves(position).filter { move ->
            position.board[move.to]?.type != PieceType.KING &&
                !isInCheck(apply(position, move), position.turn)
        }

    fun evaluate(position: ShogiPosition, perspective: Side): Int {
        var score = 0
        for (index in 0..80) {
            val piece = position.board[index] ?: continue
            var points = piece.type.point
            if (piece.promoted) {
                points += when (piece.type) {
                    PieceType.ROOK, PieceType.BISHOP -> 250
                    else -> 150
                }
            }
            val row = index / 9
            val progress = if (piece.side == Side.BLACK) 6 - row else row - 2
            val file = Squares.file(index)
            if (piece.type == PieceType.PAWN && !piece.promoted) {
                val openingPriority = when (file) {
                    7 -> 8
                    2 -> 7
                    5 -> 4
                    else -> 2
                }
                points += progress.coerceAtLeast(0) * (6 + openingPriority)
            }
            if (piece.type == PieceType.SILVER || piece.type == PieceType.GOLD)
                points += progress.coerceAtLeast(0) * 2
            score += if (piece.side == perspective) points else -points
        }
        for (side in Side.entries) {
            for ((type, count) in (position.hands[side] ?: emptyMap())) {
                val points = type.point * count
                score += if (side == perspective) points else -points
            }
        }
        return score
    }
}
