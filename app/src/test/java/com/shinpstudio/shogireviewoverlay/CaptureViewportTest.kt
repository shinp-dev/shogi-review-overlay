package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureViewportTest {
    private fun assertBoard(expected: BoardRect, actual: BoardRect) {
        assertEquals(expected.left, actual.left, .00001f)
        assertEquals(expected.top, actual.top, .00001f)
        assertEquals(expected.width, actual.width, .00001f)
        assertEquals(expected.height, actual.height, .00001f)
    }

    @Test fun fullScreenViewportIsIdentity() {
        val board = BoardRect(.012f, .179f, .977f, .480f)
        assertBoard(board, BoardRect.fromViewport(board, 0, 0, 1080, 2400, 1080, 2400))
    }

    @Test fun insetViewportUsesActualScreenOriginAndSize() {
        val board = BoardRect(.1f, .2f, .8f, .5f)
        val actual = BoardRect.fromViewport(board, 20, 80, 1040, 2200, 1080, 2400)
        assertBoard(BoardRect(124f/1080, 520f/2400, 832f/1080, 1100f/2400), actual)
    }

    @Test fun navigationBarInsetDoesNotStretchBoard() {
        val board = BoardRect(.012f, .179f, .977f, .480f)
        val actual = BoardRect.fromViewport(board, 0, 0, 1080, 2320, 1080, 2400)
        assertBoard(BoardRect(.012f, .179f*2320/2400, .977f, .480f*2320/2400), actual)
    }
}
