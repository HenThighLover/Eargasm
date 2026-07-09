package app.openhearing.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WavCodecTest {
    /** Build a minimal PCM16 mono WAV byte stream around [samples]. */
    private fun wavBytes(samples: ShortArray, sampleRate: Int = 48_000): ByteArray {
        val data = ByteArrayOutputStream()
        fun str(s: String) = data.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) {
            data.write(v and 0xFF)
            data.write((v shr 8) and 0xFF)
        }
        fun le32(v: Int) {
            le16(v and 0xFFFF)
            le16((v shr 16) and 0xFFFF)
        }
        val dataBytes = samples.size * 2
        str("RIFF")
        le32(36 + dataBytes)
        str("WAVE")
        str("fmt ")
        le32(16)
        le16(1) // PCM
        le16(1) // mono
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        str("data")
        le32(dataBytes)
        for (s in samples) le16(s.toInt() and 0xFFFF)
        return data.toByteArray()
    }

    @Test
    fun `round-trips PCM16 samples to normalized floats`() {
        val samples = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val decoded = WavCodec.read(ByteArrayInputStream(wavBytes(samples)))
        assertEquals(samples.size, decoded.size)
        assertEquals(0f, decoded[0])
        assertEquals(0.5f, decoded[1], 1e-4f)
        assertEquals(-0.5f, decoded[2], 1e-4f)
        assertEquals(1f, decoded[3], 1e-3f)
        assertEquals(-1f, decoded[4])
    }

    @Test
    fun `rejects stereo and non-PCM files`() {
        val bytes = wavBytes(shortArrayOf(1, 2, 3))
        bytes[22] = 2 // channels = 2
        val error = runCatching { WavCodec.read(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects garbage input`() {
        val error =
            runCatching { WavCodec.read(ByteArrayInputStream(ByteArray(100))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
