package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

data class BoardRecognition(
    val sfen: String?,
    val message: String,
    val recognized: Int,
    val board: List<Piece?>? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0
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
class ShogiScreenRecognizer(private val context: Context) : AutoCloseable {
    private val pieces: PieceTemplateModel = PieceTemplateModel.create(
        context.assets.open("shogi_templates_v1.txt").bufferedReader().use { it.readText() }
    )

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
        val inset = OverlaySettings.preferences(context).getInt("crop_margin_pct", 8).coerceIn(6, 12) / 100f
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        val corrected = BoardAlignment.find(
            pixels, frame.width, frame.height, r, inset, pieces
        )
        val board = corrected.board
        val occupied = corrected.occupied
        if (!corrected.reliable) return BoardRecognition(
            null,
            "位置自動補正後も未確定: " + occupied + "枚、不確実" +
                corrected.uncertain + "マス、王将" +
                (if (corrected.kings) "OK" else "NG") +
                "（補正: " + corrected.dx + "," + corrected.dy + "px）",
            occupied
        )
        if (occupied != 40) return BoardRecognition(
            null,
            "盤上" + occupied + "枚を認識。持ち駒は直前局面から復元します" +
                "（補正: " + corrected.dx + "," + corrected.dy + "px）",
            occupied,
            board,
            corrected.dx,
            corrected.dy
        )
        val position = ShogiPosition(
            board = board,
            turn = turn,
            hands = mapOf(Side.BLACK to emptyMap(), Side.WHITE to emptyMap()),
            ply = 1
        )
        if (!ReviewStateTracker.inventoryIsValid(position))
            return BoardRecognition(null, "駒種ごとの枚数が不整合です", occupied)
        val sfen = ReviewStateTracker.format(position)
        return BoardRecognition(
            sfen,
            "40枚認識・位置補正（" + corrected.dx + "," + corrected.dy +
                "px、最低一致度 %.2f）".format(corrected.lowestScore),
            occupied, board, corrected.dx, corrected.dy
        )
    }

    override fun close() = Unit
}
