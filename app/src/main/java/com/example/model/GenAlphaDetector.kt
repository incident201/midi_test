package com.example.model

import kotlin.math.abs

data class DetectionPattern(
    val n1: MidiNote,
    val n2: MidiNote,
    val n3: MidiNote,
    val n4: MidiNote?,
    val confidenceScore: Int,
    val isTruncated: Boolean,
    val startTick: Long,
    val endTick: Long,
    val notesInvolved: List<MidiNote>
) {
    val displayScore: String get() = "$confidenceScore%"
    val formattedTime: String get() {
        // Can be formatted inside ViewModel or UI as mm:ss
        val totalSecs = (startTick / 1000.0).toInt() // Dummy tick-to-seconds mapping or BPM-based
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }
}

object GenAlphaDetector {

    fun detect(
        notes: List<MidiNote>,
        ppq: Int,
        timeSigNumerator: Int,
        timeSigDenominator: Int
    ): List<DetectionPattern> {
        if (notes.isEmpty()) return emptyList()

        // 1. Filter out notes shorter than 1/32 of a bar
        // 1 bar = timeSigNumerator * (4.0 / timeSigDenominator) * PPQ
        val barLengthTicks = timeSigNumerator * (4.0 / timeSigDenominator) * ppq
        val noiseThresholdTicks = barLengthTicks / 32.0

        val cleanedNotes = notes.filter { it.durationTicks >= noiseThresholdTicks }
        val detections = mutableListOf<DetectionPattern>()

        var i = 0
        while (i < cleanedNotes.size) {
            val n1 = cleanedNotes[i]

            // Look-ahead for N2 (up to 3 indices ahead of i)
            var foundPattern = false
            for (j in i + 1..minOf(i + 3, cleanedNotes.lastIndex)) {
                val n2 = cleanedNotes[j]
                val delta1 = n2.pitch - n1.pitch

                if (delta1 == -6) { // Shift of a tritone down
                    // Look-ahead for N3 (up to 3 indices ahead of j)
                    for (k in j + 1..minOf(j + 3, cleanedNotes.lastIndex)) {
                        val n3 = cleanedNotes[k]
                        val delta2 = n3.pitch - n2.pitch

                        if (delta2 == 5) { // Perfect fourth up
                            // We have N1, N2, N3.
                            // Look-ahead for N4 (up to 3 indices ahead of k)
                            var matchedN4: MidiNote? = null
                            var isHarmonicMod = false
                            var actualDelta3 = 0

                            for (l in k + 1..minOf(k + 3, cleanedNotes.lastIndex)) {
                                val n4 = cleanedNotes[l]
                                val delta3 = n4.pitch - n3.pitch
                                if (delta3 == -7) {
                                    matchedN4 = n4
                                    actualDelta3 = -7
                                    break
                                } else if (delta3 == -6 || delta3 == -8) {
                                    matchedN4 = n4
                                    actualDelta3 = delta3
                                    isHarmonicMod = true
                                    break
                                }
                            }

                            if (matchedN4 != null) {
                                // Full pattern found
                                val score = calculateConfidenceScore(
                                    n1 = n1,
                                    n2 = n2,
                                    n3 = n3,
                                    n4 = matchedN4,
                                    i = i,
                                    j = j,
                                    k = k,
                                    l = cleanedNotes.indexOf(matchedN4),
                                    ppq = ppq,
                                    hasHarmonicMod = isHarmonicMod
                                )

                                detections.add(
                                    DetectionPattern(
                                        n1 = n1,
                                        n2 = n2,
                                        n3 = n3,
                                        n4 = matchedN4,
                                        confidenceScore = score,
                                        isTruncated = false,
                                        startTick = n1.startTick,
                                        endTick = matchedN4.endTick,
                                        notesInvolved = listOf(n1, n2, n3, matchedN4)
                                    )
                                )
                                // To avoid double-matching overlapping starts, we can skip ahead
                                // but standard overlapping window scans all starts to be thorough
                            } else {
                                // Truncated pattern (Score = 40%)
                                detections.add(
                                    DetectionPattern(
                                        n1 = n1,
                                        n2 = n2,
                                        n3 = n3,
                                        n4 = null,
                                        confidenceScore = 40,
                                        isTruncated = true,
                                        startTick = n1.startTick,
                                        endTick = n3.endTick,
                                        notesInvolved = listOf(n1, n2, n3)
                                    )
                                )
                            }
                        }
                    }
                }
            }
            i++
        }

        // Return only matches >= 50% for display, but keep truncated detections in data model just in case
        return detections
    }

    private fun calculateConfidenceScore(
        n1: MidiNote,
        n2: MidiNote,
        n3: MidiNote,
        n4: MidiNote,
        i: Int,
        j: Int,
        k: Int,
        l: Int,
        ppq: Int,
        hasHarmonicMod: Boolean
    ): Int {
        var score = 100

        // 1. Rhythmic displacement penalty (-15%)
        // If start of N1 or N3 is shifted from global strong beats (multiples of PPQ) by more than PPQ/4 (16th note)
        val offsetN1 = n1.startTick % ppq
        val distN1 = minOf(offsetN1, ppq - offsetN1)

        val offsetN3 = n3.startTick % ppq
        val distN3 = minOf(offsetN3, ppq - offsetN3)

        val threshold = ppq / 4.0 // 16th note threshold
        if (distN1 > threshold || distN3 > threshold) {
            score -= 15
        }

        // 2. Presence of melisms penalty (-10% per extra note)
        val extraNotes = (j - i - 1) + (k - j - 1) + (l - k - 1)
        score -= extraNotes * 10

        // 3. Harmonic modification penalty (-25%)
        if (hasHarmonicMod) {
            score -= 25
        }

        return maxOf(0, score)
    }
}
