package dev.abhi.zmt.audio

/**
 * Global configuration for the PingPong audio effects.
 * Accessed by both the UI thread (writers) and Audio thread (readers).
 */
object PanConfig {

    // --- Master Toggles ---
    @Volatile var isFreqPanEnabled = false
    @Volatile var isVolPanEnabled = false

    // --- Global Speed Multiplier (0.25x to 4.0x) ---
    @Volatile var speedMultiplier = 1.0f

    // --- Crossover Frequency (Hz) ---
    // Frequencies below this are "Low", above are "High"
    @Volatile var crossoverHz = 300.0 

    // --- Presets ---
    enum class Preset(val depth: Float, val lfoRateHz: Float, val attack: Float, val release: Float) {
        GENTLE(depth = 0.3f, lfoRateHz = 0.1f, attack = 0.05f, release = 0.1f),
        SMOOTH(depth = 0.7f, lfoRateHz = 0.25f, attack = 0.1f, release = 0.2f),
        WAVE(depth = 1.0f, lfoRateHz = 0.5f, attack = 0.2f, release = 0.4f)
    }

    @Volatile var currentPreset = Preset.SMOOTH

    // Helper to cycle presets from the UI
    fun cyclePreset() {
        currentPreset = when (currentPreset) {
            Preset.GENTLE -> Preset.SMOOTH
            Preset.SMOOTH -> Preset.WAVE
            Preset.WAVE -> Preset.GENTLE
        }
    }
}
