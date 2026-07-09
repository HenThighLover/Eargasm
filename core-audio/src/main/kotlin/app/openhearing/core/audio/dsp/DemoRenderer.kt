package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve

/**
 * Offline renderer for the "hear the difference" demo: runs the demo clip
 * through the *real* per-ear assist chains ([StereoAssistChain] built exactly
 * like a live session, minus presets), so what the user hears in the demo is
 * what assist mode actually does. Because the chains end in their limiters,
 * the processed buffer can never exceed the session ceiling regardless of the
 * profile's gain. Pure Kotlin — unit-tested on the JVM.
 */
object DemoRenderer {
    /** Duplicate a mono clip into interleaved stereo ([L, R, L, R, …]). */
    fun stereoRaw(mono: FloatArray): FloatArray {
        val out = FloatArray(mono.size * 2)
        for (i in mono.indices) {
            out[2 * i] = mono[i]
            out[2 * i + 1] = mono[i]
        }
        return out
    }

    /**
     * Render the profile-processed version of [mono]: per-ear chains with the
     * given fitted curves, master gain, and comfort ceiling — limiter last, as
     * always. Returns a new interleaved stereo buffer.
     */
    fun renderProcessed(
        mono: FloatArray,
        leftCurve: GainCurve,
        rightCurve: GainCurve,
        masterGainDb: Double,
        ceilingLinear: Float,
        sampleRateHz: Int,
    ): FloatArray {
        fun earChain(curve: GainCurve) = HearingAssistChain(
            gainCurve = curve,
            sampleRateHz = sampleRateHz,
            masterGainDb = masterGainDb,
            ceilingLinear = ceilingLinear,
        )
        val chain =
            StereoAssistChain(
                left = earChain(leftCurve),
                right = earChain(rightCurve),
                framesPerBlock = FRAMES_PER_BLOCK,
            )
        val out = stereoRaw(mono)
        chain.process(out)
        return out
    }

    // Offline rendering has no latency budget; a larger block is simply fewer
    // chunk iterations inside StereoAssistChain.
    private const val FRAMES_PER_BLOCK = 960
}
