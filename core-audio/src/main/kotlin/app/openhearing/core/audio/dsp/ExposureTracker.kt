package app.openhearing.core.audio.dsp

import kotlin.math.pow

/**
 * Relative listening-exposure accounting for the dashboard. Deliberately
 * energy-based ("listening units" = seconds × linear power), which gives the
 * WHO-style 3 dB exchange rate — +3 dB output halves the time to the same
 * units — **without any dB SPL claim**: the path is uncalibrated, so these are
 * relative policy numbers driving a gentle reminder, never a health limit.
 * That is also why the reference constants live here and NOT in
 * [app.openhearing.common.SafetyConstants]: they gate a note, not the output.
 */
object ExposureTracker {
    /**
     * 100% of the daily meter = [REF_HOURS] of sustained output at
     * [REF_LEVEL_DBFS] RMS (chosen near the default comfort ceiling). Policy,
     * not science; documented in the UI copy as a relative guide.
     */
    const val REF_HOURS = 4.0
    const val REF_LEVEL_DBFS = -6.0

    /** The percentage at which the UI shows the "consider a break" note. */
    const val HIGH_PERCENT = 80

    private val DAILY_ALLOWANCE_UNITS =
        REF_HOURS * 3_600.0 * dbToPower(REF_LEVEL_DBFS)

    /** Listening units contributed by a drained window spanning [seconds]. */
    fun unitsFor(window: LevelWindow, seconds: Double): Double = seconds * window.meanSquare

    /** Units as a percentage of the daily meter (may exceed 100). */
    fun percentOf(units: Double): Double = units / DAILY_ALLOWANCE_UNITS * 100.0

    /**
     * Day rollover: stored units carry over only within the same epoch day.
     * Pure so the midnight edge is unit-tested instead of waited for.
     */
    fun carriedUnits(storedEpochDay: Long, storedUnits: Double, todayEpochDay: Long): Double =
        if (storedEpochDay == todayEpochDay) storedUnits else 0.0

    private fun dbToPower(db: Double): Double = 10.0.pow(db / 10.0)
}
