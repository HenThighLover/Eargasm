package app.openhearing.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class FakePreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        state.value = transform(state.value)
        return state.value
    }
}

class ExposureSettingsTest {
    @Test
    fun `defaults to zero units on day zero`() = runTest {
        val repo = DataStoreSettingsRepository(FakePreferencesStore())
        val exposure = repo.observeExposureToday().first()
        assertEquals(0L, exposure.epochDay)
        assertEquals(0.0, exposure.units)
    }

    @Test
    fun `same-day writes accumulate`() = runTest {
        val repo = DataStoreSettingsRepository(FakePreferencesStore())
        repo.addExposureUnits(1.5, epochDay = 20_000)
        repo.addExposureUnits(2.5, epochDay = 20_000)
        val exposure = repo.observeExposureToday().first()
        assertEquals(20_000L, exposure.epochDay)
        assertEquals(4.0, exposure.units, 1e-9)
    }

    @Test
    fun `a new day replaces instead of summing`() = runTest {
        val repo = DataStoreSettingsRepository(FakePreferencesStore())
        repo.addExposureUnits(9.0, epochDay = 20_000)
        repo.addExposureUnits(1.0, epochDay = 20_001)
        val exposure = repo.observeExposureToday().first()
        assertEquals(20_001L, exposure.epochDay)
        assertEquals(1.0, exposure.units, 1e-9)
    }
}
