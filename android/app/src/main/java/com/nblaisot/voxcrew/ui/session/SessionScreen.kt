package com.nblaisot.voxcrew.ui.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nblaisot.voxcrew.audio.TransmissionMode

@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    sessionId: String,
    onLeave: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onMicPermissionResult(granted) }

    LaunchedEffect(sessionId) {
        viewModel.start(sessionId)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Session", style = MaterialTheme.typography.headlineSmall)
        Text("ID : $sessionId")
        Text("Participants : ${state.participants.joinToString()}")
        Text("WebRTC : ${state.peerState.name}")
        Text("ICE : ${state.iceState.name}")
        Text("Candidat : ${state.selectedCandidateType ?: "—"}")
        Text("Mode : ${state.transmissionMode.name}")
        Text("Transmission : ${if (state.isTransmitting) "ACTIVE" else "inactive"}")
        state.dataChannelRttMs?.let { Text("Data channel RTT : ${it} ms") }

        RowChips(
            selected = state.transmissionMode,
            onOpenMic = viewModel::useOpenMic,
            onPtt = viewModel::usePushToTalk,
        )

        if (state.transmissionMode == TransmissionMode.PUSH_TO_TALK) {
            val pttColor = if (state.isTransmitting) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
            Button(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.pttPress()
                                tryAwaitRelease()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.pttRelease()
                            },
                        )
                    },
                colors = ButtonDefaults.buttonColors(containerColor = pttColor),
            ) {
                Text("PTT — maintenir pour parler")
            }
        }

        Button(onClick = viewModel::sendDataChannelPing, modifier = Modifier.fillMaxWidth()) {
            Text("Ping data channel")
        }
        Button(onClick = viewModel::refreshStats, modifier = Modifier.fillMaxWidth()) {
            Text("Rafraîchir stats")
        }

        if (state.showDiagnostics) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            state.diagnosticsLog.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = viewModel::toggleDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.showDiagnostics) "Masquer diagnostics" else "Afficher diagnostics")
        }
        Button(onClick = {
            viewModel.leave()
            onLeave()
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Quitter la session")
        }
    }
}

@Composable
private fun RowChips(selected: TransmissionMode, onOpenMic: () -> Unit, onPtt: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == TransmissionMode.OPEN_MIC, onClick = onOpenMic, label = { Text("Micro ouvert") })
        FilterChip(selected = selected == TransmissionMode.PUSH_TO_TALK, onClick = onPtt, label = { Text("Push-to-talk") })
    }
}
