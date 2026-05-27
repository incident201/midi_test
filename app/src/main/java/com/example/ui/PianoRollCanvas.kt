package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.model.DetectionPattern
import com.example.model.MidiNote
import com.example.model.ParsedMidiFile
import com.example.ui.theme.*
import kotlin.math.max

@Composable
fun PianoRollCanvas(
    midiFile: ParsedMidiFile,
    playheadTick: Long,
    selectedPattern: DetectionPattern?,
    onSeekToTick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val notes = midiFile.notes
    val maxTick = midiFile.durationTicks

    // Piano Roll layout constants (in pixels)
    val pitchHeightPx = with(density) { 24.dp.toPx() }
    val keyboardWidthPx = with(density) { 60.dp.toPx() }
    val timelineHeightPx = with(density) { 28.dp.toPx() }

    // Navigation and zoom states
    var zoomX by remember { mutableStateOf(0.15f) } // pixels per tick
    var offsetX by remember { mutableStateOf(0f) }   // horizontal scroll offset
    var offsetY by remember { mutableStateOf(0f) }   // vertical scroll offset

    // Center view vertically on the average pitch of the midi file initially
    LaunchedEffect(midiFile) {
        val avgPitch = if (notes.isNotEmpty()) notes.map { it.pitch }.average().toInt() else 60
        // Center the average pitch row (127 - avgPitch) in the container
        val targetY = -((127 - avgPitch) * pitchHeightPx - 300f)
        offsetY = targetY.coerceIn(-128 * pitchHeightPx, 0f)
    }

    // Auto-scroll logic: Keep playhead visually centered during active playback
    var lastPlayheadTick by remember { mutableStateOf(-1L) }
    LaunchedEffect(playheadTick) {
        if (playheadTick != lastPlayheadTick && playheadTick > 0 && lastPlayheadTick != -1L) {
            // Keep playhead to center-left
            val targetOffsetX = -(playheadTick * zoomX - 350f)
            offsetX = targetOffsetX.coerceAtMost(0f)
        }
        lastPlayheadTick = playheadTick
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CosmicBackground)
            .pointerInput(midiFile) {
                // Multi-touch gestures (zoomX and pan offset)
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomX = (zoomX * zoom).coerceIn(0.015f, 2.0f)
                    offsetX = (offsetX + pan.x).coerceAtMost(0f)
                    offsetY = (offsetY + pan.y).coerceIn(-(128 * pitchHeightPx - size.height + timelineHeightPx), 0f)
                }
            }
            .pointerInput(midiFile) {
                // Single-tap scrubbing on grid or timeline ruler
                detectTapGestures { offset ->
                    if (offset.y < timelineHeightPx) {
                        // Direct timeline ruler scrubbing
                        val localX = offset.x - keyboardWidthPx - offsetX
                        val targetTick = (localX / zoomX).toLong().coerceIn(0L, maxTick)
                        onSeekToTick(targetTick)
                    } else if (offset.x > keyboardWidthPx) {
                        // Grid click scrubbing
                        val localX = offset.x - keyboardWidthPx - offsetX
                        val targetTick = (localX / zoomX).toLong().coerceIn(0L, maxTick)
                        onSeekToTick(targetTick)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 1. Draw translated MIDI Note grid and notes (clipped to respect left sidebar & top ruler)
            clipRect(
                left = keyboardWidthPx,
                top = timelineHeightPx,
                right = canvasWidth,
                bottom = canvasHeight
            ) {
                // Determine visible rows and ticks range to avoid rendering invisible assets
                val startRow = (-offsetY / pitchHeightPx).toInt().coerceIn(0, 127)
                val endRow = ((canvasHeight - offsetY) / pitchHeightPx).toInt().coerceIn(0, 127)

                // Render striped piano background row lanes (Black keys darker, White keys slightly lighter)
                for (row in startRow..endRow) {
                    val pitch = 127 - row
                    val isBlackKey = pitch % 12 in arrayOf(1, 3, 6, 8, 10)
                    val bgCol = if (isBlackKey) DarkKeyColor else LightKeyColor
                    drawRect(
                        color = bgCol,
                        topLeft = Offset(keyboardWidthPx, row * pitchHeightPx + offsetY),
                        size = Size(canvasWidth - keyboardWidthPx, pitchHeightPx)
                    )

                    // Draw horizontal row separator lines
                    drawLine(
                        color = GridLineColor,
                        start = Offset(keyboardWidthPx, row * pitchHeightPx + offsetY),
                        end = Offset(canvasWidth, row * pitchHeightPx + offsetY),
                        strokeWidth = 1f
                    )
                }

                // Draw vertical grid subdivisions (Measures & Beats)
                val ppq = midiFile.ppq
                val numerator = midiFile.timeSignatureNumerator
                val ticksPerBar = ppq * numerator
                val totalTicks = maxOf(maxTick, (canvasWidth / zoomX).toLong())

                var tick = 0L
                while (tick < totalTicks) {
                    val x = tick * zoomX + offsetX + keyboardWidthPx
                    val isMeasureNum = tick % ticksPerBar == 0L
                    val color = if (isMeasureNum) GridLineColor.copy(alpha = 0.9f) else GridLineColor.copy(alpha = 0.4f)
                    val thickness = if (isMeasureNum) 1.5f else 0.8f

                    if (x in keyboardWidthPx..canvasWidth) {
                        drawLine(
                            color = color,
                            start = Offset(x, timelineHeightPx),
                            end = Offset(x, canvasHeight),
                            strokeWidth = thickness
                        )
                    }
                    tick += ppq // One beat interval
                }

                // Render Midi Notes
                notes.forEach { note ->
                    val xStart = note.startTick * zoomX + offsetX + keyboardWidthPx
                    val xEnd = note.endTick * zoomX + offsetX + keyboardWidthPx
                    val noteWidth = xEnd - xStart

                    val y = (127 - note.pitch) * pitchHeightPx + offsetY

                    // Skip drawing note if it is out of visual scope
                    if (xStart + noteWidth >= keyboardWidthPx && xStart <= canvasWidth &&
                        y + pitchHeightPx >= timelineHeightPx && y <= canvasHeight) {

                        // Color coding notes based on pattern matches & active playing states
                        var isSelected = selectedPattern?.notesInvolved?.any {
                            it.pitch == note.pitch && it.startTick == note.startTick
                        } ?: false

                        val isPlaying = playheadTick in note.startTick..note.endTick

                        val noteColor = when {
                            isSelected && (selectedPattern?.confidenceScore ?: 0) >= 80 -> AccentEmerald
                            isSelected -> AccentWarning
                            isPlaying -> AccentCyan
                            else -> AccentCyan.copy(alpha = 0.4f)
                        }

                        val strokeColor = when {
                            isPlaying -> Color.White
                            isSelected -> Color.White.copy(alpha = 0.8f)
                            else -> AccentCyan
                        }

                        // Draw note block
                        drawRect(
                            color = noteColor,
                            topLeft = Offset(maxOf(xStart, keyboardWidthPx), y + 2f),
                            size = Size(noteWidth - 2f, pitchHeightPx - 4f)
                        )

                        // Draw thin border around notes
                        drawRect(
                            color = strokeColor,
                            topLeft = Offset(maxOf(xStart, keyboardWidthPx), y + 2f),
                            size = Size(noteWidth - 2f, pitchHeightPx - 4f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                        )
                    }
                }

                // Draw playhead vertical neon tracker line
                val playheadX = playheadTick * zoomX + offsetX + keyboardWidthPx
                if (playheadX in keyboardWidthPx..canvasWidth) {
                    drawLine(
                        color = AccentLaser,
                        start = Offset(playheadX, timelineHeightPx),
                        end = Offset(playheadX, canvasHeight),
                        strokeWidth = 2f
                    )
                }
            }

            // 2. Draw static header Timeline track ruler at the top (translated only horizontally)
            clipRect(left = keyboardWidthPx, top = 0f, right = canvasWidth, bottom = timelineHeightPx) {
                // Background ruler card
                drawRect(
                    color = CosmicSurface,
                    topLeft = Offset(keyboardWidthPx, 0f),
                    size = Size(canvasWidth - keyboardWidthPx, timelineHeightPx)
                )

                drawLine(
                    color = GridLineColor,
                    start = Offset(keyboardWidthPx, timelineHeightPx),
                    end = Offset(canvasWidth, timelineHeightPx),
                    strokeWidth = 2f
                )

                // Draw measure numerical indicators
                val ppq = midiFile.ppq
                val numerator = midiFile.timeSignatureNumerator
                val ticksPerBar = ppq * numerator
                val totalTicks = maxOf(maxTick, (canvasWidth / zoomX).toLong())

                var barIndex = 0
                var barTick = 0L
                while (barTick < totalTicks + ticksPerBar) {
                    val x = barTick * zoomX + offsetX + keyboardWidthPx
                    if (x in keyboardWidthPx..canvasWidth) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "m.${barIndex + 1}",
                            x + 6f,
                            timelineHeightPx - 8f,
                            android.graphics.Paint().apply {
                                color = TextSecondary.hashCode()
                                textSize = 26f
                                isFakeBoldText = true
                            }
                        )
                    }
                    barIndex++
                    barTick += ticksPerBar
                }
            }

            // 3. Draw static side note range headers on the left (translated only vertically)
            clipRect(left = 0f, top = timelineHeightPx, right = keyboardWidthPx, bottom = canvasHeight) {
                drawRect(
                    color = CosmicSurface,
                    topLeft = Offset(0f, timelineHeightPx),
                    size = Size(keyboardWidthPx, canvasHeight - timelineHeightPx)
                )
                drawLine(
                    color = GridLineColor,
                    start = Offset(keyboardWidthPx, timelineHeightPx),
                    end = Offset(keyboardWidthPx, canvasHeight),
                    strokeWidth = 2f
                )

                val startRow = (-offsetY / pitchHeightPx).toInt().coerceIn(0, 127)
                val endRow = ((canvasHeight - offsetY) / pitchHeightPx).toInt().coerceIn(0, 127)

                // Print note pitches
                for (row in startRow..endRow) {
                    val pitch = 127 - row
                    val octave = (pitch / 12) - 1
                    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                    val noteStr = "${noteNames[pitch % 12]}$octave"

                    val isC = (pitch % 12 == 0)
                    val noteColor = if (isC) TextPrimary else TextMuted
                    val bgKey = if (pitch % 12 in arrayOf(1, 3, 6, 8, 10)) DarkKeyColor else Color.Transparent

                    if (bgKey != Color.Transparent) {
                        drawRect(
                            color = bgKey.copy(alpha = 0.5f),
                            topLeft = Offset(0f, row * pitchHeightPx + offsetY),
                            size = Size(keyboardWidthPx, pitchHeightPx)
                        )
                    }

                    drawContext.canvas.nativeCanvas.drawText(
                        noteStr,
                        12f,
                        row * pitchHeightPx + offsetY + (pitchHeightPx / 2) + 8f,
                        android.graphics.Paint().apply {
                            color = noteColor.hashCode()
                            textSize = 24f
                            isFakeBoldText = isC
                        }
                    )
                }
            }

            // 4. Overlap top-left static intersection corner
            drawRect(
                color = CosmicSurfaceVariant,
                topLeft = Offset.Zero,
                size = Size(keyboardWidthPx, timelineHeightPx)
            )
            drawLine(
                color = GridLineColor,
                start = Offset(keyboardWidthPx, 0f),
                end = Offset(keyboardWidthPx, timelineHeightPx),
                strokeWidth = 2f
            )
            drawLine(
                color = GridLineColor,
                start = Offset(0f, timelineHeightPx),
                end = Offset(keyboardWidthPx, timelineHeightPx),
                strokeWidth = 2f
            )
        }
    }
}
