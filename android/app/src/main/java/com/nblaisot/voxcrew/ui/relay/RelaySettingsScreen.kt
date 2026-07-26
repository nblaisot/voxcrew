package com.nblaisot.voxcrew.ui.relay

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.relay.RelayConfigLinkParser
import com.nblaisot.voxcrew.relay.RelaySettingsRepository
import com.nblaisot.voxcrew.ui.theme.VoxRelayIdle
import com.nblaisot.voxcrew.ui.theme.VoxRelayReady
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(
    repository: RelaySettingsRepository,
    relayReady: Boolean,
    onBack: () -> Unit,
) {
    val stored by repository.settings.collectAsState()
    // Local edit buffer — do NOT key remember(stored) or TOFU/cert updates wipe in-progress typing.
    var enabled by remember { mutableStateOf(stored.enabled) }
    var url by remember { mutableStateOf(stored.url) }
    var secret by remember { mutableStateOf(stored.secret) }
    var certSha256 by remember { mutableStateOf(stored.certSha256.orEmpty()) }
    var pasteLink by remember { mutableStateOf("") }
    var hydrated by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.relay_saved)
    val needFieldsMessage = stringResource(R.string.relay_need_url_secret)
    val pasteFailedMessage = stringResource(R.string.relay_paste_failed)

    LaunchedEffect(stored) {
        // First paint + external applies (deep link): adopt stored values.
        // After the user has edited, only pull in cert fingerprint filled by TOFU.
        if (!hydrated) {
            enabled = stored.enabled
            url = stored.url
            secret = stored.secret
            certSha256 = stored.certSha256.orEmpty()
            hydrated = true
        } else if (!stored.certSha256.isNullOrBlank() &&
            certSha256.isBlank() &&
            stored.url == url.trim() &&
            stored.secret == secret
        ) {
            certSha256 = stored.certSha256.orEmpty()
        }
    }

    fun save(enableIfCredentials: Boolean = true) {
        val trimmedUrl = url.trim()
        val trimmedSecret = secret
        if (trimmedUrl.isBlank() || trimmedSecret.isBlank()) {
            scope.launch { snackbar.showSnackbar(needFieldsMessage) }
            return
        }
        val turnOn = if (enableIfCredentials) true else enabled
        enabled = turnOn
        repository.update(
            enabled = turnOn,
            url = trimmedUrl,
            secret = trimmedSecret,
            certSha256 = certSha256.trim().ifEmpty { null },
        )
        scope.launch { snackbar.showSnackbar(savedMessage) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                            if (url.isBlank() || secret.isBlank()) {
                                scope.launch { snackbar.showSnackbar(needFieldsMessage) }
                                return@IconButton
                            }
                            val share = RelayConfigLinkParser.buildShareText(
                                url = url.trim(),
                                secret = secret,
                                certSha256 = certSha256.trim().ifEmpty { null },
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, share)
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
            OutlinedTextField(
                value = pasteLink,
                onValueChange = { pasteLink = it },
                label = { Text(stringResource(R.string.relay_paste_link_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_paste"),
                minLines = 2,
                placeholder = { Text("voxcrew://… or https://…/invite?…") },
            )
            Button(
                onClick = {
                    val link = RelayConfigLinkParser.parse(pasteLink.trim())
                    if (link == null) {
                        scope.launch { snackbar.showSnackbar(pasteFailedMessage) }
                        return@Button
                    }
                    repository.applyLink(link, enable = true)
                    enabled = true
                    url = link.url
                    secret = link.secret
                    certSha256 = link.certSha256.orEmpty()
                    pasteLink = ""
                    scope.launch { snackbar.showSnackbar(savedMessage) }
                },
                enabled = pasteLink.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_paste_apply"),
            ) {
                Text(stringResource(R.string.relay_paste_apply))
            }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("relay_status_row"),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (relayReady) VoxRelayReady else VoxRelayIdle),
                )
                Text(
                    text = if (relayReady) {
                        stringResource(R.string.relay_status_connected)
                    } else {
                        stringResource(R.string.relay_status_disconnected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (relayReady) VoxRelayReady else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { save(enableIfCredentials = true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_save"),
            ) {
                Text(stringResource(R.string.relay_save))
            }
        }
    }
}
