package app.openhearing.ui.remotemic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhearing.R
import app.openhearing.assist.AssistService
import app.openhearing.common.SafetyConstants
import app.openhearing.ui.common.headphonesConnected
import kotlinx.coroutines.launch

/**
 * Remote-microphone screen: place the phone near the TV or a speaker and its
 * sound streams to your connected earbuds. Unlike assist, headphones are a hard
 * requirement (the feedback guard is off in this mode — mic to phone speaker
 * would howl unguarded), so Start is disabled rather than warned without them.
 */
@Composable
fun RemoteMicScreen(onBack: () -> Unit, viewModel: RemoteMicViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sampled on entry and on start attempts; device changes re-check on tap.
    var headphones by remember { mutableStateOf(headphonesConnected(context)) }

    val permissions =
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    fun proceedStart() {
        scope.launch { if (viewModel.prepare()) AssistService.start(context, remoteMic = true) }
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true && headphonesConnected(context)) {
                proceedStart()
            }
        }

    fun startRemote() {
        headphones = headphonesConnected(context)
        if (!headphones) return
        val micGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (micGranted) proceedStart() else launcher.launch(permissions)
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.remote_mic_title), style = MaterialTheme.typography.headlineSmall)
        HowToCard()

        Text(
            if (state.running) {
                stringResource(R.string.remote_mic_on)
            } else {
                stringResource(R.string.remote_mic_off)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        GainControl(masterGainDb = state.masterGainDb, onChange = viewModel::setMasterGain)

        if (!headphones && !state.running) HeadphonesRequiredCard()

        if (state.assistModeActive) {
            Text(
                stringResource(R.string.remote_mic_assist_active),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Spacer(Modifier.padding(8.dp))
        if (state.running) {
            Button(
                onClick = { AssistService.stop(context) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                Text(stringResource(R.string.remote_mic_stop), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(
                onClick = ::startRemote,
                enabled = !state.assistModeActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                Text(stringResource(R.string.remote_mic_start), style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.padding(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun HeadphonesRequiredCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            stringResource(R.string.remote_mic_needs_headphones),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun HowToCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            stringResource(R.string.remote_mic_howto),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun GainControl(masterGainDb: Double, onChange: (Double) -> Unit) {
    val sliderDescription = stringResource(R.string.assist_amplification_slider)
    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.assist_amplification, masterGainDb.toInt()),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = masterGainDb.toFloat(),
                onValueChange = { onChange(it.toDouble()) },
                valueRange = 0f..SafetyConstants.MAX_MASTER_GAIN_CAP_DB.toFloat(),
                modifier = Modifier.semantics { contentDescription = sliderDescription },
            )
        }
    }
}
