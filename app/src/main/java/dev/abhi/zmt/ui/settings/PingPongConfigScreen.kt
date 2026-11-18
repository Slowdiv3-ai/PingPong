package dev.abhi.zmt.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abhi.zmt.audio.PanConfig
import kotlin.math.roundToInt

// Monospace font for that authentic terminal aesthetic
val TuiFont = FontFamily.Monospace

@Composable
fun PingPongConfigScreen() {
    // Local UI state that syncs with the global PanConfig singleton
    var isFreqOn by remember { mutableStateOf(PanConfig.isFreqPanEnabled) }
    var isVolOn by remember { mutableStateOf(PanConfig.isVolPanEnabled) }
    var preset by remember { mutableStateOf(PanConfig.currentPreset) }
    var speed by remember { mutableStateOf(PanConfig.speedMultiplier) }

    // Sync UI changes back to the global config instantly
    LaunchedEffect(isFreqOn) { PanConfig.isFreqPanEnabled = isFreqOn }
    LaunchedEffect(isVolOn) { PanConfig.isVolPanEnabled = isVolOn }
    LaunchedEffect(preset) { PanConfig.currentPreset = preset }
    LaunchedEffect(speed) { PanConfig.speedMultiplier = speed }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "pingpong dsp config",
            fontFamily = TuiFont,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Frequency Pan Toggle
        TuiToggleRow(
            label = "frequency pan",
            isEnabled = isFreqOn,
            onToggle = { isFreqOn = it }
        )

        // Volume Pan Toggle
        TuiToggleRow(
            label = "volume pan",
            isEnabled = isVolOn,
            onToggle = { isVolOn = it }
        )

        // Preset Selector
        TuiPresetRow(
            label = "preset",
            preset = preset,
            onCycle = { 
                val next = when (preset) {
                    PanConfig.Preset.GENTLE -> PanConfig.Preset.SMOOTH
                    PanConfig.Preset.SMOOTH -> PanConfig.Preset.WAVE
                    PanConfig.Preset.WAVE -> PanConfig.Preset.GENTLE
                }
                preset = next 
            }
        )

        // Speed Slider
        TuiSpeedRow(
            label = "lfo speed",
            speed = speed,
            onSpeedChange = { speed = it }
        )
        
        Text(
            text = "tip: changes apply instantly to playing audio.\ntry 'wave' preset on a track with heavy bass and crisp highs.",
            fontFamily = TuiFont,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}

@Composable
fun TuiToggleRow(label: String, isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isEnabled) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontFamily = TuiFont, fontSize = 16.sp)
        Text(
            text = if (isEnabled) "[ on ]" else "[off]",
            fontFamily = TuiFont,
            fontSize = 16.sp,
            color = if (isEnabled) MaterialTheme.colorScheme.primary 
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TuiPresetRow(label: String, preset: PanConfig.Preset, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCycle() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontFamily = TuiFont, fontSize = 16.sp)
        Text(
            text = "[ ${preset.name.lowercase()} ]",
            fontFamily = TuiFont,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun TuiSpeedRow(label: String, speed: Float, onSpeedChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontFamily = TuiFont, fontSize = 16.sp)
            Text(text = String.format("%.1fx", speed), fontFamily = TuiFont, fontSize = 16.sp)
        }
        
        Slider(
            value = speed,
            onValueChange = { onSpeedChange(it) },
            valueRange = 0.25f..4.0f,
            steps = 14, // Creates 15 discrete steps (0.25, 0.5, 0.75 ... 4.0)
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}
