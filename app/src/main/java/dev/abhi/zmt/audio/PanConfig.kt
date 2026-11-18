package dev.abhi.zmt.audio

object PanConfig {
    @Volatile var isFreqPanEnabled = true // HARDCODED ON
    @Volatile var isVolPanEnabled = false
    @Volatile var speedMultiplier = 1.0f
    @Volatile var crossoverHz = 300.0 

    enum class Preset(val depth: Float, val attack: Float, val release: Float) {
        SMOOTH(depth = 0.8f, attack = 0.05f, release = 0.1f),
        WAVE(depth = 0.8f, attack = 0.1f, release = 0.2f),
        SUBTLE(depth = 0.4f, attack = 0.05f, release = 0.1f)
    }

    @Volatile var currentPreset = Preset.WAVE // HARDCODED TO WAVE
}
