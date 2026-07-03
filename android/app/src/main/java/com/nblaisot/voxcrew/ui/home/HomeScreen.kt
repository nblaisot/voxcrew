package com.nblaisot.voxcrew.ui.home

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
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.signaling.ConnectionState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSessionReady: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
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
            viewModel.createSession { id -> onSessionReady(id) }
        }, enabled = state.connectionState == ConnectionState.AUTHENTICATED, modifier = Modifier.fillMaxWidth()) {
            Text("Créer une session")
        }
        OutlinedTextField(
            value = state.joinSessionId,
            onValueChange = viewModel::onJoinIdChange,
            label = { Text("ID session à rejoindre") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            viewModel.joinSession { id -> onSessionReady(id) }
        }, enabled = state.connectionState == ConnectionState.AUTHENTICATED, modifier = Modifier.fillMaxWidth()) {
            Text("Rejoindre")
        }
        Button(onClick = {
            viewModel.signOut()
            onSignOut()
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Déconnexion")
        }
    }
}
