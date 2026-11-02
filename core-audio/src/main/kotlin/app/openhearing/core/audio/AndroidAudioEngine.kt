package app.openhearing.core.audio

import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.media.AudioFormat as PlatformAudioFormat

/**
 * Low-latency capture -> process -> playback loop for assist mode, backed by
 * AudioRecord + AudioTrack (float PCM, stereo with safe mono fallback). The supplied
 * [AudioProcessor] runs on a dedicated urgent-audio thread, block by block; its last
 * stage is the safety limiter (see [app.openhearing.core.audio.dsp.HearingAssistChain]).
 */
class AndroidAudioEngine : AudioEngine {
    private val lock = Any()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var activeRecord: AudioRecord? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    override val isRunning: Boolean get() = running

    override fun start(format: AudioFormat, processor: AudioProcessor) {
        synchronized(lock) {
            if (running || thread != null) {
                stopInternal()
            }
            running = true
            thread =
                Thread({ runLoop(format, processor) }, "OpenHearing-Assist").apply {
                    start()
                }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        running = false
        // Immediately halt hardware capture and flush output so blocking reads/writes
        // wake up right away and zero residual audio can play after Stop is pressed.
        activeRecord?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
        }
        activeTrack?.let { trk ->
            runCatching {
                trk.pause()
                trk.flush()
                trk.stop()
            }
        }
        thread?.let { worker ->
            worker.interrupt()
            runCatching { worker.join(STOP_JOIN_TIMEOUT_MS) }
        }
        thread = null
    }

    // Fail-closed on a missing permission: we deliberately swallow and go silent
    // rather than risk any uncontrolled audio path.
    @Suppress("SwallowedException")
    private fun runLoop(format: AudioFormat, processor: AudioProcessor) {
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
            val stereoRecord = if (format.channelCount == 2) {
                buildAudioRecord(format, PlatformAudioFormat.CHANNEL_IN_STEREO, encoding, isStereo = true)
            } else {
                null
            }
            val isStereoInput = stereoRecord != null
            record = stereoRecord ?: buildAudioRecord(format, PlatformAudioFormat.CHANNEL_IN_MONO, encoding, isStereo = false)
            if (record == null) {
                running = false
                return
            }
            activeRecord = record

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
            activeTrack = track

            val inChannels = if (isStereoInput) 2 else 1
            val block = FloatArray(format.framesPerBlock * inChannels)
            val out = FloatArray(format.framesPerBlock * format.channelCount)
            record.startRecording()
            track.play()

            while (running && !Thread.currentThread().isInterrupted) {
                val readSamples = record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                if (!running || readSamples <= 0) continue
                val framesRead = readSamples / inChannels
                if (framesRead <= 0) continue

                val toProcess = prepareBlock(format, isStereoInput, block, out, framesRead)
                if (!running) break
                processor.process(toProcess)
                if (!running) break
                track.write(toProcess, 0, framesRead * format.channelCount, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: SecurityException) {
            running = false
        } catch (e: InterruptedException) {
            running = false
        } finally {
            activeRecord = null
            activeTrack = null
            record?.runCatchingRelease()
            track?.runCatchingRelease {
                pause()
                flush()
                stop()
            }
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

    private fun audioSourceFor(tuning: InputTuning, isStereo: Boolean): Int = when (tuning) {
        InputTuning.COMMUNICATION ->
            if (isStereo) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        InputTuning.RAW_UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        InputTuning.RAW_VOICE_RECOGNITION ->
            if (isStereo) MediaRecorder.AudioSource.CAMCORDER else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}