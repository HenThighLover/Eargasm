package app.openhearing.core.audio.dsp

import app.openhearing.core.audio.AudioProcessor

enum class ChannelRoutingMode {
    NORMAL,        // Left -> Left, Right -> Right
    RIGHT_TO_LEFT, // Right audio played in Left ear ONLY (Right ear silent)
    LEFT_TO_RIGHT, // Left audio played in Right ear ONLY (Left ear silent)
    SWAP           // Left <-> Right swapped
}

/**
 * Per-ear stereo assist: one independent [HearingAssistChain] per channel.
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

    @Volatile
    private var released = false

    private val leftScratch = FloatArray(framesPerBlock)
    private val rightScratch = FloatArray(framesPerBlock)

    override fun process(buffer: FloatArray) {
        if (released) {
            buffer.fill(0f)
            return
        }
        val totalFrames = buffer.size / CHANNELS
        val mode = routingMode
        var frame = 0
        while (frame < totalFrames) {
            val chunk = minOf(leftScratch.size, totalFrames - frame)
            val base = frame * CHANNELS

            // 1. Route input channels into the target ear's scratch buffer
            for (i in 0 until chunk) {
                val inLeft = buffer[base + CHANNELS * i]
                val inRight = buffer[base + CHANNELS * i + 1]
                when (mode) {
                    ChannelRoutingMode.NORMAL -> {
                        leftScratch[i] = inLeft
                        rightScratch[i] = inRight
                    }
                    ChannelRoutingMode.RIGHT_TO_LEFT -> {
                        // Take Right side sound and play it on Left side; leave Right silent
                        leftScratch[i] = inRight
                        rightScratch[i] = 0f
                    }
                    ChannelRoutingMode.LEFT_TO_RIGHT -> {
                        // Take Left side sound and play it on Right side; leave Left silent
                        leftScratch[i] = 0f
                        rightScratch[i] = inLeft
                    }
                    ChannelRoutingMode.SWAP -> {
                        // Full vice-versa swap: Right -> Left, Left -> Right
                        leftScratch[i] = inRight
                        rightScratch[i] = inLeft
                    }
                }
            }

            for (i in chunk until leftScratch.size) {
                leftScratch[i] = 0f
                rightScratch[i] = 0f
            }

            // 2. Run DSP / RNNoise only on the active channel(s)
            when (mode) {
                ChannelRoutingMode.RIGHT_TO_LEFT -> left.process(leftScratch)
                ChannelRoutingMode.LEFT_TO_RIGHT -> right.process(rightScratch)
                else -> {
                    left.process(leftScratch)
                    right.process(rightScratch)
                }
            }

            // 3. Re-interleave to output and hard-mute the inactive ear
            for (i in 0 until chunk) {
                when (mode) {
                    ChannelRoutingMode.NORMAL, ChannelRoutingMode.SWAP -> {
                        buffer[base + CHANNELS * i] = leftScratch[i]
                        buffer[base + CHANNELS * i + 1] = rightScratch[i]
                    }
                    ChannelRoutingMode.RIGHT_TO_LEFT -> {
                        buffer[base + CHANNELS * i] = leftScratch[i]
                        buffer[base + CHANNELS * i + 1] = 0f // Right side not playing
                    }
                    ChannelRoutingMode.LEFT_TO_RIGHT -> {
                        buffer[base + CHANNELS * i] = 0f // Left side not playing
                        buffer[base + CHANNELS * i + 1] = rightScratch[i]
                    }
                }
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

    fun release() {
        released = true
        left.release()
        right.release()
    }

    private companion object {
        const val CHANNELS = 2
    }
}