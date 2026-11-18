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

    // FIX 1: Must return true, otherwise Media3 bypasses this processor entirely
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

            // FIX 2: Reset DSP state on every new track to prevent clipping/silence
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

        // If format isn't supported, pass through safely
        if (!isFormatSupported) {
            outputBuffer.put(inputBuffer)
            inputBuffer.position(limit)
            return
        }

        var i = position
        val isFreqOn = PanConfig.isFreqPanEnabled
        val isVolOn = PanConfig.isVolPanEnabled
        val preset = PanConfig.currentPreset
        val speed = PanConfig.speedMultiplier

        val lfoFreq = preset.lfoRateHz * speed
        val lfoStep = 2.0f * Math.PI.toFloat() * (lfoFreq / sampleRate)
        
        // Pre-calculate trig functions once per buffer for maximum performance
        val lfoVal = (sin(lfoPhase) + 1.0f) / 2.0f
        val angle = lfoVal * (Math.PI.toFloat() / 2.0f)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val depth = preset.depth

        while (i < limit) {
            // Read Little Endian 16-bit samples manually (Safe for all buffer types)
            val b0 = inputBuffer.get(i).toInt() and 0xFF
            val b1 = inputBuffer.get(i + 1).toInt() and 0xFF
            val b2 = inputBuffer.get(i + 2).toInt() and 0xFF
            val b3 = inputBuffer.get(i + 3).toInt() and 0xFF

            val sampleL = b0 or (b1 shl 8)
            val sampleR = b2 or (b3 shl 8)

            val floatL = sampleL.toShort() / 32768f
            val floatR = sampleR.toShort() / 32768f

            var outL = floatL
            var outR = floatR

            if (isFreqOn || isVolOn) {
                if (isFreqOn) {
                    val loL = lowPassLeft?.process(floatL) ?: floatL
                    val hiL = highPassLeft?.process(floatL) ?: 0.0f
                    val loR = lowPassRight?.process(floatR) ?: floatR
                    val hiR = highPassRight?.process(floatR) ?: 0.0f

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
                    val widthBoost = 1.0f + (volumeEnvelope * depth)
                    outL *= widthBoost
                    outR *= widthBoost
                }
            }

            // Hard clip to prevent distortion
            outL = outL.coerceIn(-1f, 1f)
            outR = outR.coerceIn(-1f, 1f)

            val shortL = (outL * 32767f).toInt().toShort()
            val shortR = (outR * 32767f).toInt().toShort()

            // Write Little Endian 16-bit samples manually
            outputBuffer.put((shortL.toInt() and 0xFF).toByte())
            outputBuffer.put((shortL.toInt() shr 8).toByte())
            outputBuffer.put((shortR.toInt() and 0xFF).toByte())
            outputBuffer.put((shortR.toInt() shr 8).toByte())

            i += 4
        }

        lfoPhase += lfoStep * (size / 4)
        if (lfoPhase > 2.0f * Math.PI.toFloat()) lfoPhase -= 2.0f * Math.PI.toFloat()

        // FIX 3: Advance input buffer to show we consumed it. 
        // DO NOT call outputBuffer.flip() here. Media3 does it automatically.
        inputBuffer.position(limit)
    }

    override fun flush() {
        super.flush()
        // FIX 4: Reset state on seek/pause to prevent audio glitches
        lfoPhase = 0.0f
        volumeEnvelope = 0.0f
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
