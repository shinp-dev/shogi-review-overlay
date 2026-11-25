package com.shinpstudio.shogireviewoverlay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp

/**
 * Shared Wars/Quest 29-class CNN, trained with PyTorch. Input is continuous
 * grayscale darkness, NOT a binary mask. Quantized weights are expanded once
 * to float; inference uses plain Kotlin, with no network or native dependency.
 * Architecture: conv(1,6,5,s2,p2), ReLU, conv(6,6,3,s2,p1), ReLU,
 * maxpool(2), flatten (CHW), dense(96,29).
 */
internal class CnnPieceModel(encoded: String) {
    companion object {
        const val CODES = ".PLNSGBRKTUVWXYplnsgbrktuvwxy"
        private const val MIN_PROBABILITY = .75
        private const val MIN_MARGIN = .25

        fun sample(
            pixels: IntArray, width: Int, height: Int,
            left: Float, top: Float, boardWidth: Float, boardHeight: Float,
            square: Int, inset: Float
        ): FloatArray {
            val cw = boardWidth / 9f
            val ch = boardHeight / 9f
            val x0 = left + (square % 9 + inset) * cw
            val y0 = top + (square / 9 + inset) * ch
            val w = cw * (1f - 2f * inset)
            val h = ch * (1f - 2f * inset)
            return FloatArray(1024) { i ->
                val x = (x0 + (i % 32 + .5f) * w / 32f).toInt().coerceIn(0, width - 1)
                val y = (y0 + (i / 32 + .5f) * h / 32f).toInt().coerceIn(0, height - 1)
                val pixel = pixels[y * width + x]
                val r = (pixel ushr 16) and 255
                val g = (pixel ushr 8) and 255
                val b = pixel and 255
                1f - (77 * r + 150 * g + 29 * b) / (256f * 255f)
            }
        }
    }

    private val c1w: FloatArray
    private val c1b: FloatArray
    private val c2w: FloatArray
    private val c2b: FloatArray
    private val fcw: FloatArray
    private val fcb: FloatArray

    init {
        val bytes = Base64.getDecoder().decode(encoded.trim())
        require(bytes.size == 3327 && String(bytes, 0, 4, Charsets.US_ASCII) == "SCN1")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        fun read(count: Int): FloatArray {
            val scale = buffer.float
            require(scale.isFinite() && scale > 0f)
            return FloatArray(count) { buffer.get().toInt() * scale }
        }
        c1w = read(150); c1b = read(6)
        c2w = read(324); c2b = read(6)
        fcw = read(2784); fcb = read(29)
        require(!buffer.hasRemaining())
    }

    private fun conv(
        input: FloatArray, size: Int, channels: Int,
        weights: FloatArray, biases: FloatArray, kernel: Int, pad: Int
    ): FloatArray {
        val outputSize = size / 2
        val output = FloatArray(6 * outputSize * outputSize)
        for (oc in 0 until 6) for (oy in 0 until outputSize) for (ox in 0 until outputSize) {
            var sum = biases[oc]
            for (ic in 0 until channels) for (ky in 0 until kernel) {
                val y = oy * 2 + ky - pad
                if (y !in 0 until size) continue
                val src = (ic * size + y) * size
                val weight = ((oc * channels + ic) * kernel + ky) * kernel
                for (kx in 0 until kernel) {
                    val x = ox * 2 + kx - pad
                    if (x in 0 until size) sum += input[src + x] * weights[weight + kx]
                }
            }
            output[(oc * outputSize + oy) * outputSize + ox] = maxOf(0f, sum)
        }
        return output
    }

    internal fun logits(input: FloatArray): FloatArray {
        require(input.size == 1024)
        val a = conv(input, 32, 1, c1w, c1b, 5, 2)
        val b = conv(a, 16, 6, c2w, c2b, 3, 1)
        val pooled = FloatArray(96)
        for (c in 0 until 6) for (y in 0 until 4) for (x in 0 until 4) {
            val i = (c * 8 + y * 2) * 8 + x * 2
            pooled[(c * 4 + y) * 4 + x] = maxOf(b[i], b[i+1], b[i+8], b[i+9])
        }
        return FloatArray(CODES.length) { c ->
            var v = fcb[c]
            for (i in pooled.indices) v += pooled[i] * fcw[c * 96 + i]
            v
        }
    }

    fun match(input: FloatArray): PieceTemplateModel.Result {
        val scores = logits(input)
        val best = scores.indices.maxByOrNull { scores[it] }!!
        val second = scores.indices.filter { it != best }.maxByOrNull { scores[it] }!!
        val sum = scores.sumOf { exp((it - scores[best]).toDouble()) }
        val probability = 1.0 / sum
        val margin = probability - exp((scores[second] - scores[best]).toDouble()) / sum
        val certain = probability >= MIN_PROBABILITY && margin >= MIN_MARGIN
        return PieceTemplateModel.Result(
            if (certain && best != 0) CODES[best] else null, probability, margin, !certain
        )
    }
}
