package app.openhearing.core.audio

import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import android.media.AudioFormat as PlatformAudioFormat

/**
 * Low-latency capture -> process -> playback loop for assist mode, backed by
 * AudioRecord + AudioTrack (float PCM, stereo with safe mono fallback). The supplied
 * [AudioProcessor] runs on a dedicated urgent-audio thread, block by block; its last
 * stage is the safety limiter (see [app.openhearing.core.audio.dsp.HearingAssistChain]).
 *
 * Requires the RECORD_AUDIO permission (the caller must hold it before [start]).
 * This is the Android I/O shell; all DSP is the testable pure-Kotlin core. Verify
 * latency and behaviour on-device (see docs/DEVICE_TESTING.md) — it can't be
 * unit-tested here.
 */
class AndroidAudioEngine : AudioEngine {
    /** One per start(): stop() ends exactly this session's thread, even if start() follows. */
    private class Session {
        @Volatile
        var active = true

        @Volatile
        var record: AudioRecord? = null

        @Volatile
        var track: AudioTrack? = null
    }

    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var session: Session? = null
    private var thread: Thread? = null

    override val isRunning: Boolean get() = running

    override fun start(format: AudioFormat, processor: AudioProcessor) {
        synchronized(lock) {
            if (running) return
            running = true
            val s = Session()
            session = s
            thread =
                Thread({ runLoop(format, processor, s) }, "OpenHearing-Assist").apply {
                    start()
                }
        }
    }

    override fun stop() {
        val s: Session?
        val t: Thread?
        synchronized(lock) {
            s = session
            t = thread
            s?.active = false
            running = false
            session = null
            thread = null
        }
        // Wake the audio thread out of whichever blocking call it is in.
        s?.record?.let { runCatching { it.stop() } }
        s?.track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        t?.join(STOP_JOIN_TIMEOUT_MS)
        if (t != null && t.isAlive) {
            Log.w(TAG, "Assist thread still alive after stop; force-releasing audio devices")
            s?.record?.let { runCatching { it.release() } }
            s?.track?.let { runCatching { it.release() } }
            t.join(FORCE_JOIN_TIMEOUT_MS)
            if (t.isAlive) Log.e(TAG, "Assist thread STILL alive after force release")
        }
    }

    private fun markStopped(s: Session) {
        s.active = false
        synchronized(lock) { if (session === s) running = false }
    }

    // Fail-closed on a missing permission: we deliberately swallow and go silent
    // rather than risk any uncontrolled audio path.
    @Suppress("SwallowedException")
    private fun runLoop(format: AudioFormat, processor: AudioProcessor, s: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val channelOut =
            if (format.channelCount == 2) {
                PlatformAudioFormat.CHANNEL_OUT_STEREO
            } else {
                PlatformAudioFormat.CHANNEL_OUT_MONO
            }
        val encoding = PlatformAudioFormat.ENCODING_PCM_FLOAT
        val bytesPerBlock = format.framesPerBlock * format.channelCount * Float.SIZE_BYTES
        val trackBytes =
            maxOf(AudioTrack.getMinBufferSize(format.sampleRateHz, channelOut, encoding), bytesPerBlock * 4)

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            // Try physical stereo mic capture first when 2 channels are requested;
            // gracefully fall back to mono if the hardware or headset only has 1 mic.
            val stereoRecord = if (format.channelCount == 2) {
                buildAudioRecord(format, PlatformAudioFormat.CHANNEL_IN_STEREO, encoding, isStereo = true)
            } else {
                null
            }
            val isStereoInput = stereoRecord != null
            record = stereoRecord ?: buildAudioRecord(format, PlatformAudioFormat.CHANNEL_IN_MONO, encoding, isStereo = false)
            if (record == null) {
                markStopped(s)
                return
            }
            s.record = record

            track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        PlatformAudioFormat.Builder()
                            .setSampleRate(format.sampleRateHz)
                            .setEncoding(encoding)
                            .setChannelMask(channelOut)
                            .build(),
                    )
                    .setBufferSizeInBytes(trackBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            s.track = track

            // Stop was pressed while we were still setting up.
            if (!s.active) return

            val inChannels = if (isStereoInput) 2 else 1
            val block = FloatArray(format.framesPerBlock * inChannels)
            val out = FloatArray(format.framesPerBlock * format.channelCount)
            record.startRecording()
            
            track.play()

            while (s.active) {
                val readSamples = record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (!s.active) break
                if (readSamples <= 0) continue
                val framesRead = readSamples / inChannels
                if (framesRead <= 0) continue

                val toProcess = prepareBlock(format, isStereoInput, block, out, framesRead)
                processor.process(toProcess)
                // Never let audio out after Stop, even if the chain was already torn down.
                if (!s.active) break
                track.write(toProcess, 0, framesRead * format.channelCount, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: SecurityException) {
            // Missing RECORD_AUDIO permission — fail closed (silent), never loud.
            markStopped(s)
        } finally {
            s.record = null
            s.track = null
            record?.runCatchingRelease()
            track?.runCatchingRelease { stop() }
        }
    }

    @Suppress("SwallowedException")
    private fun buildAudioRecord(
        format: AudioFormat,
        channelIn: Int,
        encoding: Int,
        isStereo: Boolean,
    ): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(format.sampleRateHz, channelIn, encoding)
        if (minBuf <= 0) return null
        val inChannels = if (isStereo) 2 else 1
        val bytesPerBlock = format.framesPerBlock * inChannels * Float.SIZE_BYTES
        val recordBytes = maxOf(minBuf, bytesPerBlock * 4)
        return try {
            val rec = AudioRecord.Builder()
                .setAudioSource(audioSourceFor(format.inputTuning, isStereo))
                .setAudioFormat(
                    PlatformAudioFormat.Builder()
                        .setSampleRate(format.sampleRateHz)
                        .setEncoding(encoding)
                        .setChannelMask(channelIn)
                        .build(),
                )
                .setBufferSizeInBytes(recordBytes)
                .build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                rec
            } else {
                rec.release()
                null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Exact-sized block for the processor: native stereo, mono as-is, or mono duplicated to stereo. */
    private fun prepareBlock(
        format: AudioFormat,
        isStereoInput: Boolean,
        block: FloatArray,
        out: FloatArray,
        framesRead: Int,
    ): FloatArray = when {
        isStereoInput -> {
            val samples = framesRead * 2
            if (samples == block.size) block else block.copyOf(samples)
        }
        format.channelCount == 2 -> expandMonoToStereo(block, framesRead, out)
        framesRead == block.size -> block
        else -> block.copyOf(framesRead)
    }

    /** Duplicates [framesRead] mono frames into interleaved stereo; exact-sized result. */
    private fun expandMonoToStereo(mono: FloatArray, framesRead: Int, out: FloatArray): FloatArray {
        for (i in 0 until framesRead) {
            out[2 * i] = mono[i]
            out[2 * i + 1] = mono[i]
        }
        return if (framesRead * 2 == out.size) out else out.copyOf(framesRead * 2)
    }

    private fun AudioRecord.runCatchingRelease() {
        runCatching { if (recordingState != AudioRecord.RECORDSTATE_STOPPED) stop() }
        runCatching { release() }
    }

    private inline fun AudioTrack.runCatchingRelease(pre: AudioTrack.() -> Unit) {
        runCatching { pre() }
        runCatching { release() }
    }

    // VOICE_COMMUNICATION forces mono downmixing in Android's HAL on most devices;
    // when capturing physical stereo, CAMCORDER engages the directional stereo mic array.
    private fun audioSourceFor(tuning: InputTuning, isStereo: Boolean): Int = when (tuning) {
        InputTuning.COMMUNICATION ->
            if (isStereo) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        InputTuning.RAW_UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        InputTuning.RAW_VOICE_RECOGNITION ->
            if (isStereo) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private companion object {
        const val TAG = "OpenHearingEngine"
        const val STOP_JOIN_TIMEOUT_MS = 1500L
        const val FORCE_JOIN_TIMEOUT_MS = 1000L
    }
}