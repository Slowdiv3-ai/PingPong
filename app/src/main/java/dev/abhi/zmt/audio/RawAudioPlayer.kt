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

    // --- DYNAMIC DSP PARAMETERS (controlled by the UI) ---
    enum class PanMode { OFF, VOLUME, FREQUENCY }

    // @Volatile makes these safe to change from the UI thread while the audio thread reads them
    @Volatile var currentMode: PanMode = PanMode.OFF
    @Volatile var lfoFreq: Float = 0.5f   // sweep speed in Hz
    @Volatile var depth: Float = 0.8f     // wet/dry intensity (0 = dry, 1 = full effect)

    // Filter state for Frequency Pan (1-pole lowpass)
    private var leftPrevSample = 0.0f
    private var rightPrevSample = 0.0f

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

                // Reset DSP state for a fresh playback
                lfoPhase = 0.0f
                leftPrevSample = 0.0f
                rightPrevSample = 0.0f

                while (isPlaying && !sawOutputEOS) {
                    // 1. Feed compressed data into the decoder
                    if (!sawInputEOS) {
                        val inputBufferIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    // 2. Pull decoded PCM out of the decoder
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                        if (bufferInfo.size > 0 && channelCount == 2 && outputBuffer != null) {
                            // Copy read-only decoder buffer into a writable ByteArray
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)

                            // Recompute step every loop so speed changes apply live
                            val lfoStep = 2.0f * PI.toFloat() * (lfoFreq / actualSampleRate)

                            // Apply DSP based on the mode selected in the UI
                            when (currentMode) {
                                PanMode.OFF -> { /* pass-through, do nothing */ }
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
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed")
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

    // LFO phase is shared across both modes so switching mid-playback stays smooth
    private var lfoPhase = 0.0f

    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val pan = (sin(lfoPhase) + 1.0f) / 2.0f
            val angle = pan * (PI.toFloat() / 2.0f)
            val leftGain = cos(angle)
            val rightGain = sin(angle)

            // Wet/dry mix: depth=0 -> dry, depth=1 -> full pan
            val outL = sampleL * (1f - depth + depth * leftGain)
            val outR = sampleR * (1f - depth + depth * rightGain)

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

    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        val minCutoff = 200f
        val maxCutoff = 8000f
        var i = 0

        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val lfoValue = (sin(lfoPhase) + 1.0f) / 2.0f
            val leftCutoff = minCutoff + (maxCutoff - minCutoff) * lfoValue
            val rightCutoff = maxCutoff - (maxCutoff - minCutoff) * lfoValue

            val alphaL = 1.0f - exp(-2.0f * PI.toFloat() * leftCutoff / sampleRate)
            val alphaR = 1.0f - exp(-2.0f * PI.toFloat() * rightCutoff / sampleRate)

            val filteredL = leftPrevSample + alphaL * (sampleL - leftPrevSample)
            val filteredR = rightPrevSample + alphaR * (sampleR - rightPrevSample)
            leftPrevSample = filteredL
            rightPrevSample = filteredR

            // Wet/dry mix: depth=0 -> dry, depth=1 -> fully filtered
            val outL = sampleL + depth * (filteredL - sampleL)
            val outR = sampleR + depth * (filteredR - sampleR)

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
