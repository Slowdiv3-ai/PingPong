package dev.abhi.zmt.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * The core DSP engine for PingPong.
 * Intercepts PCM audio and applies Frequency and Volume based panning.
 */
class PanEffectProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "PingPongDSP"
    }

    // Biquad filters for splitting frequencies
    private var lowPassLeft: Biquad? = null
    private var highPassLeft: Biquad? = null
    private var lowPassRight: Biquad? = null
    private var highPassRight: Biquad? = null

    // LFO (Low Frequency Oscillator) for the "shifting" effect
    private var lfoPhase = 0.0
    
    // Volume Envelope Follower
    private var volumeEnvelope = 0.0
    private var sampleRate = 44100

    override fun isActive(): Boolean {
        return PanConfig.isFreqPanEnabled || PanConfig.isVolPanEnabled
    }

    // FIX: Media3 1.5.0+ uses AudioProcessor.AudioFormat, not Format
    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Check for 16-bit PCM
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        
        sampleRate = inputAudioFormat.sampleRateHz
        val channelCount = inputAudioFormat.channelCount
        
        // We only support stereo for this specific panning effect
        if (channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        // Initialize Filters based on Config
        val fc = PanConfig.crossoverHz
        lowPassLeft = Biquad(Biquad.Type.LOWPASS, fc, sampleRate.toDouble())
        highPassLeft = Biquad(Biquad.Type.HIGHPASS, fc, sampleRate.toDouble())
        lowPassRight = Biquad(Biquad.Type.LOWPASS, fc, sampleRate.toDouble())
        highPassRight = Biquad(Biquad.Type.HIGHPASS, fc, sampleRate.toDouble())

        Log.i(TAG, "Configured for ${sampleRate}Hz. Crossover: ${fc}Hz")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount = (limit - position) / 4 // 2 channels * 2 bytes per sample (16-bit)
        
        if (frameCount == 0) return

        val outputBuffer = replaceOutputBuffer(frameCount * 4)
        val outBytes = outputBuffer.array() // Direct access to underlying array for speed

        var i = position
        var outIndex = outputBuffer.position()
        
        val preset = PanConfig.currentPreset
        val speed = PanConfig.speedMultiplier
        val isFreqOn = PanConfig.isFreqPanEnabled
        val isVolOn = PanConfig.isVolPanEnabled

        // OPTIMIZATION: Calculate LFO step once per buffer, not per sample!
        val lfoFreq = preset.lfoRateHz * speed
        val lfoStep = 2.0 * Math.PI * (lfoFreq / sampleRate)

        while (i < limit) {
            // Read 16-bit samples (Little Endian)
            val sampleL = (inputBuffer.get(i).toInt() and 0xFF) or (inputBuffer.get(i + 1).toInt() shl 8)
            val sampleR = (inputBuffer.get(i + 2).toInt() and 0xFF) or (inputBuffer.get(i + 3).toInt() shl 8)
            
            // Convert to Float (-1.0 to 1.0)
            val floatL = sampleL.toShort() / 32768f
            val floatR = sampleR.toShort() / 32768f

            var outL = floatL
            var outR = floatR

            if (isFreqOn) {
                // 1. Split frequencies
                val loL = lowPassLeft?.process(floatL.toDouble()) ?: floatL.toDouble()
                val hiL = highPassLeft?.process(floatL.toDouble()) ?: 0.0
                val loR = lowPassRight?.process(floatR.toDouble()) ?: floatR.toDouble()
                val hiR = highPassRight?.process(floatR.toDouble()) ?: 0.0

                // 2. Calculate LFO (Sine wave 0.0 to 1.0)
                val lfoVal = (sin(lfoPhase) + 1.0) / 2.0 
                
                // 3. Apply Panning (Constant Power)
                val angle = lfoVal * (Math.PI / 2.0) 
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                val depth = preset.depth
                
                // Target: Low freqs go to one side, High to the other, swapping over time
                val targetL = (loL * sinA + hiL * cosA).toFloat()
                val targetR = (loR * cosA + hiR * sinA).toFloat()

                // Blend original with effect based on depth
                outL = floatL * (1f - depth) + targetL * depth
                outR = floatR * (1f - depth) + targetR * depth
            }

            if (isVolOn) {
                // Simple Volume Panning: As volume gets louder, push the image wider
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

            // Clip to prevent distortion (Hard clip at +/- 1.0)
            outL = outL.coerceIn(-1f, 1f)
            outR = outR.coerceIn(-1f, 1f)

            // Convert back to 16-bit
            val shortL = (outL * 32767f).toInt().toShort()
            val shortR = (outR * 32767f).toInt().toShort()

            // Write to output buffer (Little Endian)
            outBytes[outIndex++] = (shortL.toInt() and 0xFF).toByte()
            outBytes[outIndex++] = (shortL.toInt() shr 8).toByte()
            outBytes[outIndex++] = (shortR.toInt() and 0xFF).toByte()
            outBytes[outIndex++] = (shortR.toInt() shr 8).toByte()

            i += 4
        }

        // Advance LFO phase for the entire buffer at once (much more efficient)
        lfoPhase += lfoStep * frameCount
        if (lfoPhase > 2.0 * Math.PI) lfoPhase -= 2.0 * Math.PI

        inputBuffer.position(limit)
        outputBuffer.position(outIndex)
    }

    override fun flush() {
        super.flush()
        lfoPhase = 0.0
        volumeEnvelope = 0.0
        lowPassLeft?.reset()
        highPassLeft?.reset()
        lowPassRight?.reset()
        highPassRight?.reset()
    }

    override fun reset() {
        super.reset()
        flush()
    }

    // --- Inner Class: Biquad Filter (The Math) ---
    private class Biquad(val type: Type, val freqHz: Double, val sampleRateHz: Double, val q: Double = 0.7071) {
        enum class Type { LOWPASS, HIGHPASS }

        private val w0 = 2.0 * Math.PI * freqHz / sampleRateHz
        private val cosW0 = cos(w0)
        private val alpha = sin(w0) / (2.0 * q)

        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a0: Double
        private val a1: Double
        private val a2: Double

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            if (type == Type.LOWPASS) {
                b0 = (1.0 - cosW0) / 2.0
                b1 = 1.0 - cosW0
                b2 = (1.0 - cosW0) / 2.0
            } else {
                b0 = (1.0 + cosW0) / 2.0
                b1 = -(1.0 + cosW0)
                b2 = (1.0 + cosW0) / 2.0
            }
            a0 = 1.0 + alpha
            a1 = -2.0 * cosW0
            a2 = 1.0 - alpha
        }

        fun process(input: Double): Double {
            val y = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1
            x1 = input
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
}
