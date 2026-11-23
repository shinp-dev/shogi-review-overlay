package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.graphics.Bitmap
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
 * Reference masks are selected from Wars (base plus promoted templates)
 * or Quest (base plus promoted templates).
 *
 * It deliberately refuses uncertain squares. When
 * previous valid position exists, ReviewStateTracker can reconstruct one legal
 * move (including drops and captures) from this detected board.
 */
class ShogiScreenRecognizer(private val context: Context) : AutoCloseable {
    private val warsPieces: PieceTemplateModel = PieceTemplateModel.create(
        listOf(
            "shogi_templates_v1.txt", // Original basic pieces: keep backwards compatibility.
            "shogi_wars_variants_v1.txt", // Another supplied Wars board theme.
            "shogi_wars_promoted_v1.txt" // Six promoted types, both orientations.
        ).joinToString("\n") { file ->
            context.assets.open(file).bufferedReader().use { it.readText() }
        }
    )
    private val questPieces: PieceTemplateModel = PieceTemplateModel.create(
        listOf(
            "shogi_quest_templates_v1.txt", // Original 16 basic-piece refs
            "shogi_quest_variants_v1.txt", // More basic-piece refs from later replay
            "shogi_quest_promoted_v1.txt" // 6 promoted kinds, 2 orientations
        ).joinToString("\n") { file ->
            context.assets.open(file).bufferedReader().use { it.readText() }
        }
    )

    private val questHands: QuestHandRecognizer = QuestHandRecognizer(
        context.assets.open("shogi_quest_hand_icons_v1.txt")
            .bufferedReader().use { it.readText() },
        context.assets.open("shogi_quest_hand_digits_v1.txt")
            .bufferedReader().use { it.readText() }
    )

    private val warsHands = WarsHandRecognizer(
        context.assets.open("shogi_wars_hand_icons_v1.txt")
            .bufferedReader().use { it.readText() },
        context.assets.open("shogi_wars_hand_digits_v1.txt")
            .bufferedReader().use { it.readText() }
    )

    fun recognize(frame: Bitmap, geometry: BoardRect, turn: Side): BoardRecognition {
        val r = geometry.pixels(frame.width, frame.height)
        if (r.left < 0 || r.top < 0 || r.right > frame.width || r.bottom > frame.height ||
            r.width() < 300 || r.height() < 300)
            return BoardRecognition(null, "盤面位置を調整してください", 0)

        // Read the frame once. All 81*1024 binary pixel samples use this array;
        // no per-square Bitmap allocation, OCR call or external dependency.
        val inset = OverlaySettings.preferences(context).getInt("crop_margin_pct", 8).coerceIn(6, 12) / 100f
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        val classifier = if (OverlaySettings.target(context) == ReviewTarget.SHOGI_QUEST)
            questPieces else warsPieces
        val corrected = BoardAlignment.find(
            pixels, frame.width, frame.height, r, inset, classifier
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
        // The Wars tray has a different style and no fixed piece order.
        // Use a restricted set of verified real-image hand glyphs and validate
        // the entire inventory before using a midgame SFEN.
        if (OverlaySettings.target(context) == ReviewTarget.SHOGI_WARS) {
            val hands = warsHands.recognize(
                pixels, frame.width, frame.height,
                r.left + corrected.dx, r.top + corrected.dy, r.width(), r.height(), board
            )
            if (hands != null) {
                val position = ShogiPosition(board, turn, hands, 1)
                return BoardRecognition(
                    ReviewStateTracker.format(position),
                    "ウォーズ：盤上${occupied}枚＋持ち駒${40 - occupied}枚を照合（手番は設定値）",
                    occupied, board, corrected.dx, corrected.dy
                )
            }
        }
        // A Quest review can start at any move if both holding trays are
        // visible and their red counts, together with the board, pass
        // the standard 40-piece inventory. Unknown numbers fail closed.
        if (OverlaySettings.target(context) == ReviewTarget.SHOGI_QUEST) {
            val hands = questHands.recognize(
                pixels, frame.width, frame.height,
                r.left + corrected.dx, r.top + corrected.dy, r.width(), r.height(), board
            )
            if (hands != null) {
                val position = ShogiPosition(board, turn, hands, 1)
                return BoardRecognition(
                    ReviewStateTracker.format(position),
                    "盤上${occupied}枚＋持ち駒${40 - occupied}枚を確認（直接SFEN生成、手番は設定値）",
                    occupied, board, corrected.dx, corrected.dy
                )
            }
        }
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
