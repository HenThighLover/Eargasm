package app.openhearing.ui.dintest

import android.content.Context
import app.openhearing.audiogram.DigitsInNoiseScreening
import app.openhearing.core.audio.WavCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the digits-in-noise audio corpus from `res/raw` (`din_digit_<n>.wav`
 * per digit plus `din_noise.wav`, all 48 kHz PCM16 mono — see docs/DIN.md for
 * the recording/normalization pipeline and license).
 *
 * The corpus ships separately from the engine: until the recordings land in
 * the repo, [isAvailable] is false and the listening-in-noise check stays off
 * the home screen. The engine and UI are complete and tested regardless.
 */
@Singleton
class DigitCorpus
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val digitCache = mutableMapOf<Int, FloatArray>()
    private var noiseCache: FloatArray? = null

    /** True when every alphabet digit plus the noise track exists in res/raw. */
    fun isAvailable(): Boolean = resId(NOISE_NAME) != 0 &&
        DigitsInNoiseScreening.DEFAULT_ALPHABET.all { resId(digitName(it)) != 0 }

    fun digit(value: Int): FloatArray = digitCache.getOrPut(value) { load(digitName(value)) }

    fun noise(): FloatArray = noiseCache ?: load(NOISE_NAME).also { noiseCache = it }

    private fun load(name: String): FloatArray {
        val id = resId(name)
        require(id != 0) { "missing raw resource $name" }
        return context.resources.openRawResource(id).use { WavCodec.read(it) }
    }

    // Name-based lookup keeps the corpus optional: R constants would fail to
    // compile while the recordings aren't in the repo yet.
    @Suppress("DiscouragedApi")
    private fun resId(name: String): Int = context.resources.getIdentifier(name, "raw", context.packageName)

    private fun digitName(value: Int): String = "din_digit_$value"

    private companion object {
        const val NOISE_NAME = "din_noise"
    }
}
