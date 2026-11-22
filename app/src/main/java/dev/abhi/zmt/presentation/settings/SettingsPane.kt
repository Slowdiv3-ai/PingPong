package dev.abhi.zmt.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.abhi.zmt.BuildConfig
import dev.abhi.zmt.R
import dev.abhi.zmt.audio.RawAudioPlayer
import dev.abhi.zmt.core.common.Caption
import dev.abhi.zmt.core.common.TuiKey
import dev.abhi.zmt.core.common.tuiClickable
import dev.abhi.zmt.domain.model.DmtSettings
import dev.abhi.zmt.domain.model.SourceMode
import dev.abhi.zmt.presentation.player.DmtAction
import dev.abhi.zmt.presentation.player.DmtState
import dev.abhi.zmt.presentation.player.DmtView
import dev.abhi.zmt.presentation.player.EQ_PRESETS
import dev.abhi.zmt.ui.theme.TuiAccent
import dev.abhi.zmt.ui.theme.TuiBg
import dev.abhi.zmt.ui.theme.TuiDim
import dev.abhi.zmt.ui.theme.TuiFaint
import dev.abhi.zmt.ui.theme.TuiFg
import dev.abhi.zmt.ui.theme.TuiLine
import dev.abhi.zmt.ui.theme.TuiRaised
import dev.abhi.zmt.ui.theme.toColor

private val COVER_COLS_STEPS = listOf(48, 64, 80, 96)
fun nextCoverCols(current: Int): Int = COVER_COLS_STEPS[(COVER_COLS_STEPS.indexOf(current) + 1).mod(COVER_COLS_STEPS.size)]

@Composable
fun SettingsPane(state: DmtState, dispatch: (DmtAction) -> Unit, rawPlayer: RawAudioPlayer? = null) {
    val settings = state.settings
    val on = stringResource(R.string.on); val off = stringResource(R.string.off)

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Caption(stringResource(R.string.section_playback))
        SettingRow(label = stringResource(R.string.set_crossfade), value = settings.crossfadeDuration.label) { dispatch(DmtAction.Config(settings.copy(crossfadeDuration = settings.crossfadeDuration.next()))) }
        SettingRow(label = stringResource(R.string.set_gapless), value = if (settings.gapless) on else off) { dispatch(DmtAction.Config(settings.copy(gapless = !settings.gapless))) }
        SettingRow(label = stringResource(R.string.set_normalize), value = if (settings.normalizeVolume) on else off) { dispatch(DmtAction.Config(settings.copy(normalizeVolume = !settings.normalizeVolume))) }
        SettingRow(label = stringResource(R.string.set_stop_on_dismiss), value = if (settings.stopOnDismiss) on else off) { dispatch(DmtAction.Config(settings.copy(stopOnDismiss = !settings.stopOnDismiss))) }
        SettingRow(label = stringResource(R.string.set_sleep_fade), value = settings.sleepFade.label) { dispatch(DmtAction.Config(settings.copy(sleepFade = settings.sleepFade.next()))) }

        Caption(stringResource(R.string.section_lyrics))
        SettingRow(label = stringResource(R.string.set_lyrics_script), value = stringResource(if (settings.romanizedLyrics) R.string.lyrics_script_romanized else R.string.lyrics_script_original)) { dispatch(DmtAction.Config(settings.copy(romanizedLyrics = !settings.romanizedLyrics))) }

        Caption(stringResource(R.string.section_display))
        SettingRow(label = stringResource(R.string.set_wave), value = if (settings.wave) on else off) { dispatch(DmtAction.Config(settings.copy(wave = !settings.wave))) }
        SettingRow(label = stringResource(R.string.set_detail), value = pluralStringResource(R.plurals.set_detail_value, settings.cols, settings.cols)) { dispatch(DmtAction.Config(settings.copy(cols = nextCoverCols(settings.cols)))) }
        SettingRow(label = stringResource(R.string.set_raw), value = if (settings.rawArt) on else off) { dispatch(DmtAction.Config(settings.copy(rawArt = !settings.rawArt))) }
        SettingRow(label = stringResource(R.string.set_specs), value = if (settings.listSpecs) on else off) { dispatch(DmtAction.Config(settings.copy(listSpecs = !settings.listSpecs))) }
        AccentRow(settings = settings, dispatch = dispatch)

// ==========================================
        // --- PingPong DSP Section ---
        // ==========================================
        Caption("pingpong dsp")
        
        // FIX 1: Changed 'val' to 'var' so the UI can actually update when clicked
        var currentMode by remember { mutableStateOf(rawPlayer?.currentMode ?: RawAudioPlayer.PanMode.OFF) }
        var currentPreset by remember { mutableStateOf(rawPlayer?.currentPreset ?: RawAudioPlayer.PanPreset.VOL_SMOOTH) }

        val modeLabel = when (currentMode) {
            RawAudioPlayer.PanMode.OFF -> "off"
            RawAudioPlayer.PanMode.VOLUME -> "volume"
            RawAudioPlayer.PanMode.FREQUENCY -> "frequency"
            RawAudioPlayer.PanMode.TREMOLO -> "tremolo"
            RawAudioPlayer.PanMode.LOFI -> "lo-fi"
            RawAudioPlayer.PanMode.TELEPHONE -> "telephone"
        }

        SettingRow(label = "dsp mode", value = modeLabel) {
            rawPlayer?.let { player ->
                player.currentMode = when (player.currentMode) {
                    RawAudioPlayer.PanMode.OFF -> RawAudioPlayer.PanMode.VOLUME
                    RawAudioPlayer.PanMode.VOLUME -> RawAudioPlayer.PanMode.FREQUENCY
                    RawAudioPlayer.PanMode.FREQUENCY -> RawAudioPlayer.PanMode.TREMOLO
                    RawAudioPlayer.PanMode.TREMOLO -> RawAudioPlayer.PanMode.LOFI
                    RawAudioPlayer.PanMode.LOFI -> RawAudioPlayer.PanMode.TELEPHONE
                    RawAudioPlayer.PanMode.TELEPHONE -> RawAudioPlayer.PanMode.OFF
                }
                // FIX 2: Force the UI to refresh instantly
                currentMode = player.currentMode 
            }
        }

        // FIX 3: Use the local 'currentPreset' variable instead of reading from the player directly
        val presetLabel = when (currentPreset) {
            RawAudioPlayer.PanPreset.VOL_SMOOTH -> "smooth"
            RawAudioPlayer.PanPreset.VOL_GENTLE -> "gentle"
            RawAudioPlayer.PanPreset.VOL_CHOP -> "chop"
            RawAudioPlayer.PanPreset.FREQ_HELICOPTER -> "helicopter"
            RawAudioPlayer.PanPreset.FREQ_DEEP_BASS -> "deep bass"
            RawAudioPlayer.PanPreset.FREQ_HI_HATS -> "hi-hats"
            RawAudioPlayer.PanPreset.FREQ_UNDERWATER -> "underwater"
            RawAudioPlayer.PanPreset.TREMOLO_SINE -> "sine wave"
            RawAudioPlayer.PanPreset.TREMOLO_SQUARE -> "square wave"
            RawAudioPlayer.PanPreset.LOFI_8BIT -> "8-bit"
            RawAudioPlayer.PanPreset.LOFI_4BIT -> "4-bit"
            RawAudioPlayer.PanPreset.TEL_VINTAGE -> "vintage"
            RawAudioPlayer.PanPreset.TEL_RADIO -> "radio"
            else -> "unknown"
        }

        SettingRow(label = "dsp preset", value = presetLabel) {
            rawPlayer?.let { player ->
                player.currentPreset = when (player.currentMode) {
                    RawAudioPlayer.PanMode.VOLUME -> when (player.currentPreset) {
                        RawAudioPlayer.PanPreset.VOL_SMOOTH -> RawAudioPlayer.PanPreset.VOL_GENTLE
                        RawAudioPlayer.PanPreset.VOL_GENTLE -> RawAudioPlayer.PanPreset.VOL_CHOP
                        RawAudioPlayer.PanPreset.VOL_CHOP -> RawAudioPlayer.PanPreset.VOL_SMOOTH
                        else -> RawAudioPlayer.PanPreset.VOL_SMOOTH

// ==========================================
        // --- PingPong DSP Section ---
        // ==========================================
        Caption("pingpong dsp")
        
        var currentMode by remember { mutableStateOf(rawPlayer?.currentMode ?: RawAudioPlayer.PanMode.OFF) }
        var currentPreset by remember { mutableStateOf(rawPlayer?.currentPreset ?: RawAudioPlayer.PanPreset.VOL_SMOOTH) }

        val modeLabel = when (currentMode) {
            RawAudioPlayer.PanMode.OFF -> "off"
            RawAudioPlayer.PanMode.VOLUME -> "volume"
            RawAudioPlayer.PanMode.FREQUENCY -> "frequency"
            RawAudioPlayer.PanMode.TREMOLO -> "tremolo"
            RawAudioPlayer.PanMode.LOFI -> "lo-fi"
            RawAudioPlayer.PanMode.TELEPHONE -> "telephone"
            RawAudioPlayer.PanMode.CUSTOM -> "custom" // NEW
        }

        SettingRow(label = "dsp mode", value = modeLabel) {
            rawPlayer?.let { player ->
                player.currentMode = when (player.currentMode) {
                    RawAudioPlayer.PanMode.OFF -> RawAudioPlayer.PanMode.VOLUME
                    RawAudioPlayer.PanMode.VOLUME -> RawAudioPlayer.PanMode.FREQUENCY
                    RawAudioPlayer.PanMode.FREQUENCY -> RawAudioPlayer.PanMode.TREMOLO
                    RawAudioPlayer.PanMode.TREMOLO -> RawAudioPlayer.PanMode.LOFI
                    RawAudioPlayer.PanMode.LOFI -> RawAudioPlayer.PanMode.TELEPHONE
                    RawAudioPlayer.PanMode.TELEPHONE -> RawAudioPlayer.PanMode.CUSTOM
                    RawAudioPlayer.PanMode.CUSTOM -> RawAudioPlayer.PanMode.OFF
                }
                currentMode = player.currentMode 
            }
        }

        // Only show preset selector if NOT in custom mode
        if (currentMode != RawAudioPlayer.PanMode.CUSTOM) {
            val presetLabel = when (currentPreset) {
                RawAudioPlayer.PanPreset.VOL_SMOOTH -> "smooth"
                RawAudioPlayer.PanPreset.VOL_GENTLE -> "gentle"
                RawAudioPlayer.PanPreset.VOL_CHOP -> "chop"
                RawAudioPlayer.PanPreset.FREQ_HELICOPTER -> "helicopter"
                RawAudioPlayer.PanPreset.FREQ_DEEP_BASS -> "deep bass"
                RawAudioPlayer.PanPreset.FREQ_HI_HATS -> "hi-hats"
                RawAudioPlayer.PanPreset.FREQ_UNDERWATER -> "underwater"
                RawAudioPlayer.PanPreset.TREMOLO_SINE -> "sine wave"
                RawAudioPlayer.PanPreset.TREMOLO_SQUARE -> "square wave"
                RawAudioPlayer.PanPreset.LOFI_8BIT -> "8-bit"
                RawAudioPlayer.PanPreset.LOFI_4BIT -> "4-bit"
                RawAudioPlayer.PanPreset.TEL_VINTAGE -> "vintage"
                RawAudioPlayer.PanPreset.TEL_RADIO -> "radio"
                else -> "unknown"
            }

            SettingRow(label = "dsp preset", value = presetLabel) {
                rawPlayer?.let { player ->
                    player.currentPreset = when (player.currentMode) {
                        RawAudioPlayer.PanMode.VOLUME -> when (player.currentPreset) {
                            RawAudioPlayer.PanPreset.VOL_SMOOTH -> RawAudioPlayer.PanPreset.VOL_GENTLE
                            RawAudioPlayer.PanPreset.VOL_GENTLE -> RawAudioPlayer.PanPreset.VOL_CHOP
                            RawAudioPlayer.PanPreset.VOL_CHOP -> RawAudioPlayer.PanPreset.VOL_SMOOTH
                            else -> RawAudioPlayer.PanPreset.VOL_SMOOTH
                        }
                        RawAudioPlayer.PanMode.FREQUENCY -> when (player.currentPreset) {
                            RawAudioPlayer.PanPreset.FREQ_HELICOPTER -> RawAudioPlayer.PanPreset.FREQ_DEEP_BASS
                            RawAudioPlayer.PanPreset.FREQ_DEEP_BASS -> RawAudioPlayer.PanPreset.FREQ_HI_HATS
                            RawAudioPlayer.PanPreset.FREQ_HI_HATS -> RawAudioPlayer.PanPreset.FREQ_UNDERWATER
// ==========================================
        // --- PingPong DSP Section ---
        // ==========================================
        Caption("pingpong dsp")
        
        var currentMode by remember { mutableStateOf(rawPlayer?.currentMode ?: RawAudioPlayer.PanMode.OFF) }
        var currentPreset by remember { mutableStateOf(rawPlayer?.currentPreset ?: RawAudioPlayer.PanPreset.VOL_SMOOTH) }

        val modeLabel = when (currentMode) {
            RawAudioPlayer.PanMode.OFF -> "off"
            RawAudioPlayer.PanMode.VOLUME -> "volume"
            RawAudioPlayer.PanMode.FREQUENCY -> "frequency"
            RawAudioPlayer.PanMode.TREMOLO -> "tremolo"
            RawAudioPlayer.PanMode.LOFI -> "lo-fi"
            RawAudioPlayer.PanMode.TELEPHONE -> "telephone"
            RawAudioPlayer.PanMode.CUSTOM -> "custom"
        }

        SettingRow(label = "dsp mode", value = modeLabel) {
            rawPlayer?.let { player ->
                player.currentMode = when (player.currentMode) {
                    RawAudioPlayer.PanMode.OFF -> RawAudioPlayer.PanMode.VOLUME
                    RawAudioPlayer.PanMode.VOLUME -> RawAudioPlayer.PanMode.FREQUENCY
                    RawAudioPlayer.PanMode.FREQUENCY -> RawAudioPlayer.PanMode.TREMOLO
                    RawAudioPlayer.PanMode.TREMOLO -> RawAudioPlayer.PanMode.LOFI
                    RawAudioPlayer.PanMode.LOFI -> RawAudioPlayer.PanMode.TELEPHONE
                    RawAudioPlayer.PanMode.TELEPHONE -> RawAudioPlayer.PanMode.CUSTOM
                    RawAudioPlayer.PanMode.CUSTOM -> RawAudioPlayer.PanMode.OFF
                }
                currentMode = player.currentMode 
            }
        }

        if (currentMode != RawAudioPlayer.PanMode.CUSTOM) {
            val presetLabel = when (currentPreset) {
                RawAudioPlayer.PanPreset.VOL_SMOOTH -> "smooth"
                RawAudioPlayer.PanPreset.VOL_GENTLE -> "gentle"
                RawAudioPlayer.PanPreset.VOL_CHOP -> "chop"
                RawAudioPlayer.PanPreset.FREQ_HELICOPTER -> "helicopter"
                RawAudioPlayer.PanPreset.FREQ_DEEP_BASS -> "deep bass"
                RawAudioPlayer.PanPreset.FREQ_HI_HATS -> "hi-hats"
                RawAudioPlayer.PanPreset.FREQ_UNDERWATER -> "underwater"
                RawAudioPlayer.PanPreset.TREMOLO_SINE -> "sine wave"
                RawAudioPlayer.PanPreset.TREMOLO_SQUARE -> "square wave"
                RawAudioPlayer.PanPreset.LOFI_8BIT -> "8-bit"
                RawAudioPlayer.PanPreset.LOFI_4BIT -> "4-bit"
                RawAudioPlayer.PanPreset.TEL_VINTAGE -> "vintage"
                RawAudioPlayer.PanPreset.TEL_RADIO -> "radio"
                else -> "unknown" // CRITICAL: 'else' MUST be the very last branch
            }

            SettingRow(label = "dsp preset", value = presetLabel) {
                rawPlayer?.let { player ->
                    player.currentPreset = when (player.currentMode) {
                        RawAudioPlayer.PanMode.VOLUME -> when (player.currentPreset) {
                            RawAudioPlayer.PanPreset.VOL_SMOOTH -> RawAudioPlayer.PanPreset.VOL_GENTLE
                            RawAudioPlayer.PanPreset.VOL_GENTLE -> RawAudioPlayer.PanPreset.VOL_CHOP
                            RawAudioPlayer.PanPreset.VOL_CHOP -> RawAudioPlayer.PanPreset.VOL_SMOOTH
                            else -> RawAudioPlayer.PanPreset.VOL_SMOOTH
                        }
                        RawAudioPlayer.PanMode.FREQUENCY -> when (player.currentPreset) {
                            RawAudioPlayer.PanPreset.FREQ_HELICOPTER -> RawAudioPlayer.PanPreset.FREQ_DEEP_BASS
                            RawAudioPlayer.PanPreset.FREQ_DEEP_BASS -> RawAudioPlayer.PanPreset.FREQ_HI_HATS
                            RawAudioPlayer.PanPreset.FREQ_HI_HATS -> RawAudioPlayer.PanPreset.FREQ_UNDERWATER
                            RawAudioPlayer.PanPreset.FREQ_UNDERWATER -> RawAudioPlayer.PanPreset.FREQ_HELICOPTER
                            else -> RawAudioPlayer.PanPreset.FREQ_HELICOPTER
                        }
                        RawAudioPlayer.PanMode.TREMOLO -> if (player.currentPreset == RawAudioPlayer.PanPreset.TREMOLO_SINE) RawAudioPlayer.PanPreset.TREMOLO_SQUARE else RawAudioPlayer.PanPreset.TREMOLO_SINE
                        RawAudioPlayer.PanMode.LOFI -> if (player.currentPreset == RawAudioPlayer.PanPreset.LOFI_8BIT) RawAudioPlayer.PanPreset.LOFI_4BIT else RawAudioPlayer.PanPreset.LOFI_8BIT
                        RawAudioPlayer.PanMode.TELEPHONE -> if (player.currentPreset == RawAudioPlayer.PanPreset.TEL_VINTAGE) RawAudioPlayer.PanPreset.TEL_RADIO else RawAudioPlayer.PanPreset.TEL_VINTAGE
                        RawAudioPlayer.PanMode.OFF -> RawAudioPlayer.PanPreset.VOL_SMOOTH
                        RawAudioPlayer.PanMode.CUSTOM -> RawAudioPlayer.PanPreset.VOL_SMOOTH // Added CUSTOM case
                    }
                    currentPreset = player.currentPreset
                }
            }
        }

        // --- CUSTOM MODE UI ---
        if (currentMode == RawAudioPlayer.PanMode.CUSTOM) {
            Caption("custom cycle presets")
            Text("Tap to toggle effects on/off", style = MaterialTheme.typography.labelSmall, color = TuiDim, modifier = Modifier.padding(bottom = 8.dp))
            
            RawAudioPlayer.PanPreset.values().forEach { preset ->
                val isSelected = rawPlayer?.customPresets?.contains(preset) == true
                SettingRow(
                    label = preset.name.lowercase().replace("_", " "), 
                    value = if (isSelected) "ON" else "OFF"
                ) {
                    val currentList = rawPlayer?.customPresets?.toMutableList() ?: mutableListOf()
                    if (isSelected) {
                        currentList.remove(preset)
                    } else {
                        currentList.add(preset)
                    }
                    rawPlayer?.customPresets = currentList
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Caption("cycle interval")
            Text("Seconds between effect changes", style = MaterialTheme.typography.labelSmall, color = TuiDim, modifier = Modifier.padding(bottom = 8.dp))
            
            val interval = rawPlayer?.intervalSeconds ?: 10f
            Text("interval: ${String.format("%.1f", interval)}s", style = MaterialTheme.typography.bodyMedium, color = TuiFg)
            AsciiSlider(
                value = interval, 
                min = 2.0f, 
                max = 120.0f, 
                steps = 118, 
                onValueChange = { newInterval -> rawPlayer?.intervalSeconds = newInterval }
            )
        }
        // --- END CUSTOM MODE UI ---

        if (currentMode != RawAudioPlayer.PanMode.OFF) {
            val speed = rawPlayer?.lfoFreq ?: 0.5f
            val depth = rawPlayer?.depth ?: 0.8f

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("lfo speed", style = MaterialTheme.typography.bodyMedium, color = TuiFg)
                AsciiSlider(value = speed, min = 0.1f, max = 2.0f, steps = 19, onValueChange = { newSpeed -> rawPlayer?.lfoFreq = newSpeed })
                Spacer(modifier = Modifier.height(12.dp))
                Text("intensity", style = MaterialTheme.typography.bodyMedium, color = TuiFg)
                AsciiSlider(value = depth, min = 0.0f, max = 1.0f, steps = 10, onValueChange = { newDepth -> rawPlayer?.depth = newDepth })
            }
        }
        // ==========================================
        Caption(stringResource(R.string.tools))
        SettingRow(label = stringResource(R.string.set_eq_preset), value = state.equalizerPresetName) {
            val nextIndex = (state.equalizerPresetName.let { current -> EQ_PRESETS.indexOfFirst { it.first == current } } + 1).mod(EQ_PRESETS.size)
            dispatch(DmtAction.SetEqualizerPreset(nextIndex))
        }
        SettingRow(label = stringResource(R.string.set_eq), value = stringResource(R.string.set_eq_open)) { dispatch(DmtAction.OpenEqualizer) }
        SettingRow(label = stringResource(R.string.stats), value = stringResource(R.string.stat_view)) { dispatch(DmtAction.Show(DmtView.STATS)) }
        SettingRow(label = stringResource(R.string.set_fingerprint_lock), value = if (settings.fingerprintLock) on else off) { dispatch(DmtAction.Config(settings.copy(fingerprintLock = !settings.fingerprintLock))) }
        SettingRow(label = stringResource(R.string.set_import_playlist), value = stringResource(R.string.set_import_playlist_action)) { dispatch(DmtAction.ShowImportDialog) }
        SettingRow(label = stringResource(R.string.set_rescan), value = stringResource(R.string.run)) { dispatch(DmtAction.Rescan) }
        SettingRow(label = stringResource(R.string.perms_title), value = stringResource(R.string.perms_manage)) { dispatch(DmtAction.Show(DmtView.PERMISSIONS)) }
        if (settings.sourceMode == SourceMode.LOCAL) {
            SettingRow(label = stringResource(R.string.blocklist_title), value = if (settings.blockedFolders.isEmpty()) stringResource(R.string.blocklist_edit) else pluralStringResource(R.plurals.blocklist_edit_count, settings.blockedFolders.size, settings.blockedFolders.size)) { dispatch(DmtAction.Show(DmtView.BLOCKLIST)) }
        }

        Caption(stringResource(R.string.about))
        Text(text = stringResource(R.string.about_title), style = MaterialTheme.typography.bodyMedium, color = TuiFg)
        Text(text = stringResource(R.string.about_body), style = MaterialTheme.typography.labelSmall, color = TuiDim, modifier = Modifier.padding(top = 4.dp))
        Text(text = "v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = TuiFaint, modifier = Modifier.padding(top = 6.dp))

        val uriHandler = LocalUriHandler.current
        val creditUrl = stringResource(R.string.credit_url)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp).tuiClickable { runCatching { uriHandler.openUri(creditUrl) } }) {
            Text(text = "▌ ", style = MaterialTheme.typography.labelMedium, color = TuiAccent)
            Text(text = stringResource(R.string.credit), style = MaterialTheme.typography.labelMedium, color = TuiDim)
            Text(text = " ↗", style = MaterialTheme.typography.labelMedium, color = TuiAccent)
        }
    }
}

@Composable
fun AccentRow(settings: DmtSettings, dispatch: (DmtAction) -> Unit) {
    var preview by remember(settings.accent) { mutableStateOf(settings.accent) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(text = stringResource(R.string.set_accent), style = MaterialTheme.typography.bodyLarge, color = TuiFg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(14.dp).background(preview.toColor()).border(1.dp, TuiLine))
                Spacer(modifier = Modifier.width(6.dp))
                TuiKey(label = "[ ${preview.label} ]") { preview = preview.next() }
                if (preview != settings.accent) { Spacer(modifier = Modifier.width(6.dp)); TuiKey(label = stringResource(R.string.accent_accept)) { dispatch(DmtAction.Config(settings.copy(accent = preview))) } }
            }
        }
        HorizontalDivider(color = TuiLine)
    }
}

@Composable
fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = TuiFg)
            TuiKey(label = "[ $value ]", onClick = onClick)
        }
        HorizontalDivider(color = TuiLine)
    }
}

@Composable
fun AsciiSlider(value: Float, min: Float, max: Float, steps: Int, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val percentage = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val filled = (percentage * steps).toInt()
    val empty = steps - filled
    val filledChar = "█"; val emptyChar = "░"
    val barText = "[" + filledChar.repeat(filled) + emptyChar.repeat(empty) + "]"
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max, steps = steps - 1, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = TuiAccent, activeTrackColor = TuiAccent, inactiveTrackColor = TuiLine))
        Text(text = "$barText  ${String.format("%.1f", value)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = TuiDim, modifier = Modifier.padding(top = 4.dp))
    }
}
