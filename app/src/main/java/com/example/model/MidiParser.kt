package com.example.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream

data class MidiNote(
    val pitch: Int,             // MIDI Key (0-127)
    val startTick: Long,
    var endTick: Long,
    val velocity: Int,
    val channel: Int,
    val trackIndex: Int
) {
    val durationTicks: Long get() = maxOf(1L, endTick - startTick)
    val noteName: String get() {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (pitch / 12) - 1
        val note = pitch % 12
        return "${names[note]}$octave"
    }
}

data class ParsedMidiFile(
    val fileName: String,
    val format: Int,
    val trackCount: Int,
    val ppq: Int, // Ticks per Quarter Note (Resolution)
    val bpm: Double, // Beats per Minute
    val timeSignatureNumerator: Int,
    val timeSignatureDenominator: Int,
    val notes: List<MidiNote>,
    val monophonizedNotes: List<MidiNote>,
    val durationTicks: Long
)

object MidiParser {
    private const val TAG = "MidiParser"

    fun parse(context: Context, uri: Uri): ParsedMidiFile? {
        var inputStream: InputStream? = null
        try {
            val contentResolver = context.contentResolver
            val name = getFileName(context, uri) ?: "untitled.mid"
            inputStream = contentResolver.openInputStream(uri) ?: return null
            val bis = BufferedInputStream(inputStream)
            return parseFromStream(bis, name)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing MIDI file: ", e)
            return null
        } finally {
            inputStream?.close()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    private fun parseFromStream(stream: BufferedInputStream, fileName: String): ParsedMidiFile? {
        // Read header "MThd"
        val headerMagic = readString(stream, 4)
        if (headerMagic != "MThd") {
            Log.e(TAG, "Invalid MIDI file: Missing 'MThd' header. Found: $headerMagic")
            return null
        }

        val headerLength = readInt32(stream)
        if (headerLength < 6) {
            Log.e(TAG, "Invalid header length: $headerLength")
            return null
        }

        val format = readInt16(stream)
        val trackCount = readInt16(stream)
        val division = readInt16(stream)

        // Skip any remaining header bytes
        if (headerLength > 6) {
            stream.skip((headerLength - 6).toLong())
        }

        // PPQ (ticks per quarter note)
        val ppq = if ((division and 0x8000) == 0) division else 96 // Default to 96 if SMPTE timecode

        var globalBpm = 120.0
        var globalNumerator = 4
        var globalDenominator = 4

        val allNotes = mutableListOf<MidiNote>()

        // Read Tracks
        for (trackIdx in 0 until trackCount) {
            val trackMagic = readString(stream, 4)
            if (trackMagic != "MTrk") {
                Log.e(TAG, "Track chunk expected but not found! Track $trackIdx. Found: $trackMagic")
                break
            }

            val trackLength = readInt32(stream)
            var bytesRead = 0L

            var currentAbsoluteTick = 0L
            val activeNotes = mutableMapOf<Int, MidiNote>() // Key: channel * 256 + pitch
            var runningStatus = -1

            val trackStream = TrackBoundedInputStream(stream, trackLength)

            while (trackStream.availableBytes() > 0) {
                val deltaTick = readVlq(trackStream)
                currentAbsoluteTick += deltaTick

                var statusByte = trackStream.peek()
                if (statusByte == -1) break

                if ((statusByte and 0x80) != 0) {
                    statusByte = trackStream.read()
                    runningStatus = statusByte
                } else {
                    statusByte = runningStatus
                }

                if (statusByte == -1) break

                val messageType = statusByte and 0xF0
                val channel = statusByte and 0x0F

                when {
                    messageType == 0x80 -> { // Note Off
                        val pitch = trackStream.read()
                        val velocity = trackStream.read()
                        val key = (channel shl 8) or pitch
                        val note = activeNotes.remove(key)
                        if (note != null) {
                            note.endTick = currentAbsoluteTick
                            allNotes.add(note)
                        }
                    }
                    messageType == 0x90 -> { // Note On / Note Off (vel 0)
                        val pitch = trackStream.read()
                        val velocity = trackStream.read()
                        val key = (channel shl 8) or pitch
                        if (velocity == 0) {
                            val note = activeNotes.remove(key)
                            if (note != null) {
                                note.endTick = currentAbsoluteTick
                                allNotes.add(note)
                            }
                        } else {
                            // If note is already active, close it out first
                            val previousNote = activeNotes.remove(key)
                            if (previousNote != null) {
                                previousNote.endTick = currentAbsoluteTick
                                allNotes.add(previousNote)
                            }
                            val newNote = MidiNote(
                                pitch = pitch,
                                startTick = currentAbsoluteTick,
                                endTick = currentAbsoluteTick, // initially 0 duration
                                velocity = velocity,
                                channel = channel,
                                trackIndex = trackIdx
                            )
                            activeNotes[key] = newNote
                        }
                    }
                    messageType == 0xA0 || messageType == 0xB0 || messageType == 0xE0 -> {
                        trackStream.read() // arg 1
                        trackStream.read() // arg 2
                    }
                    messageType == 0xC0 || messageType == 0xD0 -> {
                        trackStream.read() // arg 1
                    }
                    statusByte == 0xFF -> { // Meta byte
                        val metaType = trackStream.read()
                        val metaLength = readVlq(trackStream).toInt()
                        val metaData = ByteArray(metaLength)
                        var totalRead = 0
                        while (totalRead < metaLength) {
                            val r = trackStream.read(metaData, totalRead, metaLength - totalRead)
                            if (r == -1) break
                            totalRead += r
                        }

                        if (metaType == 0x51 && metaLength >= 3) { // Tempo Changed
                            val tempoMicro = ((metaData[0].toInt() and 0xFF) shl 16) or
                                             ((metaData[1].toInt() and 0xFF) shl 8) or
                                             (metaData[2].toInt() and 0xFF)
                            val bpm = 60_000_000.0 / tempoMicro
                            globalBpm = bpm
                        } else if (metaType == 0x58 && metaLength >= 2) { // Time Signature Changed
                            globalNumerator = metaData[0].toInt() and 0xFF
                            val denomPower = metaData[1].toInt() and 0xFF
                            globalDenominator = Math.pow(2.0, denomPower.toDouble()).toInt()
                        }
                    }
                    statusByte == 0xF0 || statusByte == 0xF7 -> { // SysEx
                        val sysExLength = readVlq(trackStream).toInt()
                        trackStream.skip(sysExLength.toLong())
                    }
                    else -> {
                        // Unknown or invalid event, consume 1 byte to prevent infinite loop
                        trackStream.read()
                    }
                }
            }

            // Close any remaining active notes
            for (note in activeNotes.values) {
                note.endTick = currentAbsoluteTick
                allNotes.add(note)
            }

            // Skip any unused bytes in this track chunk (in case trackLength was larger)
            val remaining = trackStream.availableBytes()
            if (remaining > 0) {
                trackStream.skip(remaining)
            }
        }

        val sortedNotes = allNotes.sortedWith(compareBy({ it.startTick }, { it.pitch }))
        val monophonized = monophonizeNotes(sortedNotes)

        val durationTicks = sortedNotes.maxOfOrNull { it.endTick } ?: 0L

        return ParsedMidiFile(
            fileName = fileName,
            format = format,
            trackCount = trackCount,
            ppq = ppq,
            bpm = globalBpm,
            timeSignatureNumerator = globalNumerator,
            timeSignatureDenominator = globalDenominator,
            notes = sortedNotes,
            monophonizedNotes = monophonized,
            durationTicks = durationTicks
        )
    }

    private fun monophonizeNotes(notes: List<MidiNote>): List<MidiNote> {
        if (notes.isEmpty()) return emptyList()

        // Group notes by target channel
        val channelGroups = notes.groupBy { it.channel }
        val allMonophonized = mutableListOf<MidiNote>()

        for ((channel, chanNotes) in channelGroups) {
            val points = chanNotes.flatMap { listOf(it.startTick, it.endTick) }.distinct().sorted()
            if (points.size < 2) continue

            val resultSegments = mutableListOf<MidiNoteSegment>()
            for (i in 0 until points.size - 1) {
                val start = points[i]
                val end = points[i + 1]
                if (start == end) continue

                // Find all notes on this channel covering [start, end]
                val covering = chanNotes.filter { it.startTick <= start && it.endTick >= end }
                if (covering.isNotEmpty()) {
                    // Choose the note with the highest pitch (vocal monophonization)
                    val leadNote = covering.maxByOrNull { it.pitch }!!
                    resultSegments.add(
                        MidiNoteSegment(
                            start = start,
                            end = end,
                            pitch = leadNote.pitch,
                            velocity = leadNote.velocity,
                            channel = leadNote.channel,
                            trackIndex = leadNote.trackIndex
                        )
                    )
                }
            }

            if (resultSegments.isEmpty()) continue

            // Merge consecutive segments with the same pitch & channel
            val mergedNotes = mutableListOf<MidiNote>()
            var currentSeg = resultSegments[0]

            for (i in 1 until resultSegments.size) {
                val nextSeg = resultSegments[i]
                if (nextSeg.start == currentSeg.end && nextSeg.pitch == currentSeg.pitch && nextSeg.channel == currentSeg.channel) {
                    currentSeg = currentSeg.copy(end = nextSeg.end)
                } else {
                    mergedNotes.add(
                        MidiNote(
                            pitch = currentSeg.pitch,
                            startTick = currentSeg.start,
                            endTick = currentSeg.end,
                            velocity = currentSeg.velocity,
                            channel = currentSeg.channel,
                            trackIndex = currentSeg.trackIndex
                        )
                    )
                    currentSeg = nextSeg
                }
            }
            mergedNotes.add(
                MidiNote(
                    pitch = currentSeg.pitch,
                    startTick = currentSeg.start,
                    endTick = currentSeg.end,
                    velocity = currentSeg.velocity,
                    channel = currentSeg.channel,
                    trackIndex = currentSeg.trackIndex
                )
            )

            allMonophonized.addAll(mergedNotes)
        }

        return allMonophonized.sortedWith(compareBy({ it.startTick }, { it.pitch }))
    }

    private data class MidiNoteSegment(
        val start: Long,
        val end: Long,
        val pitch: Int,
        val velocity: Int,
        val channel: Int,
        val trackIndex: Int
    )

    private fun readString(stream: InputStream, length: Int): String {
        val buffer = ByteArray(length)
        var total = 0
        while (total < length) {
            val r = stream.read(buffer, total, length - total)
            if (r == -1) break
            total += r
        }
        return String(buffer, 0, total, Charsets.US_ASCII)
    }

    private fun readInt32(stream: InputStream): Int {
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        val b4 = stream.read()
        if (b1 or b2 or b3 or b4 < 0) return 0
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun readInt16(stream: InputStream): Int {
        val b1 = stream.read()
        val b2 = stream.read()
        if (b1 or b2 < 0) return 0
        return (b1 shl 8) or b2
    }

    private fun readVlq(stream: InputStream): Long {
        var value = 0Long
        while (true) {
            val b = stream.read()
            if (b == -1) break
            value = (value shl 7) or (b and 0x7F).toLong()
            if ((b and 0x80) == 0) break
        }
        return value
    }

    /**
     * Bounded wrapper stream to read track data segments safely
     */
    private class TrackBoundedInputStream(private val base: InputStream, private val limit: Int) : InputStream() {
        private var bytesRead = 0

        fun availableBytes(): Int {
            return maxOf(0, limit - bytesRead)
        }

        fun peek(): Int {
            if (bytesRead >= limit) return -1
            base.mark(2)
            val b = base.read()
            base.reset()
            return b
        }

        override fun read(): Int {
            if (bytesRead >= limit) return -1
            val b = base.read()
            if (b != -1) {
                bytesRead++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= limit) return -1
            val maxLen = minOf(len, limit - bytesRead)
            val r = base.read(b, off, maxLen)
            if (r != -1) {
                bytesRead += r
            }
            return r
        }

        override fun skip(n: Long): Long {
            val maxSkip = minOf(n, (limit - bytesRead).toLong())
            val skipped = base.skip(maxSkip)
            bytesRead += skipped.toInt()
            return skipped
        }
    }
}
