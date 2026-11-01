package app.openhearing.assist

import android.content.Context
import android.media.AudioManager
import app.openhearing.audiogram.FittingStrategy
import app.openhearing.audiogram.GainCurve
import app.openhearing.audiogram.GainPoint
import app.openhearing.common.Ear
import app.openhearing.common.Hertz
import app.openhearing.core.audio.InputTuning
import app.openhearing.core.audio.dsp.AssistPreset
import app.openhearing.core.audio.dsp.ChannelRoutingMode
import app.openhearing.core.audio.dsp.withPreset
import app.openhearing.data.HearingProfile
import app.openhearing.data.ProfileRepository
import app.openhearing.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the active profile into an [AssistConfig] on the shared [AssistController].
 * Used by the assist screen, the remote-mic screen, and the quick-settings tile,
 * so session setup stays identical no matter where a session is started from.
 *
 * Fits each ear independently (per-ear stereo); if only one ear could be fitted,
 * its curve is used for both sides. The environment preset gently reshapes the
 * fitted curves (and may add a low-cut) — see [AssistPreset].
 *
 * [AssistMode.REMOTE_MIC] differs deliberately: no presets, raw mic tuning,
 * larger blocks, feedback guard off (headphones are mandatory there), and it
 * works without a profile via a flat 0 dB curve — remote listening is useful to
 * everyone, and the master gain cap plus the limiter still bound the output.
 */
@Singleton
class AssistSessionFactory
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val controller: AssistController,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val fittingStrategy: FittingStrategy,
) {
    /** Prepare the controller config from the active profile. Returns true if ready. */
    suspend fun prepare(mode: AssistMode = AssistMode.HEARING_ASSIST): Boolean = when (mode) {
        AssistMode.HEARING_ASSIST -> prepareAssist()
        AssistMode.REMOTE_MIC -> prepareRemoteMic()
    }

    private suspend fun prepareAssist(): Boolean {
        val profile = profileRepository.observeActiveProfile().first() ?: return false
        val (left, right) = earCurves(profile) ?: return false
        val ceiling = settingsRepository.observeComfortCeiling().first()
        val preset = AssistPreset.fromName(settingsRepository.observeAssistPreset().first())
        val routingName = settingsRepository.observeChannelRouting().first()
        val routing = runCatching { ChannelRoutingMode.valueOf(routingName) }
            .getOrDefault(ChannelRoutingMode.NORMAL)
        controller.configure(
            AssistConfig(
                leftGainCurve = left.withPreset(preset),
                rightGainCurve = right.withPreset(preset),
                masterGainDb = profile.masterGainCapDb,
                ceilingLinear = ceiling,
                highPassHz = preset.highPassHz,
                routingMode = routing,
                                noiseSuppression = true,
            ),
        )
        return true
    }

    private suspend fun prepareRemoteMic(): Boolean {
        val profile = profileRepository.observeActiveProfile().first()
        val curves = profile?.let { earCurves(it) }
        val left = curves?.first ?: flatCurve()
        val right = curves?.second ?: flatCurve()
        val ceiling = settingsRepository.observeComfortCeiling().first()
        val routingName = settingsRepository.observeChannelRouting().first()
        val routing = runCatching { ChannelRoutingMode.valueOf(routingName) }
            .getOrDefault(ChannelRoutingMode.NORMAL)
        controller.configure(
            AssistConfig(
                leftGainCurve = left,
                rightGainCurve = right,
                masterGainDb = profile?.masterGainCapDb ?: DEFAULT_REMOTE_GAIN_DB,
                ceilingLinear = ceiling,
                framesPerBlock = AssistConfig.REMOTE_FRAMES_PER_BLOCK,
                mode = AssistMode.REMOTE_MIC,
                inputTuning = rawInputTuning(),
                routingMode = routing,
            ),
        )
        return true
    }

    /** Per-ear curves (left, right) for the active profile, or null if none fit. */
    suspend fun activeEarCurves(): Pair<GainCurve, GainCurve>? {
        val profile = profileRepository.observeActiveProfile().first() ?: return null
        return earCurves(profile)
    }

    private fun earCurves(profile: HearingProfile): Pair<GainCurve, GainCurve>? {
        val rightFit = earCurve(profile, Ear.RIGHT)
        val leftFit = earCurve(profile, Ear.LEFT)
        val right = rightFit ?: leftFit ?: return null
        val left = leftFit ?: rightFit ?: return null
        return left to right
    }

    private fun earCurve(profile: HearingProfile, ear: Ear): GainCurve? =
        runCatching { fittingStrategy.fit(profile.audiogram, ear) }.getOrNull()

    // UNPROCESSED silently degrades to DEFAULT on devices that don't support it,
    // so only use it where the platform explicitly says yes.
    private fun rawInputTuning(): InputTuning {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supported =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (supported) InputTuning.RAW_UNPROCESSED else InputTuning.RAW_VOICE_RECOGNITION
    }

    private fun flatCurve(): GainCurve = GainCurve(FLAT_CURVE_HZ.map { GainPoint(Hertz(it), 0.0) })

    private companion object {
        val FLAT_CURVE_HZ = listOf(250.0, 1_000.0, 8_000.0)

        // Master gain when no profile exists; the slider adjusts it live and the
        // chain clamps to SafetyConstants.MAX_MASTER_GAIN_CAP_DB as always.
        const val DEFAULT_REMOTE_GAIN_DB = 6.0
    }
}