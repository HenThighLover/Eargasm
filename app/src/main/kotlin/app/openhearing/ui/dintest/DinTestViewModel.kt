package app.openhearing.ui.dintest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhearing.audiogram.DigitsInNoiseScreening
import app.openhearing.audiogram.DinConfig
import app.openhearing.audiogram.DinResult
import app.openhearing.audiogram.DinStep
import app.openhearing.core.audio.TonePlayer
import app.openhearing.core.audio.dsp.SpeechNoiseMixer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

enum class DinPhase { NOT_STARTED, IN_PROGRESS, DONE }

/** Result bands as behavior-in-this-check descriptions — never scores/severity. */
enum class DinBand { STRONG, MID, WEAKER }

data class DinUiState(
    val phase: DinPhase = DinPhase.NOT_STARTED,
    val tripletNumber: Int = 1,
    val totalTriplets: Int = DinConfig().totalTriplets,
    val entered: List<Int> = emptyList(),
    val isPlaying: Boolean = false,
    val srtSnrDb: Int = 0,
    val band: DinBand = DinBand.MID,
    val pinnedAtEdge: Boolean = false,
)

/**
 * Drives the listening-in-noise check: spoken-digit triplets mixed into
 * masking noise at an adaptive SNR (engine in `:core-audiogram`, mixing in
 * `:core-audio`). Playback goes through [TonePlayer], so the output limiter
 * and instant mute apply exactly as in the pure-tone check.
 */
@HiltViewModel
class DinTestViewModel
@Inject
constructor(private val corpus: DigitCorpus, private val tonePlayer: TonePlayer) :
    ViewModel() {
    private val _uiState = MutableStateFlow(DinUiState())
    val uiState: StateFlow<DinUiState> = _uiState.asStateFlow()

    private var screening: DigitsInNoiseScreening? = null
    private var playJob: Job? = null
    private val noiseOffsetRandom = Random.Default

    fun start() {
        if (!corpus.isAvailable()) return
        screening = DigitsInNoiseScreening()
        _uiState.value = DinUiState(phase = DinPhase.IN_PROGRESS)
        playCurrentTriplet()
    }

    fun tapDigit(digit: Int) {
        _uiState.update {
            if (it.entered.size >= DigitsInNoiseScreening.TRIPLET_SIZE) it else it.copy(entered = it.entered + digit)
        }
    }

    fun backspace() {
        _uiState.update { it.copy(entered = it.entered.dropLast(1)) }
    }

    fun submit() {
        val s = screening ?: return
        val answered = _uiState.value.entered
        if (answered.size != DigitsInNoiseScreening.TRIPLET_SIZE) return
        when (val step = s.submit(answered)) {
            is DinStep.Present -> {
                _uiState.update {
                    it.copy(tripletNumber = it.tripletNumber + 1, entered = emptyList())
                }
                playCurrentTriplet()
            }
            is DinStep.Done -> finish(step.result)
        }
    }

    /** Instant mute — the always-available safety control. */
    fun mute() {
        playJob?.cancel()
        tonePlayer.stop()
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun finish(result: DinResult) {
        mute()
        _uiState.update {
            it.copy(
                phase = DinPhase.DONE,
                srtSnrDb = result.srtSnrDb.toInt(),
                band = bandFor(result.srtSnrDb),
                pinnedAtEdge = result.pinnedAtEdge,
            )
        }
    }

    private fun playCurrentTriplet() {
        val s = screening ?: return
        playJob?.cancel()
        tonePlayer.stop()
        playJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isPlaying = true) }
                val speech = concatDigits(s.currentTriplet())
                val noise = corpus.noise()
                val presentation =
                    SpeechNoiseMixer.mix(
                        speech = speech,
                        noise = noise,
                        noiseOffset = noiseOffsetRandom.nextInt(noise.size),
                        targetSnrDb = s.currentSnrDb(),
                        sampleRateHz = SAMPLE_RATE_HZ,
                    )
                runCatching { tonePlayer.play(presentation) }
                _uiState.update { it.copy(isPlaying = false) }
            }
    }

    private fun concatDigits(triplet: List<Int>): FloatArray {
        val gap = FloatArray(GAP_MS * SAMPLE_RATE_HZ / 1000)
        val parts = triplet.map { corpus.digit(it) }
        val total = parts.sumOf { it.size } + gap.size * (parts.size - 1)
        val out = FloatArray(total)
        var pos = 0
        parts.forEachIndexed { index, part ->
            part.copyInto(out, pos)
            pos += part.size
            if (index < parts.lastIndex) pos += gap.size
        }
        return out
    }

    private fun bandFor(srtSnrDb: Double): DinBand = when {
        srtSnrDb <= STRONG_MAX_SNR_DB -> DinBand.STRONG
        srtSnrDb <= MID_MAX_SNR_DB -> DinBand.MID
        else -> DinBand.WEAKER
    }

    override fun onCleared() {
        tonePlayer.release()
    }

    private companion object {
        // Corpus is authored at 48 kHz (docs/DIN.md); TonePlayer default matches.
        const val SAMPLE_RATE_HZ = 48_000
        const val GAP_MS = 250

        // Band edges for the non-diagnostic result copy. Policy values for an
        // unvalidated self-recorded corpus, deliberately coarse.
        const val STRONG_MAX_SNR_DB = -8.0
        const val MID_MAX_SNR_DB = -4.0
    }
}
