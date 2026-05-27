package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.database.RecentFile
import com.example.database.RecentFileRepository
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MidiViewModel(private val repository: RecentFileRepository) : ViewModel() {
    private val TAG = "MidiViewModel"

    val recentFiles: Flow<List<RecentFile>> = repository.recentFiles

    private val _parsedMidiFile = MutableStateFlow<ParsedMidiFile?>(null)
    val parsedMidiFile: StateFlow<ParsedMidiFile?> = _parsedMidiFile.asStateFlow()

    private val _detectedPatterns = MutableStateFlow<List<DetectionPattern>>(emptyList())
    val detectedPatterns: StateFlow<List<DetectionPattern>> = _detectedPatterns.asStateFlow()

    private val _selectedPattern = MutableStateFlow<DetectionPattern?>(null)
    val selectedPattern: StateFlow<DetectionPattern?> = _selectedPattern.asStateFlow()

    private val _playheadTick = MutableStateFlow(0L)
    val playheadTick: StateFlow<Long> = _playheadTick.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var synth: SoftwareSynth? = null
    private var synthPlayheadCollectJob: Job? = null
    private var synthPlayingCollectJob: Job? = null

    fun loadMidi(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _errorMessage.value = null
            _selectedPattern.value = null

            // Stop previous synth if any
            stop()
            synth?.release()
            synth = null

            val result = withContext(Dispatchers.IO) {
                MidiParser.parse(context, uri)
            }

            if (result != null) {
                _parsedMidiFile.value = result

                // Scan for patterns (on monophonized notes track)
                val allPatterns = withContext(Dispatchers.Default) {
                    GenAlphaDetector.detect(
                        notes = result.monophonizedNotes,
                        ppq = result.ppq,
                        timeSigNumerator = result.timeSignatureNumerator,
                        timeSigDenominator = result.timeSignatureDenominator
                    )
                }

                // Filter for Confidence Score >= 50%
                val validPatterns = allPatterns.filter { it.confidenceScore >= 50 }
                _detectedPatterns.value = validPatterns

                // Add to Room history
                val maxConf = validPatterns.maxOfOrNull { it.confidenceScore } ?: 0
                repository.addRecentFile(
                    uri = uri.toString(),
                    name = result.fileName,
                    patternsCount = validPatterns.size,
                    maxConfidence = maxConf
                )

                // Initialize Synthesizer (plays monophonized notes or all notes. Playing all notes is richer,
                // while playing monophonized notes matches the exact vocal leading frame. Let's play monophonized notes
                // as they have cleaned vocal pitch, or raw notes depending on what feels better. Let's play monophonized
                // vocal track since it is monophonized and perfectly clean!)
                synth = SoftwareSynth(result.monophonizedNotes, result.ppq, result.bpm)

                // Connect synth states
                connectSynthesizer()
            } else {
                _parsedMidiFile.value = null
                _detectedPatterns.value = emptyList()
                _errorMessage.value = "Failed to parse MIDI file. Verify format 0/1."
            }
            _isAnalyzing.value = false
        }
    }

    private fun connectSynthesizer() {
        val s = synth ?: return
        synthPlayheadCollectJob?.cancel()
        synthPlayingCollectJob?.cancel()

        synthPlayheadCollectJob = viewModelScope.launch {
            s.currentTick.collect { tick ->
                _playheadTick.value = tick
            }
        }

        synthPlayingCollectJob = viewModelScope.launch {
            s.isPlaying.collect { playing ->
                _isPlaying.value = playing
            }
        }
    }

    fun togglePlay() {
        val s = synth ?: return
        if (_isPlaying.value) {
            s.pause()
        } else {
            s.play()
        }
    }

    fun stop() {
        synth?.stop()
    }

    fun seekToTick(tick: Long) {
        val s = synth ?: return
        val maxTick = _parsedMidiFile.value?.durationTicks ?: 0L
        val coerced = tick.coerceIn(0L, maxTick)
        s.seekToTick(coerced)

        // Reset selected pattern if we seek away from it
        val sel = _selectedPattern.value
        if (sel != null && (coerced < sel.startTick || coerced > sel.endTick)) {
            _selectedPattern.value = null
        }
    }

    fun selectPattern(pattern: DetectionPattern) {
        _selectedPattern.value = pattern
        seekToTick(pattern.startTick)
    }

    fun removeRecent(uri: String) {
        viewModelScope.launch {
            repository.removeRecentFile(uri)
        }
    }

    fun clearAllRecent() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun resetError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        synth?.release()
    }
}

class MidiViewModelFactory(private val repository: RecentFileRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MidiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MidiViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
