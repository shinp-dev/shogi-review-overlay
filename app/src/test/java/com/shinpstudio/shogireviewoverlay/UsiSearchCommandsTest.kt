package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.assertEquals
import org.junit.Test

class UsiSearchCommandsTest {
    @Test fun sendsConcreteValues() {
        val c = UsiSearchCommands(3, 1200)
        assertEquals("setoption name MultiPV value 3", c.multiPv)
        assertEquals("go movetime 1200", c.go)
    }

    @Test fun defaultsPreserveExistingEngineOptions() {
        val c = UsiSearchCommands(3, 1200)
        assertEquals("setoption name Threads value 1", c.threadsOption)
        assertEquals("setoption name USI_Hash value 16", c.hashOption)
    }

    @Test fun allSupportedEngineOptionsProduceNumericCommands() {
        for (threads in listOf(1, 2, 4, 6, 8)) {
            for (hash in listOf(16, 32, 64, 128)) {
                val c = UsiSearchCommands(3, 1200, threads, hash)
                assertEquals("setoption name Threads value " + threads, c.threadsOption)
                assertEquals("setoption name USI_Hash value " + hash, c.hashOption)
            }
        }
    }

    @Test fun unsupportedOptionsFallBackToSafeDefaults() {
        for (value in listOf(-1, 0, 3, 7, 999)) {
            val c = UsiSearchCommands(3, 1200, value, value)
            assertEquals(1, c.threadCount)
            assertEquals(16, c.hashSizeMb)
        }
    }

    @Test fun clampsBothCommandsToMinimum() {
        val c = UsiSearchCommands(0, 0)
        assertEquals("setoption name MultiPV value 1", c.multiPv)
        assertEquals("go movetime 250", c.go)
    }

    @Test fun clampsBothCommandsToMaximum() {
        val c = UsiSearchCommands(10, 10000)
        assertEquals("setoption name MultiPV value 3", c.multiPv)
        assertEquals("go movetime 7000", c.go)
    }
}
