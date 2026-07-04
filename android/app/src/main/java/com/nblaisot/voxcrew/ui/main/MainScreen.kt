package com.nblaisot.voxcrew.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.BuildConfig
import com.nblaisot.voxcrew.roster.CrewMember
import com.nblaisot.voxcrew.roster.MemberAvailability
import com.nblaisot.voxcrew.ui.permissions.RequestAppPermissions
import com.nblaisot.voxcrew.ui.theme.VoxOrangeLight
import com.nblaisot.voxcrew.ui.theme.VoxPttActive
import com.nblaisot.voxcrew.ui.theme.VoxPttDisabled
import com.nblaisot.voxcrew.ui.theme.VoxPttIdle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSignOut: () -> Unit,
    onOpenDebug: () -> Unit,
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
                            text = { Text("Déconnexion") },
                            onClick = {
                                menuExpanded = false
                                viewModel.signOut()
                                onSignOut()
                            },
                        )
                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(
                                text = { Text("Outils développeur") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenDebug()
                                },
                            )
                        }
                    }
                },
                title = {
                    Text(
                        "VoxCrew",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                actions = {
                    Text(
                        state.statusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp),
                    )
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
                "Équipiers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.crew, key = { it.uid }) { member ->
                    CrewMemberRow(
                        member = member,
                        isReceivingAudio = state.receivingAudioFromUid == member.uid,
                        onClick = { viewModel.selectCrewMember(member) },
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

            val pttColor = when {
                !state.pttEnabled -> VoxPttDisabled
                state.isTransmitting -> VoxPttActive
                else -> VoxPttIdle
            }
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
                    if (state.pttEnabled) {
                        if (state.isTransmitting) "Transmission…" else "PTT — maintenir pour parler"
                    } else {
                        "PTT désactivé (Vox actif)"
                    },
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CrewMemberRow(
    member: CrewMember,
    isReceivingAudio: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        isReceivingAudio -> audioShimmerBrush()
        member.isSelected -> Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        else -> Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvailabilityIcon(member.availability)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.email,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

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
private fun AvailabilityIcon(availability: MemberAvailability) {
    val (icon, tint, desc) = when (availability) {
        MemberAvailability.ONLINE_LOCAL -> Triple(Icons.Filled.Wifi, MaterialTheme.colorScheme.primary, "Local")
        MemberAvailability.ONLINE_CLOUD -> Triple(Icons.Filled.Cloud, MaterialTheme.colorScheme.tertiary, "Cloud")
        MemberAvailability.OFFLINE -> Triple(Icons.Filled.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, "Hors ligne")
    }
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = desc, tint = tint)
    }
}
