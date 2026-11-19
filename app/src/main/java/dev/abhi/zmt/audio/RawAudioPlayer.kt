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

    enum class PanMode { OFF, VOLUME, FREQUENCY }

    @Volatile var currentMode: PanMode = PanMode.OFF
    @Volatile var lfoFreq: Float = 0.5f
    @Volatile var depth: Float = 1.0f

    // Filter state for Frequency Pan
    private var leftPrevSample = 0.0f
    private var rightPrevSample = 0.0f
    private var leftPrevInput = 0.0f  // Needed for Highpass filter
    private var rightPrevInput = 0.0f // Needed for Highpass filter

    private var isPlaying = false
    private var isPaused = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var lfoPhase = 0.0f

    fun playUri(context: Context, uri: Uri, onError: (String) -> Unit, onSuccess: () -> Unit) {
        stop() // Safely stop any previous playback
        isPlaying = true
        isPaused = false
        lfoPhase = 0.0f

        playbackThread = Thread {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            
            try {
                Log.d(TAG, "Starting playback for URI: $uri")
                extractor = MediaExtractor()
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

                codec = MediaCodec.createDecoderByType(mime)
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

                while (isPlaying && !sawOutputEOS) {
                    if (Thread.currentThread().isInterrupted) break

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
                                PanMode.FREQUENCY -> applyFrequencyPan(bytes, bufferInfo.size, actualSampleRate, lfoStep)
                            }

                            if (!isPaused) {
                                audioTrack?.write(bytes, 0, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                            }
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
                if (isPlaying) {
                    e.printStackTrace()
                    Log.e(TAG, "Playback error", e)
                    Handler(Looper.getMainLooper()).post { onError(e.toString()) }
                }
            } finally {
                try { codec?.stop(); codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
                // Do NOT call stop() here, let the main thread handle lifecycle safely
            }
        }
        playbackThread?.start()
    }

    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
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

    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        // Sweeps between 200Hz and 4000Hz for a noticeable split
        val minCutoff = 200f
        val maxCutoff = 4000f
        var i = 0

        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
            val cutoff = minCutoff + (maxCutoff - minCutoff) * swing

            // Alpha for 1-pole filters
            val alpha = 1.0f - exp(-2.0f * PI.toFloat() * cutoff / sampleRate)

            // LEFT EAR: Lowpass Filter (Keeps Bass, Cuts Treble)
            // y[n] = y[n-1] + alpha * (x[n] - y[n-1])
            val lowL = leftPrevSample + alpha * (sampleL - leftPrevSample)
            leftPrevSample = lowL

            // RIGHT EAR: Highpass Filter (Keeps Treble, Cuts Bass)
            // y[n] = alpha * (y[n-1] + x[n] - x[n-1])
            val highR = alpha * (rightPrevSample + sampleR - rightPrevInput)
            rightPrevSample = highR
            rightPrevInput = sampleR

            // Mix dry/wet based on depth
            val outL = sampleL + depth * (lowL - sampleL)
            val outR = sampleR + depth * (highR - sampleR)

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

    fun pause() {
        isPaused = true
        audioTrack?.pause()
    }

    fun resume() {
        isPaused = false
        audioTrack?.play()
    }

    @Synchronized
    fun stop() {
        if (!isPlaying && playbackThread == null && audioTrack == null) return
        
        isPlaying = false
        val thread = playbackThread
        if (thread != null) {
            thread.interrupt()
            try {
                thread.join(2000) // CRITICAL: Wait for thread to finish before releasing resources
            } catch (_: InterruptedException) {}
        }
        playbackThread = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        // Reset filter states
        leftPrevSample = 0.0f
        rightPrevSample = 0.0f
        leftPrevInput = 0.0f
        rightPrevInput = 0.0f
        
        Log.d(TAG, "RawAudioPlayer stopped and released safely")
    }
}
