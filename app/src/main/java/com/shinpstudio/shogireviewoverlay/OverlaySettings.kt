package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.graphics.RectF

data class BoardRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun pixels(screenWidth: Int, screenHeight: Int): RectF =
        RectF(
            left * screenWidth, top * screenHeight,
            (left + width) * screenWidth, (top + height) * screenHeight
        )

    companion object {
        fun fromPixels(rect: RectF, screenWidth: Int, screenHeight: Int): BoardRect =
            BoardRect(
                rect.left / screenWidth, rect.top / screenHeight,
                rect.width() / screenWidth, rect.height() / screenHeight
            )
    }
}

enum class ReviewTarget(val storageValue: String) {
    SHOGI_WARS("wars"),
    SHOGI_QUEST("quest")
}

object OverlaySettings {
    private const val PREFS = "shogi_overlay"

    fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(context: Context): ReviewTarget =
        if (preferences(context).getString("review_target", "wars") == "quest")
            ReviewTarget.SHOGI_QUEST else ReviewTarget.SHOGI_WARS

    fun setTarget(context: Context, selected: ReviewTarget) {
        preferences(context).edit()
            .putString("review_target", selected.storageValue)
            .remove("last_auto_sfen")
            .remove("last_auto_status")
            .apply()
    }

    // Keep the existing Wars preference keys unchanged for existing installs.
    // Quest uses separate calibration and auto-alignment values.
    private fun key(target: ReviewTarget, name: String): String =
        if (target == ReviewTarget.SHOGI_QUEST) "quest_" + name else name

    fun board(context: Context): BoardRect {
        val p = preferences(context)
        val t = target(context)
        // Quest's initial frame is estimated from the supplied 691x1536 review screenshot.
        // Users can correct it with the normal drag/resize calibration.
        val default = if (t == ReviewTarget.SHOGI_QUEST)
            BoardRect(0.012f, 0.179f, 0.977f, 0.480f)
        else
            BoardRect(0.02f, 0.285f, 0.96f, 0.475f)
        return BoardRect(
            p.getFloat(key(t, "board_left"), default.left),
            p.getFloat(key(t, "board_top"), default.top),
            p.getFloat(key(t, "board_width"), default.width),
            p.getFloat(key(t, "board_height"), default.height)
        )
    }
    fun saveBoard(context: Context, board: BoardRect) {
        val t = target(context)
        preferences(context).edit()
            .putFloat(key(t, "board_left"), board.left)
            .putFloat(key(t, "board_top"), board.top)
            .putFloat(key(t, "board_width"), board.width)
            .putFloat(key(t, "board_height"), board.height)
            .putInt(key(t, "alignment_dx"), 0)
            .putInt(key(t, "alignment_dy"), 0)
            .apply()
    }
    /**
     * Keep the saved calibration untouched, so automatic correction does not
     * accumulate across repeated screenshots.
     */
    fun displayBoard(context: Context, screenWidth: Int, screenHeight: Int): RectF =
        board(context).pixels(screenWidth, screenHeight).apply {
            offset(
                preferences(context).getInt(key(target(context), "alignment_dx"), 0).toFloat(),
                preferences(context).getInt(key(target(context), "alignment_dy"), 0).toFloat()
            )
        }

    fun saveAlignment(context: Context, dx: Int, dy: Int) {
        val t = target(context)
        preferences(context).edit()
            .putInt(key(t, "alignment_dx"), dx)
            .putInt(key(t, "alignment_dy"), dy)
            .apply()
    }

    fun count(context: Context): Int =
        preferences(context).getInt("count", 3).coerceIn(1, 3)
    fun fontSp(context: Context): Int =
        preferences(context).getInt("font_sp", 14).coerceIn(10, 24)
    fun showArrows(context: Context): Boolean =
        preferences(context).getBoolean("arrows", true)
    fun showPanel(context: Context): Boolean =
        preferences(context).getBoolean("panel", true)
    fun sfen(context: Context): String =
        preferences(context).getString("sfen", Sfen.INITIAL) ?: Sfen.INITIAL
}
