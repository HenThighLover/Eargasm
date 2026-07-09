package app.openhearing.core.audio

import app.openhearing.common.SafetyConstants
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class DemoClipGeneratorTest {
    private val sampleRate = 48_000
    private val gen = DemoClipGenerator(sampleRateHz = sampleRate, maxAmplitude = 0.5f)

    private fun rms(b: FloatArray): Double {
        var s = 0.0
        for (v in b) s += v.toDouble() * v
        return sqrt(s / b.size)
    }

    @Test
    fun `produces the requested duration`() {
        val clip = gen.generate(durationMs = 5_000)
        assertEquals(sampleRate * 5, clip.size)
    }

    @Test
    fun `is deterministic for the same seed`() {
        assertArrayEquals(gen.generate(), gen.generate())
    }

    @Test
    fun `peak never exceeds the amplitude cap`() {
        val clip = gen.generate()
        assertTrue(clip.all { abs(it) <= 0.5f + 1e-6f }, "peak exceeded cap")
    }

    @Test
    fun `is not silent`() {
        assertTrue(rms(gen.generate()) > 0.01, "clip is essentially silent")
    }

    @Test
    fun `edges are ramped, never abrupt`() {
        val clip = gen.generate()
        val ramp = (SafetyConstants.MIN_TONE_RAMP_MS * sampleRate / 1000L).toInt()
        val edgePeak = (0 until ramp / 4).maxOf { maxOf(abs(clip[it]), abs(clip[clip.size - 1 - it])) }
        assertTrue(edgePeak < 0.25f, "clip edges are not ramped (edge peak $edgePeak)")
    }

    @Test
    fun `contains high-frequency energy where profiles apply gain`() {
        val clip = gen.generate()
        // First-difference high-pass: emphasizes content above ~4 kHz at 48 kHz.
        val high = FloatArray(clip.size)
        for (i in 1 until clip.size) high[i] = clip[i] - clip[i - 1]
        assertTrue(
            rms(high) > 0.05 * rms(clip),
            "expected audible high-frequency (consonant) energy in the demo clip",
        )
    }
}
