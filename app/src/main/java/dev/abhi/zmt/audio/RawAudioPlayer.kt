package dev.abhi.zmt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.*

class RawAudioPlayer {

    companion object {
        private const val TAG = "RawAudioPlayer"
    }

    // --- DYNAMIC DSP PARAMETERS ---
    enum class PanMode { OFF, VOLUME, FREQUENCY }

    @Volatile var currentMode: PanMode = PanMode.OFF
    @Volatile var lfoFreq: Float = 0.5f   // sweep speed in Hz
    @Volatile var depth: Float = 1.0f     // Intensity (Default 1.0 for max effect)

    // Filter state for Frequency Pan
    private var leftPrevSample = 0.0f
    private var rightPrevSample = 0.0f
    // Second pole for steeper filtering (makes the separation sharper)
    private var leftPrevSample2 = 0.0f
    private var rightPrevSample2 = 0.0f

    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    fun playUri(context: Context, uri: Uri, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (isPlaying) stop()
        isPlaying = true

        playbackThread = Thread {
            val extractor = MediaExtractor()
            try {
                Log.d(TAG, "Starting playback for URI: $uri")
                extractor.setDataSource(context, uri, null)

                var audioTrackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        format = f
                        break
                    }
                }

                if (audioTrackIndex == -1 || format == null) {
                    throw Exception("No audio track found in file")
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                Log.d(TAG, "Found audio track: $mime, SR: $actualSampleRate, Ch: $channelCount")

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val minBufferSize = AudioTrack.getMinBufferSize(
                    actualSampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(actualSampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                lfoPhase = 0.0f
                leftPrevSample = 0.0f
                rightPrevSample = 0.0f
                leftPrevSample2 = 0.0f
                rightPrevSample2 = 0.0f

                while (isPlaying && !sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inputBufferIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                        if (bufferInfo.size > 0 && channelCount == 2 && outputBuffer != null) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)

                            val lfoStep = 2.0f * PI.toFloat() * (lfoFreq / actualSampleRate)

                            when (currentMode) {
                                PanMode.OFF -> { /* pass-through */ }
                                PanMode.VOLUME -> applyVolumePan(bytes, bufferInfo.size, lfoStep)
                                PanMode.FREQUENCY ->
                                    applyFrequencyPan(bytes, bufferInfo.size, actualSampleRate, lfoStep)
                            }

                            audioTrack?.write(bytes, 0, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                }

                Log.d(TAG, "Playback loop finished successfully")
                Handler(Looper.getMainLooper()).post { onSuccess() }

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Playback error", e)
                Handler(Looper.getMainLooper()).post { onError(e.toString()) }
            } finally {
                stop()
            }
        }
        playbackThread?.start()
    }

    private var lfoPhase = 0.0f

    // --- AGGRESSIVE VOLUME PAN ---
    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            // Sine wave goes -1 to 1. We map it to 0 to 1.
            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
            
            // Linear Panning: Hard swing from Left to Right
            // If swing is 0 (Left): leftGain=1, rightGain=0
            // If swing is 1 (Right): leftGain=0, rightGain=1
            // We use 'depth' to control how extreme the swing is.
            val leftGain = 1.0f - (swing * depth)
            val rightGain = swing * depth

            val outL = sampleL * leftGain
            val outR = sampleR * rightGain

            val shortL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (shortL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (shortL.toInt() shr 8).toByte()

            val shortR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i + 2] = (shortR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (shortR.toInt() shr 8).toByte()

            i += 4
            lfoPhase += lfoStep
            if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    // --- AGGRESSIVE FREQUENCY PAN ---
    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        // Extreme range: 40Hz (Sub bass/Kicks) to 16000Hz (Hi-hats/Air)
        val minCutoff = 40f
        val maxCutoff = 16000f
        var i = 0

        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
            
            // Left ear gets Low frequencies when swing is 0, High when swing is 1
            // Right ear gets High frequencies when swing is 0, Low when swing is 1
            val leftCutoff = minCutoff + (maxCutoff - minCutoff) * swing
            val rightCutoff = maxCutoff - (maxCutoff - minCutoff) * swing

            // Calculate filter coefficients
            val alphaL = 1.0f - exp(-2.0f * PI.toFloat() * leftCutoff / sampleRate)
            val alphaR = 1.0f - exp(-2.0f * PI.toFloat() * rightCutoff / sampleRate)

            // Apply 2-Pole Filter (Cascading two 1-pole filters for sharper separation)
            // Pole 1
            val tempL1 = leftPrevSample + alphaL * (sampleL - leftPrevSample)
            val tempR1 = rightPrevSample + alphaR * (sampleR - rightPrevSample)
            leftPrevSample = tempL1
            rightPrevSample = tempR1

            // Pole 2 (Makes the "muffled" sound much mudier and the "bright" sound much sharper)
            val tempL2 = leftPrevSample2 + alphaL * (tempL1 - leftPrevSample2)
            val tempR2 = rightPrevSample2 + alphaR * (tempR1 - rightPrevSample2)
            leftPrevSample2 = tempL2
            rightPrevSample2 = tempR2

            // Mix dry/wet based on depth
            val outL = sampleL + depth * (tempL2 - sampleL)
            val outR = sampleR + depth * (tempR2 - sampleR)

            val shortL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (shortL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (shortL.toInt() shr 8).toByte()

            val shortR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i + 2] = (shortR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (shortR.toInt() shr 8).toByte()

            i += 4
            lfoPhase += lfoStep
            if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "RawAudioPlayer stopped and released")
    }
}
