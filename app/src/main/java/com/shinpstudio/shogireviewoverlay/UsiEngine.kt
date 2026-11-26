package com.shinpstudio.shogireviewoverlay

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
        val commands = UsiSearchCommands(count, millis)
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
            Log.d("ShogiUsi", "> $command")
            writer.write(command)
            writer.newLine()
            writer.flush()
        }
        fun awaitLine(expected: String, timeoutMs: Long) {
            val task = executor.submit {
                while (true) {
                    val line = reader.readLine() ?: error("エンジンが終了しました: $expected")
                    Log.d("ShogiUsi", "< " + line.take(500))
                    if (line.trim() == expected) return@submit
                }
            }
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
        try {
            send("usi")
            awaitLine("usiok", 4500)
            send(commands.multiPv)
            send("setoption name USI_Hash value 16")
            send("setoption name Threads value 1")
            send("setoption name USI_OwnBook value false")
            send("setoption name BookFile value no_book")
            send("isready")
            awaitLine("readyok", 7000)
            send("usinewgame")
            send("position sfen $sfen")

            val output = executor.submit<Pair<List<UsiPrincipalVariation>, String?>> {
                val pvByRank = mutableMapOf<Int, UsiPrincipalVariation>()
                var bestmove: String? = null
                val deadline = SystemClock.elapsedRealtime() + commands.thinkMillis + 5500L
                while (SystemClock.elapsedRealtime() < deadline) {
                    val text = reader.readLine()?.trim() ?: break
                    Log.d("ShogiUsi", "< " + text.take(1000))
                    if (text.startsWith("info string CRITICAL ERROR:")) error(text)
                    if (text.startsWith("bestmove ")) {
                        bestmove = text.split(Regex("\\s+")).getOrNull(1)
                        break
                    }
                    val pv = UsiInfoParser.parse(text) ?: continue
                    if (pv.rank !in 1..commands.candidateCount || pv.moves.isEmpty() ||
                        UsiInfoParser.score(pv) == null) continue
                    val old = pvByRank[pv.rank]
                    if (old == null || pv.depth >= old.depth) pvByRank[pv.rank] = pv
                }
                pvByRank.values.sortedBy { it.rank } to bestmove
            }
            send(commands.go)
            val (multiPv, bestmove) = output.get(
                commands.thinkMillis + 6500L, TimeUnit.MILLISECONDS)
            val legal = ShogiRules.legalMoves(position)
            val parsed = UsiInfoParser.candidates(position, multiPv, bestmove, commands.candidateCount)
            Log.i("ShogiUsi", "legal=${legal.size} pv=${multiPv.map { it.moves.firstOrNull() }} " +
                "bestmove=$bestmove accepted=${parsed.map { it.move.usi() }}")
            check(parsed.isNotEmpty()) {
                when {
                    legal.isEmpty() -> "合法手なし（詰み等）"
                    bestmove == "resign" || bestmove == "win" -> "エンジン終局応答: $bestmove"
                    bestmove == null -> "エンジン最善手なし（PV ${multiPv.size}件）"
                    else -> "エンジン応答 $bestmove を合法手として確認できません（PV ${multiPv.size}件）"
                }
            }
            return parsed
        } finally {
            try { writer.write("quit\n"); writer.flush() } catch (_: Exception) {}
            try { writer.close() } catch (_: Exception) {}
            process.destroy()
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
