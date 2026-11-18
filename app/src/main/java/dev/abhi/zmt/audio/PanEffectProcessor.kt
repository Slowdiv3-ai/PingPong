package dev.abhi.zmt.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.*

class PanEffectProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "PingPongDSP"
    }

    // MUST be true to keep the processor in the chain
    override fun isActive(): Boolean = true

    private var sampleRate = 44100
    private var lfoPhase = 0.0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only support 16-bit stereo. Throw if not, so Media3 bypasses us safely.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        
        sampleRate = inputAudioFormat.sampleRate
        lfoPhase = 0.0f
        
        Log.d(TAG, "✅ Volume Pan Configured! SampleRate: $sampleRate")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position

        if (size == 0) return

        // Request output buffer of the EXACT same size
        val outputBuffer = replaceOutputBuffer(size)

        // LFO: 0.5 Hz cycle (swaps left/right every 2 seconds)
        val lfoFreq = 0.5f 
        val lfoStep = 2.0f * Math.PI.toFloat() * (lfoFreq / sampleRate)

        var i = position

        while (i < limit) {
            // 1. Read Left Channel (16-bit Little Endian)
            val b0 = inputBuffer.get(i).toInt() and 0xFF
            val b1 = inputBuffer.get(i + 1).toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            // 2. Read Right Channel (16-bit Little Endian)
            val b2 = inputBuffer.get(i + 2).toInt() and 0xFF
            val b3 = inputBuffer.get(i + 3).toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            // 3. Calculate Panning (Constant Power Sine Wave)
            // pan goes from 0.0 (Full Left) to 1.0 (Full Right)
            val pan = (sin(lfoPhase) + 1.0f) / 2.0f
            val angle = pan * (Math.PI.toFloat() / 2.0f)
            
            // Constant power gains prevent volume dips in the center
            val leftGain = cos(angle)
            val rightGain = sin(angle)

            var outL = sampleL * leftGain
            var outR = sampleR * rightGain

            // 4. Hard clip to prevent distortion
            outL = outL.coerceIn(-1f, 1f)
            outR = outR.coerceIn(-1f, 1f)

            // 5. Write Left Channel (16-bit Little Endian)
            val shortL = (outL * 32767f).toInt().toShort()
            outputBuffer.put((shortL.toInt() and 0xFF).toByte())
            outputBuffer.put((shortL.toInt() shr 8).toByte())

            // 6. Write Right Channel (16-bit Little Endian)
            val shortR = (outR * 32767f).toInt().toShort()
            outputBuffer.put((shortR.toInt() and 0xFF).toByte())
            outputBuffer.put((shortR.toInt() shr 8).toByte())

            // Advance 4 bytes (2 channels * 2 bytes)
            i += 4
            
            // Advance LFO phase
            lfoPhase += lfoStep
            if (lfoPhase > 2.0f * Math.PI.toFloat()) {
                lfoPhase -= 2.0f * Math.PI.toFloat()
            }
        }

        // Tell Media3 we consumed all input bytes
        inputBuffer.position(limit)
    }
}
