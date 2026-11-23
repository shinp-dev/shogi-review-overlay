package com.shinpstudio.shogireviewoverlay

import android.graphics.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import java.util.concurrent.TimeUnit

data class BoardRecognition(val sfen: String?, val message: String, val recognized: Int)

/**
 * In-device OCR: isolate the grid into two atlases, rotate pieces 180 degrees for gote.
 * Do not invent hand pieces or let an uncertain scan overwrite the current position.
 */
class ShogiScreenRecognizer : AutoCloseable {
    private val client = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private fun glyph(raw: String, side: Side): Piece? {
        val t = raw.trim()
        val promo = t.startsWith("成") || t.startsWith("と") || t.startsWith("龍") ||
            t.startsWith("竜") || t.startsWith("馬") || t.startsWith("杏") ||
            t.startsWith("圭") || t.startsWith("全")
        val type = when {
            "歩" in t || t.startsWith("と") -> PieceType.PAWN
            "香" in t || t.startsWith("杏") -> PieceType.LANCE
            "桂" in t || t.startsWith("圭") -> PieceType.KNIGHT
            "銀" in t || t.startsWith("全") -> PieceType.SILVER
            "金" in t -> PieceType.GOLD
            "角" in t || t.startsWith("馬") -> PieceType.BISHOP
            "飛" in t || t.startsWith("龍") || t.startsWith("竜") -> PieceType.ROOK
            "王" in t || "玉" in t -> PieceType.KING
            else -> return null
        }
        return Piece(type, side, promo && type.canPromote)
    }

    private fun atlas(frame: Bitmap, r: RectF, rotated: Boolean, cell: Int): Bitmap {
        val out = Bitmap.createBitmap(cell * 9, cell * 9, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (i in 0..80) {
            val row = i / 9
            val col = i % 9
            val sw = r.width() / 9
            val sh = r.height() / 9
            val src = Rect(
                (r.left + (col + .08f) * sw).toInt().coerceIn(0, frame.width - 1),
                (r.top + (row + .08f) * sh).toInt().coerceIn(0, frame.height - 1),
                (r.left + (col + .92f) * sw).toInt().coerceIn(1, frame.width),
                (r.top + (row + .92f) * sh).toInt().coerceIn(1, frame.height)
            )
            if (src.isEmpty) continue
            c.save()
            c.translate((col + .5f) * cell, (row + .5f) * cell)
            if (rotated) c.rotate(180f)
            val h = cell * .39f
            c.drawBitmap(frame, src, RectF(-h, -h, h, h), paint)
            c.restore()
        }
        return out
    }

    private fun recognizeAtlas(bmp: Bitmap, side: Side, cell: Int): Map<Int, Piece> {
        val result = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)), 25, TimeUnit.SECONDS)
        val found = mutableMapOf<Int, Piece>()
        for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
            val b = el.boundingBox ?: continue
            val col = (b.centerX() / cell).coerceIn(0, 8)
            val row = (b.centerY() / cell).coerceIn(0, 8)
            if (kotlin.math.abs(b.centerX() - (col + .5f) * cell) > cell * .4f ||
                kotlin.math.abs(b.centerY() - (row + .5f) * cell) > cell * .4f) continue
            val piece = glyph(el.text, side) ?: continue
            found.putIfAbsent(row * 9 + col, piece)
        }
        return found
    }

    private fun darkInk(frame: Bitmap, r: RectF, i: Int): Boolean {
        var n = 0
        for (y in 3..13) for (x in 3..13) {
            val px = (r.left + (i % 9 + x / 16f) * r.width() / 9).toInt()
            val py = (r.top + (i / 9 + y / 16f) * r.height() / 9).toInt()
            if (px !in 0 until frame.width || py !in 0 until frame.height) continue
            val p = frame.getPixel(px, py)
            if (Color.red(p) < 125 && Color.green(p) < 125 && Color.blue(p) < 125) n++
        }
        return n >= 3
    }

    fun recognize(frame: Bitmap, geometry: BoardRect, turn: Side): BoardRecognition {
        val r = geometry.pixels(frame.width, frame.height)
        if (r.left < 0 || r.top < 0 || r.right > frame.width || r.bottom > frame.height ||
            r.width() < 300 || r.height() < 300)
            return BoardRecognition(null, "盤面位置の調整が必要です", 0)
        val cell = 112
        val normal = atlas(frame, r, false, cell)
        val rotated = atlas(frame, r, true, cell)
        val black: Map<Int, Piece>
        val white: Map<Int, Piece>
        try {
            black = recognizeAtlas(normal, Side.BLACK, cell)
            white = recognizeAtlas(rotated, Side.WHITE, cell)
        } finally {
            normal.recycle()
            rotated.recycle()
        }
        val board = MutableList<Piece?>(81) { null }
        var ambiguous = 0
        for (i in 0..80) {
            val a = black[i]
            val b = white[i]
            board[i] = when {
                a == null -> b
                b == null -> a
                else -> {
                    if (a.type != b.type || a.promoted != b.promoted) ambiguous++
                    if (i / 9 < 4) b else a
                }
            }
            if (board[i] == null && darkInk(frame, r, i)) ambiguous++
        }
        val occupied = board.count { it != null }
        val kings = Side.entries.all { side ->
            board.count { it?.type == PieceType.KING && it.side == side } == 1
        }
        if (occupied != 40 || ambiguous != 0 || !kings)
            return BoardRecognition(null, "認識未確定：盤上" + occupied +
                "/40、不確実" + ambiguous + "、王将" + (if (kings) "OK" else "NG") +
                "。持ち駒・駒種を確認してSFENを修正してください", occupied)
        val sfenBoard = (0..8).joinToString("/") { row ->
            val result = StringBuilder()
            var empty = 0
            for (col in 0..8) {
                val p = board[row * 9 + col]
                if (p == null) { empty++; continue }
                if (empty > 0) { result.append(empty); empty = 0 }
                if (p.promoted) result.append('+')
                result.append(if (p.side == Side.BLACK) p.type.sfen else p.type.sfen.lowercaseChar())
            }
            if (empty > 0) result.append(empty)
            result.toString()
        }
        val sfen = sfenBoard + (if (turn == Side.BLACK) " b - 1" else " w - 1")
        return BoardRecognition(sfen, "盤上40枚をOCRで認識。持ち駒なしの局面のみ", occupied)
    }

    override fun close() { client.close() }
}
