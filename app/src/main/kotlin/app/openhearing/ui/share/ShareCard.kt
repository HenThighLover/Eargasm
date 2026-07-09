package app.openhearing.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openhearing.R
import app.openhearing.audiogram.Audiogram
import app.openhearing.ui.hearingtest.AudiogramChart
import app.openhearing.ui.theme.OpenHearingTheme

// The exported image must look identical from dark-mode or high-contrast
// sessions, so every color here is pinned rather than taken from the ambient
// theme (the light-theme wrapper covers what AudiogramChart reads internally).
private val Ink = Color(0xFF1B1B1B)
private val InkSoft = Color(0xFF444444)
private val Brand = Color(0xFF0B5D66)

/**
 * Fixed-size, always-light card rendered into the share image: title, date,
 * the audiogram chart, the non-diagnostic disclaimer, and the project footer.
 */
@Composable
fun ShareCard(audiogram: Audiogram, dateText: String, modifier: Modifier = Modifier) {
    OpenHearingTheme(darkTheme = false, highContrast = false) {
        // Surface (not a background modifier) so LocalContentColor flips to the
        // light scheme's onSurface for everything that doesn't set its own color,
        // e.g. the chart legend.
        Surface(color = Color.White, modifier = modifier.width(300.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Brand,
                )
                Text(
                    stringResource(R.string.share_card_subtitle, dateText),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft,
                )
                AudiogramChart(
                    audiogram,
                    darkTheme = false,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.share_card_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.share_card_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
