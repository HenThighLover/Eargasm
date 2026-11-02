package app.openhearing.assist

import app.openhearing.audiogram.GainCurve
import app.openhearing.core.audio.AndroidAudioEngine
import app.openhearing.core.audio.AudioFormat
import app.openhearing.core.audio.InputTuning
import app.openhearing.core.audio.dsp.ChannelRoutingMode
import app.openhearing.core.audio.dsp.HearingAssistChain
import app.openhearing.core.audio.dsp.LevelWindow
import app.openhearing.core.audio.dsp.MeteredAudioProcessor
import app.openhearing.core.audio.dsp.OutputLevelMeter
import app.openhearing.core.audio.dsp.RnNoiseDenoiser
import app.openhearing.core.audio.dsp.StereoAssistChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AssistMode { HEARING_ASSIST, REMOTE_MIC }

data class ExposureSnapshot(
    val sessionSeconds: Long = 0,
    val sessionUnits: Double = 0.0,
    val unflushedUnits: Double = 0.0,
    val lastRmsDbfs: Double = Double.NEGATIVE_INFINITY,
)

data class AssistConfig(
    val leftGainCurve: GainCurve,
    val rightGainCurve: GainCurve,
    val masterGainDb: Double,
    val ceilingLinear: Float = HearingAssistChain.DEFAULT_CEILING_LINEAR,
    val highPassHz: Double? = null,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val framesPerBlock: Int = DEFAULT_FRAMES_PER_BLOCK,
    val mode: AssistMode = AssistMode.HEARING_ASSIST,
    val inputTuning: InputTuning = InputTuning.COMMUNICATION,
    val routingMode: ChannelRoutingMode = ChannelRoutingMode.NORMAL,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_FRAMES_PER_BLOCK = 192
        const val REMOTE_FRAMES_PER_BLOCK = 960
    }
}

@Singleton
class AssistController
@Inject
constructor() {
    private val engine = AndroidAudioEngine()

    @Volatile
    private var config: AssistConfig? = null

    @Volatile
    private var chain: StereoAssistChain? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _activeMode = MutableStateFlow<AssistMode?>(null)
    val activeMode: StateFlow<AssistMode?> = _activeMode.asStateFlow()

    private val outputMeter = OutputLevelMeter()

    private val _exposure = MutableStateFlow(ExposureSnapshot())
    val exposure: StateFlow<ExposureSnapshot> = _exposure.asStateFlow()

    fun drainOutputLevel(): LevelWindow = outputMeter.drain()

    fun publishExposure(snapshot: ExposureSnapshot) {
        _exposure.value = snapshot
    }

    fun configure(newConfig: AssistConfig) {
        config = newConfig
        chain?.routingMode = newConfig.routingMode
    }

    fun hasConfig(): Boolean = config != null

    fun setMasterGainDb(db: Double) {
        config = config?.copy(masterGainDb = db)
        chain?.setMasterGainDb(db)
    }

    fun setChannelRouting(mode: ChannelRoutingMode) {
        config = config?.copy(routingMode = mode)
        chain?.routingMode = mode
    }

    /** Build the per-ear chains from the current config and start the real-time loop. */
    fun startEngine() {
        val c = config ?: return
        if (engine.isRunning) return
        // Clean up any leftover chain before starting a fresh session
        chain?.release()
        chain = null

        fun earChain(curve: GainCurve) = HearingAssistChain(
            gainCurve = curve,
            sampleRateHz = c.sampleRateHz,
            masterGainDb = c.masterGainDb,
            ceilingLinear = c.ceilingLinear,
            highPassHz = c.highPassHz,
            feedbackGuardEnabled = c.mode == AssistMode.HEARING_ASSIST,
            denoiser = if (c.sampleRateHz == RnNoiseDenoiser.SAMPLE_RATE_HZ) {
                RnNoiseDenoiser.createOrNull()
            } else {
                null
            },
        )
        val newChain =
            StereoAssistChain(
                left = earChain(c.leftGainCurve),
                right = earChain(c.rightGainCurve),
                framesPerBlock = c.framesPerBlock,
            ).apply {
                routingMode = c.routingMode
            }
        chain = newChain
        outputMeter.drain()
        engine.start(
            AudioFormat(
                c.sampleRateHz,
                channelCount = 2,
                framesPerBlock = c.framesPerBlock,
                inputTuning = c.inputTuning,
            ),
            MeteredAudioProcessor(newChain, outputMeter),
        )
        _running.value = engine.isRunning
        _activeMode.value = if (engine.isRunning) c.mode else null
    }

    fun stopEngine() {
        // 1. Mark chain released first so any in-flight buffer outputs 0f (strict silence)
        val oldChain = chain
        chain = null
        // 2. Stop and flush the hardware AudioRecord + AudioTrack
        engine.stop()
        // 3. Free the native RNNoise C handles safely after the audio thread halts
        oldChain?.release()
        _running.value = false
        _activeMode.value = null
        _exposure.value = ExposureSnapshot()
    }
}