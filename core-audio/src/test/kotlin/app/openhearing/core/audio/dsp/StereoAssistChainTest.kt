package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve
import app.openhearing.audiogram.GainPoint
import app.openhearing.common.Hertz
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Channel-routing and safety tests for the stereo wrapper. Per-ear differences
 * are asserted through the linear master-gain stage, where the expected ratio is
 * deterministic; curve application itself is covered by [HearingAssistChainTest]
 * (the WDRC and feedback guard deliberately compress curve-level differences,
 * which would make RMS-ratio assertions on curves flaky).
 */
class StereoAssistChainTest {
    private val sampleRate = 48_000
    private val framesPerBlock = 192

    private val flatCurve =
        GainCurve(listOf(GainPoint(Hertz(1000.0), 0.0), GainPoint(Hertz(4000.0), 0.0)))

    private fun chain(leftMasterDb: Double, rightMasterDb: Double, ceiling: Float = 0.9f) = StereoAssistChain(
        left = HearingAssistChain(flatCurve, sampleRate, masterGainDb = leftMasterDb, ceilingLinear = ceiling),
        right = HearingAssistChain(flatCurve, sampleRate, masterGainDb = rightMasterDb, ceilingLinear = ceiling),
        framesPerBlock = framesPerBlock,
    )

    /** Interleaved [L, R, L, R…] stereo buffer with the same mono sine on both channels. */
    private fun stereoSine(amp: Double, freq: Double, frames: Int): FloatArray {
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (amp * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
            buffer[2 * i] = sample
            buffer[2 * i + 1] = sample
        }
        return buffer
    }

    private fun channelRms(buffer: FloatArray, channel: Int, fromFrame: Int): Double {
        var sum = 0.0
        var count = 0
        var i = fromFrame
        while (2 * i + channel < buffer.size) {
            val s = buffer[2 * i + channel].toDouble()
            sum += s * s
            count++
            i++
        }
        return sqrt(sum / count)
    }

    private fun processAll(chain: StereoAssistChain, buffer: FloatArray) {
        var offset = 0
        while (offset < buffer.size) {
            val chunk = minOf(framesPerBlock * 2, buffer.size - offset)
            val block = buffer.copyOfRange(offset, offset + chunk)
            chain.process(block)
            block.copyInto(buffer, offset)
            offset += chunk
        }
    }

    @Test
    fun `each ear gets its own gain and channels are not swapped`() {
        // Right ear +12 dB (x3.98 amplitude), left unity: the right channel must
        // come out ~4x louder. A swapped or mixed interleave would break this.
        val chain = chain(leftMasterDb = 0.0, rightMasterDb = 12.0)
        val buffer = stereoSine(amp = 0.02, freq = 2000.0, frames = sampleRate)
        processAll(chain, buffer)
        val left = channelRms(buffer, channel = 0, fromFrame = sampleRate / 5)
        val right = channelRms(buffer, channel = 1, fromFrame = sampleRate / 5)
        val ratio = right / left
        assertTrue(ratio in 3.0..5.0, "expected ~4x right/left ratio, got $ratio (L=$left R=$right)")
    }

    @Test
    fun `both channels respect the ceiling under overload`() {
        val chain = chain(leftMasterDb = 40.0, rightMasterDb = 40.0, ceiling = 0.8f)
        val buffer = stereoSine(amp = 0.9, freq = 1000.0, frames = sampleRate)
        processAll(chain, buffer)
        assertTrue(buffer.all { abs(it) <= 0.8f + 1e-6f }, "stereo output exceeded ceiling")
    }

    @Test
    fun `live master gain applies to both ears`() {
        val chain = chain(leftMasterDb = 0.0, rightMasterDb = 0.0)
        val before = stereoSine(amp = 0.02, freq = 2000.0, frames = sampleRate / 10)
        processAll(chain, before)
        chain.setMasterGainDb(12.0)
        val after = stereoSine(amp = 0.02, freq = 2000.0, frames = sampleRate / 10)
        processAll(chain, after)
        assertTrue(channelRms(after, 0, 0) > channelRms(before, 0, 0) * 2.0)
        assertTrue(channelRms(after, 1, 0) > channelRms(before, 1, 0) * 2.0)
    }

    @Test
    fun `partial final block is processed without corruption`() {
        val chain = chain(leftMasterDb = 0.0, rightMasterDb = 0.0)
        // 50 frames — far less than framesPerBlock.
        val buffer = stereoSine(amp = 0.5, freq = 1000.0, frames = 50)
        chain.process(buffer)
        assertTrue(buffer.all { abs(it) <= 0.9f + 1e-6f })
    }
}
