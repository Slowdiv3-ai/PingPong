package dev.abhi.zmt.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class PanEffectProcessor : BaseAudioProcessor() {

    override fun isActive(): Boolean = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Request a buffer of the exact same size
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        
        // Copy the bytes exactly as they are. No math. No changes.
        outputBuffer.put(inputBuffer)
        
        // Mark input as consumed
        inputBuffer.position(inputBuffer.limit())
    }
}
