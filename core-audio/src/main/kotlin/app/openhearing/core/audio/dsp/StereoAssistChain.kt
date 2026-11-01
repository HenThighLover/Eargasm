package app.openhearing.core.audio.dsp

import app.openhearing.core.audio.AudioProcessor

enum class ChannelRoutingMode {
    NORMAL,        // Left -> Left, Right -> Right
    RIGHT_TO_LEFT, // Right audio played in Left ear
    LEFT_TO_RIGHT, // Left audio played in Right ear
    SWAP           // Left <-> Right swapped
}

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

    @Volatile
    var routingMode: ChannelRoutingMode = ChannelRoutingMode.NORMAL

    private val leftScratch = FloatArray(framesPerBlock)
    private val rightScratch = FloatArray(framesPerBlock)

    override fun process(buffer: FloatArray) {
        val totalFrames = buffer.size / CHANNELS
        val mode = routingMode
        var frame = 0
        while (frame < totalFrames) {
            val chunk = minOf(leftScratch.size, totalFrames - frame)
            val base = frame * CHANNELS
            
            // De-interleave with input channel routing applied BEFORE per-ear DSP curves
            for (i in 0 until chunk) {
                val inLeft = buffer[base + CHANNELS * i]
                val inRight = buffer[base + CHANNELS * i + 1]
                when (mode) {
                    ChannelRoutingMode.NORMAL -> {
                        leftScratch[i] = inLeft
                        rightScratch[i] = inRight
                    }
                    ChannelRoutingMode.RIGHT_TO_LEFT -> {
                        leftScratch[i] = inRight
                        rightScratch[i] = inRight
                    }
                    ChannelRoutingMode.LEFT_TO_RIGHT -> {
                        leftScratch[i] = inLeft
                        rightScratch[i] = inLeft
                    }
                    ChannelRoutingMode.SWAP -> {
                        leftScratch[i] = inRight
                        rightScratch[i] = inLeft
                    }
                }
            }
            
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