package app.openhearing.ui.assist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhearing.assist.AssistController
import app.openhearing.assist.AssistMode
import app.openhearing.assist.AssistSessionFactory
import app.openhearing.core.audio.dsp.AssistPreset
import app.openhearing.core.audio.dsp.ChannelRoutingMode
import app.openhearing.core.audio.dsp.ExposureTracker
import app.openhearing.data.HearingProfile
import app.openhearing.data.ProfileRepository
import app.openhearing.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Relative listening-meter values for the card; see ExposureTracker. */
data class ExposureUi(
    val sessionSeconds: Long = 0,
    val todayPercent: Int = 0,
    /** Last-second output level mapped 0..1 for the live bar. */
    val levelFraction: Float = 0f,
    val showHighNote: Boolean = false,
)

data class AssistUiState(
    val hasProfile: Boolean = false,
    /** True when an on-body assist session is live (not a remote-mic one). */
    val running: Boolean = false,
    /** True when a remote-mic session owns the shared pipeline instead. */
    val remoteMicActive: Boolean = false,
    val masterGainDb: Double = DEFAULT_MASTER_GAIN_DB,
    val profiles: List<HearingProfile> = emptyList(),
    val activeProfileId: String? = null,
    val preset: AssistPreset = AssistPreset.STANDARD,
    val routingMode: ChannelRoutingMode = ChannelRoutingMode.NORMAL,
    val exposure: ExposureUi = ExposureUi(),
) {
    companion object {
        const val DEFAULT_MASTER_GAIN_DB = 12.0
    }
}

/**
 * Drives assist mode: prepares the session via [AssistSessionFactory], reflects
 * run state, and manages the saved profiles. Master gain changes apply live to a
 * running session (the chain clamps them; the limiter stays downstream). Actually
 * starting/stopping the foreground service is done by the screen (it needs a
 * Context + permissions).
 */
@HiltViewModel
class AssistViewModel
@Inject
constructor(
    private val controller: AssistController,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionFactory: AssistSessionFactory,
) : ViewModel() {
    private val masterGain = MutableStateFlow(AssistUiState.DEFAULT_MASTER_GAIN_DB)

    private val session =
        combine(controller.running, controller.activeMode) { running, mode -> running to mode }

    private val exposureUi =
        combine(controller.exposure, settingsRepository.observeExposureToday()) { snapshot, stored ->
            val carried =
                ExposureTracker.carriedUnits(stored.epochDay, stored.units, LocalDate.now().toEpochDay())
            val todayPercent = ExposureTracker.percentOf(carried + snapshot.unflushedUnits)
            ExposureUi(
                sessionSeconds = snapshot.sessionSeconds,
                todayPercent = todayPercent.toInt().coerceAtLeast(0),
                levelFraction = levelFraction(snapshot.lastRmsDbfs),
                showHighNote = todayPercent >= ExposureTracker.HIGH_PERCENT,
            )
        }

    private val runtime = combine(session, exposureUi) { s, e -> s to e }

    private val settingsFlow =
        combine(
            settingsRepository.observeAssistPreset(),
            settingsRepository.observeChannelRouting(),
        ) { presetName, routingName ->
            val preset = AssistPreset.fromName(presetName)
            val routing = runCatching { ChannelRoutingMode.valueOf(routingName) }
                .getOrDefault(ChannelRoutingMode.NORMAL)
            preset to routing
        }

    val uiState: StateFlow<AssistUiState> =
        combine(
            profileRepository.observeActiveProfile(),
            profileRepository.observeProfiles(),
            runtime,
            masterGain,
            settingsFlow,
        ) { active, profiles, (sessionState, exposure), gain, (preset, routing) ->
            val (running, mode) = sessionState
            AssistUiState(
                hasProfile = active?.audiogram?.thresholds?.isNotEmpty() == true,
                running = running && mode == AssistMode.HEARING_ASSIST,
                remoteMicActive = running && mode == AssistMode.REMOTE_MIC,
                masterGainDb = active?.masterGainCapDb ?: gain,
                profiles = profiles,
                activeProfileId = active?.id,
                preset = preset,
                routingMode = routing,
                exposure = exposure,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AssistUiState())


    /** Applies immediately (also to a running session) and persists on the active profile. */
    fun setMasterGain(db: Double) {
        masterGain.value = db
        controller.setMasterGainDb(db)
        viewModelScope.launch {
            val profile = profileRepository.observeActiveProfile().first()
            if (profile != null) {
                profileRepository.save(profile.copy(masterGainCapDb = db))
            }
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            profileRepository.setActive(profileId)
            // A running session keeps its old curve; restarting applies the new
            // profile. Stop here so the user never hears an unexpected switch.
            if (controller.running.value) return@launch
            sessionFactory.prepare()
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch { profileRepository.delete(profileId) }
    }

    /** Persisted; a running session keeps its preset until restarted (like profiles). */
    fun setPreset(preset: AssistPreset) {
        viewModelScope.launch {
            settingsRepository.setAssistPreset(preset.name)
            if (!controller.running.value) sessionFactory.prepare()
        }
    }

    /** Applies immediately (also to a running session) and persists in DataStore. */
    fun setChannelRouting(mode: ChannelRoutingMode) {
        controller.setChannelRouting(mode)
        viewModelScope.launch {
            settingsRepository.setChannelRouting(mode.name)
            if (!controller.running.value) sessionFactory.prepare()
        }
    }

    /** Prepare the controller config from the active profile. Returns true if ready. */
    suspend fun prepare(): Boolean = sessionFactory.prepare()

    /** Stops audio immediately, independent of the service's lifecycle. */
    fun stopNow() {
        viewModelScope.launch(Dispatchers.IO) {
            controller.stopEngine()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        // Map the last-second RMS (dBFS) onto a 0..1 bar; −60 dBFS ≈ silence.
        const val LEVEL_FLOOR_DBFS = -60.0

        fun levelFraction(rmsDbfs: Double): Float =
            ((rmsDbfs - LEVEL_FLOOR_DBFS) / -LEVEL_FLOOR_DBFS).coerceIn(0.0, 1.0).toFloat()
    }
}
