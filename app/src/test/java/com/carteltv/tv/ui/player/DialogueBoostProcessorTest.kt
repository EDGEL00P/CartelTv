package com.carteltv.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DialogueBoostProcessorTest {

    private fun processor(level: Int, encoding: Int = C.ENCODING_PCM_16BIT, channels: Int = 2) =
        DialogueBoostProcessor(level).apply {
            configure(AudioProcessor.AudioFormat(48_000, channels, encoding))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

    private fun pcm16(vararg samples: Int): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
        .order(ByteOrder.nativeOrder())
        .also { buffer -> samples.forEach { buffer.putShort(it.toShort()) } }
        .also { it.flip() }

    private fun pcmFloat(vararg samples: Float): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 4)
        .order(ByteOrder.nativeOrder())
        .also { buffer -> samples.forEach { buffer.putFloat(it) } }
        .also { it.flip() }

    private fun ByteBuffer.shorts(): List<Short> = ByteBuffer.allocateDirect(remaining()).order(order())
        .also { it.put(this) }.also { it.flip() }.asShortBuffer().let { s -> List(s.remaining()) { s.get() } }

    @Test fun inputIsConsumedAndOutputHoldsExactlyTheFramesGiven() {
        val p = processor(level = 2)

        val first = pcm16(1000, -1000, 2000, -2000, 3000, -3000, 4000, -4000)
        p.queueInput(first)
        assertEquals("the whole input must be consumed", 0, first.remaining())
        assertEquals(16, p.getOutput().remaining())

        // The output buffer is reused; a smaller chunk must not carry the previous chunk's tail.
        val second = pcm16(500, -500)
        p.queueInput(second)
        assertEquals(0, second.remaining())
        assertEquals(4, p.getOutput().remaining())
    }

    @Test fun floatInputIsConsumedAndOutputHoldsExactlyTheFramesGiven() {
        val p = processor(level = 1, encoding = C.ENCODING_PCM_FLOAT)

        val first = pcmFloat(0.25f, -0.25f, 0.5f, -0.5f)
        p.queueInput(first)
        assertEquals(0, first.remaining())
        assertEquals(16, p.getOutput().remaining())

        val second = pcmFloat(0.1f, -0.1f)
        p.queueInput(second)
        assertEquals(0, second.remaining())
        assertEquals(8, p.getOutput().remaining())
    }

    @Test fun partialTrailingFrameWaitsForTheRestOfIt() {
        val p = processor(level = 1)
        val input = pcm16(1, 2, 3, 4, 5, 6, 7)
        p.queueInput(input)
        assertEquals(2, input.remaining())
        assertEquals(12, p.getOutput().remaining())
    }

    @Test fun readsFromTheBuffersPositionNotItsStart() {
        val skipped = pcm16(9000, 9000, 1000, -1000, 1000, -1000)
        val direct = pcm16(1000, -1000, 1000, -1000)

        val withOffset = processor(level = 3)
        skipped.position(4)
        withOffset.queueInput(skipped)
        assertEquals(0, skipped.remaining())

        val withoutOffset = processor(level = 3)
        withoutOffset.queueInput(direct)

        assertEquals(withoutOffset.getOutput().shorts(), withOffset.getOutput().shorts())
    }

    @Test fun monoPassesThroughUnchanged() {
        val p = processor(level = 3, channels = 1)
        val input = pcm16(0, 1, -1, 32767, -32768)
        p.queueInput(input)
        assertEquals(listOf<Short>(0, 1, -1, 32767, -32768), p.getOutput().shorts())
    }
}
