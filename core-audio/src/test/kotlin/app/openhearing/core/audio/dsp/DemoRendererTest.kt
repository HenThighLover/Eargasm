package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve
import app.openhearing.audiogram.GainPoint
import app.openhearing.common.Hertz
import app.openhearing.core.audio.DemoClipGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class DemoRendererTest {
    private val sampleRate = 48_000
    private val clip = DemoClipGenerator(sampleRateHz = sampleRate).generate(durationMs = 1_000)

    private val flat = GainCurve(listOf(GainPoint(Hertz(1000.0), 0.0), GainPoint(Hertz(4000.0), 0.0)))
    private val boost =
        GainCurve(
            listOf(
                GainPoint(Hertz(1000.0), 40.0),
                GainPoint(Hertz(2000.0), 40.0),
                GainPoint(Hertz(4000.0), 40.0),
            ),
        )

    private fun rms(b: FloatArray): Double {
        var s = 0.0
        for (v in b) s += v.toDouble() * v
        return sqrt(s / b.size)
    }

    @Test
    fun `raw expansion duplicates mono into both channels`() {
        val stereo = DemoRenderer.stereoRaw(clip)
        assertEquals(clip.size * 2, stereo.size)
        for (i in 0 until 200) {
            assertEquals(stereo[2 * i], stereo[2 * i + 1])
            assertEquals(clip[i], stereo[2 * i])
        }
    }

    @Test
    fun `pathological gain never exceeds the ceiling`() {
        val ceiling = 0.6f
        val out =
            DemoRenderer.renderProcessed(
                mono = clip,
                leftCurve = boost,
                rightCurve = boost,
                masterGainDb = 40.0,
                ceilingLinear = ceiling,
                sampleRateHz = sampleRate,
            )
        assertTrue(out.all { abs(it) <= ceiling + 1e-4f }, "SAFETY: processed demo exceeded ceiling")
    }

    @Test
    fun `raw and processed are the same length`() {
        val out =
            DemoRenderer.renderProcessed(clip, flat, flat, 0.0, 0.9f, sampleRate)
        assertEquals(DemoRenderer.stereoRaw(clip).size, out.size)
    }

    @Test
    fun `boosted profile is audibly louder than flat`() {
        val flatOut = DemoRenderer.renderProcessed(clip, flat, flat, 0.0, 0.9f, sampleRate)
        val boostOut = DemoRenderer.renderProcessed(clip, boost, boost, 12.0, 0.9f, sampleRate)
        assertTrue(
            rms(boostOut) > rms(flatOut) * 1.5,
            "expected an audible A/B difference for a boosted profile",
        )
    }
}
