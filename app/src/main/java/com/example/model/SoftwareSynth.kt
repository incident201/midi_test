package com.example.model

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.sin

class SoftwareSynth(
    private val notes: List<MidiNote>,
    private val ppq: Int,
    private val bpm: Double
) {
    private val TAG = "SoftwareSynth"

    private val _currentTick = MutableStateFlow(0L)
    val currentTick: StateFlow<Long> = _currentTick.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var synthJob: Job? = null
    private val synthScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sampleRate = 44100
    private val ticksPerSecond = (bpm / 60.0) * ppq

    // Thread-safe lock for playhead seeking
    private val stateLock = Any()
    private var currentPlayheadTick = 0L
    private var pendingSeekTick: Long? = null

    // Set of active voices
    private val activeNoteTriggers = mutableMapOf<Int, Double>() // Pitch to Phase offset

    fun play() {
        synchronized(stateLock) {
            if (_isPlaying.value) return
            _isPlaying.value = true
        }

        initAudioTrack()

        synthJob = synthScope.launch {
            try {
                audioTrack?.play()
                val bufferSize = 1024 // Frames per render block (~23ms)
                val buffer = ShortArray(bufferSize)

                var framesProcessed = 0L
                var sectionStartTick = currentPlayheadTick
                var sectionStartFrames = 0L

                val maxTick = notes.maxOfOrNull { it.endTick } ?: 0L

                while (isActive && _isPlaying.value) {
                    var seekTick: Long? = null
                    synchronized(stateLock) {
                        seekTick = pendingSeekTick
                        if (seekTick != null) {
                            pendingSeekTick = null
                        }
                    }

                    if (seekTick != null) {
                        sectionStartTick = seekTick!!
                        sectionStartFrames = framesProcessed
                        currentPlayheadTick = seekTick!!
                        _currentTick.value = seekTick!!
                    }

                    // Calculate current playhead tick
                    val framesSinceSection = framesProcessed - sectionStartFrames
                    val timeSinceSectionSecs = framesSinceSection.toDouble() / sampleRate
                    val ticksSinceSection = (timeSinceSectionSecs * ticksPerSecond).toLong()
                    val nowTick = sectionStartTick + ticksSinceSection

                    currentPlayheadTick = nowTick
                    _currentTick.value = nowTick

                    if (nowTick >= maxTick + ppq) { // Stop after end of song + 1 beat buffer
                        stop()
                        break
                    }

                    // Render synthesized chunk
                    val startFrame = framesProcessed
                    val endFrame = framesProcessed + bufferSize
                    val sectionStartSecs = sectionStartTick.toDouble() / ticksPerSecond

                    val blockStartSecs = sectionStartSecs + (startFrame - sectionStartFrames).toDouble() / sampleRate
                    val blockEndSecs = sectionStartSecs + (endFrame - sectionStartFrames).toDouble() / sampleRate

                    val blockStartTick = (blockStartSecs * ticksPerSecond).toLong()
                    val blockEndTick = (blockEndSecs * ticksPerSecond).toLong()

                    // Pre-filter active notes for this 23ms block to avoid iterating all notes inside inner loop
                    val activeNotesInBlock = notes.filter { note ->
                        note.startTick <= blockEndTick + 10 && note.endTick >= blockStartTick - 10
                    }.take(16) // Max 16 notes to guarantee real-time performance

                    for (frameIndex in 0 until bufferSize) {
                        val localFrame = framesProcessed + frameIndex
                        val currentFrameSecs = sectionStartSecs + (localFrame - sectionStartFrames).toDouble() / sampleRate
                        val frameTick = (currentFrameSecs * ticksPerSecond).toLong()

                        var sampleSum = 0.0
                        var soundingCount = 0

                        for (i in activeNotesInBlock.indices) {
                            val note = activeNotesInBlock[i]
                            if (note.startTick <= frameTick && note.endTick >= frameTick) {
                                soundingCount++
                                val frequency = 440.0 * Math.pow(2.0, (note.pitch - 69) / 12.0)
                                val noteSecs = maxOf(0.0, currentFrameSecs - (note.startTick.toDouble() / ticksPerSecond))

                                // Physical modeling impulse string pluck formula
                                val envelope = exp(-4.0 * noteSecs) * (1.0 - exp(-80.0 * noteSecs))

                                // Fast linear fade-out release phase to avoid sharp audio clicks when notes cut off
                                val timeRemaining = (note.endTick.toDouble() / ticksPerSecond) - currentFrameSecs
                                val releaseMultiplier = if (timeRemaining < 0.04) {
                                    maxOf(0.0, timeRemaining / 0.04)
                                } else {
                                    1.0
                                }

                                val angle = noteSecs * 2.0 * Math.PI * frequency
                                // Warm additive synth wave: fundamental sine + warm odd/even harmonic content
                                val noteSample = (sin(angle) * 0.65 + sin(2.0 * angle) * 0.20 + sin(3.0 * angle) * 0.10) * envelope * releaseMultiplier
                                sampleSum += noteSample
                            }
                        }

                        // Apply soft limiting to avoid clipping on chords
                        if (soundingCount > 1) {
                            sampleSum = Math.tanh(sampleSum)
                        }

                        // Write to PCM Short (max 32767)
                        val shortVal = (sampleSum * 18000.0).toInt().coerceIn(-32768, 32767)
                        buffer[frameIndex] = shortVal.toShort()
                    }

                    audioTrack?.write(buffer, 0, bufferSize)
                    framesProcessed += bufferSize
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synth rendering error: ", e)
            } finally {
                try {
                    audioTrack?.stop()
                } catch (e: Exception) {}
            }
        }
    }

    fun pause() {
        synchronized(stateLock) {
            _isPlaying.value = false
        }
        synthJob?.cancel()
        audioTrack?.pause()
    }

    fun stop() {
        synchronized(stateLock) {
            _isPlaying.value = false
            currentPlayheadTick = 0L
            pendingSeekTick = null
            _currentTick.value = 0L
        }
        synthJob?.cancel()
        audioTrack?.stop()
        audioTrack?.flush()
    }

    fun seekToTick(tick: Long) {
        synchronized(stateLock) {
            currentPlayheadTick = tick
            pendingSeekTick = tick
            _currentTick.value = tick
        }
    }

    private fun initAudioTrack() {
        if (audioTrack == null) {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
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
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufSize, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        synthScope.cancel()
    }
}
