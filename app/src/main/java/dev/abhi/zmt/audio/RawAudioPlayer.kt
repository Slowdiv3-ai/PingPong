package dev.abhi.zmt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
    private val lfoFreq = 0.5f // Swaps left/right every 2 seconds
    private val depth = 0.8f   // Pan intensity

    fun playUri(context: Context, uri: Uri) {
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
                    Log.e(TAG, "No audio track found in file")
                    return@Thread
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                Log.d(TAG, "Found audio track: $mime, SampleRate: $actualSampleRate, Channels: $channelCount")

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                Log.d(TAG, "MediaCodec started")

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
                Log.d(TAG, "AudioTrack created and playing. Min buffer: $minBufferSize")

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                lfoPhase = 0.0f
                val lfoStep = 2.0f * Math.PI.toFloat() * (lfoFreq / actualSampleRate)
                var framesProcessed = 0

                while (isPlaying && !sawOutputEOS) {
                    // 1. Feed data to decoder
                    if (!sawInputEOS) {
                        val inputBufferIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                                Log.d(TAG, "Reached end of input stream")
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // 2. Get decoded data
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        
                        if (bufferInfo.size > 0 && channelCount == 2 && outputBuffer != null) {
                            // Apply our Volume Pan math
                            applyVolumePan(outputBuffer, bufferInfo.size, lfoStep)
                            
                            // CRITICAL FIX: Explicitly write the modified bytes to the AudioTrack!
                            val bytesWritten = audioTrack?.write(outputBuffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                            if (bytesWritten != null && bytesWritten > 0) {
                                framesProcessed++
                                if (framesProcessed % 50 == 0) {
                                    Log.d(TAG, "Successfully wrote $bytesWritten bytes to AudioTrack")
                                }
                            }
                        }

                        // Release the buffer back to the codec (false = we handled the rendering manually)
                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                            Log.d(TAG, "Reached end of output stream")
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed: ${codec.outputFormat}")
                    }
                }

                Log.d(TAG, "Playback loop finished")

            } catch (e: Exception) {
                Log.e(TAG, "Playback error occurred", e)
            } finally {
                stop()
            }
        }
        playbackThread?.start()
    }

    private fun applyVolumePan(buffer: ByteBuffer, size: Int, lfoStep: Float) {
        val limit = buffer.position() + size
        var i = buffer.position()

        while (i < limit) {
            val b0 = buffer.get(i).toInt() and 0xFF
            val b1 = buffer.get(i + 1).toInt() and 0xFF
            val sampleL = (b0 or (b1 shl 8)).toShort() / 32768f

            val b2 = buffer.get(i + 2).toInt() and 0xFF
            val b3 = buffer.get(i + 3).toInt() and 0xFF
            val sampleR = (b2 or (b3 shl 8)).toShort() / 32768f

            val pan = (sin(lfoPhase) + 1.0f) / 2.0f
            val angle = pan * (Math.PI.toFloat() / 2.0f)
            val leftGain = cos(angle)
            val rightGain = sin(angle)

            var outL = (sampleL * leftGain * depth).coerceIn(-1f, 1f)
            var outR = (sampleR * rightGain * depth).coerceIn(-1f, 1f)

            val shortL = (outL * 32767f).toInt().toShort()
            buffer.put(i, (shortL.toInt() and 0xFF).toByte())
            buffer.put(i + 1, (shortL.toInt() shr 8).toByte())

            val shortR = (outR * 32767f).toInt().toShort()
            buffer.put(i + 2, (shortR.toInt() and 0xFF).toByte())
            buffer.put(i + 3, (shortR.toInt() shr 8).toByte())

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
        Log.d(TAG, "RawAudioPlayer stopped and released")
    }
}
