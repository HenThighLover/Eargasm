package app.openhearing.ui.dintest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhearing.R
import app.openhearing.audiogram.DigitsInNoiseScreening

/**
 * Listening-in-noise check: spoken digits in adaptive background noise. The
 * SNR-relative design makes the result meaningful on uncalibrated hardware —
 * the one screening where "just set a comfortable volume" is scientifically
 * fine. Mirrors the pure-tone check's phase structure and safety controls.
 */
@Composable
fun DinTestScreen(onBack: () -> Unit, viewModel: DinTestViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.din_title), style = MaterialTheme.typography.headlineSmall)
        NoticeCard()

        when (state.phase) {
            DinPhase.NOT_STARTED -> NotStarted(onStart = viewModel::start, onBack = onBack)
            DinPhase.IN_PROGRESS -> InProgress(state = state, viewModel = viewModel)
            DinPhase.DONE -> Results(state = state, onBack = onBack)
        }
    }
}

@Composable
private fun NoticeCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            stringResource(R.string.din_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun NotStarted(onStart: () -> Unit, onBack: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.din_intro),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) { Text(stringResource(R.string.din_start), style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.padding(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun InProgress(state: DinUiState, viewModel: DinTestViewModel) {
    Column {
        LinearProgressIndicator(
            progress = { state.tripletNumber.toFloat() / state.totalTriplets },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        Text(
            stringResource(R.string.din_progress, state.tripletNumber, state.totalTriplets),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            if (state.isPlaying) {
                stringResource(R.string.din_playing)
            } else {
                stringResource(R.string.din_enter_prompt)
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )

        EnteredDigits(state.entered)
        Keypad(enabled = !state.isPlaying, onDigit = viewModel::tapDigit)

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::backspace,
                enabled = state.entered.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.din_backspace)) }
            Button(
                onClick = viewModel::submit,
                enabled = state.entered.size == DigitsInNoiseScreening.TRIPLET_SIZE && !state.isPlaying,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.din_submit)) }
        }

        Button(
            onClick = viewModel::mute,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 16.dp),
        ) { Text(stringResource(R.string.check_mute)) }
    }
}

@Composable
private fun EnteredDigits(entered: List<Int>) {
    val display =
        List(DigitsInNoiseScreening.TRIPLET_SIZE) { index ->
            entered.getOrNull(index)?.toString() ?: "–"
        }.joinToString("  ")
    Text(
        display,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Int) -> Unit) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    OutlinedButton(
                        onClick = { onDigit(digit) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) { Text(digit.toString(), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

@Composable
private fun Results(state: DinUiState, onBack: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.din_complete),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        if (state.pinnedAtEdge) {
            Text(
                stringResource(R.string.din_result_pinned),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Text(
                stringResource(R.string.din_result_value, state.srtSnrDb),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                stringResource(
                    when (state.band) {
                        DinBand.STRONG -> R.string.din_band_strong
                        DinBand.MID -> R.string.din_band_mid
                        DinBand.WEAKER -> R.string.din_band_weaker
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(
            stringResource(R.string.check_results_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}
