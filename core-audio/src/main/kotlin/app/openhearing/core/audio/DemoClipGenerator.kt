package app.openhearing.core.audio

import app.openhearing.common.SafetyConstants
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesizes the short "hear the difference" demo clip: a speech-*like* signal
 * (not speech, and never called speech in the UI) built from syllabic bursts —
 * harmonic "vowels" on a low fundamental alternating with high-frequency noise
 * "consonants". The consonant energy sits at 4–8 kHz on purpose: that is where
 * fitted profiles apply the most gain, so the A/B contrast lands where it matters.
 *
 * Synthesized rather than shipped as a recording so the repo stays asset- and
 * license-clean, and so the clip is deterministic (fixed seed) and unit-testable.
 * Same safety shape as [ToneGenerator]: peak is capped at [maxAmplitude] and the
 * clip edges carry raised-cosine ramps of at least
 * [SafetyConstants.MIN_TONE_RAMP_MS].
 */
class DemoClipGenerator(
    private val sampleRateHz: Int = ToneGenerator.DEFAULT_SAMPLE_RATE_HZ,
    private val maxAmplitude: Float = ToneGenerator.DEFAULT_MAX_AMPLITUDE,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(maxAmplitude in 0f..1f) { "maxAmplitude must be in 0..1" }
    }

    /** Generate the mono demo clip (deterministic for a given [seed]). */
    fun generate(durationMs: Long = DEFAULT_DURATION_MS, seed: Int = DEFAULT_SEED): FloatArray {
        require(durationMs > 0) { "durationMs must be positive" }
        val total = (durationMs * sampleRateHz / 1000L).toInt().coerceAtLeast(1)
        val out = FloatArray(total)
        val random = Random(seed)

        val syllableSamples = msToSamples(SYLLABLE_MS)
        val gapSamples = msToSamples(SYLLABLE_GAP_MS)
        var start = 0
        var syllable = 0
        while (start < total) {
            val end = min(start + syllableSamples, total)
            if (syllable % CONSONANT_EVERY == CONSONANT_EVERY - 1) {
                addConsonant(out, start, end, random)
            } else {
                addVowel(out, start, end, syllable)
            }
            start = end + gapSamples
            syllable++
        }

        applyEdgeRamps(out)
        normalizePeak(out)
        return out
    }

    /** Harmonic stack on a low fundamental with a formant-ish mid emphasis. */
    private fun addVowel(out: FloatArray, start: Int, end: Int, syllable: Int) {
        val length = end - start
        if (length <= 0) return
        // Small per-syllable pitch movement keeps it from sounding like a buzzer.
        val fundamental = FUNDAMENTAL_HZ * (1.0 + PITCH_STEPS[syllable % PITCH_STEPS.size])
        for (i in 0 until length) {
            val t = (start + i).toDouble() / sampleRateHz
            var sample = 0.0
            for (h in HARMONIC_WEIGHTS.indices) {
                val freq = fundamental * (h + 1)
                if (freq >= sampleRateHz / 2.0) break
                sample += HARMONIC_WEIGHTS[h] * sin(2.0 * PI * freq * t)
            }
            out[start + i] += (sample * syllableEnvelope(i, length)).toFloat()
        }
    }

    /** High-frequency noise burst: white noise high-passed by differencing. */
    private fun addConsonant(out: FloatArray, start: Int, end: Int, random: Random) {
        val length = end - start
        if (length <= 0) return
        var previous = 0.0
        for (i in 0 until length) {
            val white = random.nextDouble(-1.0, 1.0)
            // First-difference of white noise tilts energy toward high frequencies.
            val high = (white - previous) * CONSONANT_LEVEL
            previous = white
            out[start + i] += (high * syllableEnvelope(i, length)).toFloat()
        }
    }

    /** Raised-cosine on/off inside each syllable so bursts never click. */
    private fun syllableEnvelope(i: Int, length: Int): Double {
        val ramp = min(msToSamples(SYLLABLE_RAMP_MS), length / 2)
        if (ramp <= 0) return 1.0
        return when {
            i < ramp -> 0.5 * (1.0 - cos(PI * i / ramp))
            i >= length - ramp -> 0.5 * (1.0 - cos(PI * (length - 1 - i) / ramp))
            else -> 1.0
        }
    }

    /** Safety ramps over the whole clip, matching the [ToneGenerator] guarantee. */
    private fun applyEdgeRamps(out: FloatArray) {
        val ramp = min(msToSamples(SafetyConstants.MIN_TONE_RAMP_MS.toInt()), out.size / 2)
        for (i in 0 until ramp) {
            val g = 0.5 * (1.0 - cos(PI * i / ramp))
            out[i] = (out[i] * g).toFloat()
            out[out.size - 1 - i] = (out[out.size - 1 - i] * g).toFloat()
        }
    }

    private fun normalizePeak(out: FloatArray) {
        val peak = AudioMath.peak(out)
        if (peak <= 0f) return
        val scale = maxAmplitude / peak
        for (i in out.indices) out[i] = out[i] * scale
    }

    private fun msToSamples(ms: Int): Int = (ms * sampleRateHz / 1000)

    companion object {
        const val DEFAULT_DURATION_MS = 5_000L
        const val DEFAULT_SEED = 20_260_709

        private const val FUNDAMENTAL_HZ = 150.0
        private const val SYLLABLE_MS = 280
        private const val SYLLABLE_GAP_MS = 60
        private const val SYLLABLE_RAMP_MS = 30
        private const val CONSONANT_EVERY = 3
        private const val CONSONANT_LEVEL = 0.35

        // Rough single-formant emphasis around harmonics 3–5 (~450–750 Hz) with a
        // gently decaying tail — enough to read as "voice-ish", cheap to compute.
        private val HARMONIC_WEIGHTS =
            doubleArrayOf(0.6, 0.8, 1.0, 0.9, 0.7, 0.4, 0.3, 0.22, 0.16, 0.12, 0.09, 0.07)

        private val PITCH_STEPS = doubleArrayOf(0.0, 0.06, -0.04, 0.10, 0.02, -0.07)
    }
}
