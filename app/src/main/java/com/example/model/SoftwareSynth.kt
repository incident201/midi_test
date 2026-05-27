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
                    for (frameIndex in 0 until bufferSize) {
                        val localFrame = framesProcessed + frameIndex
                        val localTicksSinceSection = ((localFrame - sectionStartFrames).toDouble() / sampleRate * ticksPerSecond).toLong()
                        val frameTick = sectionStartTick + localTicksSinceSection

                        // Find notes sounding at FrameTick
                        var sampleSum = 0.0
                        var soundingCount = 0

                        // Standard safety optimization: filter search for active notes in sorted array
                        var activeNotesCount = 0
                        for (i in notes.indices) {
                            val note = notes[i]
                            if (note.startTick <= frameTick && note.endTick >= frameTick) {
                                soundingCount++
                                val frequency = 440.0 * Math.pow(2.0, (note.pitch - 69) / 12.0)
                                val noteSecs = (frameTick - note.startTick).toDouble() / ticksPerSecond

                                // Physical modeling impulse string pluck formula
                                val envelope = exp(-3.5 * noteSecs) * (1.0 - exp(-75.0 * noteSecs))
                                val angle = noteSecs * 2.0 * Math.PI * frequency

                                // Mix fundamental frequency with a rich 1st overtone for string-like warmth
                                val noteSample = (sin(angle) * 0.7 + sin(2.0 * angle) * 0.25) * envelope
                                sampleSum += noteSample
                                activeNotesCount++
                                if (activeNotesCount >= 8) break // Max 8 voices polyphony to prevent clipping/lag
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
