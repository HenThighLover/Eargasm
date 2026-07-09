package app.openhearing.core.audio.dsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SpeechNoiseMixerTest {
    private val sampleRate = 48_000
    private val speech =
        FloatArray(sampleRate) { (0.4 * sin(2.0 * PI * 800.0 * it / sampleRate)).toFloat() }
    private val noise = Random(3).let { r -> FloatArray(sampleRate) { r.nextDouble(-0.3, 0.3).toFloat() } }

    private fun rms(b: FloatArray): Double {
        var s = 0.0
        for (v in b) s += v.toDouble() * v
        return sqrt(s / b.size)
    }

    @Test
    fun `scales hit the requested SNR exactly`() {
        for (snr in listOf(-15.0, -6.0, 0.0, 8.0)) {
            val (speechScale, noiseScale) =
                SpeechNoiseMixer.scales(rms(speech), rms(noise), snr, noiseRmsLinear = 0.056)
            val achieved =
                20.0 * log10((speechScale * rms(speech)) / (noiseScale * rms(noise)))
            assertEquals(snr, achieved, 0.01, "achieved SNR should match request")
        }
    }

    @Test
    fun `noise anchor level stays constant regardless of SNR (DIN convention)`() {
        // Measure the noise-only lead-in region (skipping the edge ramp).
        val leadSamples = sampleRate / 2
        val levels =
            listOf(-15.0, -5.0, 5.0).map { snr ->
                val mix =
                    SpeechNoiseMixer.mix(
                        speech = speech,
                        noise = noise,
                        noiseOffset = 0,
                        targetSnrDb = snr,
                        sampleRateHz = sampleRate,
                    )
                val leadIn = mix.copyOfRange(leadSamples / 2, leadSamples)
                20.0 * log10(rms(leadIn))
            }
        val spread = levels.max() - levels.min()
        assertTrue(spread < 0.1, "noise anchor varied with SNR: $levels (spread $spread dB)")
    }

    @Test
    fun `output never exceeds the ceiling`() {
        val mix =
            SpeechNoiseMixer.mix(
                speech = speech,
                noise = noise,
                noiseOffset = 0,
                targetSnrDb = 16.0,
                sampleRateHz = sampleRate,
                ceiling = 0.5f,
            )
        assertTrue(mix.all { abs(it) <= 0.5f + 1e-6f })
    }

    @Test
    fun `edges are ramped`() {
        val mix =
            SpeechNoiseMixer.mix(
                speech = speech,
                noise = noise,
                noiseOffset = 0,
                targetSnrDb = 0.0,
                sampleRateHz = sampleRate,
            )
        assertEquals(0f, mix.first(), 1e-4f)
        assertEquals(0f, mix.last(), 1e-4f)
    }

    @Test
    fun `speech sits after the noise lead-in`() {
        val mix =
            SpeechNoiseMixer.mix(
                speech = speech,
                noise = noise,
                noiseOffset = 0,
                targetSnrDb = 0.0,
                sampleRateHz = sampleRate,
                leadInMs = 500,
            )
        assertEquals(speech.size + sampleRate, mix.size) // 2 × 500 ms lead
    }
}
