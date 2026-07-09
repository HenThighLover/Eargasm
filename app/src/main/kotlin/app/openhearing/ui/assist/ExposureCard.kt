package app.openhearing.ui.assist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.openhearing.R
import java.util.Locale

/**
 * Relative listening meter: session time, live output-level bar, and today's
 * percentage of the (policy, uncalibrated) daily meter — a gentle guide, never
 * a health claim. See ExposureTracker for the unit definition.
 */
@Composable
fun ExposureCard(exposure: ExposureUi, running: Boolean, modifier: Modifier = Modifier) {
    if (!running && exposure.todayPercent == 0) return
    Card(modifier = modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.exposure_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.exposure_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (running) {
                Text(
                    stringResource(R.string.exposure_session, formatDuration(exposure.sessionSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.exposure_level_now),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LinearProgressIndicator(
                    progress = { exposure.levelFraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Text(
                stringResource(R.string.exposure_today, exposure.todayPercent),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            LinearProgressIndicator(
                progress = { (exposure.todayPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (exposure.showHighNote) {
                Text(
                    stringResource(R.string.exposure_high_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
