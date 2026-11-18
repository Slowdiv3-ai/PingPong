package dev.abhi.zmt.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class PingPongRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    // Temporarily commented out to test if the ROM is still crashing
    // private val panProcessor = PanEffectProcessor()

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            // REMOVED: .setAudioProcessors(arrayOf(panProcessor))
            .build()
    }
}
