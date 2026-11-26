package com.carteltv.tv.engine.stability

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderBudgetTest {

    @Test fun aSingleDecoderStillPlaysOneStream() {
        assertEquals(1, DecoderBudget.computeMaxStreams(1, 16, heapMb = 1024, lowRam = false, physicalRamMb = 4096))
        assertEquals(1, DecoderBudget.computeMaxStreams(16, 1, heapMb = 1024, lowRam = false, physicalRamMb = 4096))
    }

    @Test fun ramThatWasNotReportedCountsAsTheSmallestTier() {
        assertEquals(2, DecoderBudget.computeMaxStreams(16, 16, heapMb = 600, lowRam = false, physicalRamMb = 0))
    }

    @Test fun knownRamStillSetsTheTier() {
        assertEquals(2, DecoderBudget.computeMaxStreams(16, 16, heapMb = 1024, lowRam = false, physicalRamMb = 2048))
        assertEquals(3, DecoderBudget.computeMaxStreams(16, 16, heapMb = 1024, lowRam = false, physicalRamMb = 3072))
        assertEquals(4, DecoderBudget.computeMaxStreams(16, 16, heapMb = 1024, lowRam = false, physicalRamMb = 4096))
    }

    @Test fun aSmallAppHeapKeepsABigBoxAtThreeStreams() {
        assertEquals(3, DecoderBudget.computeMaxStreams(16, 16, heapMb = 600, lowRam = false, physicalRamMb = 4096))
    }

    @Test fun aLowRamDeviceStaysAtTwoStreams() {
        assertEquals(2, DecoderBudget.computeMaxStreams(16, 16, heapMb = 1024, lowRam = true, physicalRamMb = 4096))
    }
}
