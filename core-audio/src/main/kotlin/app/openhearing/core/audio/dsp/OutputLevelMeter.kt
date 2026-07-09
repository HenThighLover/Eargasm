package app.openhearing.core.audio.dsp

import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder
import kotlin.math.log10
import kotlin.math.sqrt

/** One drained measurement window: energy and length, RMS derivable. */
data class LevelWindow(val sumSquares: Double, val sampleCount: Long) {
    val rmsLinear: Double
        get() = if (sampleCount == 0L) 0.0 else sqrt(sumSquares / sampleCount)

    val rmsDbfs: Double
        get() = if (rmsLinear <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rmsLinear)

    /** Mean square = linear power; the energy basis for exposure accounting. */
    val meanSquare: Double
        get() = if (sampleCount == 0L) 0.0 else sumSquares / sampleCount
}

/**
 * Real-time-safe output level accumulator: the audio thread adds
 * sum-of-squares per block ([accumulate], one multiply-add per sample, no
 * locks, no allocation), and a sampling coroutine periodically [drain]s a
 * window. Adder cells make the single-writer/single-reader handoff lock-free.
 */
class OutputLevelMeter {
    private val sumSquares = DoubleAdder()
    private val samples = LongAdder()

    /** Called on the audio thread with the post-limiter output block. */
    fun accumulate(buffer: FloatArray) {
        var s = 0.0
        for (v in buffer) s += v.toDouble() * v
        sumSquares.add(s)
        samples.add(buffer.size.toLong())
    }

    /** Called on the sampling thread; resets the accumulators. */
    fun drain(): LevelWindow = LevelWindow(sumSquares.sumThenReset(), samples.sumThenReset())
}
