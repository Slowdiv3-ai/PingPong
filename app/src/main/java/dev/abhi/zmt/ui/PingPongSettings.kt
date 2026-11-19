package dev.abhi.zmt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.audio.RawAudioPlayer

@Composable
fun PingPongSettingsCard(
    player: RawAudioPlayer,
    modifier: Modifier = Modifier
) {
    // Local UI state
    var selectedMode by remember { mutableStateOf(player.currentMode) }
    var speed by remember { mutableStateOf(player.lfoFreq) }
    var intensity by remember { mutableStateOf(player.depth) }

    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "PingPong DSP Engine",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            // --- MODE SELECTION (Mutually Exclusive) ---
            Text("Processing Mode:", style = MaterialTheme.typography.bodySmall)
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedMode == RawAudioPlayer.PanMode.OFF,
                    onClick = { 
                        selectedMode = RawAudioPlayer.PanMode.OFF
                        player.currentMode = RawAudioPlayer.PanMode.OFF 
                    }
                )
                Text("Off (Bypass)", modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedMode == RawAudioPlayer.PanMode.VOLUME,
                    onClick = { 
                        selectedMode = RawAudioPlayer.PanMode.VOLUME
                        player.currentMode = RawAudioPlayer.PanMode.VOLUME 
                    }
                )
                Text("Volume Pan", modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedMode == RawAudioPlayer.PanMode.FREQUENCY,
                    onClick = { 
                        selectedMode = RawAudioPlayer.PanMode.FREQUENCY
                        player.currentMode = RawAudioPlayer.PanMode.FREQUENCY 
                    }
                )
                Text("Frequency Pan", modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- ASCII STYLE SLIDERS ---
            if (selectedMode != RawAudioPlayer.PanMode.OFF) {
                
                // Speed Slider
                Text("LFO Speed:", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = speed,
                    onValueChange = { 
                        speed = it
                        player.lfoFreq = it 
                    },
                    valueRange = 0.1f..2.0f,
                    steps = 18, // Creates discrete steps for the ASCII bar
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary)
                )
                // ASCII UI Readout
                Text(
                    text = generateAsciiBar(speed, 0.1f, 2.0f, 20) + " ${String.format("%.2f", speed)} Hz",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Intensity Slider
                Text("Intensity / Depth:", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = intensity,
                    onValueChange = { 
                        intensity = it
                        player.depth = it 
                    },
                    valueRange = 0.0f..1.0f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )
                // ASCII UI Readout
                Text(
                    text = generateAsciiBar(intensity, 0.0f, 1.0f, 20) + " ${String.format("%.0f", intensity * 100)}%",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Helper function to generate the [|||||.....] ASCII bar
private fun generateAsciiBar(value: Float, min: Float, max: Float, length: Int): String {
    val percentage = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val filled = (percentage * length).toInt()
    val empty = length - filled
    
    val filledChar = "█" // Or use "|" for a more terminal look
    val emptyChar = "░" // Or use "."
    
    return "[" + filledChar.repeat(filled) + emptyChar.repeat(empty) + "]"
}
