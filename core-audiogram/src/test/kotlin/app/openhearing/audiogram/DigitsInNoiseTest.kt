package app.openhearing.audiogram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class DigitsInNoiseTest {
    @Test
    fun `staircase steps down on correct and up on wrong`() {
        val s = DinStaircase(DinConfig(startSnrDb = 4.0, stepDb = 2.0))
        assertEquals(4.0, s.currentSnrDb())
        s.submit(tripletCorrect = true)
        assertEquals(2.0, s.currentSnrDb())
        s.submit(tripletCorrect = false)
        assertEquals(4.0, s.currentSnrDb())
    }

    @Test
    fun `staircase clamps at the range edges and flags pinning`() {
        val s = DinStaircase(DinConfig(startSnrDb = -18.0, minSnrDb = -20.0, totalTriplets = 24))
        var step: DinStep? = null
        repeat(24) { step = s.submit(tripletCorrect = true) }
        val done = step as DinStep.Done
        assertTrue(done.result.pinnedAtEdge, "sitting on min SNR must be flagged")
        assertEquals(-20.0, s.currentSnrDb())
    }

    @Test
    fun `finishes after the configured number of triplets`() {
        val s = DinStaircase(DinConfig(totalTriplets = 10))
        var done: DinStep.Done? = null
        repeat(10) { i ->
            val step = s.submit(tripletCorrect = i % 2 == 0)
            if (step is DinStep.Done) done = step
        }
        assertNotNull(done)
        assertEquals(10, done!!.result.tripletsPresented)
    }

    @Test
    fun `triplets contain three distinct digits from the alphabet`() {
        val screening = DigitsInNoiseScreening(random = Random(7))
        repeat(23) {
            val t = screening.currentTriplet()
            assertEquals(3, t.size)
            assertEquals(3, t.distinct().size, "digits within a triplet must be distinct")
            assertTrue(t.all { d -> d in DigitsInNoiseScreening.DEFAULT_ALPHABET })
            assertFalse(t.contains(7), "7 (two syllables) is excluded from the alphabet")
            screening.submit(t) // answer correctly, move on
        }
    }

    @Test
    fun `simulated listener converges near its true SRT`() {
        // Logistic psychometric function around a "true" SRT with a typical slope.
        fun listener(random: Random, trueSrtDb: Double, snrDb: Double): Boolean {
            val pCorrect = 1.0 / (1.0 + exp(-(snrDb - trueSrtDb) / 1.0))
            return random.nextDouble() < pCorrect
        }
        val trueSrt = -9.0
        val estimates =
            (1..12).map { seed ->
                val random = Random(seed)
                val screening = DigitsInNoiseScreening(random = Random(seed + 100))
                var result: DinResult? = null
                while (result == null) {
                    val answered =
                        if (listener(random, trueSrt, screening.currentSnrDb())) {
                            screening.currentTriplet()
                        } else {
                            screening.currentTriplet().map { (it + 1) % 10 } // wrong on purpose
                        }
                    val step = screening.submit(answered)
                    if (step is DinStep.Done) result = step.result
                }
                result!!.srtSnrDb
            }
        val mean = estimates.average()
        assertTrue(
            abs(mean - trueSrt) < 1.5,
            "mean SRT estimate $mean should be within 1.5 dB of true $trueSrt",
        )
        assertTrue(estimates.none { abs(it - trueSrt) > 5.0 }, "no wild outliers: $estimates")
    }

    @Test
    fun `screening rejects submissions after completion`() {
        val screening = DigitsInNoiseScreening(DinConfig(totalTriplets = 5), random = Random(1))
        repeat(5) { screening.submit(screening.currentTriplet()) }
        assertTrue(screening.isComplete())
        val error = runCatching { screening.submit(listOf(1, 2, 3)) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
