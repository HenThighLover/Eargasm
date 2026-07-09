package app.openhearing.core.audio

import java.io.InputStream

/**
 * Minimal PCM16 mono WAV reader for the digits-in-noise assets. Pure Kotlin
 * over an [InputStream] (JVM-testable with synthesized bytes) — deliberately
 * not `MediaCodec`, so the asset pipeline stays verifiable off-device. Only
 * the exact format we ship is accepted; anything else fails loudly.
 */
object WavCodec {
    /** Decode a PCM16 mono WAV stream into normalized floats in [-1, 1]. */
    fun read(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        require(bytes.size >= HEADER_MIN_BYTES) { "not a WAV file (too short)" }
        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "not a RIFF/WAVE file" }

        var pos = 12
        var dataStart = -1
        var dataLength = 0
        var formatOk = false
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val audioFormat = le16(bytes, body)
                    val channels = le16(bytes, body + 2)
                    val bits = le16(bytes, body + 14)
                    require(audioFormat == PCM_FORMAT) { "only PCM WAV is supported" }
                    require(channels == 1) { "only mono WAV is supported" }
                    require(bits == BITS_PER_SAMPLE) { "only 16-bit WAV is supported" }
                    formatOk = true
                }
                "data" -> {
                    dataStart = body
                    dataLength = size
                }
            }
            pos = body + size + (size and 1) // chunks are word-aligned
        }
        require(formatOk) { "missing fmt chunk" }
        require(dataStart >= 0) { "missing data chunk" }
        val end = minOf(dataStart + dataLength, bytes.size)

        val samples = FloatArray((end - dataStart) / 2)
        for (i in samples.indices) {
            val lo = bytes[dataStart + 2 * i].toInt() and 0xFF
            val hi = bytes[dataStart + 2 * i + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return samples
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int = le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)

    private const val HEADER_MIN_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16
}
