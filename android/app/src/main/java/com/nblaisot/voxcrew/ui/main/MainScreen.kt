package com.nblaisot.voxcrew.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.audio.AudioPermissionIssue
import com.nblaisot.voxcrew.audio.CaptureInputKind
import com.nblaisot.voxcrew.audio.ManualRouteStatus
import com.nblaisot.voxcrew.audio.VoxSensitivity
import com.nblaisot.voxcrew.lanlink.PathLabels
import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.MemberAvailability
import com.nblaisot.voxcrew.ui.permissions.RequestAppPermissions
import com.nblaisot.voxcrew.ui.theme.VoxOrangeLight
import com.nblaisot.voxcrew.ui.theme.VoxPttActive
import com.nblaisot.voxcrew.ui.theme.VoxPttIdle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToAbout: () -> Unit,
    onSignOut: () -> Unit,
    onQuitApplication: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var menuExpanded by remember { mutableStateOf(false) }
    var audioMenuExpanded by remember { mutableStateOf(false) }
    var memberPendingForget by remember { mutableStateOf<CrewMember?>(null) }

    RequestAppPermissions(onResult = viewModel::onPermissionsResult)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onMicrophonePermissionResult,
    )
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onBluetoothPermissionResult,
    )

    state.permissionPrompt?.let { issue ->
        val isBluetoothIssue = issue == AudioPermissionIssue.BLUETOOTH_CONNECT
        AlertDialog(
            onDismissRequest = viewModel::dismissPermissionPrompt,
            title = {
                Text(
                    stringResource(
                        if (isBluetoothIssue) R.string.permission_bluetooth_title
                        else R.string.permission_mic_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (isBluetoothIssue) R.string.permission_bluetooth_body
                        else R.string.permission_mic_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBluetoothIssue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) {
                    Text(stringResource(R.string.permission_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPermissionPrompt) {
                    Text(stringResource(R.string.permission_later))
                }
            },
        )
    }

    memberPendingForget?.let { member ->
        AlertDialog(
            onDismissRequest = { memberPendingForget = null },
            title = { Text(stringResource(R.string.forget_title, member.displayName)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetMember(member)
                        memberPendingForget = null
                    },
                ) {
                    Text(stringResource(R.string.forget_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { memberPendingForget = null }) {
                    Text(stringResource(R.string.forget_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("main_menu"),
                    ) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.menu_content_description))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            onClick = {
                                menuExpanded = false
                                onNavigateToAbout()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.menu_change_name))
                            },
                            onClick = {
                                menuExpanded = false
                                viewModel.signOut()
                                onSignOut()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_quit)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.quitApplication()
                                onQuitApplication()
                            },
                        )
                    }
                },
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    val routeMenuEnabled = state.audioRouteStatus != ManualRouteStatus.STARTING &&
                        state.audioRouteStatus != ManualRouteStatus.REQUESTING
                    IconButton(
                        enabled = routeMenuEnabled,
                        onClick = { audioMenuExpanded = true },
                        modifier = Modifier.testTag("main_audio_route"),
                    ) {
                        Icon(
                            imageVector = audioRouteIcon(state.pttMicIconKind),
                            contentDescription = stringResource(
                                R.string.audio_route_content_description,
                                state.selectedAudioRoute.name,
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = audioMenuExpanded,
                        onDismissRequest = { audioMenuExpanded = false },
                    ) {
                        state.audioRouteChoices.forEach { choice ->
                            DropdownMenuItem(
                                enabled = routeMenuEnabled,
                                text = { Text(choice.name) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = audioRouteIcon(choice.inputKind),
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = if (choice.key == state.selectedAudioRoute.key) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.audio_route_selected),
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    audioMenuExpanded = false
                                    viewModel.selectAudioRoute(choice.key)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.bannerMessage?.let { banner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        banner,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.showAudioRetry) {
                        TextButton(onClick = viewModel::retryAudio) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.participants_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.participants_gesture_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Column (not LazyColumn): MVP crews are small, and landscape tablets
            // otherwise only compose ~1 row above the PTT — demo peers vanish from a11y.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.crew.forEach { member ->
                    val metrics = state.peerMetrics[member.uid]
                    CrewMemberRow(
                        member = member,
                        isReceivingAudio = state.receivingAudioFromUid == member.uid,
                        rttMs = metrics?.rttMs,
                        pathLabel = metrics?.pathLabel,
                        linkState = metrics?.linkState,
                        backlogMs = metrics?.backlogMs?.takeIf { it > 0L },
                        onClick = { viewModel.toggleRecipient(member) },
                        onDoubleClick = { viewModel.soloRecipient(member) },
                        onLongClick = { memberPendingForget = member },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vox", color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = state.voxEnabled,
                    onCheckedChange = viewModel::setVoxEnabled,
                    modifier = Modifier.testTag("main_vox_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
            if (state.voxEnabled && !landscape) {
                VoxSensitivitySlider(
                    sensitivity = state.voxSensitivity,
                    onSensitivityChange = viewModel::setVoxSensitivity,
                )
            }

            val pttPreparing = !state.voxEnabled && !state.pttEnabled
            val pttColor = when {
                state.isTransmitting -> VoxPttActive
                pttPreparing -> MaterialTheme.colorScheme.surfaceVariant
                else -> VoxPttIdle
            }
            val pttContentColor = if (pttPreparing) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
            val micIcon = audioRouteIcon(state.pttMicIconKind)
            // Deliberately NOT a material Button: its internal clickable consumes
            // the down event, which prevents detectTapGestures.onPress from ever
            // firing (press-and-hold would be dead).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (landscape) 64.dp else 140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(pttColor)
                    .testTag("main_ptt")
                    .pointerInput(state.pttEnabled) {
                        if (!state.pttEnabled) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.pttPress()
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    viewModel.pttRelease()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = micIcon,
                        contentDescription = null,
                        tint = pttContentColor,
                        modifier = Modifier
                            .size(28.dp)
                            .padding(end = 8.dp),
                    )
                    Text(
                        when {
                            state.audioRoutePending ->
                                stringResource(
                                    R.string.audio_connecting,
                                    state.selectedAudioRoute.name,
                                )
                            state.audioRouteStatus == ManualRouteStatus.DIVERGED ||
                                state.audioRouteStatus == ManualRouteStatus.UNAVAILABLE ||
                                state.audioRouteStatus == ManualRouteStatus.FAILED ->
                                stringResource(R.string.audio_choose_output)
                            state.voxEnabled && state.isTransmitting ->
                                stringResource(R.string.vox_transmitting)
                            state.voxEnabled -> stringResource(R.string.vox_listening)
                            state.isTransmitting -> stringResource(R.string.ptt_transmitting)
                            else -> stringResource(R.string.ptt_hold_to_talk)
                        },
                        color = pttContentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun audioRouteIcon(kind: CaptureInputKind): ImageVector = when (kind) {
    CaptureInputKind.BUILTIN -> Icons.Filled.PhoneAndroid
    CaptureInputKind.BLUETOOTH -> Icons.Filled.Bluetooth
    CaptureInputKind.USB -> Icons.Filled.Usb
    CaptureInputKind.WIRED -> Icons.Filled.Headset
}

/**
 * VOX sensitivity control: low end only transmits on confident, sustained speech
 * (most robust to outdoor wind/traffic noise), high end triggers more easily on
 * quieter voices at the cost of more false positives. See [VoxSensitivity].
 */
@Composable
private fun VoxSensitivitySlider(sensitivity: Int, onSensitivityChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.vox_sensitivity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(voxSensitivityLabelRes(sensitivity)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sensitivity.toFloat(),
            onValueChange = { onSensitivityChange(it.roundToInt()) },
            valueRange = VoxSensitivity.MIN.toFloat()..VoxSensitivity.MAX.toFloat(),
            steps = VoxSensitivity.MAX - VoxSensitivity.MIN - 1,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

private fun voxSensitivityLabelRes(level: Int): Int = when (level) {
    1 -> R.string.vox_sensitivity_1
    2 -> R.string.vox_sensitivity_2
    3 -> R.string.vox_sensitivity_3
    4 -> R.string.vox_sensitivity_4
    else -> R.string.vox_sensitivity_5
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrewMemberRow(
    member: CrewMember,
    isReceivingAudio: Boolean,
    rttMs: Long?,
    pathLabel: String?,
    linkState: PeerLink.LinkState?,
    backlogMs: Long?,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val inactiveAlpha = if (member.isActiveRecipient) 1f else 0.45f
    val bg = when {
        isReceivingAudio -> audioShimmerBrush()
        member.isActiveRecipient -> Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        else -> Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ),
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("crew_${member.displayName.lowercase()}")
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(16.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = if (member.isActiveRecipient) {
                            stringResource(R.string.included_in_group)
                        } else {
                            stringResource(R.string.muted_not_included)
                        },
                        tint = if (member.isActiveRecipient) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    AvailabilityIcon(
                        availability = displayAvailability(member.availability, pathLabel, linkState),
                        rttMs = if (linkState is PeerLink.LinkState.Connected) rttMs else null,
                        inactiveAlpha = inactiveAlpha,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = member.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = inactiveAlpha),
                        )
                        if (pathLabel != null) {
                            Text(
                                text = localizedPathLabel(pathLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = inactiveAlpha),
                            )
                        }
                    }
                }
                if (backlogMs != null) {
                    BacklogGauge(backlogMs = backlogMs, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/**
 * Fills proportionally to audio time buffered on the sender because the peer hasn't
 * acknowledged it yet (see [com.nblaisot.voxcrew.lanlink.PeerLink.backlogMs]). Frames
 * may be evicted by age ([com.nblaisot.voxcrew.lanlink.SendBuffer.DEFAULT_MAX_AGE_MS])
 * or byte cap. Caps its visual fill at [BACKLOG_GAUGE_MAX_MS] (10 s).
 */
@Composable
private fun BacklogGauge(backlogMs: Long, modifier: Modifier = Modifier) {
    val fraction = (backlogMs.toFloat() / BACKLOG_GAUGE_MAX_MS).coerceIn(0f, 1f)
    val color = when {
        fraction >= 0.7f -> MaterialTheme.colorScheme.error
        fraction >= 0.3f -> VoxOrangeLight
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

private const val BACKLOG_GAUGE_MAX_MS = 10_000f

@Composable
private fun audioShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "audioShimmer")
    val offset = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    return Brush.linearGradient(
        colors = listOf(container, VoxOrangeLight.copy(alpha = 0.55f), primary.copy(alpha = 0.35f), container),
        start = Offset(offset.value, 0f),
        end = Offset(offset.value + 400f, 400f),
    )
}

@Composable
private fun localizedPathLabel(pathLabel: String): String = when (pathLabel) {
    PathLabels.LOCAL -> stringResource(R.string.path_local)
    PathLabels.VPN -> stringResource(R.string.path_vpn)
    else -> pathLabel
}

@Composable
private fun AvailabilityIcon(
    availability: MemberAvailability,
    rttMs: Long? = null,
    inactiveAlpha: Float = 1f,
) {
    val (icon, tint, desc) = when (availability) {
        MemberAvailability.ONLINE_LOCAL -> Triple(
            Icons.Filled.Wifi,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.path_local),
        )
        MemberAvailability.ONLINE_OVERLAY -> Triple(
            Icons.Filled.VpnLock,
            MaterialTheme.colorScheme.secondary,
            stringResource(R.string.path_vpn),
        )
        MemberAvailability.OFFLINE -> Triple(
            Icons.Filled.CloudOff,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.availability_offline),
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 28.dp),
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
        if (rttMs != null) {
            Text(
                text = "${rttMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = inactiveAlpha),
                maxLines = 1,
            )
        }
    }
}
