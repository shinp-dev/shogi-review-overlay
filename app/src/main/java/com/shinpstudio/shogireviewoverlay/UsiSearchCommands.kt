package com.shinpstudio.shogireviewoverlay

/** USI wire commands must contain numeric values, never literal expressions. */
internal class UsiSearchCommands(count: Int, millis: Int) {
    val candidateCount = count.coerceIn(1, 3)
    val thinkMillis = millis.coerceIn(250, 7000)
    val multiPv = "setoption name MultiPV value " + candidateCount
    val go = "go movetime " + thinkMillis
}
