package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve
import app.openhearing.common.SafetyConstants
import app.openhearing.core.audio.AudioProcessor
import kotlin.math.pow

/**
 * The full real-time hearing-assist signal chain (mono, v1):
 *
 * ```
 * input ─► EQ (audiogram gain) ─► WDRC ─► feedback guard ─► master gain ─► LIMITER ─► output
 * ```
 *
 * The [LookaheadLimiter] is always the final stage, so nothing leaves above the
 * ceiling regardless of the audiogram, the master gain, or a feedback event. The
 * master gain is hard-capped by [SafetyConstants]. Pure (no Android, no
 * allocation in [process]) so the whole chain is unit-testable.
 */
class HearingAssistChain(
    gainCurve: GainCurve,
    sampleRateHz: Int,
    masterGainDb: Double,
    ceilingLinear: Float = DEFAULT_CEILING_LINEAR,
    /** Optional low-cut (e.g. wind/rumble for the outdoors preset); null = off. */
    highPassHz: Double? = null,
    /**
     * The feedback guard's tonality detector false-triggers on sustained musical
     * notes, so remote-mic mode (phone far from the ears — acoustic feedback is
     * physically impossible, and headphones are mandatory there) disables it.
     * On-body assist MUST keep it enabled. The limiter is unaffected either way.
     */
    feedbackGuardEnabled: Boolean = true,
) : AudioProcessor {
    private val highPass: Biquad? =
        highPassHz?.let { Biquad.highPass(it, HIGH_PASS_Q, sampleRateHz) }
    private val eq = GainEqualizer(gainCurve, sampleRateHz)
    private val wdrc = Wdrc(sampleRateHz)
    private val guard: FeedbackGuard? =
        if (feedbackGuardEnabled) {
            FeedbackGuard(sampleRateHz, activationRms = CHAIN_GUARD_ACTIVATION_RMS)
        } else {
            null
        }
    private val limiter = LookaheadLimiter(ceilingLinear, sampleRateHz)

    // Master gain can never exceed the safety cap, and never attenuates below unity
    // here (the user's volume cap scales this separately, upstream). Volatile so the
    // UI thread can adjust it while the audio thread keeps processing; the limiter
    // downstream bounds the output regardless of when the new value lands.
    @Volatile
    private var masterGainLinear: Float = linearGain(masterGainDb)

    /** Live-adjustable master gain; clamped to [0, SafetyConstants.MAX_MASTER_GAIN_CAP_DB]. */
    fun setMasterGainDb(db: Double) {
        masterGainLinear = linearGain(db)
    }

    override fun process(buffer: FloatArray) {
        highPass?.let { hp ->
            for (i in buffer.indices) {
                buffer[i] = hp.processSample(buffer[i].toDouble()).toFloat()
            }
        }
        eq.process(buffer)
        wdrc.process(buffer)
        guard?.process(buffer)
        val gain = masterGainLinear // one volatile read per block
        for (i in buffer.indices) {
            buffer[i] = buffer[i] * gain
        }
        limiter.processInPlace(buffer)
    }

    fun reset() {
        highPass?.reset()
        eq.reset()
        wdrc.reset()
        guard?.reset()
        limiter.reset()
    }

    companion object {
        /**
         * Digital output ceiling (linear). Conservative headroom below full scale.
         * Until the path is calibrated this is a digital cap, not a true dB SPL
         * cap; calibration may lower it further. See docs/SAFETY.md / docs/CALIBRATION.md.
         */
        const val DEFAULT_CEILING_LINEAR = 0.9f

        /** Gentle slope; the low-cut is a comfort feature, not surgical filtering. */
        private const val HIGH_PASS_Q = 0.707

        /**
         * The guard sits AFTER the WDRC, whose ratio-3 compression above a
         * −35 dBFS threshold squashes even full-scale input to ≈0.05 RMS — so the
         * guard's standalone default (0.1) could never activate here. This value
         * is calibrated to post-WDRC levels: a sustained full-scale howl lands
         * ≈0.04–0.05 RMS, ordinary content well below. Tonality ≥ 0.9 remains
         * the primary gate against ducking speech/music.
         */
        private const val CHAIN_GUARD_ACTIVATION_RMS = 0.03

        private fun linearGain(db: Double): Float =
            10.0.pow(db.coerceIn(0.0, SafetyConstants.MAX_MASTER_GAIN_CAP_DB) / 20.0).toFloat()
    }
}
