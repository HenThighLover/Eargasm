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
    private val lock = Any() // uncontended in practice; only guards release vs. process
    private var handle: Long = nativeCreate()
    private val inBuf = FloatArray(FRAME)
    private val outBuf = FloatArray(FRAME) // starts silent = the 10 ms of latency
    private var pos = 0

    override fun process(buffer: FloatArray) {
        synchronized(lock) {
            val h = handle
            if (h == 0L) {
                // Released/stopped: output strict silence, NEVER pass raw unsuppressed audio!
                buffer.fill(0f)
                return
            }
            for (i in buffer.indices) {
                inBuf[pos] = buffer[i] * SCALE
                buffer[i] = outBuf[pos] * INV_SCALE
                if (++pos == FRAME) {
                    nativeProcess(h, inBuf, outBuf)
                    pos = 0
                }
            }
        }
    }

    override fun reset() {
        synchronized(lock) {
            inBuf.fill(0f)
            outBuf.fill(0f)
            pos = 0
            val old = handle
            if (old != 0L) {
                handle = nativeCreate() // fresh RNN state
                nativeDestroy(old)
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            inBuf.fill(0f)
            outBuf.fill(0f)
            pos = 0
            val h = handle
            handle = 0L
            if (h != 0L) nativeDestroy(h)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray)

    companion object {
        const val SAMPLE_RATE_HZ = 48_000 // RNNoise is trained for 48 kHz only
        private const val FRAME = 480
        private const val SCALE = 32768f
        private const val INV_SCALE = 1f / 32768f

        @Volatile
        private var libraryLoaded = false

        /** Null if the native library isn't available, so assist still works without it. */
        fun createOrNull(): RnNoiseDenoiser? = try {
            if (!libraryLoaded) {
                System.loadLibrary("rnnoise_jni")
                libraryLoaded = true
            }
            RnNoiseDenoiser()
        } catch (_: Throwable) {
            null
        }
    }
}