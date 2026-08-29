package com.nblaisot.voxcrew.ui.audio

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.lanlink.AudioCapture
import com.nblaisot.voxcrew.lanlink.JitterBufferSettings
import com.nblaisot.voxcrew.lanlink.LanIntercomEngine
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    lanEngine: LanIntercomEngine,
    onBack: () -> Unit,
) {
    val baseMs by lanEngine.jitterBaseMs.collectAsState()
    val maxMs by lanEngine.jitterMaxMs.collectAsState()
    val adaptiveEnabled by lanEngine.jitterAdaptiveEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audio_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.audio_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            JitterDelaySlider(
                label = stringResource(R.string.audio_settings_jitter_base),
                valueMs = baseMs,
                minMs = JitterBufferSettings.MIN_BASE_DELAY_MS,
                maxMs = JitterBufferSettings.MAX_BASE_DELAY_MS,
                onValueChange = lanEngine::setJitterBaseMs,
                modifier = Modifier.testTag("jitter_base_slider"),
            )
            JitterDelaySlider(
                label = stringResource(R.string.audio_settings_jitter_max),
                valueMs = maxMs,
                minMs = JitterBufferSettings.MIN_MAX_ADAPTIVE_DELAY_MS,
                maxMs = JitterBufferSettings.MAX_MAX_ADAPTIVE_DELAY_MS,
                onValueChange = lanEngine::setJitterMaxMs,
                enabled = adaptiveEnabled,
                modifier = Modifier.testTag("jitter_max_slider"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.audio_settings_jitter_adaptive),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.audio_settings_jitter_adaptive_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = adaptiveEnabled,
                    onCheckedChange = lanEngine::setJitterAdaptiveEnabled,
                    modifier = Modifier.testTag("jitter_adaptive_switch"),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun JitterDelaySlider(
    label: String,
    valueMs: Int,
    minMs: Int,
    maxMs: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val stepMs = AudioCapture.FRAME_MS
    val steps = ((maxMs - minMs) / stepMs) - 1
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.audio_settings_delay_ms, valueMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = valueMs.toFloat(),
            onValueChange = { raw ->
                val snapped = (raw.roundToInt() / stepMs) * stepMs
                onValueChange(snapped.coerceIn(minMs, maxMs))
            },
            enabled = enabled,
            valueRange = minMs.toFloat()..maxMs.toFloat(),
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}
