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

    enum class PanMode { OFF, VOLUME, FREQUENCY, TREMOLO, LOFI, TELEPHONE, CUSTOM }

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

    @Volatile var customPresets: List<PanPreset> = emptyList()
    @Volatile var intervalSeconds: Float = 10f
    @Volatile var customModeIsLayered: Boolean = false

    @Volatile private var fadeOutStartMs = 0L
    @Volatile private var fadeOutDurationMs = 0L

    private var leftPrevLow = 0.0f; private var rightPrevLow = 0.0f
    private var leftPrevHigh = 0.0f; private var rightPrevHigh = 0.0f
    private var leftPrevInput = 0.0f; private var rightPrevInput = 0.0f
    private var lofiHoldL = 0.0f; private var lofiHoldR = 0.0f
    private var lofiSmoothL = 0.0f; private var lofiSmoothR = 0.0f
    private var lofiCounter = 0

    @Volatile var isExoPlaying = true
    @Volatile private var isPlaying = false
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var outgoingThread: Thread? = null
    private var lfoPhase = 0.0f
    @Volatile private var currentAudioTrack: AudioTrack? = null

    private fun resetFilters() {
        leftPrevLow = 0.0f; rightPrevLow = 0.0f
        leftPrevHigh = 0.0f; rightPrevHigh = 0.0f
        leftPrevInput = 0.0f; rightPrevInput = 0.0f
        lofiHoldL = 0.0f; lofiHoldR = 0.0f
        lofiSmoothL = 0.0f; lofiSmoothR = 0.0f
        lofiCounter = 0
        lfoPhase = 0.0f
    }

@Synchronized
fun playUri(
    context: Context, 
    uri: Uri, 
    startTimeMs: Long = 0, 
    crossfadeSeconds: Int = 0, 
    onError: (String) -> Unit, 
    onSuccess: () -> Unit
) {
    val isOverlap = crossfadeSeconds > 0 && playbackThread?.isAlive == true
    val fadeDurationMs = (crossfadeSeconds * 1000).toLong()

    if (isOverlap) {
        outgoingThread = playbackThread
        fadeOutStartMs = System.currentTimeMillis()
        fadeOutDurationMs = fadeDurationMs
        val fadeInStartMs = System.currentTimeMillis() + (fadeDurationMs / 2)
        
        playbackThread = Thread {
            try {
                Thread.sleep(fadeDurationMs / 2)
                decodeAndPlay(context, uri, startTimeMs, fadeInStartMs, fadeDurationMs / 2, onError, onSuccess)
            } catch (_: InterruptedException) { 
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onError(e.toString()) }
            }
        }.apply { start() }
    } else {
        // 1. Interrupt old thread and immediately stop/flush its AudioTrack so WRITE_BLOCKING unblocks without .join()
        playbackThread?.interrupt()
        outgoingThread?.interrupt()
        outgoingThread = null
        
        val oldTrack = currentAudioTrack
        currentAudioTrack = null
        try {
            oldTrack?.pause()
            oldTrack?.flush()
            oldTrack?.release()
        } catch (_: Exception) {}

        playbackThread = Thread {
            decodeAndPlay(context, uri, startTimeMs, System.currentTimeMillis(), if (crossfadeSeconds > 0) fadeDurationMs else 0, onError, onSuccess)
        }.apply { start() }
    }
}


    private fun decodeAndPlay(
        context: Context, uri: Uri, startTimeMs: Long, 
        fadeInStartMs: Long, fadeInDurationMs: Long,
        onError: (String) -> Unit, onSuccess: () -> Unit
    ) {
        // Ensure a clean slate for every new track
        resetFilters()

        var localExtractor: MediaExtractor? = null
        var localCodec: MediaCodec? = null
        var localAudioTrack: AudioTrack? = null

        val isOutgoing = Thread.currentThread() == outgoingThread

        try {
            localExtractor = MediaExtractor()
            if (uri.scheme == "file") {
                val file = java.io.File(uri.path)
                if (file.exists()) {
                    val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    localExtractor.setDataSource(fd.fileDescriptor)
                } else throw Exception("File not found")
            } else {
                localExtractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until localExtractor.trackCount) {
                val f = localExtractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) { audioTrackIndex = i; format = f; break }
            }
            if (audioTrackIndex == -1 || format == null) throw Exception("No audio track found")

            localExtractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            if (startTimeMs > 0) {
                localExtractor.seekTo(startTimeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            localCodec = MediaCodec.createDecoderByType(mime)
            localCodec.configure(format, null, null, 0)
            localCodec.start()

            val minBufferSize = AudioTrack.getMinBufferSize(actualSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            localAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(actualSampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(minBufferSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
                        if (!isOutgoing) {
                currentAudioTrack = localAudioTrack
                if (isExoPlaying) {
                    localAudioTrack.play()
                }
            } else {
                localAudioTrack.play()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            
            var lastSwitchTime = System.currentTimeMillis()
            var currentCustomIndex = 0
            
            var outgoingPreset: PanPreset? = null
            var transitionProgress = 1.0f
            val transitionDurationMs = 1200L

            while (!sawOutputEOS) {
                if (Thread.currentThread().isInterrupted) break

                if (!isOutgoing && currentMode == PanMode.CUSTOM && customPresets.isNotEmpty() && !customModeIsLayered) {
                    val now = System.currentTimeMillis()
                    if (now - lastSwitchTime > intervalSeconds * 1000) {
                        outgoingPreset = currentPreset
                        currentCustomIndex = (currentCustomIndex + 1) % customPresets.size
                        currentPreset = customPresets[currentCustomIndex]
                        lastSwitchTime = now
                        transitionProgress = 0.0f
                        
                        // CRITICAL FIX: Do NOT reset filters or LFO phase here. 
                        // Keeping them continuous prevents the "electric shock" click during transitions.
                        
                        Log.d(TAG, "🔄 Switched to preset: $currentPreset")
                    }
                    if (outgoingPreset != null) {
                        val elapsed = System.currentTimeMillis() - lastSwitchTime
                        transitionProgress = (elapsed.toFloat() / transitionDurationMs).coerceIn(0f, 1f)
                        if (transitionProgress >= 1.0f) {
                            outgoingPreset = null
                        }
                    }
                }

                var currentGain = 1.0f
                if (isOutgoing) {
                    val elapsed = System.currentTimeMillis() - fadeOutStartMs
                    currentGain = 1.0f - (elapsed.toFloat() / fadeOutDurationMs)
                    if (currentGain <= 0.0f) break
                } else {
                    if (fadeInDurationMs > 0) {
                        val elapsed = System.currentTimeMillis() - fadeInStartMs
                        currentGain = (elapsed.toFloat() / fadeInDurationMs).coerceIn(0f, 1f)
                    }
                }

                if (!sawInputEOS) {
                    val inputBufferIndex = localCodec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = localCodec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        val sampleSize = localExtractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            localCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            localCodec.queueInputBuffer(inputBufferIndex, 0, sampleSize, localExtractor.sampleTime, 0)
                            localExtractor.advance()
                        }
                    }
                }

                val outputBufferIndex = localCodec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = localCodec.getOutputBuffer(outputBufferIndex)
                    if (bufferInfo.size > 0 && outputBuffer != null) {
                        val bytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(bytes)
                        val lfoStep = 2.0f * PI.toFloat() * (lfoFreq / actualSampleRate)

                        if (currentMode == PanMode.CUSTOM && customPresets.isNotEmpty()) {
                            if (customModeIsLayered) {
                                if (customPresets.any { it.name.startsWith("LOFI_") }) applyLoFi(bytes, bufferInfo.size, lfoStep, 1.0f)
                                if (customPresets.any { it.name.startsWith("TEL_") }) applyTelephone(bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f)
                                if (customPresets.any { it.name.startsWith("FREQ_") }) {
                                    val freqPreset = customPresets.first { it.name.startsWith("FREQ_") }
                                    val oldPreset = currentPreset; currentPreset = freqPreset
                                    applyFrequencyPan(bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f)
                                    currentPreset = oldPreset
                                }
                                if (customPresets.any { it.name.startsWith("TREMOLO_") }) {
                                    val tremPreset = customPresets.first { it.name.startsWith("TREMOLO_") }
                                    val oldPreset = currentPreset; currentPreset = tremPreset
                                    applyTremolo(bytes, bufferInfo.size, lfoStep, 1.0f)
                                    currentPreset = oldPreset
                                } else if (customPresets.any { it.name.startsWith("VOL_") }) {
                                    val volPreset = customPresets.first { it.name.startsWith("VOL_") }
                                    val oldPreset = currentPreset; currentPreset = volPreset
                                    applyVolumePan(bytes, bufferInfo.size, lfoStep, 1.0f)
                                    currentPreset = oldPreset
                                }
                            } else {
                                // Apply the current preset directly. Continuous LFO/filter states ensure no clicks.
                                applySinglePreset(currentPreset, bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f)
                            }
                            
                            if (currentGain < 1.0f) applyFade(bytes, bufferInfo.size, currentGain)
                        } else {
                            when (currentMode) {
                                PanMode.OFF -> applyFade(bytes, bufferInfo.size, currentGain)
                                PanMode.VOLUME -> applyVolumePan(bytes, bufferInfo.size, lfoStep, currentGain)
                                PanMode.FREQUENCY -> applyFrequencyPan(bytes, bufferInfo.size, actualSampleRate, lfoStep, currentGain)
                                PanMode.TREMOLO -> applyTremolo(bytes, bufferInfo.size, lfoStep, currentGain)
                                PanMode.LOFI -> applyLoFi(bytes, bufferInfo.size, lfoStep, currentGain)
                                PanMode.TELEPHONE -> applyTelephone(bytes, bufferInfo.size, actualSampleRate, lfoStep, currentGain)
                                PanMode.CUSTOM -> applyFade(bytes, bufferInfo.size, currentGain)
                            }
                        }
                        
                        // Advance LFO exactly ONCE per buffer
                        lfoPhase += (bufferInfo.size / 4) * lfoStep
                        while (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()

                        localAudioTrack?.write(bytes, 0, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                    }
                    localCodec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                } else { 
                    Thread.sleep(20) 
                }
            }
            
            if (!isOutgoing) {
                Handler(Looper.getMainLooper()).post { onSuccess() }
            }
        } catch (e: Exception) {
            if (!isOutgoing) Handler(Looper.getMainLooper()).post { onError(e.toString()) }
        } finally {
            try { localCodec?.stop(); localCodec?.release() } catch (_: Exception) {}
            try { localExtractor?.release() } catch (_: Exception) {}
            try { 
                if (!isOutgoing && currentAudioTrack === localAudioTrack) currentAudioTrack = null
                localAudioTrack?.stop(); localAudioTrack?.release() 
            } catch (_: Exception) {}
        }
    }

    private fun applySinglePreset(preset: PanPreset, bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float, gain: Float) {
        when (preset) {
            PanPreset.VOL_SMOOTH, PanPreset.VOL_GENTLE, PanPreset.VOL_CHOP -> applyVolumePan(bytes, size, lfoStep, gain)
            PanPreset.FREQ_HELICOPTER, PanPreset.FREQ_DEEP_BASS, PanPreset.FREQ_HI_HATS, PanPreset.FREQ_UNDERWATER -> applyFrequencyPan(bytes, size, sampleRate, lfoStep, gain)
            PanPreset.TREMOLO_SINE, PanPreset.TREMOLO_SQUARE -> applyTremolo(bytes, size, lfoStep, gain)
            PanPreset.LOFI_8BIT, PanPreset.LOFI_4BIT -> applyLoFi(bytes, size, lfoStep, gain)
            PanPreset.TEL_VINTAGE, PanPreset.TEL_RADIO -> applyTelephone(bytes, size, sampleRate, lfoStep, gain)
        }
    }

    private fun applyFade(bytes: ByteArray, size: Int, gain: Float) {
        var i = 0
        while (i < size - 3) {
            val sL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort()
            val sR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort()
            val outL = (sL * gain).toInt().toShort()
            val outR = (sR * gain).toInt().toShort()
            bytes[i] = (outL.toInt() and 0xFF).toByte(); bytes[i+1] = (outL.toInt() shr 8).toByte()
            bytes[i+2] = (outR.toInt() and 0xFF).toByte(); bytes[i+3] = (outR.toInt() shr 8).toByte()
            i += 4
        }
    }

    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float, gain: Float) {
        var i = 0
        var phase = lfoPhase
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val swing = sin(phase); val pan = swing * depth
            val leftGain = (1.0f - maxOf(0f, pan)) * gain
            val rightGain = (1.0f + minOf(0f, pan)) * gain
            val outL = sampleL * leftGain; val outR = sampleR * rightGain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float, gain: Float) {
        var i = 0
        var phase = lfoPhase
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val swing = (sin(phase) + 1.0f) / 2.0f
            var outL = sampleL; var outR = sampleR
            if (currentPreset == PanPreset.FREQ_UNDERWATER) {
                val logMin = ln(200f); val logMax = ln(16000f)
                val currentLogCutoff = logMax - (logMax - logMin) * (swing * depth)
                val alpha = 1.0f - exp(-2.0f * PI.toFloat() * exp(currentLogCutoff) / sampleRate)
                val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow); leftPrevLow = lowL
                val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow); rightPrevLow = lowR
                outL = lowL * gain; outR = lowR * gain
            } else {
                val baseFreq = when (currentPreset) { PanPreset.FREQ_DEEP_BASS -> 200f; PanPreset.FREQ_HI_HATS -> 6000f; else -> 1000f }
                val alpha = 1.0f - exp(-2.0f * PI.toFloat() * baseFreq / sampleRate)
                val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow); leftPrevLow = lowL
                val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow); rightPrevLow = lowR
                val highL = sampleL - lowL; val highR = sampleR - lowR
                val swapL = lowL * (1f - swing) + highL * swing; val swapR = highR * (1f - swing) + lowR * swing
                outL = (sampleL + (swapL - sampleL) * depth) * gain
                outR = (sampleR + (swapR - sampleR) * depth) * gain
            }
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyTremolo(bytes: ByteArray, size: Int, lfoStep: Float, gain: Float) {
        var i = 0
        var phase = lfoPhase
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            var lfoVal = (sin(phase) + 1.0f) / 2.0f
            if (currentPreset == PanPreset.TREMOLO_SQUARE) lfoVal = if (lfoVal > 0.5f) 1f else 0f
            val effectGain = (0.3f + (0.7f * lfoVal * depth)) * gain
            val outL = sampleL * effectGain; val outR = sampleR * effectGain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

    private fun applyLoFi(bytes: ByteArray, size: Int, lfoStep: Float, gain: Float) {
        var i = 0
        val bits = if (currentPreset == PanPreset.LOFI_4BIT) 4 else 8
        val levels = (1 shl bits).toFloat()
        val reduction = 4
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            lofiCounter++
            if (lofiCounter >= reduction) {
                lofiCounter = 0
                lofiHoldL = (sampleL * levels).toInt().toFloat() / levels
                lofiHoldR = (sampleR * levels).toInt().toFloat() / levels
            }
            val alpha = 0.2f
            val smoothL = lofiSmoothL + alpha * (lofiHoldL - lofiSmoothL); lofiSmoothL = smoothL
            val smoothR = lofiSmoothR + alpha * (lofiHoldR - lofiSmoothR); lofiSmoothR = smoothR
            val outL = smoothL * gain; val outR = smoothR * gain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (smoothR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4
        }
    }

    private fun applyTelephone(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float, gain: Float) {
        var i = 0
        var phase = lfoPhase
        val baseHighCut = if (currentPreset == PanPreset.TEL_VINTAGE) 2500f else 3500f
        val baseLowCut = 300f
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val currentHighCut = baseHighCut + (4000f * depth * ((sin(phase) + 1.0f) / 2.0f))
            val currentLowCut = baseLowCut + (1500f * depth * ((sin(phase) + 1.0f) / 2.0f))
            val alphaLow = 1.0f - exp(-2.0f * PI.toFloat() * currentHighCut / sampleRate)
            val alphaHigh = exp(-2.0f * PI.toFloat() * currentLowCut / sampleRate)
            val lpL = leftPrevLow + alphaLow * (sampleL - leftPrevLow); leftPrevLow = lpL
            val lpR = rightPrevLow + alphaLow * (sampleR - rightPrevLow); rightPrevLow = lpR
            val hpL = alphaHigh * (leftPrevHigh + lpL - leftPrevInput); leftPrevHigh = hpL; leftPrevInput = lpL
            val hpR = alphaHigh * (rightPrevHigh + lpR - rightPrevInput); rightPrevHigh = hpR; rightPrevInput = lpR
            val outL = (sampleL + depth * (hpL - sampleL)) * gain
            val outR = (sampleR + depth * (hpR - sampleR)) * gain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

    fun pause() { 
        val track = currentAudioTrack
        try { if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause() } catch (_: Exception) {}
    }
    
    fun resume() { 
        val track = currentAudioTrack
        try { if (track != null && track.playState == AudioTrack.PLAYSTATE_PAUSED) track.play() } catch (_: Exception) {}
    }
    
    fun refreshAudioTrack() {
        val track = currentAudioTrack
        try { if (track != null && track.playState == AudioTrack.PLAYSTATE_STOPPED) { track.stop(); track.release() } else if (track != null) track.play() } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        playbackThread?.interrupt(); try { playbackThread?.join(1500) } catch (_: Exception) {}
        outgoingThread?.interrupt(); try { outgoingThread?.join(1500) } catch (_: Exception) {}
        playbackThread = null; outgoingThread = null; currentAudioTrack = null
    }
}
