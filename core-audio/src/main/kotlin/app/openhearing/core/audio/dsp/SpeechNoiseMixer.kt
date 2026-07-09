package app.openhearing.core.audio.dsp

import app.openhearing.common.SafetyConstants
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mixes a speech buffer into masking noise at an exact SNR for the
 * digits-in-noise screening. Two properties carry the science:
 *
 * 1. **Exact SNR**: speech is scaled relative to the noise so the RMS ratio
 *    hits the requested SNR precisely (the adaptive variable).
 * 2. **Constant noise anchor**: the noise is always presented at the same RMS
 *    (the DIN convention), so the perceived level barely changes as the SNR
 *    adapts — and because only the *ratio* matters, the result is
 *    volume-setting-independent on uncalibrated hardware.
 *
 * Safety shape as everywhere: raised-cosine lead-in/out on the noise (at least
 * [SafetyConstants.MIN_TONE_RAMP_MS]) and a hard peak clamp to [ceiling]
 * (belt) even though the playback path adds its own limiter (braces).
 */
object SpeechNoiseMixer {
    /** Linear gains: noise anchored at [noiseRmsLinear], speech [targetSnrDb] above/below it. */
    internal fun scales(
        speechRms: Double,
        noiseRms: Double,
        targetSnrDb: Double,
        noiseRmsLinear: Double,
    ): Pair<Double, Double> {
        require(speechRms > 0 && noiseRms > 0) { "speech and noise must not be silent" }
        val noiseScale = noiseRmsLinear / noiseRms
        val speechScale = noiseScale * noiseRms / speechRms * 10.0.pow(targetSnrDb / 20.0)
        return speechScale to noiseScale
    }

    /**
     * Build the presentation: noise from [noise] (looped, starting at
     * [noiseOffset]) running [leadInMs] before and after the speech, with
     * [speech] mixed in at [targetSnrDb]. Returns a new mono buffer.
     */
    @Suppress("LongParameterList")
    fun mix(
        speech: FloatArray,
        noise: FloatArray,
        noiseOffset: Int,
        targetSnrDb: Double,
        sampleRateHz: Int,
        presentationRmsDbfs: Double = DEFAULT_PRESENTATION_RMS_DBFS,
        ceiling: Float = DEFAULT_CEILING,
        leadInMs: Int = DEFAULT_LEAD_IN_MS,
    ): FloatArray {
        require(speech.isNotEmpty() && noise.isNotEmpty()) { "buffers must not be empty" }
        val lead = leadInMs * sampleRateHz / 1000
        val total = speech.size + 2 * lead
        val (speechScale, noiseScale) =
            scales(
                speechRms = rms(speech),
                noiseRms = rms(noise),
                targetSnrDb = targetSnrDb,
                noiseRmsLinear = 10.0.pow(presentationRmsDbfs / 20.0),
            )

        val out = FloatArray(total)
        for (i in 0 until total) {
            out[i] = (noise[(noiseOffset + i) % noise.size] * noiseScale).toFloat()
        }
        for (i in speech.indices) {
            out[lead + i] = out[lead + i] + (speech[i] * speechScale).toFloat()
        }
        applyEdgeRamps(out, sampleRateHz)
        clamp(out, ceiling)
        return out
    }

    private fun rms(buffer: FloatArray): Double {
        var s = 0.0
        for (v in buffer) s += v.toDouble() * v
        return sqrt(s / buffer.size)
    }

    private fun applyEdgeRamps(out: FloatArray, sampleRateHz: Int) {
        val ramp = min((SafetyConstants.MIN_TONE_RAMP_MS * sampleRateHz / 1000L).toInt(), out.size / 2)
        for (i in 0 until ramp) {
            val g = 0.5 * (1.0 - cos(PI * i / ramp))
            out[i] = (out[i] * g).toFloat()
            out[out.size - 1 - i] = (out[out.size - 1 - i] * g).toFloat()
        }
    }

    private fun clamp(out: FloatArray, ceiling: Float) {
        for (i in out.indices) {
            val v = out[i]
            if (abs(v) > ceiling) out[i] = if (v > 0) ceiling else -ceiling
        }
    }

    // Comfortable noise anchor level with ample headroom above it (speech can
    // sit up to maxSnrDb above the anchor and still clear the ceiling).
    private const val DEFAULT_PRESENTATION_RMS_DBFS = -25.0
    private const val DEFAULT_CEILING = 0.9f
    private const val DEFAULT_LEAD_IN_MS = 500
}
