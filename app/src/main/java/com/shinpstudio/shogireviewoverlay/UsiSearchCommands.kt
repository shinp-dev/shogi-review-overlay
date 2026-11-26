package com.shinpstudio.shogireviewoverlay

/** USI wire commands must contain numeric values, never literal expressions. */
internal object UsiEngineOptions {
    val threadChoices = listOf(1, 2, 4, 6, 8)
    val hashChoices = listOf(16, 32, 64, 128)
    fun threads(value: Int): Int = if (value in threadChoices) value else 1
    fun hashMb(value: Int): Int = if (value in hashChoices) value else 16
}

internal class UsiSearchCommands(count: Int, millis: Int, threads: Int = 1, hashMb: Int = 16) {
    val threadCount = UsiEngineOptions.threads(threads)
    val hashSizeMb = UsiEngineOptions.hashMb(hashMb)
    val threadsOption = "setoption name Threads value " + threadCount
    val hashOption = "setoption name USI_Hash value " + hashSizeMb
    val candidateCount = count.coerceIn(1, 3)
    val thinkMillis = millis.coerceIn(250, 7000)
    val multiPv = "setoption name MultiPV value " + candidateCount
    val go = "go movetime " + thinkMillis
}
