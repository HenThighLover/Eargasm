package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve
import app.openhearing.audiogram.GainPoint
import app.openhearing.common.Hertz
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Covers OutputLevelMeter, MeteredAudioProcessor, and ExposureTracker. */
class ExposureMeteringTest {
    private val sampleRate = 48_000

    private fun sine(amp: Double, n: Int) = FloatArray(n) { (amp * sin(2.0 * PI * 1000.0 * it / sampleRate)).toFloat() }

    @Test
    fun `meter reports the RMS of a known sine`() {
        val meter = OutputLevelMeter()
        meter.accumulate(sine(amp = 1.0, n = sampleRate))
        val window = meter.drain()
        // Full-scale sine: RMS = 1/sqrt(2) = -3.01 dBFS.
        assertEquals(-3.01, window.rmsDbfs, 0.05)
        assertEquals(sampleRate.toLong(), window.sampleCount)
    }

    @Test
    fun `drain resets the accumulators`() {
        val meter = OutputLevelMeter()
        meter.accumulate(sine(amp = 0.5, n = 1024))
        meter.drain()
        val second = meter.drain()
        assertEquals(0L, second.sampleCount)
        assertEquals(0.0, second.sumSquares)
    }

    @Test
    fun `windows accumulate across multiple blocks`() {
        val meter = OutputLevelMeter()
        repeat(4) { meter.accumulate(sine(amp = 0.5, n = 1000)) }
        assertEquals(4_000L, meter.drain().sampleCount)
    }

    @Test
    fun `metered processor delegates and observes post-limiter output`() {
        val curve = GainCurve(listOf(GainPoint(Hertz(250.0), 40.0), GainPoint(Hertz(8000.0), 40.0)))
        val ceiling = 0.5f
        val chain =
            HearingAssistChain(curve, sampleRate, masterGainDb = 40.0, ceilingLinear = ceiling)
        val meter = OutputLevelMeter()
        val metered = MeteredAudioProcessor(chain, meter)

        val buffer = sine(amp = 0.9, n = sampleRate)
        metered.process(buffer)

        // Delegate ran (limiter enforced)...
        assertTrue(buffer.all { abs(it) <= ceiling + 1e-4f }, "chain/limiter did not run")
        // ...and the meter saw the same post-limiter samples (safety-ordering check).
        val window = meter.drain()
        assertTrue(window.rmsLinear <= ceiling + 1e-4, "meter must observe post-limiter output")
        assertTrue(window.sampleCount == sampleRate.toLong())
    }

    @Test
    fun `three dB more level halves the time to the same units`() {
        val quiet = LevelWindow(sumSquares = 0.25 * 1000, sampleCount = 1000) // meanSquare 0.25
        val loud = LevelWindow(sumSquares = 0.5 * 1000, sampleCount = 1000) // +3 dB power
        val quietUnits = ExposureTracker.unitsFor(quiet, seconds = 2.0)
        val loudUnits = ExposureTracker.unitsFor(loud, seconds = 1.0)
        assertEquals(quietUnits, loudUnits, 1e-9)
    }

    @Test
    fun `reference hours at reference level is exactly one hundred percent`() {
        val refPower = 10.0.pow(ExposureTracker.REF_LEVEL_DBFS / 10.0)
        val window = LevelWindow(sumSquares = refPower * 1000, sampleCount = 1000)
        val units = ExposureTracker.unitsFor(window, seconds = ExposureTracker.REF_HOURS * 3600.0)
        assertEquals(100.0, ExposureTracker.percentOf(units), 1e-6)
    }

    @Test
    fun `silence accrues nothing`() {
        val silent = LevelWindow(sumSquares = 0.0, sampleCount = 48_000)
        assertEquals(0.0, ExposureTracker.unitsFor(silent, seconds = 3600.0))
    }

    @Test
    fun `day rollover clears carried units`() {
        assertEquals(42.0, ExposureTracker.carriedUnits(19_900, 42.0, 19_900))
        assertEquals(0.0, ExposureTracker.carriedUnits(19_900, 42.0, 19_901))
    }
}
