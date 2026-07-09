package app.openhearing.core.audio

/**
 * Pure block source for the A/B demo player: two equal-length interleaved
 * stereo buffers (raw and processed), a shared play position that loops, and a
 * live toggle. Flipping [processedActive] takes effect at the next block *at
 * the same position*, crossfaded linearly across that block so the switch
 * never clicks — the classic hearing-aid demo interaction.
 *
 * Pure Kotlin so the seek/toggle/loop/crossfade logic is JVM-unit-tested; the
 * AudioTrack shell around it ([AbPlayer]) stays deliberately thin.
 */
class AbBufferSource(private val raw: FloatArray, private val processed: FloatArray) {
    init {
        require(raw.size == processed.size) { "raw and processed must be equal length" }
        require(raw.isNotEmpty()) { "buffers must not be empty" }
    }

    @Volatile
    var processedActive: Boolean = false

    private var position = 0
    private var current: FloatArray = raw

    /** Total samples per loop (interleaved). */
    val length: Int get() = raw.size

    /** Fill [out] with the next block, looping and crossfading as needed. */
    fun fill(out: FloatArray) {
        val target = if (processedActive) processed else raw
        if (target === current) {
            copyLooping(current, out, mix = null)
        } else {
            // Crossfade old -> new across this one block, same position in both.
            copyLooping(current, out, mix = target)
            current = target
        }
        position = (position + out.size) % length
    }

    fun reset() {
        position = 0
        current = if (processedActive) processed else raw
    }

    private fun copyLooping(source: FloatArray, out: FloatArray, mix: FloatArray?) {
        val n = out.size
        for (i in 0 until n) {
            val p = (position + i) % length
            out[i] =
                if (mix == null) {
                    source[p]
                } else {
                    val t = i.toFloat() / n
                    source[p] * (1f - t) + mix[p] * t
                }
        }
    }
}
