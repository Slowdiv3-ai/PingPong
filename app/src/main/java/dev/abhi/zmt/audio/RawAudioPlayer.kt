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

    enum class PanMode { OFF, VOLUME, FREQUENCY, TREMOLO, LOFI, TELEPHONE }

    enum class PanPreset {
        VOL_SMOOTH, VOL_GENTLE, VOL_CHOP,
        FREQ_HELICOPTER, FREQ_DEEP_BASS, FREQ_HI_HATS, FREQ_UNDERWATER,
        TREMOLO_SINE, TREMOLO_SQUARE,
        LOFI_8BIT, LOFI_4BIT,
        TEL_VINTAGE, TEL_RADIO
    }

    @Volatile var currentMode: PanMode = PanMode.OFF
    @Volatile var currentPreset: PanPreset = PanPreset.VOL_SMOOTH
    @Volatile var lfoFreq: Float = 0.5f
    @Volatile var depth: Float = 1.0f

    private var leftPrevLow = 0.0f; private var rightPrevLow = 0.0f
    private var leftPrevHigh = 0.0f; private var rightPrevHigh = 0.0f
    private var leftPrevInput = 0.0f; private var rightPrevInput = 0.0f
    private var lofiHoldL = 0.0f; private var lofiHoldR = 0.0f
    private var lofiCounter = 0

    private var isPlaying = false
    private var isPaused = false
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var lfoPhase = 0.0f
    private var currentSampleRate: Int = 44100

    fun playUri(context: Context, uri: Uri, startTimeMs: Long = 0, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (playbackThread?.isAlive == true) {
            Log.d(TAG, "Ignoring duplicate playUri call")
            return
        }

        stop()
        isPlaying = true
        isPaused = false
        lfoPhase = 0.0f
        leftPrevLow = 0.0f; rightPrevLow = 0.0f
        leftPrevHigh = 0.0f; rightPrevHigh = 0.0f
        leftPrevInput = 0.0f; rightPrevInput = 0.0f
        lofiCounter = 0

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

                var audioTrackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) { audioTrackIndex = i; format = f; break }
                }
                if (audioTrackIndex == -1 || format == null) throw Exception("No audio track found")

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                currentSampleRate = actualSampleRate

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val minBufferSize = AudioTrack.getMinBufferSize(actualSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(actualSampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(minBufferSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
                audioTrack?.play()

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false; var sawOutputEOS = false

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
                                PanMode.OFF -> {}
                                PanMode.VOLUME -> applyVolumePan(bytes, bufferInfo.size, lfoStep)
                                PanMode.FREQUENCY -> applyFrequencyPan(bytes, bufferInfo.size, actualSampleRate, lfoStep)
                                PanMode.TREMOLO -> applyTremolo(bytes, bufferInfo.size, lfoStep)
                                PanMode.LOFI -> applyLoFi(bytes, bufferInfo.size, lfoStep)
                                PanMode.TELEPHONE -> applyTelephone(bytes, bufferInfo.size, actualSampleRate, lfoStep)
                            }
                            if (!isPaused) audioTrack?.write(bytes, 0, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    } else if (isPaused) { Thread.sleep(20) }
                }
                Handler(Looper.getMainLooper()).post { onSuccess() }
            } catch (e: Exception) {
                if (isPlaying) Handler(Looper.getMainLooper()).post { onError(e.toString()) }
            } finally {
                try { codec?.stop(); codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        } // <--- THIS BRACE CLOSES THE THREAD LAMBDA
        playbackThread?.start()
    } // <--- THIS BRACE CLOSES playUri

    // --- DSP EFFECTS ---
    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            var swing = (sin(lfoPhase) + 1.0f) / 2.0f
            if (currentPreset == PanPreset.VOL_GENTLE) swing = (swing - 0.5f) * 0.4f + 0.5f
            if (currentPreset == PanPreset.VOL_CHOP) swing = if (swing > 0.5f) 1f else 0f
            val outL = sampleL * (1.0f - (swing * depth)); val outR = sampleR * (swing * depth)
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
            var outL = sampleL; var outR = sampleR

            if (currentPreset == PanPreset.FREQ_UNDERWATER) {
                val logMin = ln(200f); val logMax = ln(16000f)
                val currentLogCutoff = logMax - (logMax - logMin) * (swing * depth)
                val alpha = 1.0f - exp(-2.0f * PI.toFloat() * exp(currentLogCutoff) / sampleRate)
                val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow); leftPrevLow = lowL
                val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow); rightPrevLow = lowR
                outL = lowL; outR = lowR
            } else {
                val baseFreq = when (currentPreset) { PanPreset.FREQ_DEEP_BASS -> 200f; PanPreset.FREQ_HI_HATS -> 6000f; else -> 1000f }
                val alpha = 1.0f - exp(-2.0f * PI.toFloat() * baseFreq / sampleRate)
                val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow); leftPrevLow = lowL
                val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow); rightPrevLow = lowR
                val highL = sampleL - lowL; val highR = sampleR - lowR
                val swapL = lowL * (1f - swing) + highL * swing; val swapR = highR * (1f - swing) + lowR * swing
                outL = sampleL + depth * (swapL - sampleL); outR = sampleR + depth * (swapR - sampleR)
            }
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyTremolo(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            var lfoVal = (sin(lfoPhase) + 1.0f) / 2.0f
            if (currentPreset == PanPreset.TREMOLO_SQUARE) lfoVal = if (lfoVal > 0.5f) 1f else 0f
            val gain = 0.3f + (0.7f * lfoVal * depth)
            val outL = sampleL * gain; val outR = sampleR * gain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyLoFi(bytes: ByteArray, size: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
            val currentBits = 16f - (14f * depth * swing)
            val safeBits = currentBits.coerceIn(2f, 16f)
            val levels = (1 shl safeBits.toInt()).toFloat()
            val reduction = (1 + (4 * lfoFreq * swing)).toInt().coerceIn(1, 8)
            lofiCounter++
            if (lofiCounter >= reduction) {
                lofiCounter = 0
                lofiHoldL = (sampleL * levels).toInt().toFloat() / levels
                lofiHoldR = (sampleR * levels).toInt().toFloat() / levels
            }
            val alpha = 0.2f
            val smoothL = leftPrevLow + alpha * (lofiHoldL - leftPrevLow); leftPrevLow = smoothL
            val smoothR = rightPrevLow + alpha * (lofiHoldR - rightPrevLow); rightPrevLow = smoothR
            val sL = (smoothL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (smoothR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyTelephone(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float) {
        var i = 0
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val swing = (sin(lfoPhase) + 1.0f) / 2.0f
            val baseHighCut = if (currentPreset == PanPreset.TEL_VINTAGE) 2500f else 3500f
            val currentHighCut = baseHighCut + (4000f * depth * swing)
            val currentLowCut = 300f + (1500f * depth * swing)
            val alphaLow = 1.0f - exp(-2.0f * PI.toFloat() * currentHighCut / sampleRate)
            val alphaHigh = exp(-2.0f * PI.toFloat() * currentLowCut / sampleRate)
            val lpL = leftPrevLow + alphaLow * (sampleL - leftPrevLow); leftPrevLow = lpL
            val lpR = rightPrevLow + alphaLow * (sampleR - rightPrevLow); rightPrevLow = lpR
            val hpL = alphaHigh * (leftPrevHigh + lpL - leftPrevInput); leftPrevHigh = hpL; leftPrevInput = lpL
            val hpR = alphaHigh * (rightPrevHigh + lpR - rightPrevInput); rightPrevHigh = hpR; rightPrevInput = lpR
            val outL = sampleL + depth * (hpL - sampleL); val outR = sampleR + depth * (hpR - sampleR)
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; lfoPhase += lfoStep; if (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()
        }
    }

    // --- LIFECYCLE ---
    fun seekTo(timeMs: Long) {
        val track = audioTrack ?: return
        try {
            track.flush()
            lfoPhase = 0.0f; leftPrevLow = 0.0f; rightPrevLow = 0.0f
        } catch (e: Exception) { Log.e(TAG, "Seek failed", e) }
    }

    fun refreshAudioTrack() {
        val track = audioTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_STOPPED) {
            try { track.stop(); track.release() } catch (_: Exception) {}
            val minBufferSize = AudioTrack.getMinBufferSize(currentSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(currentSampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(minBufferSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
            audioTrack = newTrack
            newTrack.play()
        } else if (track != null) { track.play() }
    }

    fun pause() { isPaused = true; audioTrack?.pause() }
    fun resume() { isPaused = false; audioTrack?.play() }

    @Synchronized
    fun stop() {
        if (Thread.currentThread() == playbackThread) { isPlaying = false; return }
        if (!isPlaying && playbackThread == null && audioTrack == null) return
        isPlaying = false
        playbackThread?.interrupt()
        try { playbackThread?.join(1000) } catch (_: InterruptedException) {}
        playbackThread = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
    }
} // <--- THIS BRACE CLOSES THE CLASS
