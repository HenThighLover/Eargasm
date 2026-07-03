package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve

/**
 * Environment presets layered on top of the user's fitted gain curve. Deliberately
 * gentle: the profile does the personalization; a preset only nudges it for a
 * situation. The limiter downstream bounds everything regardless.
 */
enum class AssistPreset(
    /** Optional low-cut frequency (wind/rumble suppression); null = no low cut. */
    val highPassHz: Double?,
) {
    /** The fitted profile as-is. */
    STANDARD(null),

    /** Extra clarity in the speech band (1–4 kHz). */
    CONVERSATION(null),

    /** Low-cut against wind and handling rumble. */
    OUTDOORS(OUTDOORS_HIGH_PASS_HZ),
    ;

    companion object {
        const val CONVERSATION_BOOST_DB = 4.0
        const val SPEECH_BAND_LOW_HZ = 1_000.0
        const val SPEECH_BAND_HIGH_HZ = 4_000.0

        fun fromName(name: String?): AssistPreset = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

private const val OUTDOORS_HIGH_PASS_HZ = 150.0

/** Applies [preset] to this fitted curve (pure; the original curve is untouched). */
fun GainCurve.withPreset(preset: AssistPreset): GainCurve = when (preset) {
    AssistPreset.CONVERSATION ->
        GainCurve(
            points.map { p ->
                val inSpeechBand =
                    p.frequency.value in
                        AssistPreset.SPEECH_BAND_LOW_HZ..AssistPreset.SPEECH_BAND_HIGH_HZ
                if (inSpeechBand) {
                    p.copy(gainDb = p.gainDb + AssistPreset.CONVERSATION_BOOST_DB)
                } else {
                    p
                }
            },
        )
    AssistPreset.STANDARD, AssistPreset.OUTDOORS -> this
}
