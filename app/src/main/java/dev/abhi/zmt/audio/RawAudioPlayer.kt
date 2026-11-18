package dev.abhi.zmt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.*

class RawAudioPlayer {

    companion object {
        private const val TAG = "RawAudioPlayer"
    }

    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    // Volume Pan Parameters
    private var lfoPhase = 0.0f
    private val lfoFreq = 0.5f // 0.5 Hz cycle (swaps left/right every 2 seconds)
    private val depth = 0.8f   // How extreme the pan is

    fun playFile(filePath: String, sampleRate: Int = 44100) {
        if (isPlaying) stop()
        isPlaying = true

        playbackThread = Thread {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(filePath)
                
                // Find the audio track
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
                    Log.e(TAG, "No audio track found")
                    return@Thread
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                Log.d(TAG, "Decoding: $mime, SR: $actualSampleRate, Channels: $channelCount")

                // Create the decoder
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                // Create the raw AudioTrack (Stereo, 16-bit PCM)
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
                    .setBufferSizeInBytes(minBufferSize * 4) // Larger buffer for smooth playback
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                val bufferInfo = MediaCodec.BufferInfo()
                val inputBuffers = codec.inputBuffers
                val outputBuffers = codec.outputBuffers
                var sawInputEOS = false
                var sawOutputEOS = false

                lfoPhase = 0.0f
                val lfoStep = 2.0f * Math.PI.toFloat() * (lfoFreq / actualSampleRate)

                while (isPlaying && !sawOutputEOS) {
                    // 1. Feed data to decoder
                    if (!sawInputEOS) {
                        val inputBufferIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = inputBuffers[inputBufferIndex]
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // 2. Get decoded data and apply Volume Pan
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = outputBuffers[outputBufferIndex]
                        
                        if (bufferInfo.size > 0 && channelCount == 2) {
                            applyVolumePan(outputBuffer, bufferInfo.size, actualSampleRate, lfoStep)
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, true) // 'true' renders to AudioTrack

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
            } finally {
                stop()
            }
        }
        playbackThread?.start()
    }

    private fun applyVolumePan(buffer: ByteBuffer, size: Int, sampleRate: Int, lfoStep: Float) {
        val limit = buffer.position() + size
        var i = buffer.position()

        while (i < limit) {
            // Read 16-bit Little Endian Stereo
            val b0 = buffer.get(i).toInt() and 0xFF
            val b1 = buffer.get(i + 1).toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = buffer.get(i + 2).toInt() and 0xFF
            val b3 = buffer.get(i + 3).toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            // Calculate Panning (Constant Power)
            val pan = (sin(lfoPhase) + 1.0f) / 2.0f
            val angle = pan * (Math.PI.toFloat() / 2.0f)
            val leftGain = cos(angle)
            val rightGain = sin(angle)

            var outL = (sampleL * leftGain * depth).coerceIn(-1f, 1f)
            var outR = (sampleR * rightGain * depth).coerceIn(-1f, 1f)

            // Write back 16-bit Little Endian
            val shortL = (outL * 32767f).toInt().toShort()
            buffer.put(i, (shortL.toInt() and 0xFF).toByte())
            buffer.put(i + 1, (shortL.toInt() shr 8).toByte())

            val shortR = (outR * 32767f).toInt().toShort()
            buffer.put(i + 2, (shortR.toInt() and 0xFF).toByte())
            buffer.put(i + 3, (shortR.toInt() shr 8).toByte)

            i += 4
            lfoPhase += lfoStep
            if (lfoPhase > 2.0f * Math.PI.toFloat()) lfoPhase -= 2.0f * Math.PI.toFloat()
        }
        buffer.position(limit)
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
