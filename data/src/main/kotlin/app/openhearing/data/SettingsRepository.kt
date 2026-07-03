package app.openhearing.data

import kotlinx.coroutines.flow.Flow

/** App-level settings and the one-time disclaimer consent flag. */
interface SettingsRepository {
    /** True once the user has acknowledged the safety/legal disclaimer. */
    fun observeConsentAccepted(): Flow<Boolean>

    suspend fun setConsentAccepted(accepted: Boolean)

    /** High-contrast theme preference (accessibility). */
    fun observeHighContrast(): Flow<Boolean>

    suspend fun setHighContrast(enabled: Boolean)

    /**
     * The "comfort" output ceiling as a linear amplitude in (0, 1]. This is the
     * maximum loudness the assist limiter will allow, set by the user during
     * comfort calibration. A conservative default applies until calibrated. See
     * docs/CALIBRATION.md.
     */
    fun observeComfortCeiling(): Flow<Float>

    suspend fun setComfortCeiling(value: Float)

    /**
     * Assist environment preset, stored by name (e.g. "STANDARD"). The app layer
     * maps it to the DSP preset enum; unknown names fall back to standard.
     */
    fun observeAssistPreset(): Flow<String>

    suspend fun setAssistPreset(name: String)

    /** Experimental media EQ (profile applied to other apps' audio) on/off. */
    fun observeMediaEqEnabled(): Flow<Boolean>

    suspend fun setMediaEqEnabled(enabled: Boolean)
}
