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

    override fun isActive(): Boolean = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
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
            Log.w(TAG, "Format not supported. Passing through.")
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

        val frameCount = size / 4
        val frameLimit = position + (frameCount * 4)

        var i = position
        val isFreqOn = PanConfig.isFreqPanEnabled
        val isVolOn = PanConfig.isVolPanEnabled
        val preset = PanConfig.currentPreset
        val speed = PanConfig.speedMultiplier

        // 🚀 YOUR MATH, OPTIMIZED FOR THE AUDIO THREAD:
        // Calculate phase increment per frame
        val freq = (2.0f * PI.toFloat() / 4.0f) * speed
        val phaseIncrement = freq / sampleRate
        
        var currentPhase = lfoPhase

        while (i < frameLimit) {
            // 1. Calculate Pan Value based on your patterns (evaluated once per frame for efficiency)
            val panValue = when (preset) {
                PanConfig.Preset.SMOOTH -> (sin(currentPhase) * preset.depth)
                PanConfig.Preset.WAVE -> ((2.0f / PI.toFloat()) * asin(sin(currentPhase).toFloat()) * preset.depth)
                PanConfig.Preset.SUBTLE -> (sin(currentPhase) * preset.depth)
            }

            val b0 = inputBuffer.get(i).toInt() and 0xFF
            val b1 = inputBuffer.get(i + 1).toInt() and 0xFF
            val b2 = inputBuffer.get(i + 2).toInt() and 0xFF
            val b3 = inputBuffer.get(i + 3).toInt() and 0xFF

            val floatL = (b0 or (b1 shl 8)).toShort() / 32768f
            val floatR = (b2 or (b3 shl 8)).toShort() / 32768f

            var outL = floatL
            var outR = floatR

            if (isFreqOn || isVolOn) {
                if (isFreqOn) {
                    val loL = lowPassLeft?.process(floatL) ?: floatL
                    val hiL = highPassLeft?.process(floatL) ?: 0.0f
                    val loR = lowPassRight?.process(floatR) ?: floatR
                    val hiR = highPassRight?.process(floatR) ?: 0.0f

                    // panValue goes from -depth to +depth. 
                    // We map it to a 0.0 to 1.0 range for constant-power panning.
                    val normalizedPan = (panValue + preset.depth) / (2f * preset.depth)
                    val angle = normalizedPan * (PI.toFloat() / 2.0f)
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    val targetL = (loL * sinA + hiL * cosA)
                    val targetR = (loR * cosA + hiR * sinA)

                    outL = floatL * (1f - preset.depth) + targetL * preset.depth
                    outR = floatR * (1f - preset.depth) + targetR * preset.depth
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

            outputBuffer.put((shortL.toInt() and 0xFF).toByte())
            outputBuffer.put((shortL.toInt() shr 8).toByte())
            outputBuffer.put((shortR.toInt() and 0xFF).toByte())
            outputBuffer.put((shortR.toInt() shr 8).toByte())

            i += 4
            currentPhase += phaseIncrement
            if (currentPhase > 2.0f * PI.toFloat()) currentPhase -= 2.0f * PI.toFloat()
        }

        lfoPhase = currentPhase // Save phase for the next buffer

        if (frameLimit < limit) {
            var j = frameLimit
            while (j < limit) {
                outputBuffer.put(inputBuffer.get(j))
                j++
            }
        }

        inputBuffer.position(limit)
    }

    private class Biquad(val type: Type, val freqHz: Float, val sampleRateHz: Float, val q: Float = 0.7071f) {
        enum class Type { LOWPASS, HIGHPASS }
        private val w0 = 2.0f * PI.toFloat() * freqHz / sampleRateHz
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
