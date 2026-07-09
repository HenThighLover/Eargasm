package app.openhearing.core.audio

import android.media.AudioAttributes
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Streams an [AbBufferSource] (interleaved stereo) to an AudioTrack in small
 * blocks, looping until [stop] or cancellation. Like [TonePlayer], the
 * configured [OutputLimiter] runs on **every** outgoing block — the processed
 * demo buffer is already limited by its chain, and this is the independent
 * backstop on the playback path (see docs/SAFETY.md).
 *
 * Thin Android I/O shell; the block/toggle/crossfade logic lives in the pure,
 * unit-tested [AbBufferSource].
 */
class AbPlayer(
    private val sampleRateHz: Int = ToneGenerator.DEFAULT_SAMPLE_RATE_HZ,
    private val limiter: OutputLimiter = HardCeilingLimiter(TonePlayer.DEFAULT_OUTPUT_CEILING),
) {
    @Volatile private var track: AudioTrack? = null

    @Volatile private var stopped = false

    /** Stream [source] until [stop] is called or the coroutine is cancelled. */
    suspend fun play(source: AbBufferSource) = withContext(Dispatchers.IO) {
        stopped = false
        source.reset()
        val block = FloatArray(BLOCK_FRAMES * CHANNELS)

        val minBytes =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                android.media.AudioFormat.CHANNEL_OUT_STEREO,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
            )
        val t = build(maxOf(minBytes, block.size * Float.SIZE_BYTES * 2))
        track = t
        try {
            t.play()
            while (!stopped) {
                coroutineContext.ensureActive()
                source.fill(block)
                // SAFETY-CRITICAL: independent ceiling on the playback path.
                limiter.processInPlace(block)
                var offset = 0
                while (offset < block.size && !stopped) {
                    val written = t.write(block, offset, block.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) return@withContext
                    offset += written
                }
            }
        } finally {
            releaseTrack(t)
        }
    }

    /** Instant mute: pause + flush now; a running [play] returns promptly. */
    fun stop() {
        stopped = true
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
    }

    /** Release any audio resources. Safe to call repeatedly. */
    fun release() {
        stop()
        track?.let { runCatching { it.release() } }
        track = null
    }

    private fun build(bufferBytes: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            android.media.AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.setVolume(AudioTrack.getMaxVolume()) }

    private fun releaseTrack(t: AudioTrack) {
        runCatching {
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
        }
        runCatching { t.release() }
        if (track === t) track = null
    }

    companion object {
        // 20 ms blocks: small enough that a toggle lands quickly, large enough
        // to stream without underruns.
        private const val BLOCK_FRAMES = 960
        private const val CHANNELS = 2
    }
}
