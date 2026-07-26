package com.nblaisot.voxcrew.ui.relay

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.relay.RelayConfigLinkParser
import com.nblaisot.voxcrew.relay.RelaySettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    repository: RelaySettingsRepository,
    relayReady: Boolean,
    onBack: () -> Unit,
) {
    val stored by repository.settings.collectAsState()
    var enabled by remember(stored) { mutableStateOf(stored.enabled) }
    var url by remember(stored) { mutableStateOf(stored.url) }
    var secret by remember(stored) { mutableStateOf(stored.secret) }
    var certSha256 by remember(stored) { mutableStateOf(stored.certSha256.orEmpty()) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relay_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("relay_back")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (url.isBlank() || secret.isBlank()) return@IconButton
                            val link = RelayConfigLinkParser.build(
                                url = url.trim(),
                                secret = secret,
                                certSha256 = certSha256.trim().ifEmpty { null },
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, link)
                            }
                            context.startActivity(
                                Intent.createChooser(send, context.getString(R.string.relay_share)),
                            )
                        },
                        modifier = Modifier.testTag("relay_share"),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.relay_share))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.relay_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.relay_enabled))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    modifier = Modifier.testTag("relay_enabled"),
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.relay_url_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_url"),
                singleLine = true,
                placeholder = { Text("wss://host:8443") },
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.relay_secret_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_secret"),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = certSha256,
                onValueChange = { certSha256 = it },
                label = { Text(stringResource(R.string.relay_cert_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_cert"),
                singleLine = true,
            )
            Text(
                text = if (relayReady) {
                    stringResource(R.string.relay_status_connected)
                } else {
                    stringResource(R.string.relay_status_disconnected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (relayReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    repository.update(
                        enabled = enabled,
                        url = url.trim(),
                        secret = secret,
                        certSha256 = certSha256.trim().ifEmpty { null },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_save"),
            ) {
                Text(stringResource(R.string.relay_save))
            }
        }
    }
}
