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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.audio.VoxSensitivity
import com.nblaisot.voxcrew.lanlink.PeerLink
import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.MemberAvailability
import com.nblaisot.voxcrew.ui.permissions.RequestAppPermissions
import com.nblaisot.voxcrew.ui.theme.VoxOrangeLight
import com.nblaisot.voxcrew.ui.theme.VoxPttActive
import com.nblaisot.voxcrew.ui.theme.VoxPttIdle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToAbout: () -> Unit,
    onSignOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }

    RequestAppPermissions(onResult = viewModel::onPermissionsResult)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
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
                            text = { Text("Déconnexion") },
                            onClick = {
                                menuExpanded = false
                                viewModel.signOut()
                                onSignOut()
                            },
                        )
                    }
                },
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            "VoxCrew",
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
                Text(
                    banner,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "Participants",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Toucher pour activer/désactiver · Appui long = envoi privé",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.crew, key = { it.uid }) { member ->
                    val metrics = state.peerMetrics[member.uid]
                    CrewMemberRow(
                        member = member,
                        isReceivingAudio = state.receivingAudioFromUid == member.uid,
                        rttMs = metrics?.rttMs,
                        pathLabel = metrics?.pathLabel,
                        linkState = metrics?.linkState,
                        backlogMs = metrics?.backlogMs?.takeIf { it > 0L },
                        onClick = { viewModel.toggleRecipient(member) },
                        onLongClick = { viewModel.soloRecipient(member) },
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
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
            if (state.voxEnabled) {
                VoxSensitivitySlider(
                    sensitivity = state.voxSensitivity,
                    onSensitivityChange = viewModel::setVoxSensitivity,
                )
            }

            val pttColor = if (state.isTransmitting) VoxPttActive else VoxPttIdle
            // Deliberately NOT a material Button: its internal clickable consumes
            // the down event, which prevents detectTapGestures.onPress from ever
            // firing (press-and-hold would be dead).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(pttColor)
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
                Text(
                    when {
                        state.voxEnabled && state.isTransmitting -> "Vox — transmission…"
                        state.voxEnabled -> "Vox actif — en écoute"
                        state.isTransmitting -> "Transmission…"
                        else -> "PTT — maintenir pour parler"
                    },
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
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
                "Sensibilité Vox",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                voxSensitivityLabel(sensitivity),
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

private fun voxSensitivityLabel(level: Int): String = when (level) {
    1 -> "Faible — voix seule"
    2 -> "Réduite"
    3 -> "Moyenne"
    4 -> "Élevée"
    else -> "Maximale"
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
            .combinedClickable(
                onClick = onClick,
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
                        contentDescription = if (member.isActiveRecipient) "Destinataire actif" else "Destinataire inactif",
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
                            text = member.email,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = inactiveAlpha),
                        )
                        if (pathLabel != null) {
                            Text(
                                text = pathLabel,
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
private fun AvailabilityIcon(
    availability: MemberAvailability,
    rttMs: Long? = null,
    inactiveAlpha: Float = 1f,
) {
    val (icon, tint, desc) = when (availability) {
        MemberAvailability.ONLINE_LOCAL -> Triple(Icons.Filled.Wifi, MaterialTheme.colorScheme.primary, "Local")
        MemberAvailability.ONLINE_CLOUD -> Triple(Icons.Filled.Cloud, MaterialTheme.colorScheme.tertiary, "Cloud")
        MemberAvailability.OFFLINE -> Triple(Icons.Filled.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, "Hors ligne")
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
