package app.openhearing.assist

import app.openhearing.audiogram.FittingStrategy
import app.openhearing.audiogram.GainCurve
import app.openhearing.common.Ear
import app.openhearing.core.audio.dsp.AssistPreset
import app.openhearing.core.audio.dsp.withPreset
import app.openhearing.data.HearingProfile
import app.openhearing.data.ProfileRepository
import app.openhearing.data.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the active profile into an [AssistConfig] on the shared [AssistController].
 * Used by both the assist screen and the quick-settings tile, so session setup
 * stays identical no matter where assist is started from.
 *
 * Fits each ear independently (per-ear stereo); if only one ear could be fitted,
 * its curve is used for both sides. The environment preset gently reshapes the
 * fitted curves (and may add a low-cut) — see [AssistPreset].
 */
@Singleton
class AssistSessionFactory
@Inject
constructor(
    private val controller: AssistController,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val fittingStrategy: FittingStrategy,
) {
    /** Prepare the controller config from the active profile. Returns true if ready. */
    suspend fun prepare(): Boolean {
        val profile = profileRepository.observeActiveProfile().first() ?: return false
        val (left, right) = earCurves(profile) ?: return false
        val ceiling = settingsRepository.observeComfortCeiling().first()
        val preset = AssistPreset.fromName(settingsRepository.observeAssistPreset().first())
        controller.configure(
            AssistConfig(
                leftGainCurve = left.withPreset(preset),
                rightGainCurve = right.withPreset(preset),
                masterGainDb = profile.masterGainCapDb,
                ceilingLinear = ceiling,
                highPassHz = preset.highPassHz,
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
}
