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
    
    // CRITICAL: Track if the current audio format is supported
    private var isFormatSupported = false

    // Always return true so Media3 keeps this processor in the audio chain.
    override fun isActive(): Boolean {
        return true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Check if it's standard 16-bit stereo. If not, we will just pass it through unchanged.
        isFormatSupported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2)
        
        if (isFormatSupported) {
            sampleRate = inputAudioFormat.sampleRate
            val fc = PanConfig.crossoverHz.toFloat()
            
            lowPassLeft = Biquad(Biquad.Type.LOWPASS, fc, sampleRate.toFloat())
            highPassLeft = Biquad(Biquad.Type.HIGHPASS, fc, sampleRate.toFloat())
            lowPassRight = Biquad(Biquad.Type.LOWPASS, fc, sampleRate.toFloat())
            highPassRight = Biquad(Biquad.Type.HIGHPASS, fc, sampleRate.toFloat())

            lfoPhase = 0.0f
            volumeEnvelope = 0.0f
            
            Log.i(TAG, "Configured for ${sampleRate}Hz. Crossover: ${fc}Hz")
        } else {
            Log.w(TAG, "Format not 16-bit stereo (encoding: ${inputAudioFormat.encoding}, channels: ${inputAudioFormat.channelCount}). Passing through unchanged.")
        }
        
        // Never throw an exception here, or it will break the audio chain and cause silence!
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position

        if (size == 0) return

        // If the format isn't supported, just pass the buffer through unchanged
        if (!isFormatSupported) {
            val outputBuffer = replaceOutputBuffer(size)
            outputBuffer.put(inputBuffer)
            inputBuffer.position(limit)
            return
        }

        val frameCount = size / 4 // 2 channels * 2 bytes per sample (16-bit)
        val outputBuffer = replaceOutputBuffer(size)
        val outBytes = outputBuffer.array()

        var i = position
        var outIndex = outputBuffer.position()

        val preset = PanConfig.currentPreset
        val speed = PanConfig.speedMultiplier
        val isFreqOn = PanConfig.isFreqPanEnabled
        val isVolOn = PanConfig.isVolPanEnabled

        val lfoFreq = preset.lfoRateHz * speed
        val lfoStep = 2.0f * Math.PI.toFloat() * (lfoFreq / sampleRate)

        while (i < limit) {
            val sampleL = (inputBuffer.get(i).toInt() and 0xFF) or (inputBuffer.get(i + 1).toInt() shl 8)
            val sampleR = (inputBuffer.get(i + 2).toInt() and 0xFF) or (inputBuffer.get(i + 3).toInt() shl 8)

            val floatL = sampleL.toShort() / 32768f
            val floatR = sampleR.toShort() / 32768f

            var outL = floatL
            var outR = floatR

            // Only do the heavy DSP math if at least one effect is turned on
            if (isFreqOn || isVolOn) {
                if (isFreqOn) {
                    val loL = lowPassLeft?.process(floatL) ?: floatL
                    val hiL = highPassLeft?.process(floatL) ?: 0.0f
                    val loR = lowPassRight?.process(floatR) ?: floatR
                    val hiR = highPassRight?.process(floatR) ?: 0.0f

                    val lfoVal = (sin(lfoPhase) + 1.0f) / 2.0f
                    val angle = lfoVal * (Math.PI.toFloat() / 2.0f)
                    val cosA = cos(angle)
                    val sinA = sin(angle)
                    val depth = preset.depth

                    val targetL = (loL * sinA + hiL * cosA)
                    val targetR = (loR * cosA + hiR * sinA)

                    outL = floatL * (1f - depth) + targetL * depth
                    outR = floatR * (1f - depth) + targetR * depth
                }

                if (isVolOn) {
                    val currentVol = abs((outL + outR) / 2f)
                    volumeEnvelope = if (currentVol > volumeEnvelope) {
                        volumeEnvelope + (currentVol - volumeEnvelope) * preset.attack
                    } else {
                        volumeEnvelope + (currentVol - volumeEnvelope) * preset.release
                    }

                    val widthBoost = 1.0f + (volumeEnvelope * preset.depth)
                    outL *= widthBoost
                    outR *= widthBoost
                }
            }

            outL = outL.coerceIn(-1f, 1f)
            outR = outR.coerceIn(-1f, 1f)

            val shortL = (outL * 32767f).toInt().toShort()
            val shortR = (outR * 32767f).toInt().toShort()

            outBytes[outIndex++] = (shortL.toInt() and 0xFF).toByte()
            outBytes[outIndex++] = (shortL.toInt() shr 8).toByte()
            outBytes[outIndex++] = (shortR.toInt() and 0xFF).toByte()
            outBytes[outIndex++] = (shortR.toInt() shr 8).toByte()

            i += 4
        }

        lfoPhase += lfoStep * frameCount
        if (lfoPhase > 2.0f * Math.PI.toFloat()) lfoPhase -= 2.0f * Math.PI.toFloat()

        inputBuffer.position(limit)
        outputBuffer.position(outIndex)
    }

    // --- Inner Class: Biquad Filter (Strictly Float) ---
    private class Biquad(val type: Type, val freqHz: Float, val sampleRateHz: Float, val q: Float = 0.7071f) {
        enum class Type { LOWPASS, HIGHPASS }

        private val w0 = 2.0f * Math.PI.toFloat() * freqHz / sampleRateHz
        private val cosW0 = cos(w0)
        private val alpha = sin(w0) / (2.0f * q)

        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a0: Float
        private val a1: Float
        private val a2: Float

        private var x1 = 0.0f
        private var x2 = 0.0f
        private var y1 = 0.0f
        private var y2 = 0.0f

        init {
            if (type == Type.LOWPASS) {
                b0 = (1.0f - cosW0) / 2.0f
                b1 = 1.0f - cosW0
                b2 = (1.0f - cosW0) / 2.0f
            } else {
                b0 = (1.0f + cosW0) / 2.0f
                b1 = -(1.0f + cosW0)
                b2 = (1.0f + cosW0) / 2.0f
            }
            a0 = 1.0f + alpha
            a1 = -2.0f * cosW0
            a2 = 1.0f - alpha
        }

        fun process(input: Float): Float {
            val y = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1
            x1 = input
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0f; x2 = 0.0f; y1 = 0.0f; y2 = 0.0f
        }
    }
}
