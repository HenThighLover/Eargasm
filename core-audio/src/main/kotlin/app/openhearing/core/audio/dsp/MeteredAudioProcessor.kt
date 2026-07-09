package app.openhearing.core.audio.dsp

import app.openhearing.core.audio.AudioProcessor

/**
 * Wraps a processing chain with an output level tap. The meter observes the
 * buffer *after* the delegate — i.e. post-limiter, exactly what reaches the
 * device — and the safety-critical chain itself stays untouched.
 */
class MeteredAudioProcessor(private val delegate: AudioProcessor, private val meter: OutputLevelMeter) :
    AudioProcessor {
    override fun process(buffer: FloatArray) {
        delegate.process(buffer)
        meter.accumulate(buffer)
    }
}
