package app.openhearing.core.audio

/**
 * The boundary between the real-time audio device (AAudio/Oboe on Android) and
 * the pure-Kotlin DSP core. Keeping I/O behind this interface is what lets the
 * DSP — including the safety limiter — be unit-tested with no device or emulator.
 *
 * Phase 0 ships the interface. Phase 2 implements an AAudio/Oboe-backed engine
 * and benchmarks end-to-end latency.
 */
interface AudioEngine {
    /** True while the capture -> process -> playback loop is running. */
    val isRunning: Boolean

    /** Start the low-latency loop, routing each captured block through [processor]. */
    fun start(format: AudioFormat, processor: AudioProcessor)

    /** Stop the loop and release the audio device. Safe to call when not running. */
    fun stop()
}

/**
 * How the platform should pre-process the microphone signal before it reaches
 * the chain. On-body assist wants the platform AEC/NS ([COMMUNICATION]) as extra
 * feedback protection; remote-mic wants the signal untouched — far-field TV or
 * music reads as "noise" to a communication-tuned suppressor and gets mangled.
 */
enum class InputTuning {
    /** VOICE_COMMUNICATION: platform AEC/NS enabled where available. */
    COMMUNICATION,

    /** UNPROCESSED: no platform effects (only where the device reports support). */
    RAW_UNPROCESSED,

    /** VOICE_RECOGNITION: AGC/AEC/NS disabled — the universal raw fallback. */
    RAW_VOICE_RECOGNITION,
}

/** Audio stream parameters negotiated with the device. */
data class AudioFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val framesPerBlock: Int,
    val inputTuning: InputTuning = InputTuning.COMMUNICATION,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channelCount in 1..2) { "channelCount must be 1 (mono) or 2 (stereo)" }
        require(framesPerBlock > 0) { "framesPerBlock must be positive" }
    }
}

/**
 * Processes one block of interleaved float samples in place. Implementations must
 * be real-time safe: no allocation, locking, or blocking on the audio thread.
 *
 * The final stage of any processing chain MUST be an [OutputLimiter] so nothing
 * can exceed the safety ceiling on its way to the device.
 */
fun interface AudioProcessor {
    fun process(buffer: FloatArray)
}
