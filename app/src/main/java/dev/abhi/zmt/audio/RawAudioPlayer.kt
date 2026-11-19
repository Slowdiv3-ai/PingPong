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

       // --- NEW: PRESET SYSTEM ---
    enum class PanPreset {
        // Volume Presets
        VOL_SMOOTH, VOL_GENTLE, VOL_CHOP,
        // Frequency Presets
        FREQ_HELICOPTER, FREQ_DEEP_BASS, FREQ_HI_HATS
    }

    @Volatile var currentMode: PanMode = PanMode.OFF
    @Volatile var lfoFreq: Float = 0.5f
    @Volatile var depth: Float = 1.0f

    private var leftPrevLow = 0.0f
    private var rightPrevLow = 0.0f

    private var isPlaying = false
    private var isPaused = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var lfoPhase = 0.0f

    // Keep references so we can seek and refresh the audio track
    private var currentExtractor: MediaExtractor? = null
    private var currentCodec: MediaCodec? = null
    private var currentSampleRate: Int = 44100
    
    // Debounce to prevent ExoPlayer from spamming playUri
    private var lastUri: String? = null
    private var lastStartTime: Long = -1

    fun playUri(context: Context, uri: Uri, startTimeMs: Long = 0, onError: (String) -> Unit, onSuccess: () -> Unit) {
        val uriString = uri.toString()
        
        // DEBOUNCE: If ExoPlayer spams the same URI and time, ignore it
        if (uriString == lastUri && Math.abs(startTimeMs - lastStartTime) < 500) {
            Log.d(TAG, "Ignoring duplicate playUri call")
            return
        }
        lastUri = uriString
        lastStartTime = startTimeMs

        // Safely stop previous playback from the MAIN thread
        stop() 
        
        isPlaying = true
        isPaused = false
        lfoPhase = 0.0f

        playbackThread = Thread {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            
            try {
                Log.d(TAG, "Starting playback for URI: $uri at ${startTimeMs}ms")
                extractor = MediaExtractor()
                
                if (uri.scheme == "file") {
                    val file = java.io.File(uri.path)
                    if (file.exists()) {
                        val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        extractor.setDataSource(fd.fileDescriptor)
                    } else {
                        throw Exception("File not found: ${uri.path}")
                    }
                } else {
                    extractor.setDataSource(context, uri, null)
                }

                // SEEK SUPPORT
                if (startTimeMs > 0) {
                    val seekSuccess = extractor.seekTo(startTimeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    Log.d(TAG, "Seek to ${startTimeMs}ms result: $seekSuccess")
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

                if (audioTrackIndex == -1 || format == null) throw Exception("No audio track found")

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                codec = MediaCodec.createDecoderByType(mime)
                currentExtractor = extractor
                currentCodec = codec
                currentSampleRate = actualSampleRate
                codec.configure(format, null, null, 0)
                codec.start()

                val minBufferSize = AudioTrack.getMinBufferSize(actualSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(actualSampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
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
                        // ADD THIS LOG TO PROVE IT'S WORKING
                        if (bufferInfo.size > 0) {
                            android.util.Log.d("RawAudioPlayer", "Decoding chunk: ${bufferInfo.size} bytes")
                        }
                        
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        // ... rest of the code ...
                        if (bufferInfo.size > 0 && channelCount == 2 && outputBuffer != null) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            val lfoStep = 2.0f * PI.toFloat() * (lfoFreq / actualSampleRate)

                            when (currentMode) {
                                PanMode.OFF -> {}
                                PanMode.VOLUME -> applyVolumePan(bytes, bufferInfo.size, lfoStep)
                                PanMode.FREQUENCY -> applyFrequencyBandSwap(bytes, bufferInfo.size, actualSampleRate, lfoStep)
                            }

                            if (!isPaused) {
                                audioTrack?.write(bytes, 0, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    } else if (isPaused) {
                        Thread.sleep(20)
                    }
                }
                Handler(Looper.getMainLooper()).post { onSuccess() }
            } catch (e: Exception) {
                if (isPlaying) Handler(Looper.getMainLooper()).post { onError(e.toString()) }
            } finally {
                // DO NOT CALL stop() HERE. It causes the crash.
                try { codec?.stop(); codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        }
        playbackThread?.start()
    }

    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f

            var swing = (sin(lfoPhase) + 1.0f) / 2.0f
            
            // Apply Preset Math
            when (currentPreset) {
                PanPreset.VOL_SMOOTH -> { /* Standard sine wave */ }
                PanPreset.VOL_GENTLE -> { 
                    // Compress the swing so it only moves 40% left/right instead of 100%
                    swing = (swing - 0.5f) * 0.4f + 0.5f 
                }
                PanPreset.VOL_CHOP -> { 
                    // Square wave: instantly jumps from left to right
                    swing = if (swing > 0.5f) 1f else 0f 
                }
                else -> {}
            }

            val leftGain = 1.0f - (swing * depth)
            val rightGain = swing * depth

            val outL = sampleL * leftGain
            val outR = sampleR * rightGain

            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()

            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        // Determine crossover frequency based on preset
        val crossoverFreq = when (currentPreset) {
            PanPreset.FREQ_HELICOPTER -> 1000f  // Standard mid-range swap
            PanPreset.FREQ_DEEP_BASS -> 200f    // Only swaps sub-bass/808s
            PanPreset.FREQ_HI_HATS -> 6000f     // Only swaps cymbals/air
            else -> 1000f
        }

        val alpha = 1.0f - exp(-2.0f * PI.toFloat() * crossoverFreq / sampleRate)
        var i = 0

        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f

            var swing = (sin(lfoPhase) + 1.0f) / 2.0f
            
            // The "DJ Scratch" effect (optional square wave for frequency)
            // if (currentPreset == PanPreset.FREQ_SCRATCH) swing = if (swing > 0.5f) 1f else 0f

            val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow); leftPrevLow = lowL
            val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow); rightPrevLow = lowR
            
            val highL = sampleL - lowL
            val highR = sampleR - lowR

            val outL = lowL * (1f - swing) + highL * swing
            val outR = highR * (1f - swing) + lowR * swing

            val finalL = sampleL + depth * (outL - sampleL)
            val finalR = sampleR + depth * (outR - sampleR)

            val sL = (finalL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (finalR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()

            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }
    fun pause() { isPaused = true; audioTrack?.pause() }
    fun resume() { isPaused = false; audioTrack?.play() }



    fun seekTo(timeMs: Long) {
        val ext = currentExtractor ?: return
        val cod = currentCodec ?: return
        val track = audioTrack ?: return

        try {
            // 1. Seek the extractor to the exact microsecond
            ext.seekTo(timeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            // 2. Flush the decoder and audiotrack to clear old buffers
            cod.flush()
            track.flush()
            // 3. Reset LFO phase so the effect doesn't jump weirdly
            lfoPhase = 0.0f
            leftPrevLow = 0.0f
            rightPrevLow = 0.0f
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed", e)
        }
    }

    fun refreshAudioTrack() {
        // When Bluetooth/Speaker switches, the old AudioTrack dies. 
        // We recreate it so the audio route is fixed.
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
            track.play() // Force play if it was just paused
        }
    }
    
    @Synchronized
    fun stop() {
        // CRITICAL FIX: If stop() is called from the background thread, just set flags.
        // Do NOT call thread.join() on the current thread.
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
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        leftPrevLow = 0.0f; rightPrevLow = 0.0f
        Log.d(TAG, "RawAudioPlayer stopped safely from main thread")
    }
}
