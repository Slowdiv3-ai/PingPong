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

    private class FilterState {
        var leftPrevLow = 0.0f; var rightPrevLow = 0.0f
        var leftPrevHigh = 0.0f; var rightPrevHigh = 0.0f
        var leftPrevInput = 0.0f; var rightPrevInput = 0.0f
        var lofiHoldL = 0.0f; var lofiHoldR = 0.0f
        var lofiSmoothL = 0.0f; var lofiSmoothR = 0.0f
        var lofiCounter = 0

        fun reset() {
            leftPrevLow = 0.0f; rightPrevLow = 0.0f
            leftPrevHigh = 0.0f; rightPrevHigh = 0.0f
            leftPrevInput = 0.0f; rightPrevInput = 0.0f
            lofiHoldL = 0.0f; lofiHoldR = 0.0f
            lofiSmoothL = 0.0f; lofiSmoothR = 0.0f
            lofiCounter = 0
        }

        fun copyFrom(other: FilterState) {
            leftPrevLow = other.leftPrevLow; rightPrevLow = other.rightPrevLow
            leftPrevHigh = other.leftPrevHigh; rightPrevHigh = other.rightPrevHigh
            leftPrevInput = other.leftPrevInput; rightPrevInput = other.rightPrevInput
            lofiHoldL = other.lofiHoldL; lofiHoldR = other.lofiHoldR
            lofiSmoothL = other.lofiSmoothL; lofiSmoothR = other.lofiSmoothR
            lofiCounter = other.lofiCounter
        }
    }

    private val mainFilter = FilterState()
    private val outgoingFilter = FilterState()
    private val layeredTelFilter = FilterState()
    private val layeredFreqFilter = FilterState()


    @Volatile var isExoPlaying = false
    @Volatile private var isPlaying = false
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var outgoingThread: Thread? = null
    private var lfoPhase = 0.0f
    @Volatile private var currentAudioTrack: AudioTrack? = null

    private fun resetFilters() {
        mainFilter.reset()
        outgoingFilter.reset()
        layeredTelFilter.reset()
        layeredFreqFilter.reset()
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
        Log.d(TAG, "▶ playUri CALLED: crossfade = $crossfadeSeconds")
        
        val isOverlap = crossfadeSeconds > 0 && playbackThread?.isAlive == true
        val fadeDurationMs = (crossfadeSeconds * 1000).toLong()

        if (isOverlap) {
            Log.d(TAG, "🎧 Starting Overlap Crossfade...")
            outgoingThread = playbackThread
            fadeOutStartMs = System.currentTimeMillis()
            fadeOutDurationMs = fadeDurationMs
            val fadeInStartMs = System.currentTimeMillis()
            
            playbackThread = Thread {
                try {
                    decodeAndPlay(context, uri, startTimeMs, fadeInStartMs, fadeDurationMs, onError, onSuccess)
                } catch (e: InterruptedException) { } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post { onError(e.toString()) }
                }
            }
            playbackThread?.start()
        } else {

            Log.d(TAG, "⚡ Instant playback mode")
            isPlaying = false
            playbackThread?.interrupt()
            outgoingThread?.interrupt()
            outgoingThread = null

            try { currentAudioTrack?.pause() } catch (_: Exception) {}
            currentAudioTrack = null

            playbackThread = Thread {
                decodeAndPlay(context, uri, startTimeMs, System.currentTimeMillis(), if (crossfadeSeconds > 0) fadeDurationMs else 0, onError, onSuccess)
            }
            playbackThread?.start()
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
            
            var outgoingMode: PanMode? = null
            var outgoingPreset: PanPreset? = null
            var lastActiveMode = currentMode
            var lastActivePreset = currentPreset
            var transitionStartMs = 0L
            var transitionProgress = 1.0f
            val transitionDurationMs = 2500L
            var lastOutGain = 1.0f
            var lastInGain = 0.0f

            while (!sawOutputEOS) {
                if (Thread.currentThread().isInterrupted) break

                if (!isOutgoing && !isExoPlaying) {
                    if (localAudioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        try { localAudioTrack.pause() } catch (_: Exception) {}
                    }
                    try { Thread.sleep(25) } catch (_: InterruptedException) { break }
                    continue
                }

                if (!isOutgoing) {

                    val now = System.currentTimeMillis()
                    if (currentMode == PanMode.CUSTOM && customPresets.isNotEmpty() && !customModeIsLayered) {
                        if (now - lastSwitchTime > intervalSeconds * 1000) {
                            currentCustomIndex = (currentCustomIndex + 1) % customPresets.size
                            currentPreset = customPresets[currentCustomIndex]
                            lastSwitchTime = now
                            Log.d(TAG, "🔄 Switched to preset: $currentPreset")
                        }
                    }

                    // Detect ANY mode or preset change (manual tap OR custom timer)
                    if (currentMode != lastActiveMode || currentPreset != lastActivePreset) {
                        outgoingMode = lastActiveMode
                        outgoingPreset = lastActivePreset
                        outgoingFilter.copyFrom(mainFilter)
                        mainFilter.lofiCounter = 4
                        lastActiveMode = currentMode
                        lastActivePreset = currentPreset
                        transitionStartMs = now
                        transitionProgress = 0.0f
                        lastOutGain = 1.0f
                        lastInGain = 0.0f
                    }


                    if (outgoingMode != null && outgoingPreset != null) {
                        val elapsed = now - transitionStartMs
                        val effectiveDuration = if (currentMode == PanMode.CUSTOM && !customModeIsLayered) {
                            minOf(transitionDurationMs, (intervalSeconds * 600f).toLong().coerceAtLeast(1000L))
                        } else {
                            transitionDurationMs
                        }
                        val rawT = (elapsed.toFloat() / effectiveDuration).coerceIn(0f, 1f)
                        transitionProgress = rawT * rawT * (3f - 2f * rawT)
                        if (rawT >= 1.0f) {
                            outgoingMode = null
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

                        if (!isOutgoing && outgoingMode != null && outgoingPreset != null && transitionProgress < 1.0f) {
                            val angle = transitionProgress * (PI.toFloat() / 2.0f)
                            val targetOutGain = cos(angle) * currentGain
                            val targetInGain = sin(angle) * currentGain
                            val outBytes = bytes.copyOf(bufferInfo.size)
                            applyModeAndPreset(outgoingMode!!, outgoingPreset!!, outBytes, bufferInfo.size, actualSampleRate, lfoStep, lastOutGain, targetOutGain, outgoingFilter)
                            applyModeAndPreset(currentMode, currentPreset, bytes, bufferInfo.size, actualSampleRate, lfoStep, lastInGain, targetInGain, mainFilter)
                            mixPcmBuffers(bytes, outBytes, bufferInfo.size)
                            lastOutGain = targetOutGain
                            lastInGain = targetInGain
                        } else if (currentMode == PanMode.CUSTOM && customPresets.isNotEmpty()) {

                            if (customModeIsLayered) {
                                if (customPresets.any { it.name.startsWith("LOFI_") }) applyLoFi(bytes, bufferInfo.size, lfoStep, 1.0f, 1.0f, mainFilter)
                                if (customPresets.any { it.name.startsWith("TEL_") }) applyTelephone(bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f, 1.0f, layeredTelFilter)
                                if (customPresets.any { it.name.startsWith("FREQ_") }) {
                                    val freqPreset = customPresets.first { it.name.startsWith("FREQ_") }
                                    val oldPreset = currentPreset; currentPreset = freqPreset
                                    applyFrequencyPan(bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f, 1.0f, layeredFreqFilter)
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
                                applySinglePreset(currentPreset, bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f, 1.0f, mainFilter)
                            }
                            if (currentGain < 1.0f) applyFade(bytes, bufferInfo.size, currentGain)
                        } else {
                            applyModeAndPreset(currentMode, currentPreset, bytes, bufferInfo.size, actualSampleRate, lfoStep, currentGain, currentGain, mainFilter)
                        }


                        // Advance LFO exactly ONCE per buffer
                        lfoPhase += (bufferInfo.size / 4) * lfoStep
                        while (lfoPhase > 2.0f * PI.toFloat()) lfoPhase -= 2.0f * PI.toFloat()

                        if (!isOutgoing) {
                            if (isExoPlaying && localAudioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                try { localAudioTrack?.play() } catch (_: Exception) {}
                            } else if (!isExoPlaying && localAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                try { localAudioTrack?.pause() } catch (_: Exception) {}
                            }
                        }

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
    private fun applyModeAndPreset(
        mode: PanMode,
        preset: PanPreset,
        bytes: ByteArray,
        size: Int,
        sampleRate: Int,
        lfoStep: Float,
        startGain: Float,
        endGain: Float = startGain,
        state: FilterState
    ) {
        val savedPreset = currentPreset
        currentPreset = preset
        when (mode) {
            PanMode.OFF -> applyFade(bytes, size, startGain, endGain)
            PanMode.VOLUME -> applyVolumePan(bytes, size, lfoStep, startGain, endGain)
            PanMode.FREQUENCY -> applyFrequencyPan(bytes, size, sampleRate, lfoStep, startGain, endGain, state)
            PanMode.TREMOLO -> applyTremolo(bytes, size, lfoStep, startGain, endGain)
            PanMode.LOFI -> applyLoFi(bytes, size, lfoStep, startGain, endGain, state)
            PanMode.TELEPHONE -> applyTelephone(bytes, size, sampleRate, lfoStep, startGain, endGain, state)
            PanMode.CUSTOM -> applySinglePreset(preset, bytes, size, sampleRate, lfoStep, startGain, endGain, state)
        }
        currentPreset = savedPreset
    }

    private fun applySinglePreset(
        preset: PanPreset,
        bytes: ByteArray,
        size: Int,
        sampleRate: Int,
        lfoStep: Float,
        startGain: Float,
        endGain: Float = startGain,
        state: FilterState
    ) {
        when (preset) {
            PanPreset.VOL_SMOOTH, PanPreset.VOL_GENTLE, PanPreset.VOL_CHOP -> applyVolumePan(bytes, size, lfoStep, startGain, endGain)
            PanPreset.FREQ_HELICOPTER, PanPreset.FREQ_DEEP_BASS, PanPreset.FREQ_HI_HATS, PanPreset.FREQ_UNDERWATER -> applyFrequencyPan(bytes, size, sampleRate, lfoStep, startGain, endGain, state)
            PanPreset.TREMOLO_SINE, PanPreset.TREMOLO_SQUARE -> applyTremolo(bytes, size, lfoStep, startGain, endGain)
            PanPreset.LOFI_8BIT, PanPreset.LOFI_4BIT -> applyLoFi(bytes, size, lfoStep, startGain, endGain, state)
            PanPreset.TEL_VINTAGE, PanPreset.TEL_RADIO -> applyTelephone(bytes, size, sampleRate, lfoStep, startGain, endGain, state)
        }
    }



    private fun mixPcmBuffers(target: ByteArray, addend: ByteArray, size: Int) {
        var i = 0
        while (i < size - 1) {
            val a = ((target[i].toInt() and 0xFF) or ((target[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
            val b = ((addend[i].toInt() and 0xFF) or ((addend[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
            val mixed = (a + b).coerceIn(-32768, 32767)
            target[i] = (mixed and 0xFF).toByte()
            target[i + 1] = (mixed shr 8).toByte()
            i += 2
        }
    }

    private fun applyFade(bytes: ByteArray, size: Int, startGain: Float, endGain: Float = startGain) {
        var i = 0
        val gainStep = (endGain - startGain) / (size / 4).coerceAtLeast(1)
        var gain = startGain
        while (i < size - 3) {
            val sL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort()
            val sR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort()
            val outL = (sL * gain).toInt().toShort()
            val outR = (sR * gain).toInt().toShort()
            bytes[i] = (outL.toInt() and 0xFF).toByte(); bytes[i+1] = (outL.toInt() shr 8).toByte()
            bytes[i+2] = (outR.toInt() and 0xFF).toByte(); bytes[i+3] = (outR.toInt() shr 8).toByte()
            i += 4; gain += gainStep
        }
    }

    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float, startGain: Float, endGain: Float = startGain) {
        var i = 0
        var phase = lfoPhase
        val gainStep = (endGain - startGain) / (size / 4).coerceAtLeast(1)
        var gain = startGain
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
            i += 4; gain += gainStep; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

   
    private fun applyFrequencyPan(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float, startGain: Float, endGain: Float = startGain, state: FilterState) {
        var i = 0
        var phase = lfoPhase
        val gainStep = (endGain - startGain) / (size / 4).coerceAtLeast(1)
        var gain = startGain
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val swing = (sin(phase) + 1.0f) / 2.0f
            var outL = sampleL; var outR = sampleR
            if (currentPreset == PanPreset.FREQ_UNDERWATER) {
                val logMin = ln(200f); val logMax = ln(16000f)
                val currentLogCutoff = logMax - (logMax - logMin) * (swing * depth)
                val alpha = 1.0f - exp(-2.0f * PI.toFloat() * exp(currentLogCutoff) / sampleRate)
                val lowL = state.leftPrevLow + alpha * (sampleL - state.leftPrevLow); state.leftPrevLow = lowL
                val lowR = state.rightPrevLow + alpha * (sampleR - state.rightPrevLow); state.rightPrevLow = lowR
                outL = lowL * gain; outR = lowR * gain
            } else {
                val baseFreq = when (currentPreset) { PanPreset.FREQ_DEEP_BASS -> 200f; PanPreset.FREQ_HI_HATS -> 6000f; else -> 1000f }
                val alpha = 1.0f - exp(-2.0f * PI.toFloat() * baseFreq / sampleRate)
                val lowL = state.leftPrevLow + alpha * (sampleL - state.leftPrevLow); state.leftPrevLow = lowL
                val lowR = state.rightPrevLow + alpha * (sampleR - state.rightPrevLow); state.rightPrevLow = lowR
                val highL = sampleL - lowL; val highR = sampleR - lowR
                val swapL = lowL * (1f - swing) + highL * swing; val swapR = highR * (1f - swing) + lowR * swing
                outL = (sampleL + (swapL - sampleL) * depth) * gain
                outR = (sampleR + (swapR - sampleR) * depth) * gain
            }
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; gain += gainStep; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }



    private fun applyTremolo(bytes: ByteArray, size: Int, lfoStep: Float, startGain: Float, endGain: Float = startGain) {
        var i = 0
        var phase = lfoPhase
        val gainStep = (endGain - startGain) / (size / 4).coerceAtLeast(1)
        var gain = startGain
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
            i += 4; gain += gainStep; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }


    private fun applyLoFi(bytes: ByteArray, size: Int, lfoStep: Float, startGain: Float, endGain: Float = startGain, state: FilterState) {
        var i = 0
        val bits = if (currentPreset == PanPreset.LOFI_4BIT) 4 else 8
        val levels = (1 shl bits).toFloat()
        val reduction = 4
        val gainStep = (endGain - startGain) / (size / 4).coerceAtLeast(1)
        var gain = startGain
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            state.lofiCounter++
            if (state.lofiCounter >= reduction) {
                state.lofiCounter = 0
                state.lofiHoldL = (sampleL * levels).toInt().toFloat() / levels
                state.lofiHoldR = (sampleR * levels).toInt().toFloat() / levels
                if (state.lofiSmoothL == 0f && state.lofiSmoothR == 0f) {
                    state.lofiSmoothL = state.lofiHoldL
                    state.lofiSmoothR = state.lofiHoldR
                }
            }
            val alpha = 0.2f
            val smoothL = state.lofiSmoothL + alpha * (state.lofiHoldL - state.lofiSmoothL); state.lofiSmoothL = smoothL
            val smoothR = state.lofiSmoothR + alpha * (state.lofiHoldR - state.lofiSmoothR); state.lofiSmoothR = smoothR
            val outL = smoothL * gain; val outR = smoothR * gain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; gain += gainStep
        }
    }


    private fun applyTelephone(bytes: ByteArray, size: Int, sampleRate: Int, lfoStep: Float, startGain: Float, endGain: Float = startGain, state: FilterState) {
        var i = 0
        var phase = lfoPhase
        val baseHighCut = if (currentPreset == PanPreset.TEL_VINTAGE) 2500f else 3500f
        val baseLowCut = 300f
        val gainStep = (endGain - startGain) / (size / 4).coerceAtLeast(1)
        var gain = startGain
        while (i < size - 3) {
            val sampleL = (bytes[i].toInt() and 0xFF or ((bytes[i+1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = (bytes[i+2].toInt() and 0xFF or ((bytes[i+3].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val currentHighCut = baseHighCut + (4000f * depth * ((sin(phase) + 1.0f) / 2.0f))
            val currentLowCut = baseLowCut + (1500f * depth * ((sin(phase) + 1.0f) / 2.0f))
            val alphaLow = 1.0f - exp(-2.0f * PI.toFloat() * currentHighCut / sampleRate)
            val alphaHigh = exp(-2.0f * PI.toFloat() * currentLowCut / sampleRate)
            val lpL = state.leftPrevLow + alphaLow * (sampleL - state.leftPrevLow); state.leftPrevLow = lpL
            val lpR = state.rightPrevLow + alphaLow * (sampleR - state.rightPrevLow); state.rightPrevLow = lpR
            val hpL = alphaHigh * (state.leftPrevHigh + lpL - state.leftPrevInput); state.leftPrevHigh = hpL; state.leftPrevInput = lpL
            val hpR = alphaHigh * (state.rightPrevHigh + lpR - state.rightPrevInput); state.rightPrevHigh = hpR; state.rightPrevInput = lpR
            val outL = (sampleL + depth * (hpL - sampleL)) * gain
            val outR = (sampleR + depth * (hpR - sampleR)) * gain
            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i] = (sL.toInt() and 0xFF).toByte(); bytes[i+1] = (sL.toInt() shr 8).toByte()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[i+2] = (sR.toInt() and 0xFF).toByte(); bytes[i+3] = (sR.toInt() shr 8).toByte()
            i += 4; gain += gainStep; phase += lfoStep; if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

    fun pause() { 
        isExoPlaying = false
        val track = currentAudioTrack
        try { if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause() } catch (_: Exception) {}
    }
    
    fun resume() { 
        isExoPlaying = true
        val track = currentAudioTrack
        try { if (track != null && track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play() } catch (_: Exception) {}
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
