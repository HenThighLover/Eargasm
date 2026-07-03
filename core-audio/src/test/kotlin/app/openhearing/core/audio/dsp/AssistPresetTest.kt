package app.openhearing.core.audio.dsp

import app.openhearing.audiogram.GainCurve
import app.openhearing.audiogram.GainPoint
import app.openhearing.common.Hertz
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AssistPresetTest {
    private val curve =
        GainCurve(
            listOf(
                GainPoint(Hertz(250.0), 5.0),
                GainPoint(Hertz(1000.0), 10.0),
                GainPoint(Hertz(2000.0), 12.0),
                GainPoint(Hertz(8000.0), 20.0),
            ),
        )

    @Test
    fun `standard preset leaves the curve untouched`() {
        assertEquals(curve.points, curve.withPreset(AssistPreset.STANDARD).points)
        assertNull(AssistPreset.STANDARD.highPassHz)
    }

    @Test
    fun `conversation preset boosts only the speech band`() {
        val shaped = curve.withPreset(AssistPreset.CONVERSATION)
        val byFreq = shaped.points.associateBy { it.frequency.value }
        assertEquals(5.0, byFreq.getValue(250.0).gainDb)
        assertEquals(10.0 + AssistPreset.CONVERSATION_BOOST_DB, byFreq.getValue(1000.0).gainDb)
        assertEquals(12.0 + AssistPreset.CONVERSATION_BOOST_DB, byFreq.getValue(2000.0).gainDb)
        assertEquals(20.0, byFreq.getValue(8000.0).gainDb)
    }

    @Test
    fun `outdoors preset keeps gains but adds a low cut`() {
        assertEquals(curve.points, curve.withPreset(AssistPreset.OUTDOORS).points)
        assertNotNull(AssistPreset.OUTDOORS.highPassHz)
    }

    @Test
    fun `unknown preset names fall back to standard`() {
        assertEquals(AssistPreset.STANDARD, AssistPreset.fromName("SOMETHING_ELSE"))
        assertEquals(AssistPreset.STANDARD, AssistPreset.fromName(null))
        assertEquals(AssistPreset.OUTDOORS, AssistPreset.fromName("OUTDOORS"))
    }
}
