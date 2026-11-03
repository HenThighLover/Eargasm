package app.openhearing.core.audio.dsp

import app.openhearing.core.audio.AudioProcessor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) 48 kHz stereo -> 16 kHz mono tap.
 * Real-time safe: zero allocations and no locks on the audio thread.
 */
class AsrAudioTap(capacitySeconds: Int = 5) {
    private val capacity = SAMPLE_RATE_16K * capacitySeconds
    private val ring = FloatArray(capacity)
    private val writeIdx = AtomicInteger(0)
    private val readIdx = AtomicInteger(0)

    private var decimateSum = 0f
    private var decimateCount = 0

    @Volatile
    var enabled: Boolean = false

    fun pushInterleaved48kStereo(buffer: FloatArray) {
        if (!enabled) return
        var w = writeIdx.get()
        val r = readIdx.get()

        var i = 0
        while (i + 1 < buffer.size) {
            val l = buffer[i]
            val right = buffer[i + 1]
            // If one ear is hard-muted (e.g. R->L or L->R mode), don't halve the amplitude
            val mono = if (l == 0f) right else if (right == 0f) l else (l + right) * 0.5f

            decimateSum += mono
            if (++decimateCount == DECIMATE_RATIO) {
                val sample16k = decimateSum / DECIMATE_RATIO
                decimateSum = 0f
                decimateCount = 0

                val nextW = (w + 1) % capacity
                if (nextW != r) {
                    ring[w] = sample16k
                    w = nextW
                }
            }
            i += 2
        }
        writeIdx.lazySet(w)
    }

    fun drain16kMono(dest: FloatArray): Int {
        var r = readIdx.get()
        val w = writeIdx.get()
        var count = 0
        while (r != w && count < dest.size) {
            dest[count++] = ring[r]
            r = (r + 1) % capacity
        }
        readIdx.lazySet(r)
        return count
    }

    fun clear() {
        readIdx.set(writeIdx.get())
        decimateSum = 0f
        decimateCount = 0
    }

    companion object {
        const val SAMPLE_RATE_16K = 16_000
        private const val DECIMATE_RATIO = 3 // 48,000 Hz / 16,000 Hz = 3
    }
}

/** Decorator that taps the processed audio stream for offline ASR without blocking DSP. */
class TappedAudioProcessor(
    private val delegate: AudioProcessor,
    private val tap: AsrAudioTap,
) : AudioProcessor {
    override fun process(buffer: FloatArray) {
        delegate.process(buffer)
        tap.pushInterleaved48kStereo(buffer)
    }
}