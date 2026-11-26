package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class OverlayCandidate(val move: ShogiMove, val label: String, val score: Int)

class CandidateOverlayView(context: Context) : View(context) {
    private var candidates: List<OverlayCandidate> = emptyList()
    private var uncertainSquares = intArrayOf()
    private var uncertainDx = 0
    private var uncertainDy = 0

    fun setUncertainSquares(squares: IntArray, dx: Int, dy: Int) {
        uncertainSquares = squares.filter { it in 0..80 }.distinct().toIntArray()
        uncertainDx = dx
        uncertainDy = dy
        invalidate()
    }
    private var diagnostic = false
    private var capturingFrame = false

    fun setCapturingFrame(active: Boolean) {
        if (capturingFrame == active) return
        capturingFrame = active
        invalidate()
    }

    fun setDiagnostic(enabled: Boolean) {
        diagnostic = enabled
        invalidate()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(
        Color.rgb(45, 198, 86),
        Color.rgb(39, 125, 230),
        Color.rgb(255, 153, 43)
    )
    private val density = resources.displayMetrics.density

    fun setCandidates(newCandidates: List<OverlayCandidate>) {
        candidates = newCandidates.take(3)
        invalidate()
    }

    private fun dp(value: Float): Float = density * value

    private fun point(rect: RectF, index: Int): PointF =
        PointF(
            rect.left + (index % 9 + 0.5f) * rect.width() / 9f,
            rect.top + (index / 9 + 0.5f) * rect.height() / 9f
        )

    private fun label(canvas: Canvas, x: Float, y: Float, text: String, color: Int, sp: Int) {
        val textSize = resources.displayMetrics.scaledDensity * sp
        paint.apply {
            style = Paint.Style.FILL
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            this.textSize = textSize
        }
        val padding = dp(5f)
        val w = paint.measureText(text) + padding * 2
        val h = textSize + padding * 1.5f
        val left = (x - w / 2f).coerceIn(dp(2f), width - w - dp(2f))
        val top = (y - h).coerceAtLeast(dp(2f))
        paint.color = 0xE51F2630.toInt()
        canvas.drawRoundRect(left, top, left + w, top + h, dp(6f), dp(6f), paint)
        paint.color = color
        canvas.drawText(text, left + padding, top + h - padding * 0.75f, paint)
    }

    private fun drawCandidate(canvas: Canvas, board: RectF, index: Int, entry: OverlayCandidate) {
        val color = colors[index]
        val to = point(board, entry.move.to)
        val cw = board.width() / 9f
        val ch = board.height() / 9f
        val inset = dp(2f)
        val rect = RectF(
            to.x - cw / 2 + inset, to.y - ch / 2 + inset,
            to.x + cw / 2 - inset, to.y + ch / 2 - inset
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(52, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = color
        canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
        paint.style = Paint.Style.FILL

        if (OverlaySettings.showArrows(context)) {
            val from = entry.move.from?.let { point(board, it) }
            if (from != null) {
                val dx = to.x - from.x
                val dy = to.y - from.y
                val len = kotlin.math.hypot(dx, dy)
                if (len > dp(4f)) {
                    val ux = dx / len
                    val uy = dy / len
                    val ex = to.x - ux * min(cw, ch) * 0.25f
                    val ey = to.y - uy * min(cw, ch) * 0.25f
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeWidth = dp(5f)
                    paint.color = 0xB0000000.toInt()
                    canvas.drawLine(from.x, from.y, ex, ey, paint)
                    paint.strokeWidth = dp(3f)
                    paint.color = color
                    canvas.drawLine(from.x, from.y, ex, ey, paint)
                    paint.style = Paint.Style.FILL
                    val angle = atan2(uy, ux)
                    val size = dp(10f)
                    val path = Path().apply {
                        moveTo(to.x, to.y)
                        lineTo(
                            ex - size * cos(angle - 0.7f),
                            ey - size * sin(angle - 0.7f)
                        )
                        lineTo(
                            ex - size * cos(angle + 0.7f),
                            ey - size * sin(angle + 0.7f)
                        )
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            } else {
                // Drops have no origin square on the board: highlight target only.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                paint.color = color
                canvas.drawCircle(to.x, to.y, min(cw, ch) * 0.33f, paint)
                paint.style = Paint.Style.FILL
            }
        }
        val marker = (index + 1).toString() + " " +
            formatEngineScore(entry.score)
        label(
            canvas,
            to.x,
            rect.top + (if (index == 1) -dp(8f) else -dp(2f)),
            marker,
            color,
            OverlaySettings.fontSp(context)
        )
    }

    private fun drawPanel(canvas: Canvas, board: RectF) {
        if (!OverlaySettings.showPanel(context)) return
        val sp = OverlaySettings.fontSp(context)
        val lineHeight = dp(29f)
        val panelHeight = lineHeight * candidates.size + dp(10f)
        val panelWidth = min(board.width() * 0.76f, dp(360f))
        val left = (board.right - panelWidth).coerceAtLeast(0f)
        val below = board.bottom + dp(9f)
        val top = if (below + panelHeight <= height - dp(8f)) below
                  else (board.bottom - panelHeight - dp(7f)).coerceAtLeast(0f)

        paint.style = Paint.Style.FILL
        paint.color = 0xE5212731.toInt()
        canvas.drawRoundRect(
            left, top, left + panelWidth, top + panelHeight, dp(12f), dp(12f), paint
        )
        val fontSize = sp * resources.displayMetrics.scaledDensity
        candidates.forEachIndexed { i, item ->
            val rowY = top + dp(10f) + i * lineHeight + lineHeight * 0.6f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = fontSize
            paint.color = colors[i]
            canvas.drawText((i + 1).toString(), left + dp(10f), rowY, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.color = Color.WHITE
            canvas.drawText(item.label, left + dp(31f), rowY, paint)

            val value = formatEngineScore(item.score)
            paint.color = colors[i]
            val valueWidth = paint.measureText(value)
            canvas.drawText(value, left + panelWidth - valueWidth - dp(12f), rowY, paint)
        }
    }

    private fun drawHeader(canvas: Canvas, board: RectF) {
        val size = OverlaySettings.fontSp(context) *
            resources.displayMetrics.scaledDensity
        val text = "将棋解析・候補 " + candidates.size + " 手"
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textSize = size
        val boxWidth = paint.measureText(text) + dp(24f)
        val top = (board.top - dp(37f)).coerceAtLeast(0f)
        val left = board.left + (board.width() - boxWidth) / 2
        paint.color = 0xE5212731.toInt()
        canvas.drawRoundRect(
            left, top, left + boxWidth, top + dp(31f), dp(13f), dp(13f), paint
        )
        paint.color = Color.WHITE
        canvas.drawText(text, left + dp(12f), top + dp(22f), paint)
    }

    private fun drawBoardFrame(canvas: Canvas, board: RectF) {
        // Keep the outline on the board perimeter. Recognition samples the
        // interior of each square and the holding trays outside the board.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.2f)
        paint.color = 0xFF40E080.toInt()
        canvas.drawRect(board, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawUncertainSquares(canvas: Canvas) {
        if (uncertainSquares.isEmpty()) return
        // Use the rejected scan's offset, not the last accepted alignment.
        // Keep the saved calibration and green outline unchanged.
        val board = OverlaySettings.board(context).pixels(width, height).apply {
            offset(uncertainDx.toFloat(), uncertainDy.toFloat())
        }
        val cw = board.width() / 9f
        val ch = board.height() / 9f
        val inset = dp(2f).coerceAtMost(min(cw, ch) / 4f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.RED
        for (index in uncertainSquares) {
            val center = point(board, index)
            canvas.drawRect(center.x - cw / 2 + inset, center.y - ch / 2 + inset,
                center.x + cw / 2 - inset, center.y + ch / 2 - inset, paint)
        }
        paint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val board = OverlaySettings.displayBoard(context, width, height)
        if (!capturingFrame && candidates.isNotEmpty()) {
            drawHeader(canvas, board)
            candidates.forEachIndexed { i, entry -> drawCandidate(canvas, board, i, entry) }
            drawPanel(canvas, board)
        }
        drawBoardFrame(canvas, board)
        // Never let diagnostic ink contaminate the next captured frame.
        if (!capturingFrame) drawUncertainSquares(canvas)
        if (diagnostic && !capturingFrame) {
            // Diagnostic mode separates WindowManager visibility from board
            // recognition. This banner must appear even with zero moves.
            val top = (board.top - dp(36f)).coerceAtLeast(dp(2f))
            val left = board.left.coerceAtLeast(dp(2f))
            paint.style = Paint.Style.FILL
            paint.color = 0xE5212731.toInt()
            canvas.drawRoundRect(left, top, left + dp(190f),
                top + dp(30f), dp(7f), dp(7f), paint)
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = dp(13f)
            canvas.drawText("枠表示テスト：起動中", left + dp(8f),
                top + dp(21f), paint)
        }
    }
}
