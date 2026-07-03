package app.openhearing.core.audio.dsp

import app.openhearing.core.audio.AudioProcessor

/**
 * Per-ear stereo assist: one independent [HearingAssistChain] per channel, fed
 * from a mono microphone block duplicated into interleaved stereo ([L, R, L, R…]).
 * Each ear gets its own fitted gain curve, so asymmetric hearing is corrected per
 * side; each channel keeps its own limiter as the final stage.
 *
 * Real-time safe: scratch buffers are pre-allocated for [framesPerBlock]; larger
 * inputs are processed in chunks.
 */
class StereoAssistChain(
    private val left: HearingAssistChain,
    private val right: HearingAssistChain,
    framesPerBlock: Int,
) : AudioProcessor {
    private val leftScratch = FloatArray(framesPerBlock)
    private val rightScratch = FloatArray(framesPerBlock)

    override fun process(buffer: FloatArray) {
        val totalFrames = buffer.size / CHANNELS
        var frame = 0
        while (frame < totalFrames) {
            val chunk = minOf(leftScratch.size, totalFrames - frame)
            val base = frame * CHANNELS
            for (i in 0 until chunk) {
                leftScratch[i] = buffer[base + CHANNELS * i]
                rightScratch[i] = buffer[base + CHANNELS * i + 1]
            }
            // A short partial block (final read) leaves a zero tail in the
            // scratch buffers; the chains just see a moment of silence.
            for (i in chunk until leftScratch.size) {
                leftScratch[i] = 0f
                rightScratch[i] = 0f
            }
            left.process(leftScratch)
            right.process(rightScratch)
            for (i in 0 until chunk) {
                buffer[base + CHANNELS * i] = leftScratch[i]
                buffer[base + CHANNELS * i + 1] = rightScratch[i]
            }
            frame += chunk
        }
    }

    /** Live master gain for both ears; each chain clamps to the safety cap. */
    fun setMasterGainDb(db: Double) {
        left.setMasterGainDb(db)
        right.setMasterGainDb(db)
    }

    fun reset() {
        left.reset()
        right.reset()
    }

    private companion object {
        const val CHANNELS = 2
    }
}
