package com.example.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.database.RecentFile
import com.example.model.DetectionPattern
import com.example.model.ParsedMidiFile
import com.example.ui.theme.*
import com.example.viewmodel.MidiViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainScreen(
    viewModel: MidiViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val parsedMidiFile by viewModel.parsedMidiFile.collectAsState()
    val detectedPatterns by viewModel.detectedPatterns.collectAsState()
    val selectedPattern by viewModel.selectedPattern.collectAsState()
    val playheadTick by viewModel.playheadTick.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val recentFiles by viewModel.recentFiles.collectAsState(initial = emptyList())

    // File selection launcher using general mime to avoid SAF filtering bugs on custom ROMs
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Attempt to persist permissions for SAF recent file reuse
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Fail-silent, standard for non-persistable URIs on old APIs
            }
            viewModel.loadMidi(context, uri)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CosmicBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Screen switching: If midi data is parsed, enter Workspace, otherwise look at home screen
            AnimatedContent(
                targetState = parsedMidiFile != null,
                transitionSpec = {
                    slideInHorizontally { width -> width } + fadeIn() with
                    slideOutHorizontally { width -> -width } + fadeOut()
                },
                label = "ScreenTransition"
            ) { isWorkspace ->
                if (isWorkspace) {
                    WorkspaceView(
                        midiFile = parsedMidiFile!!,
                        playheadTick = playheadTick,
                        isPlaying = isPlaying,
                        detectedPatterns = detectedPatterns,
                        selectedPattern = selectedPattern,
                        onBack = {
                            viewModel.stop()
                            // Force clear parsed state
                            viewModel.loadMidi(context, Uri.EMPTY) // Or write a clear action. Let's write a simple custom clear in our MainScreen
                        },
                        onTogglePlay = { viewModel.togglePlay() },
                        onStop = { viewModel.stop() },
                        onSeek = { tick -> viewModel.seekToTick(tick) },
                        onSelectPattern = { pattern -> viewModel.selectPattern(pattern) }
                    )
                } else {
                    MenuView(
                        recentFiles = recentFiles,
                        isAnalyzing = isAnalyzing,
                        onImportClick = { filePickerLauncher.launch("*/*") },
                        onRecentClick = { fileUri ->
                            try {
                                val uri = Uri.parse(fileUri)
                                viewModel.loadMidi(context, uri)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show()
                                viewModel.removeRecent(fileUri)
                            }
                        },
                        onDeleteRecent = { fileUri -> viewModel.removeRecent(fileUri) },
                        onClearAll = { viewModel.clearAllRecent() }
                    )
                }
            }

            // Global analytics loading banner overlay
            if (isAnalyzing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.70f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CosmicSurfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = AccentCyan)
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "Extracting MIDI Core Data...",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Monophonizing vocal path & running detector...",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Global error banner message
            errorMessage?.let { errorMsg ->
                AlertDialog(
                    onDismissRequest = { viewModel.resetError() },
                    confirmButton = {
                        TextButton(onClick = { viewModel.resetError() }) {
                            Text("OK", color = AccentCyan)
                        }
                    },
                    title = {
                        Text(
                            text = "Analysis Error",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AccentLaser
                        )
                    },
                    text = {
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    },
                    containerColor = CosmicSurface,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
fun MenuView(
    recentFiles: List<RecentFile>,
    isAnalyzing: Boolean,
    onImportClick: () -> Unit,
    onRecentClick: (String) -> Unit,
    onDeleteRecent: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        // App Identity Header Card with linear neon laser gradient
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(AccentPurple.copy(alpha = 0.8f), CosmicSurface)
                    )
                )
                .padding(24.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "GenAlpha Detector",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        letterSpacing = (-0.5).sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A MIDI vocal track wave analyzer & software syntesizer designed to automatically extract rhythmic structures and verify the presence of Generation Alpha melody signatures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Center primary Launch Trigger Action Block
        Button(
            onClick = onImportClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .testTag("import_midi_button"),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "IMPORT MIDI FILE (.MID)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Recent Opened Files Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECENT CORE ANALYSES",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.2.sp
            )
            if (recentFiles.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentLaser)
                ) {
                    Text(
                        text = "Clear History",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (recentFiles.isEmpty()) {
            EmptyListState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recentFiles) { item ->
                    RecentFileCard(
                        item = item,
                        onClick = { onRecentClick(item.fileUri) },
                        onDelete = { onDeleteRecent(item.fileUri) }
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyListState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = CosmicSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.HistoryToggleOff,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(48.dp)
              )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Empty Analytics Journal",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Import a .mid file above to trigger the algorithmic scan engine and save results.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun RecentFileCard(
    item: RecentFile,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(item.timestamp) {
        val date = Date(item.timestamp)
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(date)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = CosmicSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(CosmicSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Badges indicating findings
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentPurple.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${item.detectedPatternsCount} matches",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                    }
                    if (item.detectedPatternsCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (item.maxConfidence >= 80) AccentEmerald.copy(alpha = 0.2f)
                                    else AccentWarning.copy(alpha = 0.2f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Max ${item.maxConfidence}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (item.maxConfidence >= 80) AccentEmerald else AccentWarning
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove item",
                    tint = AccentLaser.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun WorkspaceView(
    midiFile: ParsedMidiFile,
    playheadTick: Long,
    isPlaying: Boolean,
    detectedPatterns: List<DetectionPattern>,
    selectedPattern: DetectionPattern?,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectPattern: (DetectionPattern) -> Unit
) {
    // Exact musical metrics calculation for timelines
    val ticksPerSecond = remember(midiFile) { (midiFile.bpm / 60.0) * midiFile.ppq }

    val playheadSecs = (playheadTick / ticksPerSecond).toInt()
    val maxSecs = (midiFile.durationTicks / ticksPerSecond).toInt()

    fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    val playheadFormatted = remember(playheadSecs) { formatTime(playheadSecs) }
    val maxFormatted = remember(maxSecs) { formatTime(maxSecs) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Workspace Top Title Action Bar
        Surface(
            color = CosmicSurface,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Go back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = midiFile.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "MIDI Track Workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Mini stats badges tag row
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpecsBadge(text = "${midiFile.bpm.toInt()} BPM")
                    SpecsBadge(text = "${midiFile.timeSignatureNumerator}/${midiFile.timeSignatureDenominator}")
                    SpecsBadge(text = "PPQ: ${midiFile.ppq}")
                }
            }
        }

        // Workspace Controls Panel
        Surface(
            color = CosmicSurfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause Key Action Trigger
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(AccentCyan)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Stop button
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(CosmicSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = AccentLaser
                        )
                    }

                    Spacer(modifier = Modifier.width(18.dp))

                    // Progress timelines
                    Text(
                        text = playheadFormatted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Slider(
                        value = playheadTick.toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..midiFile.durationTicks.toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = AccentCyan,
                            inactiveTrackColor = CosmicSurface,
                            thumbColor = AccentLaser
                        )
                    )

                    Text(
                        text = maxFormatted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }
        }

        // Central MIDI Sheet canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            PianoRollCanvas(
                midiFile = midiFile,
                playheadTick = playheadTick,
                selectedPattern = selectedPattern,
                onSeekToTick = onSeek
            )
        }

        // Bottom horizontal analysis lists block (scroller drawer template)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            color = CosmicSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "DETECTED HARMONIES (${detectedPatterns.size} MATCHES)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.1.sp
                    )
                    if (selectedPattern != null) {
                        TextButton(
                            onClick = { onSeek(selectedPattern.startTick) },
                            colors = ButtonDefaults.textButtonColors(contentColor = AccentEmerald)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CenterFocusStrong,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Focus Target Note",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (detectedPatterns.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No Generation Alpha signatures detected.\nThis tract is mathematically clean.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(detectedPatterns) { pattern ->
                            val isCurrentSelected = selectedPattern == pattern
                            val timeSecs = (pattern.startTick / ticksPerSecond).toInt()
                            val progressTime = formatTime(timeSecs)

                            val outlineC = when {
                                isCurrentSelected -> AccentCyan
                                pattern.confidenceScore >= 80 -> AccentEmerald.copy(alpha = 0.3f)
                                else -> AccentWarning.copy(alpha = 0.3f)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPattern(pattern) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrentSelected) CosmicSurfaceVariant else CosmicSurfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.2.dp, outlineC)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Circular color-coded Confidence Badge
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (pattern.confidenceScore >= 80) AccentEmerald.copy(alpha = 0.2f)
                                                else AccentWarning.copy(alpha = 0.2f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${pattern.confidenceScore}%",
                                            fontWeight = FontWeight.Bold,
                                            color = if (pattern.confidenceScore >= 80) AccentEmerald else AccentWarning,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Skibidi Tritone Hook @ $progressTime",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        // Display notes structure
                                        Text(
                                            text = "${pattern.n1.noteName} ➔ ${pattern.n2.noteName} ➔ ${pattern.n3.noteName} ➔ ${pattern.n4?.noteName ?: "?"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentCyan
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = if (pattern.confidenceScore >= 80) "Perfect Model" else "Harmonic Shift",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (pattern.confidenceScore >= 80) AccentEmerald else AccentWarning
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "tick ${pattern.startTick}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpecsBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CosmicSurfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            fontSize = 11.sp
        )
    }
}
