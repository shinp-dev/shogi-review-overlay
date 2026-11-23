package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

data class BoardRecognition(
    val sfen: String?,
    val message: String,
    val recognized: Int,
    val board: List<Piece?>? = null
)

/**
 * 81-square image matching: 8% border excluded; sample 32x32 monochrome pixels;
 * compare horizontal and vertical ink distributions plus exact pixel positions.
 *
 * This class does NOT use OCR, ML Kit, or machine learning inference.
 * Reference masks are loaded from 16 unpromoted side-specific piece templates.
 *
 * It deliberately refuses unknown promoted pieces or uncertain squares. When
 * previous valid position exists, ReviewStateTracker can reconstruct one legal
 * move (including drops and captures) from this detected board.
 */
class ShogiScreenRecognizer(context: Context) : AutoCloseable {
    private val pieces: PieceTemplateModel = PieceTemplateModel.create(
        context.assets.open("shogi_templates_v1.txt").bufferedReader().use { it.readText() }
    )
    private val inset = PieceTemplateModel.INSET

    /**
     * Conservative UI guard, not a general live/replay classifier:
     * a large blue/cyan surrender button above the board means live mode.
     */
    fun looksLikeActiveMatch(frame: Bitmap, board: RectF): Boolean {
        val h = board.height() / 9f
        val left = (board.right - board.width() * .27f).toInt().coerceIn(0, frame.width - 1)
        val right = (board.right - board.width() * .135f).toInt().coerceIn(left + 1, frame.width)
        val top = (board.top - 1.55f * h).toInt().coerceIn(0, frame.height - 1)
        val bottom = (board.top - .25f * h).toInt().coerceIn(top + 1, frame.height)
        var blue = 0
        var total = 0
        for (y in top until bottom step 4) for (x in left until right step 4) {
            val color = frame.getPixel(x, y)
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            if (b > 135 && b > r + 40 && g > r + 35) blue++
            total++
        }
        return total > 40 && blue.toFloat() / total > 0.38f
    }

    fun recognize(frame: Bitmap, geometry: BoardRect, turn: Side): BoardRecognition {
        val r = geometry.pixels(frame.width, frame.height)
        if (r.left < 0 || r.top < 0 || r.right > frame.width || r.bottom > frame.height ||
            r.width() < 300 || r.height() < 300)
            return BoardRecognition(null, "盤面位置を調整してください", 0)
        if (looksLikeActiveMatch(frame, r))
            return BoardRecognition(null, "対局中の画面を検出したため解析しません", 0)

        // Read the frame once. All 81*1024 binary pixel samples use this array;
        // no per-square Bitmap allocation, OCR call or external dependency.
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        val board = MutableList<Piece?>(81) { null }
        var uncertain = 0
        var occupied = 0
        var minScore = 1.0
        for (square in 0..80) {
            val features = PieceTemplateModel.sample(
                pixels, frame.width, frame.height,
                r.left, r.top, r.width(), r.height(), square, inset
            )
            val result = pieces.match(features)
            if (result.unknown) {
                uncertain++
                continue
            }
            val code = result.code ?: continue
            occupied++
            minScore = kotlin.math.min(minScore, result.score)
            board[square] = Piece(
                PieceType.fromSfen(code),
                if (code.isUpperCase()) Side.BLACK else Side.WHITE
            )
        }
        val kingsValid = Side.entries.all { side ->
            board.count { it?.type == PieceType.KING && it.side == side } == 1
        }
        if (uncertain > 0 || !kingsValid)
            return BoardRecognition(
                null,
                "画像比較が未確定: $occupied枚 / 不確定$uncertainマス / 王将" +
                    (if (kingsValid) "OK" else "NG") + "。テンプレートを追加してください",
                occupied
            )

        // We do not synthesize hands from missing board pieces. A previous
        // trusted position may reconstruct hands from exactly one legal move.
        if (occupied != 40) {
            return BoardRecognition(
                null,
                "盤上$occupied枚を画像比較で認識。持ち駒は直前の合法局面から復元します",
                occupied,
                board
            )
        }
        val position = ShogiPosition(
            board = board,
            turn = turn,
            hands = mapOf(Side.BLACK to emptyMap(), Side.WHITE to emptyMap()),
            ply = 1
        )
        if (!ReviewStateTracker.inventoryIsValid(position))
            return BoardRecognition(null, "駒種ごとの枚数が不整合です", occupied)
        val sfen = ReviewStateTracker.format(position)
        return BoardRecognition(sfen, "画像比較で40枚を認識（最低スコア: %.2f）".format(minScore), occupied, board)
    }

    override fun close() = Unit
}
