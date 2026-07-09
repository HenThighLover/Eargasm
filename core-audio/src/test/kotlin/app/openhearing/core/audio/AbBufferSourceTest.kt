package app.openhearing.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbBufferSourceTest {
    // 12 samples: raw = 1..12, processed = 101..112 so positions are recognizable.
    private val raw = FloatArray(12) { (it + 1).toFloat() }
    private val processed = FloatArray(12) { (it + 101).toFloat() }

    @Test
    fun `plays raw from the start`() {
        val source = AbBufferSource(raw, processed)
        val block = FloatArray(4)
        source.fill(block)
        assertEquals(listOf(1f, 2f, 3f, 4f), block.toList())
    }

    @Test
    fun `loops when reaching the end`() {
        val source = AbBufferSource(raw, processed)
        val block = FloatArray(8)
        source.fill(block)
        source.fill(block)
        // Second fill: samples 9..12 then wraps to 1..4.
        assertEquals(listOf(9f, 10f, 11f, 12f, 1f, 2f, 3f, 4f), block.toList())
    }

    @Test
    fun `toggle crossfades within one block and continues at the same position`() {
        val source = AbBufferSource(raw, processed)
        val block = FloatArray(4)
        source.fill(block) // positions 0..3, raw
        source.processedActive = true
        source.fill(block) // positions 4..7, crossfade raw -> processed
        // Linear crossfade: t = i/n.
        for (i in 0 until 4) {
            val t = i / 4f
            val expected = raw[4 + i] * (1 - t) + processed[4 + i] * t
            assertEquals(expected, block[i], 1e-6f, "crossfade sample $i")
        }
        source.fill(block) // positions 8..11, fully processed
        assertEquals(listOf(109f, 110f, 111f, 112f), block.toList())
    }

    @Test
    fun `toggling back mid-stream crossfades again`() {
        val source = AbBufferSource(raw, processed)
        val block = FloatArray(4)
        source.processedActive = true
        source.fill(block) // crossfade into processed on the very first block
        source.processedActive = false
        source.fill(block) // crossfade back
        source.fill(block) // fully raw again, positions 8..11
        assertEquals(listOf(9f, 10f, 11f, 12f), block.toList())
    }

    @Test
    fun `reset returns to the start`() {
        val source = AbBufferSource(raw, processed)
        val block = FloatArray(6)
        source.fill(block)
        source.reset()
        source.fill(block)
        assertEquals(1f, block[0])
    }

    @Test
    fun `rejects mismatched buffer lengths`() {
        val error = runCatching { AbBufferSource(FloatArray(4), FloatArray(5)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
