package app.openhearing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhearing.assist.AssistSessionFactory
import app.openhearing.common.Hertz
import app.openhearing.core.audio.ToneGenerator
import app.openhearing.core.audio.TonePlayer
import app.openhearing.core.audio.dsp.MediaEqPlanner
import app.openhearing.data.ProfileRepository
import app.openhearing.data.SettingsRepository
import app.openhearing.mediaeq.MediaEqController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [consentAccepted] is null while loading, then true/false. */
data class RootUiState(
    val consentAccepted: Boolean? = null,
    val highContrast: Boolean = false,
    val comfortCeiling: Float = 0.5f,
    val hasProfile: Boolean = false,
    val mediaEqEnabled: Boolean = false,
    val mediaEqSupported: Boolean = false,
    val mediaEqFailed: Boolean = false,
)

@HiltViewModel
class RootViewModel
@Inject
constructor(
    private val settings: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val sessionFactory: AssistSessionFactory,
    private val mediaEq: MediaEqController,
    private val toneGenerator: ToneGenerator,
    private val tonePlayer: TonePlayer,
) : ViewModel() {
    private var previewJob: Job? = null
    private val mediaEqFailed = MutableStateFlow(false)

    val uiState: StateFlow<RootUiState> =
        combine(
            combine(
                settings.observeConsentAccepted(),
                settings.observeHighContrast(),
                settings.observeComfortCeiling(),
            ) { consent, highContrast, ceiling -> Triple(consent, highContrast, ceiling) },
            combine(
                settings.observeMediaEqEnabled(),
                profileRepository.observeActiveProfile(),
                mediaEqFailed,
            ) { eqEnabled, profile, failed -> Triple(eqEnabled, profile, failed) },
        ) { (consent, highContrast, ceiling), (eqEnabled, profile, failed) ->
            RootUiState(
                consentAccepted = consent,
                highContrast = highContrast,
                comfortCeiling = ceiling,
                hasProfile = profile?.audiogram?.thresholds?.isNotEmpty() == true,
                mediaEqEnabled = eqEnabled,
                mediaEqSupported = mediaEq.isSupported,
                mediaEqFailed = failed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RootUiState())

    init {
        // Media EQ follows the persisted toggle and the active profile: applied
        // (or re-applied with fresh curves) when on, released when off. On a
        // device that rejects the global effect, the toggle snaps back off and
        // the failure is surfaced.
        viewModelScope.launch {
            combine(
                settings.observeMediaEqEnabled(),
                profileRepository.observeActiveProfile(),
            ) { enabled, profile -> enabled to profile }
                .collect { (enabled, _) ->
                    if (enabled) {
                        val ok = applyMediaEq()
                        mediaEqFailed.value = !ok
                        if (!ok) settings.setMediaEqEnabled(false)
                    } else {
                        mediaEq.release()
                    }
                }
        }
    }

    fun acceptDisclaimer() {
        viewModelScope.launch { settings.setConsentAccepted(true) }
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { settings.setHighContrast(enabled) }
    }

    fun setComfortCeiling(value: Float) {
        viewModelScope.launch { settings.setComfortCeiling(value) }
    }

    fun setMediaEq(enabled: Boolean) {
        viewModelScope.launch {
            mediaEqFailed.value = false
            settings.setMediaEqEnabled(enabled)
        }
    }

    private suspend fun applyMediaEq(): Boolean {
        val curves = sessionFactory.activeEarCurves() ?: return false
        return mediaEq.apply(MediaEqPlanner.plan(curves.first, curves.second))
    }

    /**
     * Play a short 1 kHz tone at the chosen ceiling so the user can hear how
     * loud the maximum will be and pick a comfortable level. The TonePlayer's
     * own limiter still bounds the output.
     */
    fun previewComfort(ceiling: Float) {
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                val tone = toneGenerator.generate(Hertz(1000.0), durationMs = 800, amplitude = ceiling)
                runCatching { tonePlayer.play(tone) }
            }
    }

    override fun onCleared() {
        previewJob?.cancel()
        tonePlayer.release()
        // Deliberately NOT releasing the media EQ here: the whole point is that
        // it keeps shaping other apps' audio after the user leaves this app. It
        // dies with the process; the toggle re-applies it on next launch.
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
