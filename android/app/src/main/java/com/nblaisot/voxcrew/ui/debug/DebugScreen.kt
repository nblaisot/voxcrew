package com.nblaisot.voxcrew.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.ui.home.HomeScreen
import com.nblaisot.voxcrew.ui.home.HomeViewModel

@Composable
fun DebugScreen(
    homeViewModel: HomeViewModel,
    onBack: () -> Unit,
    onSessionReady: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Button(onClick = onBack, modifier = Modifier.padding(16.dp)) {
            Text("Retour")
        }
        Text("Outils développeur", modifier = Modifier.padding(horizontal = 16.dp))
        HomeScreen(
            viewModel = homeViewModel,
            onSessionReady = onSessionReady,
            onSignOut = onBack,
        )
    }
}
