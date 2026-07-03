package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve
import app.openhearing.common.Hertz
import app.openhearing.common.SafetyConstants
import kotlin.math.sqrt

/**
 * One media-EQ band: [cutoffHz] is the band's upper edge (as the platform
 * DynamicsProcessing effect expects), gains are per ear and already capped.
 */
data class MediaEqBand(val centerHz: Double, val cutoffHz: Double, val leftGainDb: Double, val rightGainDb: Double)

/**
 * Pure planning for the experimental media EQ: samples the fitted per-ear curves
 * at the standard audiometric centers and caps every boost at
 * [SafetyConstants.MEDIA_EQ_MAX_BAND_GAIN_DB] (never a cut below 0 dB — media EQ
 * only compensates, it doesn't attenuate). The platform-effect wiring lives in
 * the app layer; this math is unit-tested here.
 */
object MediaEqPlanner {
    val BAND_CENTERS_HZ = listOf(250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0)

    /** Upper cutoff of the final band; content above it keeps the last gain. */
    const val TOP_CUTOFF_HZ = 16_000.0

    fun plan(
        leftCurve: GainCurve,
        rightCurve: GainCurve,
        maxBandGainDb: Double = SafetyConstants.MEDIA_EQ_MAX_BAND_GAIN_DB,
    ): List<MediaEqBand> = BAND_CENTERS_HZ.mapIndexed { index, center ->
        val cutoff =
            if (index == BAND_CENTERS_HZ.lastIndex) {
                TOP_CUTOFF_HZ
            } else {
                sqrt(center * BAND_CENTERS_HZ[index + 1])
            }
        MediaEqBand(
            centerHz = center,
            cutoffHz = cutoff,
            leftGainDb = leftCurve.gainAt(Hertz(center)).coerceIn(0.0, maxBandGainDb),
            rightGainDb = rightCurve.gainAt(Hertz(center)).coerceIn(0.0, maxBandGainDb),
        )
    }
}
