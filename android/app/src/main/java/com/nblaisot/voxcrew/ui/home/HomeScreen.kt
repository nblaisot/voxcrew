package com.nblaisot.voxcrew.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nblaisot.voxcrew.connectivity.local.QrJoinPayload
import com.nblaisot.voxcrew.signaling.ConnectionState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSessionReady: (sessionId: String, isLocalHost: Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            QrJoinPayload.fromUri(android.net.Uri.parse(text))?.let { payload ->
                viewModel.joinLocalFromQr(payload) { id -> onSessionReady(id, false) }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Accueil VoxCrew")
        Text("Utilisateur : ${state.userEmail ?: "—"}")
        Text("Backend : ${state.backendUrl}")
        Text("WebSocket : ${state.connectionState.name}")
        state.lastError?.let { Text("Erreur : $it") }
        state.lastRttMs?.let { Text("RTT signaling : ${it} ms") }

        Button(onClick = viewModel::connectSignaling, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.connectionState == ConnectionState.AUTHENTICATED) "Reconnecter" else "Connecter signaling")
        }
        Button(onClick = {
            viewModel.createSession { id, _ -> onSessionReady(id, false) }
        }, enabled = state.connectionState == ConnectionState.AUTHENTICATED, modifier = Modifier.fillMaxWidth()) {
            Text("Créer session cloud")
        }
        Button(onClick = {
            viewModel.createLocalSession { id -> onSessionReady(id, true) }
        }, enabled = state.connectionState == ConnectionState.AUTHENTICATED, modifier = Modifier.fillMaxWidth()) {
            Text("Créer session locale (hôte)")
        }
        state.localQrPayload?.let { qr ->
            Text("QR join : ${qr.toUri()}")
            Text("LAN : ${qr.toDisplayHost()} — code masqué")
            Button(onClick = viewModel::dismissQr, modifier = Modifier.fillMaxWidth()) {
                Text("Masquer QR")
            }
        }
        Button(onClick = {
            qrLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    setPrompt("Scanner le QR de l'hôte")
                    setBeepEnabled(false)
                },
            )
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Scanner QR local")
        }
        OutlinedTextField(
            value = state.joinSessionId,
            onValueChange = viewModel::onJoinIdChange,
            label = { Text("ID session") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            viewModel.joinSession { id, _ -> onSessionReady(id, false) }
        }, enabled = state.connectionState == ConnectionState.AUTHENTICATED, modifier = Modifier.fillMaxWidth()) {
            Text("Rejoindre cloud")
        }
        Text("Rejoindre local manuellement")
        OutlinedTextField(
            value = state.manualHost,
            onValueChange = viewModel::onManualHostChange,
            label = { Text("Hôte LAN") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.manualPort,
            onValueChange = viewModel::onManualPortChange,
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.manualToken,
            onValueChange = viewModel::onManualTokenChange,
            label = { Text("Code secret") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            viewModel.joinLocalManual { id -> onSessionReady(id, false) }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Rejoindre local")
        }
        Button(onClick = viewModel::toggleHotspotGuide, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.showHotspotGuide) "Masquer guide hotspot" else "Guide hotspot")
        }
        if (state.showHotspotGuide) {
            Text(
                "Sur l'appareil hôte : Paramètres → Connexions → Point d'accès mobile → activer le hotspot. " +
                    "L'invité se connecte au Wi‑Fi du hôte, puis scanne le QR ou saisit l'adresse LAN.",
            )
        }
        Button(onClick = {
            viewModel.signOut()
            onSignOut()
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Déconnexion")
        }
    }
}
