package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.assertEquals
import org.junit.Test

class UsiSearchCommandsTest {
    @Test fun sendsConcreteValues() {
        val c = UsiSearchCommands(3, 1200)
        assertEquals("setoption name MultiPV value 3", c.multiPv)
        assertEquals("go movetime 1200", c.go)
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
