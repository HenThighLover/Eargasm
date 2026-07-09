package app.openhearing.ui.remotemic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhearing.assist.AssistController
import app.openhearing.assist.AssistMode
import app.openhearing.assist.AssistSessionFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RemoteMicUiState(
    /** True when THIS mode's session is live. */
    val running: Boolean = false,
    /** True when regular assist owns the pipeline instead. */
    val assistModeActive: Boolean = false,
    val masterGainDb: Double = 0.0,
)

/**
 * Drives remote-microphone mode: the same shared [AssistController]/service as
 * assist, prepared with [AssistMode.REMOTE_MIC]. Works with or without a
 * profile (the factory falls back to a flat curve). Gain changes apply live.
 */
@HiltViewModel
class RemoteMicViewModel
@Inject
constructor(
    private val controller: AssistController,
    private val sessionFactory: AssistSessionFactory,
) : ViewModel() {
    private val masterGain = MutableStateFlow(DEFAULT_GAIN_DB)

    val uiState: StateFlow<RemoteMicUiState> =
        combine(controller.running, controller.activeMode, masterGain) { running, mode, gain ->
            RemoteMicUiState(
                running = running && mode == AssistMode.REMOTE_MIC,
                assistModeActive = running && mode == AssistMode.HEARING_ASSIST,
                masterGainDb = gain,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RemoteMicUiState())

    /** Applies immediately, also to a running session (chain clamps, limiter last). */
    fun setMasterGain(db: Double) {
        masterGain.value = db
        controller.setMasterGainDb(db)
    }

    /** Configure the controller for a remote-mic session. Returns true if ready. */
    suspend fun prepare(): Boolean {
        val ready = sessionFactory.prepare(AssistMode.REMOTE_MIC)
        // The on-screen slider is the source of truth for remote gain.
        if (ready) controller.setMasterGainDb(masterGain.value)
        return ready
    }

    private companion object {
        const val DEFAULT_GAIN_DB = 6.0
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
