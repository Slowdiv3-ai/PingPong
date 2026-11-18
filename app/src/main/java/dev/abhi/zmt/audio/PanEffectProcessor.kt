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
        private const val DEFAULT_SAMPLE_RATE = 44100
    }

    private var lowPassLeft: Biquad? = null
    private var highPassLeft: Biquad? = null
    private var lowPassRight: Biquad? = null
    private var highPassRight: Biquad? = null

    private var lfoPhase = 0.0f
    private var volumeEnvelope = 0.0f
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var isFormatSupported = false

    // ALWAYS ACTIVE so Media3 keeps it in the chain
    override fun isActive(): Boolean {
        return true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        isFormatSupported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2)
        
        if (isFormatSupported) {
            sampleRate = inputAudioFormat.sampleRate
            val fc = PanConfig.crossoverHz.toFloat()
            
            lowPassLeft = Biquad(Biquad.Type.LOWPASS, fc, sampleRate.toFloat())
            highPassLeft = Biquad(Biquad.Type.HIGHPASS, fc, sampleRate.toFloat())
            lowPassRight = Biquad(Biquad.Type.LOWPASS, fc, sampleRate.toFloat())
            highPassRight = Biquad(Biquad.Type.HIGHPASS, fc, sampleRate.toFloat())

            // Reset state here on every track/format change
            lfoPhase = 0.0f
            volumeEnvelope = 0.0f
            
            Log.i(TAG, "✅ Configured for ${sampleRate}Hz. Crossover: ${fc}Hz")
        } else {
            Log.w(TAG, "⚠️ Format not supported. Passing through.")
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position

        if (size == 0) return

        val outputBuffer = replaceOutputBuffer(size)

        if (!isFormatSupported) {
            outputBuffer.put(inputBuffer)
            inputBuffer.position(limit)
            return
        }

        // SAFETY: Only process complete 4-byte frames (2 channels * 16-bit)
        val frameLimit = position + ((size / 4) * 4)
        var i = position

        // Hardcoded LFO for testing (0.5 Hz wave)
        val lfoFreq = 0.5f 
        val lfoStep = 2.0f * Math.PI.toFloat() * (lfoFreq / sampleRate)
        val lfoVal = (sin(lfoPhase) + 1.0f) / 2.0f
        val angle = lfoVal * (Math.PI.toFloat() / 2.0f)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val depth = 0.8f // Hardcoded depth

        while (i < frameLimit) {
            val b0 = inputBuffer.get(i).toInt() and 0xFF
            val b1 = inputBuffer.get(i + 1).toInt() and 0xFF
            val b2 = inputBuffer.get(i + 2).toInt() and 0xFF
            val b3 = inputBuffer.get(i + 3).toInt() and 0xFF

            val sampleL = b0 or (b1 shl 8)
            val sampleR = b2 or (b3 shl 8)

            val floatL = sampleL.toShort() / 32768f
            val floatR = sampleR.toShort() / 32768f

            val loL = lowPassLeft?.process(floatL) ?: floatL
            val hiL = highPassLeft?.process(floatL) ?: 0.0f
            val loR = lowPassRight?.process(floatR) ?: floatR
            val hiR = highPassRight?.process(floatR) ?: 0.0f

            val targetL = (loL * sinA + hiL * cosA)
            val targetR = (loR * cosA + hiR * sinA)

            var outL = floatL * (1f - depth) + targetL * depth
            var outR = floatR * (1f - depth) + targetR * depth

            outL = outL.coerceIn(-1f, 1f)
            outR = outR.coerceIn(-1f, 1f)

            val shortL = (outL * 32767f).toInt().toShort()
            val shortR = (outR * 32767f).toInt().toShort()

            outputBuffer.put((shortL.toInt() and 0xFF).toByte())
            outputBuffer.put((shortL.toInt() shr 8).toByte())
            outputBuffer.put((shortR.toInt() and 0xFF).toByte())
            outputBuffer.put((shortR.toInt() shr 8).toByte())

            i += 4
        }

        // Copy any leftover bytes (safety net for odd-sized buffers)
        while (i < limit) {
            outputBuffer.put(inputBuffer.get(i))
            i++
        }

        lfoPhase += lfoStep * ((frameLimit - position) / 4)
        if (lfoPhase > 2.0f * Math.PI.toFloat()) lfoPhase -= 2.0f * Math.PI.toFloat()

        inputBuffer.position(limit)
    }

    // --- Inner Class: Biquad Filter ---
    private class Biquad(val type: Type, val freqHz: Float, val sampleRateHz: Float, val q: Float = 0.7071f) {
        enum class Type { LOWPASS, HIGHPASS }
        private val w0 = 2.0f * Math.PI.toFloat() * freqHz / sampleRateHz
        private val cosW0 = cos(w0)
        private val alpha = sin(w0) / (2.0f * q)
        private val b0: Float; private val b1: Float; private val b2: Float
        private val a0: Float; private val a1: Float; private val a2: Float
        private var x1 = 0.0f; private var x2 = 0.0f; private var y1 = 0.0f; private var y2 = 0.0f

        init {
            if (type == Type.LOWPASS) {
                b0 = (1.0f - cosW0) / 2.0f; b1 = 1.0f - cosW0; b2 = (1.0f - cosW0) / 2.0f
            } else {
                b0 = (1.0f + cosW0) / 2.0f; b1 = -(1.0f + cosW0); b2 = (1.0f + cosW0) / 2.0f
            }
            a0 = 1.0f + alpha; a1 = -2.0f * cosW0; a2 = 1.0f - alpha
        }

        fun process(input: Float): Float {
            val y = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = input; y2 = y1; y1 = y
            return y
        }
    }
}
