package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.os.SystemClock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real USI MultiPV runner for the native YaneuraOu material edition. */
class UsiEngine(private val context: Context) {
    companion object {
        fun executablePath(context: Context) =
            context.applicationInfo.nativeLibraryDir + "/libshogi_engine.so"
        fun isInstalled(context: Context): Boolean =
            java.io.File(executablePath(context)).canExecute()
    }

    fun analyze(sfen: String, position: ShogiPosition, count: Int, millis: Int): List<Candidate> {
        check(isInstalled(context)) { "やねうら王本体がAPKにありません（NDKを含むCIでビルドしてください）" }
        val process = ProcessBuilder(executablePath(context))
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "shogi-usi-reader").apply { isDaemon = true }
        }
        fun send(command: String) {
            writer.write(command)
            writer.newLine()
            writer.flush()
        }
        fun awaitLine(expected: String, timeoutMs: Long) {
            val task = executor.submit {
                while (true) {
                    val line = reader.readLine() ?: error("エンジンが終了しました: $expected")
                    if (line.trim() == expected) return@submit
                }
            }
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
        try {
            send("usi")
            awaitLine("usiok", 4500)
            send("setoption name MultiPV value \${count.coerceIn(1, 3)}")
            send("setoption name USI_Hash value 16")
            send("setoption name Threads value 1")
            send("setoption name USI_OwnBook value false")
            send("isready")
            awaitLine("readyok", 7000)
            send("usinewgame")
            send("position sfen $sfen")

            val output = executor.submit<List<UsiPrincipalVariation>> {
                val pvByRank = mutableMapOf<Int, UsiPrincipalVariation>()
                val deadline = SystemClock.elapsedRealtime() + millis + 5500L
                while (SystemClock.elapsedRealtime() < deadline) {
                    val text = reader.readLine() ?: break
                    if (text.startsWith("bestmove")) break
                    val pv = UsiInfoParser.parse(text) ?: continue
                    if (pv.rank !in 1..count || pv.moves.isEmpty()) continue
                    val old = pvByRank[pv.rank]
                    if (old == null || pv.depth >= old.depth) pvByRank[pv.rank] = pv
                }
                pvByRank.values.sortedBy { it.rank }
            }
            send("go movetime \${millis.coerceIn(250, 7000)}")
            val multiPv = output.get(millis.coerceIn(250, 7000) + 6500L, TimeUnit.MILLISECONDS)
            val legalByUsi = ShogiRules.legalMoves(position).associateBy { it.usi() }
            val parsed = multiPv.mapNotNull { pv ->
                val move = legalByUsi[pv.moves.firstOrNull()] ?: return@mapNotNull null
                val score = pv.scoreCp ?: pv.mate?.toIntOrNull()?.let {
                    if (it >= 0) 30000 - it else -30000 - it
                } ?: return@mapNotNull null
                Candidate(move, score, move.japanese(position))
            }
            check(parsed.isNotEmpty()) { "エンジンから合法な読み筋を取得できませんでした" }
            return parsed.take(count.coerceIn(1, 3))
        } finally {
            try { writer.write("quit\n"); writer.flush() } catch (_: Exception) {}
            try { writer.close() } catch (_: Exception) {}
            process.destroy()
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
