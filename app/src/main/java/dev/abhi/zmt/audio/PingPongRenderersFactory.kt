package dev.abhi.zmt.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Custom RenderersFactory that injects the PingPong DSP into the audio pipeline.
 */
class PingPongRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    // We keep one instance of the processor so it remembers its state (like the LFO phase)
    // across track changes, unless the player is fully destroyed.
    private val panProcessor = PanEffectProcessor()

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            // CRITICAL: If this is true, ExoPlayer bypasses all AudioProcessors!
            // We must force it to false to let our PingPong effect run.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            // Inject our custom processor here
            .setAudioProcessors(panProcessor)
            .build()
    }
}
