package app.openhearing.core.audio.dsp

/** Speech-preserving noise suppressor stage. Real-time safe: no allocation in [process]. */
interface SpeechDenoiser {
    fun process(buffer: FloatArray)
    fun reset()
    fun release()
}

/**
 * RNNoise-backed denoiser (wind, traffic, horns, steady hum). RNNoise works on
 * fixed 480-sample frames (10 ms @ 48 kHz), so this adds exactly 10 ms of latency
 * regardless of the engine's block size. Mono: use one instance per channel.
 */
class RnNoiseDenoiser private constructor() : SpeechDenoiser {
    private var handle: Long = nativeCreate()
    private val inBuf = FloatArray(FRAME)
    private val outBuf = FloatArray(FRAME) // starts silent = the 10 ms of latency
    private var pos = 0

    override fun process(buffer: FloatArray) {
        val h = handle
        if (h == 0L) return
        for (i in buffer.indices) {
            inBuf[pos] = buffer[i] * SCALE
            buffer[i] = outBuf[pos] * INV_SCALE
            if (++pos == FRAME) {
                nativeProcess(h, inBuf, outBuf)
                pos = 0
            }
        }
    }

    override fun reset() {
        inBuf.fill(0f)
        outBuf.fill(0f)
        pos = 0
        val old = handle
        handle = nativeCreate() // fresh RNN state
        if (old != 0L) nativeDestroy(old)
    }

    override fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray)

    companion object {
        const val SAMPLE_RATE_HZ = 48_000 // RNNoise is trained for 48 kHz only
        private const val FRAME = 480
        private const val SCALE = 32768f
        private const val INV_SCALE = 1f / 32768f

        /** Null if the native library isn't available, so assist still works without it. */
        fun createOrNull(): RnNoiseDenoiser? = try {
            System.loadLibrary("rnnoise_jni")
            RnNoiseDenoiser()
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }
}