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

    enum class PanPreset {
        VOL_SMOOTH, VOL_GENTLE, VOL_CHOP,
        FREQ_HELICOPTER, FREQ_DEEP_BASS, FREQ_HI_HATS
    }

    @Volatile var currentMode: PanMode = PanMode.OFF
    @Volatile var currentPreset: PanPreset = PanPreset.VOL_SMOOTH
    @Volatile var lfoFreq: Float = 0.5f
    @Volatile var depth: Float = 1.0f

    private var leftPrevLow = 0.0f
    private var rightPrevLow = 0.0f

    private var isPlaying = false
    private var isPaused = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var lfoPhase = 0.0f

    private var currentExtractor: MediaExtractor? = null
    private var currentCodec: MediaCodec? = null
    private var currentSampleRate: Int = 44100

    fun playUri(context: Context, uri: Uri, startTimeMs: Long = 0, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (isPlaying) stop()
        isPlaying = true
        isPaused = false
        lfoPhase = 0.0f
        leftPrevLow = 0.0f
        rightPrevLow = 0.0f

        playbackThread = Thread {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null

            try {
                Log.d(TAG, "Starting playback for URI: $uri at ${startTimeMs}ms")
                extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)

                if (startTimeMs > 0) {
                    extractor.seekTo(startTimeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }

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

                currentSampleRate = actualSampleRate
                currentExtractor = extractor

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                currentCodec = codec

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
                    } else if (isPaused) {
                        Thread.sleep(20)
                    }
                }

                Handler(Looper.getMainLooper()).post { onSuccess() }

            } catch (e: Exception) {
                if (isPlaying) {
                    e.printStackTrace()
                    Log.e(TAG, "Playback error", e)
                    Handler(Looper.getMainLooper()).post { onError(e.toString()) }
                }
            } finally {
                // CRITICAL: DO NOT call stop() here - it causes thread deadlock
                try { codec?.stop(); codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
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

            var swing = (sin(lfoPhase) + 1.0f) / 2.0f

            when (currentPreset) {
                PanPreset.VOL_SMOOTH -> { /* Standard sine wave */ }
                PanPreset.VOL_GENTLE -> {
                    swing = (swing - 0.5f) * 0.4f + 0.5f
                }
                PanPreset.VOL_CHOP -> {
                    swing = if (swing > 0.5f) 1f else 0f
                }
                else -> {}
            }

            val leftGain = 1.0f - (swing * depth)
            val rightGain = swing * depth

            val outL = sampleL * leftGain
            val outR = sampleR * rightGain

            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (sL.toInt() shr 8).toByte()

            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i + 2] = (sR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (sR.toInt() shr 8).toByte()

            i += 4
            lfoPhase += lfoStep
            if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        val crossoverFreq = when (currentPreset) {
            PanPreset.FREQ_HELICOPTER -> 1000f
            PanPreset.FREQ_DEEP_BASS -> 200f
            PanPreset.FREQ_HI_HATS -> 6000f
            else -> 1000f
        }

        val alpha = 1.0f - exp(-2.0f * PI.toFloat() * crossoverFreq / sampleRate)
        var i = 0

        while (i < size - 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val swing = (sin(lfoPhase) + 1.0f) / 2.0f

            val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow)
            leftPrevLow = lowL
            val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow)
            rightPrevLow = lowR

            val highL = sampleL - lowL
            val highR = sampleR - lowR

            val outL = lowL * (1f - swing) + highL * swing
            val outR = highR * (1f - swing) + lowR * swing

            val finalL = sampleL + depth * (outL - sampleL)
            val finalR = sampleR + depth * (outR - sampleR)

            val sL = (finalL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (sL.toInt() shr 8).toByte()

            val sR = (finalR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i + 2] = (sR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (sR.toInt() shr 8).toByte()

            i += 4
            lfoPhase += lfoStep
            if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    fun seekTo(timeMs: Long) {
        val ext = currentExtractor ?: return
        val cod = currentCodec ?: return
        val track = audioTrack ?: return

        try {
            ext.seekTo(timeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            cod.flush()
            track.flush()
            lfoPhase = 0.0f
            leftPrevLow = 0.0f
            rightPrevLow = 0.0f
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed", e)
        }
    }

    fun refreshAudioTrack() {
        val track = audioTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_STOPPED) {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}

            val minBufferSize = AudioTrack.getMinBufferSize(
                currentSampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(currentSampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(minBufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = newTrack
            newTrack.play()
        } else if (track != null) {
            track.play()
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
        if (Thread.currentThread() == playbackThread) {
            isPlaying = false
            return
        }

        if (!isPlaying && playbackThread == null && audioTrack == null) return
        isPlaying = false
        val thread = playbackThread
        if (thread != null) {
            thread.interrupt()
            try { thread.join(2000) } catch (_: InterruptedException) {}
        }
        playbackThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        leftPrevLow = 0.0f
        rightPrevLow = 0.0f
        Log.d(TAG, "RawAudioPlayer stopped safely from main thread")
    }
}
