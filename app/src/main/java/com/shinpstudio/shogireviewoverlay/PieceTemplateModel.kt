package com.shinpstudio.shogireviewoverlay

import java.util.Base64
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * 32x32 monochrome piece templates.
 *
 * We compare the sum of dark pixels on each of 32 horizontal and vertical
 * axes first (64 cheap features); then verify their full pixel arrangement.
 *
 * Templates are from cropped piece-only screenshot cells. No personal/user
 * image content or full screenshots are embedded.
 */
internal class PieceTemplateModel(private val templates: Map<Char, Signature>) {
    data class Signature(
        val pixels: LongArray,
        val axes: IntArray,
        val ink: Int
    ) {
        init {
            require(pixels.size == 16 && axes.size == 64)
        }
    }

    data class Result(val code: Char?, val score: Double, val margin: Double, val unknown: Boolean)

    companion object {
        const val TILE = 32
        const val INSET = 0.08f
        private const val MIN_INK = 70
        private const val MIN_SCORE = 0.74
        private const val MIN_MARGIN = 0.075

        fun create(templates: String): PieceTemplateModel {
            val map = mutableMapOf<Char, Signature>()
            for (line in templates.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val sep = trimmed.indexOf(':')
                require(sep == 1) { "Invalid template row" }
                val key = trimmed[0]
                require(key in "PLNSGBRKplnsgbrk") { "Invalid piece code" }
                val bytes = Base64.getDecoder().decode(trimmed.substring(sep + 1))
                require(bytes.size == 128) { "Invalid 32x32 mask for $key" }
                val dark = BooleanArray(1024) { i ->
                    ((bytes[i ushr 3].toInt() and 0xFF) and (1 shl (7 - (i and 7)))) != 0
                }
                map[key] = fromMask(dark)
            }
            require(map.size == 16) { "Need all 16 unpromoted side-specific pieces" }
            return PieceTemplateModel(map)
        }

        fun fromMask(mask: BooleanArray): Signature {
            require(mask.size == TILE * TILE)
            val bits = LongArray(16)
            val axis = IntArray(64)
            var ink = 0
            for (i in mask.indices) {
                if (!mask[i]) continue
                bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
                axis[i / TILE]++
                axis[32 + i % TILE]++
                ink++
            }
            return Signature(bits, axis, ink)
        }

        /**
         * Sample only the center 84% of the square, dropping outer grid lines.
         * Crop is proportional to square size, not a fixed pixel count.
         */
        fun sample(
            argb: IntArray,
            imageWidth: Int,
            imageHeight: Int,
            boardLeft: Float,
            boardTop: Float,
            boardWidth: Float,
            boardHeight: Float,
            square: Int,
            inset: Float = INSET
        ): Signature {
            require(square in 0..80)
            require(inset >= 0f && inset < .25f)
            require(imageWidth > 0 && imageHeight > 0)
            require(argb.size >= imageWidth * imageHeight)
            val cellW = boardWidth / 9f
            val cellH = boardHeight / 9f
            val col = square % 9
            val row = square / 9
            val x0 = boardLeft + (col + inset) * cellW
            val y0 = boardTop + (row + inset) * cellH
            val usableW = cellW * (1f - 2 * inset)
            val usableH = cellH * (1f - 2 * inset)
            val mask = BooleanArray(1024)
            for (y in 0 until TILE) for (x in 0 until TILE) {
                val sx = (x0 + (x + .5f) / TILE * usableW - .5f)
                    .roundToInt().coerceIn(0, imageWidth - 1)
                val sy = (y0 + (y + .5f) / TILE * usableH - .5f)
                    .roundToInt().coerceIn(0, imageHeight - 1)
                val pixel = argb[sy * imageWidth + sx]
                val red = (pixel ushr 16) and 255
                val green = (pixel ushr 8) and 255
                val blue = pixel and 255
                mask[y * TILE + x] = (77 * red + 150 * green + 29 * blue) < 115 * 256
            }
            return fromMask(mask)
        }

        private fun axisScore(a: Signature, b: Signature): Double {
            var dot = 0.0
            var asq = 0.0
            var bsq = 0.0
            for (i in 0..63) {
                val x = a.axes[i].toDouble()
                val y = b.axes[i].toDouble()
                dot += x * y
                asq += x * x
                bsq += y * y
            }
            return if (asq == 0.0 || bsq == 0.0) 0.0 else dot / sqrt(asq * bsq)
        }

        private fun fullScore(a: Signature, b: Signature): Double {
            if (a.ink == 0 || b.ink == 0) return 0.0
            var common = 0
            for (i in 0 until 16) common += java.lang.Long.bitCount(a.pixels[i] and b.pixels[i])
            return common / sqrt(a.ink.toDouble() * b.ink)
        }
    }

    fun match(signature: Signature): Result {
        if (signature.ink < MIN_INK) return Result(null, 0.0, 0.0, false)
        // Pass 1: cheap one-dimensional dark-pixel profiles for all piece types.
        val ranked = templates.entries.map { (code, template) ->
            code to axisScore(signature, template)
        }.sortedByDescending { it.second }
        // Pass 2: distinguish visually similar glyphs via the 32x32 pixel layout.
        val scores = ranked.take(6).map { (code, profile) ->
            val spatial = fullScore(signature, templates.getValue(code))
            code to (0.55 * profile + 0.45 * spatial)
        }.sortedByDescending { it.second }
        val best = scores[0]
        val runnerUp = scores[1]
        val margin = best.second - runnerUp.second
        val certain = best.second >= MIN_SCORE && margin >= MIN_MARGIN
        return Result(if (certain) best.first else null, best.second, margin, !certain)
    }
}
