package app.openhearing.assist

import app.openhearing.audiogram.GainCurve
import app.openhearing.core.audio.AndroidAudioEngine
import app.openhearing.core.audio.AudioFormat
import app.openhearing.core.audio.InputTuning
import app.openhearing.core.audio.dsp.HearingAssistChain
import app.openhearing.core.audio.dsp.LevelWindow
import app.openhearing.core.audio.dsp.MeteredAudioProcessor
import app.openhearing.core.audio.dsp.OutputLevelMeter
import app.openhearing.core.audio.dsp.StereoAssistChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two ways the mic→earbuds pipeline is used. [HEARING_ASSIST] amplifies the
 * world around you (phone on you, guard on, low latency). [REMOTE_MIC] streams
 * sound from *near the phone* to your earbuds across the room (guard off — safe
 * only because headphones are mandatory there; raw mic tuning; latency-tolerant
 * blocks).
 */
enum class AssistMode { HEARING_ASSIST, REMOTE_MIC }

/**
 * Live relative-exposure numbers for the current session, published by
 * [AssistService]'s sampling loop. `unflushedUnits` is the portion not yet
 * persisted (the UI adds it to the stored day so the meter never jumps).
 */
data class ExposureSnapshot(
    val sessionSeconds: Long = 0,
    val sessionUnits: Double = 0.0,
    val unflushedUnits: Double = 0.0,
    val lastRmsDbfs: Double = Double.NEGATIVE_INFINITY,
)

/** Immutable configuration for an assist session, derived from the active profile. */
data class AssistConfig(
    /** Per-ear fitted curves; left ear drives the left channel, right the right. */
    val leftGainCurve: GainCurve,
    val rightGainCurve: GainCurve,
    val masterGainDb: Double,
    /** Output ceiling (linear) from comfort calibration; the limiter enforces it. */
    val ceilingLinear: Float = HearingAssistChain.DEFAULT_CEILING_LINEAR,
    /** Optional low-cut from the environment preset (e.g. outdoors wind cut). */
    val highPassHz: Double? = null,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val framesPerBlock: Int = DEFAULT_FRAMES_PER_BLOCK,
    val mode: AssistMode = AssistMode.HEARING_ASSIST,
    val inputTuning: InputTuning = InputTuning.COMMUNICATION,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000

        // ~4 ms at 48 kHz — a low-latency block size; the engine adjusts device buffers.
        const val DEFAULT_FRAMES_PER_BLOCK = 192

        // Remote-mic has no lip-to-ear latency budget (Bluetooth alone adds far
        // more); 20 ms blocks are robust against underruns with the screen off.
        const val REMOTE_FRAMES_PER_BLOCK = 960
    }
}

/**
 * Single source of truth for assist-mode state, shared between the UI and the
 * foreground [AssistService]. The UI sets [config] and starts/stops the service;
 * the service drives the engine through here. Mute is immediate.
 */
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

    // Which mode the running session is in (null when stopped), so each screen
    // can tell the user when the *other* mode owns the pipeline.
    private val _activeMode = MutableStateFlow<AssistMode?>(null)
    val activeMode: StateFlow<AssistMode?> = _activeMode.asStateFlow()

    // Post-limiter output tap; drained ~1/s by the service's sampling loop.
    private val outputMeter = OutputLevelMeter()

    private val _exposure = MutableStateFlow(ExposureSnapshot())
    val exposure: StateFlow<ExposureSnapshot> = _exposure.asStateFlow()

    /** Drain the level window accumulated since the last call (service loop). */
    fun drainOutputLevel(): LevelWindow = outputMeter.drain()

    fun publishExposure(snapshot: ExposureSnapshot) {
        _exposure.value = snapshot
    }

    fun configure(newConfig: AssistConfig) {
        config = newConfig
    }

    fun hasConfig(): Boolean = config != null

    /**
     * Adjusts master gain immediately, including while the engine is running.
     * The chain clamps to the safety cap and the limiter stays downstream.
     */
    fun setMasterGainDb(db: Double) {
        config = config?.copy(masterGainDb = db)
        chain?.setMasterGainDb(db)
    }

    /** Build the per-ear chains from the current config and start the real-time loop. */
    fun startEngine() {
        val c = config ?: return
        if (engine.isRunning) return
        fun earChain(curve: GainCurve) = HearingAssistChain(
            gainCurve = curve,
            sampleRateHz = c.sampleRateHz,
            masterGainDb = c.masterGainDb,
            ceilingLinear = c.ceilingLinear,
            highPassHz = c.highPassHz,
            // Guard off is a remote-mic-only relaxation; see AssistMode.
            feedbackGuardEnabled = c.mode == AssistMode.HEARING_ASSIST,
        )
        val newChain =
            StereoAssistChain(
                left = earChain(c.leftGainCurve),
                right = earChain(c.rightGainCurve),
                framesPerBlock = c.framesPerBlock,
            )
        chain = newChain
        outputMeter.drain() // discard anything left from a previous session
        engine.start(
            AudioFormat(
                c.sampleRateHz,
                channelCount = 2,
                framesPerBlock = c.framesPerBlock,
                inputTuning = c.inputTuning,
            ),
            // The meter taps the buffer AFTER the chain (post-limiter), so it
            // sees exactly what reaches the device; the chain stays untouched.
            MeteredAudioProcessor(newChain, outputMeter),
        )
        _running.value = engine.isRunning
        _activeMode.value = if (engine.isRunning) c.mode else null
    }

    fun stopEngine() {
        engine.stop()
        chain = null
        _running.value = false
        _activeMode.value = null
        _exposure.value = ExposureSnapshot()
    }
}
