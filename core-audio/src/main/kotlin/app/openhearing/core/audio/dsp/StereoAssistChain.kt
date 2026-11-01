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
            
            // 1. De-interleave with input channel routing applied BEFORE per-ear DSP curves
            // so the target ear's audiogram/limiter processes the routed audio properly.
            for (i in 0 until chunk) {
                val inLeft = buffer[base + CHANNELS * i]
                val inRight = buffer[base + CHANNELS * i + 1]
                when (mode) {
                    ChannelRoutingMode.NORMAL -> {
                        leftScratch[i] = inLeft
                        rightScratch[i] = inRight
                    }
                    ChannelRoutingMode.RIGHT_TO_LEFT -> {
                        // Takes sound from the right side and routes it to the left side
                        leftScratch[i] = inRight
                        rightScratch[i] = inRight
                    }
                    ChannelRoutingMode.LEFT_TO_RIGHT -> {
                        // Takes sound from the left side and routes it to the right side
                        leftScratch[i] = inLeft
                        rightScratch[i] = inLeft
                    }
                    ChannelRoutingMode.SWAP -> {
                        // Full vice-versa swap: Right -> Left, Left -> Right
                        leftScratch[i] = inRight
                        rightScratch[i] = inLeft
                    }
                }
            }
            
            // A short partial block (final read) leaves a zero tail in the
            // scratch buffers; the chains just see a moment of silence.
            for (i in chunk until leftScratch.size) {
                leftScratch[i] = 0f
                rightScratch[i] = 0f