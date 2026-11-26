package com.carteltv.tv.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MatPlaybackHeadTest {

    private val wrap = 1L shl 32

    @Test fun headBeforeAnyWrapIsItself() {
        assertEquals(1000L, Iec61937MatSink.unwrappedHead(head = 1000L, writtenFrames = 5000L))
    }

    @Test fun headAfterTheFirstWrapIsContinuous() {
        val written = wrap + 3840L
        assertEquals(written, Iec61937MatSink.unwrappedHead(head = 3840L, writtenFrames = written))
    }

    @Test fun headThatHasNotWrappedYetIsNotPushedForward() {
        // Everything written lands exactly on the wrap boundary while the head is still behind it.
        assertEquals(wrap - 10L, Iec61937MatSink.unwrappedHead(head = wrap - 10L, writtenFrames = wrap))
    }

    @Test fun headAfterTheSecondWrapIsContinuous() {
        assertEquals(2 * wrap + 5L, Iec61937MatSink.unwrappedHead(head = 5L, writtenFrames = 2 * wrap + 10L))
    }
}
