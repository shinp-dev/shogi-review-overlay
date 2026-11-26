package com.shinpstudio.shogireviewoverlay

import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.exp

/**
 * One local, debug-only manual scan. No full screenshots, usernames, or
 * network transmission. The next manual scan replaces the previous sample.
 */
internal object CnnInputProbe {
    fun write(
        cacheDir: File, asset: String, cnn: CnnPieceModel, pixels: IntArray,
        width: Int, height: Int, rect: RectF, inset: Float, scan: BoardAlignment.Scan
    ) {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(asset.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val dest = File(cacheDir, "shogi-cnn-probe.txt")
        val temp = File(cacheDir, "shogi-cnn-probe.tmp")
        temp.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("SCN-PROBE-1")
            out.appendLine("model_sha256=$hash")
            out.appendLine("frame=${width}x${height}")
            out.appendLine("rect=${rect.left},${rect.top},${rect.width()},${rect.height()}")
            out.appendLine("inset=$inset offset=${scan.dx},${scan.dy}")
            out.appendLine("occupied=${scan.occupied} uncertain=${scan.uncertain} kings=${scan.kings}")
            out.appendLine("square,top,probability,margin,unknown,input_f32le_base64")
            for (i in 0..80) {
                val input = CnnPieceModel.sample(pixels, width, height,
                    rect.left + scan.dx, rect.top + scan.dy,
                    rect.width(), rect.height(), i, inset)
                val logits = cnn.logits(input)
                val best = logits.indices.maxByOrNull { logits[it] }!!
                val second = logits.indices.filter { it != best }.maxByOrNull { logits[it] }!!
                val sum = logits.sumOf { exp((it - logits[best]).toDouble()) }
                val probability = 1.0 / sum
                val margin = probability - exp((logits[second] - logits[best]).toDouble()) / sum
                val unknown = cnn.match(input).unknown
                val buf = ByteBuffer.allocate(input.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                input.forEach { buf.putFloat(it) }
                val encoded = Base64.getEncoder().encodeToString(buf.array())
                out.appendLine("$i,${CnnPieceModel.CODES[best]},$probability,$margin,$unknown,$encoded")
            }
        }
        check(temp.renameTo(dest)) { "Cannot publish CNN probe" }
        Log.i("ShogiOverlay", "CNN probe saved: ${dest.name}; model=$hash; " +
            "occupied=${scan.occupied} uncertain=${scan.uncertain}")
    }
}
