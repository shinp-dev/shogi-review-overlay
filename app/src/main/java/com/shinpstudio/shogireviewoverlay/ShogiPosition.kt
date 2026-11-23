package com.shinpstudio.shogireviewoverlay

enum class Side {
    BLACK, WHITE;
    val opposite: Side get() = if (this == BLACK) WHITE else BLACK
}

enum class PieceType(val sfen: Char, val kanji: String, val point: Int) {
    PAWN('P', "歩", 100),
    LANCE('L', "香", 300),
    KNIGHT('N', "桂", 330),
    SILVER('S', "銀", 450),
    GOLD('G', "金", 560),
    BISHOP('B', "角", 850),
    ROOK('R', "飛", 1000),
    KING('K', "玉", 20000);

    val canPromote: Boolean get() = this != GOLD && this != KING
    companion object {
        fun fromSfen(ch: Char): PieceType =
            entries.firstOrNull { it.sfen == ch.uppercaseChar() }
                ?: throw IllegalArgumentException("未対応の駒: " + ch)
    }
}

data class Piece(val type: PieceType, val side: Side, val promoted: Boolean = false)

data class ShogiPosition(
    val board: List<Piece?>,
    val turn: Side,
    val hands: Map<Side, Map<PieceType, Int>>,
    val ply: Int
) {
    init { require(board.size == 81) { "盤面は81マス必要です" } }
    fun handCount(side: Side, type: PieceType): Int = hands[side]?.get(type) ?: 0
}

object Squares {
    /** USI: files 9..1 left to right, ranks a..i top to bottom. */
    fun index(file: Int, rank: Int): Int {
        require(file in 1..9 && rank in 0..8)
        return rank * 9 + (9 - file)
    }
    fun file(index: Int): Int = 9 - (index % 9)
    fun rank(index: Int): Int = index / 9
    fun usi(index: Int): String = file(index).toString() + ('a'.code + rank(index)).toChar()
    fun fromUsi(text: String): Int {
        require(text.length == 2 && text[0] in '1'..'9' && text[1] in 'a'..'i') {
            "USIのマス表記が不正です: " + text
        }
        return index(text[0].digitToInt(), text[1] - 'a')
    }
    fun japanese(index: Int): String {
        val files = "１２３４５６７８９"
        val ranks = "一二三四五六七八九"
        return files[file(index) - 1].toString() + ranks[rank(index)]
    }
}

data class ShogiMove(
    val from: Int?,
    val to: Int,
    val promote: Boolean = false,
    val drop: PieceType? = null
) {
    init {
        require((from == null) == (drop != null)) { "移動元または打ち駒を指定してください" }
        require(to in 0..80)
    }
    fun usi(): String = if (drop != null) {
        drop.sfen.toString() + "*" + Squares.usi(to)
    } else {
        Squares.usi(requireNotNull(from)) + Squares.usi(to) + (if (promote) "+" else "")
    }
    fun japanese(position: ShogiPosition): String {
        val sideMark = if (position.turn == Side.BLACK) "▲" else "△"
        val type = drop ?: position.board[requireNotNull(from)]?.type ?: PieceType.PAWN
        val suffix = when {
            drop != null -> "打"
            promote -> "成"
            else -> ""
        }
        return sideMark + Squares.japanese(to) + type.kanji + suffix
    }
}

data class Candidate(val move: ShogiMove, val score: Int, val label: String)

object Sfen {
    const val INITIAL = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1"

    fun parse(input: String): ShogiPosition {
        val stripped = input.trim().removePrefix("position sfen ").trim()
        val value = when (stripped) {
            "startpos", "position startpos" -> INITIAL
            else -> stripped
        }
        require(!value.contains(" moves ")) { "手順つきSFENは未対応です。局面SFENを入力してください" }
        val parts = value.split(Regex("\\s+"))
        require(parts.size in 3..4) { "SFENは「盤面 手番 持ち駒 手数」です" }
        val rows = parts[0].split("/")
        require(rows.size == 9) { "盤面が9段ではありません" }
        val board = MutableList<Piece?>(81) { null }
        rows.forEachIndexed { rowNo, row ->
            var col = 0
            var promoted = false
            row.forEach { ch ->
                when {
                    ch == '+' -> {
                        require(!promoted) { "成り駒の表記が不正です" }
                        promoted = true
                    }
                    ch in '1'..'9' -> {
                        require(!promoted) { "成り駒の表記が不正です" }
                        col += ch.digitToInt()
                    }
                    else -> {
                        val type = PieceType.fromSfen(ch)
                        require(!promoted || type.canPromote) { "成れない駒です" }
                        require(col in 0..8) { "各段は9マスです" }
                        board[rowNo * 9 + col] = Piece(
                            type,
                            if (ch.isUpperCase()) Side.BLACK else Side.WHITE,
                            promoted
                        )
                        col++
                        promoted = false
                    }
                }
            }
            require(col == 9 && !promoted) { "各段は9マスです" }
        }
        val turn = when (parts[1]) {
            "b" -> Side.BLACK
            "w" -> Side.WHITE
            else -> throw IllegalArgumentException("手番が不正です")
        }
        val hands = mutableMapOf(
            Side.BLACK to mutableMapOf<PieceType, Int>(),
            Side.WHITE to mutableMapOf<PieceType, Int>()
        )
        if (parts[2] != "-") {
            var count = ""
            for (ch in parts[2]) {
                if (ch.isDigit()) {
                    count += ch
                    continue
                }
                val type = PieceType.fromSfen(ch)
                require(type != PieceType.KING) { "王将は持ち駒になりません" }
                val owner = if (ch.isUpperCase()) Side.BLACK else Side.WHITE
                val amount = if (count.isEmpty()) 1 else count.toInt()
                require(amount > 0) { "持ち駒の数が不正です" }
                hands.getValue(owner)[type] = (hands.getValue(owner)[type] ?: 0) + amount
                count = ""
            }
            require(count.isEmpty()) { "持ち駒の表記が不正です" }
        }
        val ply = parts.getOrNull(3)?.toIntOrNull() ?: 1
        require(ply > 0)
        require(board.count { it?.type == PieceType.KING && it.side == Side.BLACK } == 1)
        require(board.count { it?.type == PieceType.KING && it.side == Side.WHITE } == 1)
        return ShogiPosition(board, turn, hands, ply)
    }
}
