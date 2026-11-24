package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** The *only* touchable overlay. Its Done button returns to tap-through display. */
class BoardCalibrationView(
    context: Context,
    private val onDone: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var box = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private var resizing = false
    private var initialized = false

    private fun dp(x: Float): Float = x * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        box = OverlaySettings.board(context).pixels(w, h)
        initialized = true
    }

    private fun doneRect(): RectF =
        RectF(width - dp(117f), dp(48f), width - dp(12f), dp(94f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!initialized) return
        paint.style = Paint.Style.FILL
        paint.color = 0x3040E080
        canvas.drawRect(box, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.2f)
        paint.color = 0xFF40E080.toInt()
        canvas.drawRect(box, paint)
        paint.strokeWidth = dp(0.9f)
        paint.color = 0xCCFFFFFF.toInt()
        for (n in 1..8) {
            val x = box.left + box.width() * n / 9f
            val y = box.top + box.height() * n / 9f
            canvas.drawLine(x, box.top, x, box.bottom, paint)
            canvas.drawLine(box.left, y, box.right, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFF137365.toInt()
        canvas.drawCircle(box.right, box.bottom, dp(17f), paint)

        val done = doneRect()
        paint.color = 0xF511835B.toInt()
        canvas.drawRoundRect(done, dp(10f), dp(10f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(19f)
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("完了", done.left + dp(28f), done.centerY() + dp(7f), paint)

        paint.color = 0xDB1C2430.toInt()
        val hint = RectF(dp(10f), dp(48f), width - dp(129f), dp(94f))
        canvas.drawRoundRect(hint, dp(9f), dp(9f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(12f)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        canvas.drawText("枠内をドラッグで移動", hint.left + dp(7f), hint.top + dp(18f), paint)
        canvas.drawText("右下の丸で拡大・縮小", hint.left + dp(7f), hint.top + dp(35f), paint)
    }

    fun finish() {
        if (initialized) OverlaySettings.saveBoard(context, BoardRect.fromPixels(box, width, height))
        onDone()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!initialized) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (doneRect().contains(event.x, event.y)) {
                    post { finish() }
                    return true
                }
                lastX = event.x
                lastY = event.y
                resizing = abs(event.x - box.right) <= dp(50f) &&
                    abs(event.y - box.bottom) <= dp(50f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (resizing) {
                    box.right = (box.right + dx).coerceIn(
                        box.left + dp(180f), width.toFloat()
                    )
                    box.bottom = (box.bottom + dy).coerceIn(
                        box.top + dp(180f), height.toFloat()
                    )
                } else {
                    box.offset(
                        dx.coerceIn(-box.left, width - box.right),
                        dy.coerceIn(-box.top, height - box.bottom)
                    )
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                OverlaySettings.saveBoard(
                    context, BoardRect.fromPixels(box, width, height)
                )
                return true
            }
        }
        return true
    }
}
