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

object OverlaySettings {
    private const val PREFS = "shogi_overlay"

    fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun board(context: Context): BoardRect {
        val p = preferences(context)
        return BoardRect(
            p.getFloat("board_left", 0.02f),
            p.getFloat("board_top", 0.285f),
            p.getFloat("board_width", 0.96f),
            p.getFloat("board_height", 0.475f)
        )
    }
    fun saveBoard(context: Context, board: BoardRect) {
        preferences(context).edit()
            .putFloat("board_left", board.left)
            .putFloat("board_top", board.top)
            .putFloat("board_width", board.width)
            .putFloat("board_height", board.height)
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
