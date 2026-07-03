package app.openhearing.mediaeq

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import app.openhearing.core.audio.dsp.MediaEqBand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EXPERIMENTAL: applies the user's per-ear sound profile to other apps' audio by
 * attaching a [DynamicsProcessing] effect (pre-EQ per channel + the effect's own
 * limiter) to the global output mix (audio session 0).
 *
 * Honest limitations, surfaced in the UI copy:
 * - Global-session effects are deprecated platform behavior; several OEMs ignore
 *   or reject them. Every call is wrapped and failure is reported, never thrown.
 * - The effect lives only as long as the app's process.
 * - Requires API 28+ ([DynamicsProcessing]'s introduction).
 *
 * Band gains arrive pre-capped by the pure planner (see MediaEqPlanner and
 * SafetyConstants.MEDIA_EQ_MAX_BAND_GAIN_DB).
 */
@Singleton
class MediaEqController
@Inject
constructor() {
    private var effect: DynamicsProcessing? = null

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** True while the effect object exists (created and not yet released). */
    val isActive: Boolean
        get() = effect != null

    /** Attaches (or re-attaches) the effect with [bands]. Returns false on any failure. */
    fun apply(bands: List<MediaEqBand>): Boolean {
        if (!isSupported || bands.isEmpty()) return false
        release()
        return runCatching { effect = buildEffect(bands) }.isSuccess
    }

    fun release() {
        runCatching { effect?.setEnabled(false) }
        runCatching { effect?.release() }
        effect = null
    }

    private fun buildEffect(bands: List<MediaEqBand>): DynamicsProcessing {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val config =
            DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNEL_COUNT,
                true,
                bands.size,
                false,
                0,
                false,
                0,
                true,
            ).setPreferredFrameDuration(FRAME_DURATION_MS).build()

        val dp = DynamicsProcessing(0, GLOBAL_OUTPUT_MIX_SESSION, config)
        bands.forEachIndexed { index, band ->
            dp.setPreEqBandByChannelIndex(
                LEFT_CHANNEL,
                index,
                DynamicsProcessing.EqBand(true, band.cutoffHz.toFloat(), band.leftGainDb.toFloat()),
            )
            dp.setPreEqBandByChannelIndex(
                RIGHT_CHANNEL,
                index,
                DynamicsProcessing.EqBand(true, band.cutoffHz.toFloat(), band.rightGainDb.toFloat()),
            )
        }
        // The effect's limiter is the only downstream protection on this path —
        // always on, so boosted media can't clip harshly.
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                true,
                LIMITER_LINK_GROUP,
                LIMITER_ATTACK_MS,
                LIMITER_RELEASE_MS,
                LIMITER_RATIO,
                LIMITER_THRESHOLD_DB,
                LIMITER_POST_GAIN_DB,
            ),
        )
        dp.setEnabled(true)
        return dp
    }

    private companion object {
        const val GLOBAL_OUTPUT_MIX_SESSION = 0
        const val CHANNEL_COUNT = 2
        const val LEFT_CHANNEL = 0
        const val RIGHT_CHANNEL = 1
        const val FRAME_DURATION_MS = 10.0f
        const val LIMITER_LINK_GROUP = 0
        const val LIMITER_ATTACK_MS = 1.0f
        const val LIMITER_RELEASE_MS = 60.0f
        const val LIMITER_RATIO = 10.0f
        const val LIMITER_THRESHOLD_DB = -2.0f
        const val LIMITER_POST_GAIN_DB = 0.0f
    }
}
