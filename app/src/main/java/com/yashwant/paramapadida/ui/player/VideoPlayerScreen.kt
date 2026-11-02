package com.yashwant.paramapadida.ui.player

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.data.model.Note
import com.yashwant.paramapadida.data.repository.CourseRepository
import com.yashwant.paramapadida.engine.ExternalPlayerHelper
import com.yashwant.paramapadida.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    lessonId: String,
    repository: CourseRepository,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val lesson by repository.getLesson(lessonId).collectAsState(initial = null)
    val notes by repository.getNotesForLesson(lessonId).collectAsState(initial = emptyList())

    var streamUrl by remember { mutableStateOf<String?>(null) }
    var isLoadingUrl by remember { mutableStateOf(true) }

    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAddNoteDialog by remember { mutableStateOf(false) }

    // Resolve direct stream URL if not local
    LaunchedEffect(lesson) {
        val l = lesson ?: return@LaunchedEffect
        if (l.isDownloaded) {
            streamUrl = l.localFilePath
            isLoadingUrl = false
        } else {
            isLoadingUrl = true
            val url = repository.resolveStreamUrl(l)
            streamUrl = url ?: l.directStreamUrl
            isLoadingUrl = false
        }
    }

    // Media3 ExoPlayer instance
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    // Setup media and progress tracking
    LaunchedEffect(streamUrl) {
        val url = streamUrl ?: return@LaunchedEffect
        val uri = if (url.startsWith("/")) {
            Uri.fromFile(File(url))
        } else {
            Uri.parse(url)
        }

        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        lesson?.let { l ->
            if (l.watchProgressMs > 0) {
                exoPlayer.seekTo(l.watchProgressMs)
            }
        }
        exoPlayer.playWhenReady = true
    }

    // Update speed
    LaunchedEffect(currentSpeed) {
        exoPlayer.setPlaybackSpeed(currentSpeed)
    }

    // Periodic progress saver & auto 90% completion tracker
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(3000)
            if (exoPlayer.isPlaying) {
                val currentPos = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                lesson?.let { l ->
                    repository.updateLessonProgress(l.id, currentPos, duration, l.courseId)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val currentPos = exoPlayer.currentPosition
            val duration = exoPlayer.duration
            lesson?.let { l ->
                coroutineScope.launch {
                    repository.updateLessonProgress(l.id, currentPos, duration, l.courseId)
                }
            }
            exoPlayer.release()
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lesson?.title ?: "Playing Lesson",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "Speed: ${currentSpeed}x",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Kyoto Player Button
                IconButton(onClick = {
                    lesson?.let { l ->
                        ExternalPlayerHelper.openInKyotoPlayer(
                            context = context,
                            title = l.title,
                            localFilePath = l.localFilePath,
                            streamUrl = streamUrl ?: l.webUrl
                        )
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = "Open in Kyoto Player",
                        tint = AccentBlue
                    )
                }

                // Speed Selector
                IconButton(onClick = { showSpeedDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Playback Speed",
                        tint = TextPrimary
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Video Player Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isLoadingUrl) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Extracting video stream...",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                } else if (streamUrl == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Failed to load stream link.",
                            color = Color(0xFFEF4444),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                lesson?.let { l ->
                                    ExternalPlayerHelper.openInKyotoPlayer(
                                        context,
                                        l.title,
                                        l.localFilePath,
                                        l.webUrl
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("Try in Kyoto Player")
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Learning & Notes Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Study Notes (${notes.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Button(
                    onClick = { showAddNoteDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkCardHighlight),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add Note at Timestamp",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No notes yet for this episode. Tap 'Add Note' during video playback to bookmark key concepts and timestamps.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onSeek = { timestamp ->
                                exoPlayer.seekTo(timestamp)
                                Toast.makeText(context, "Jumped to ${note.formattedTimestamp}", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                coroutineScope.launch {
                                    repository.deleteNote(note.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Playback Speed Dialog
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed", color = TextPrimary) },
            text = {
                Column {
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSpeed = speed
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (currentSpeed == speed),
                                onClick = {
                                    currentSpeed = speed
                                    showSpeedDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentBlue)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${speed}x",
                                color = if (currentSpeed == speed) AccentBlue else TextPrimary,
                                fontWeight = if (currentSpeed == speed) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = DarkSurface
        )
    }

    // Add Note Dialog
    if (showAddNoteDialog) {
        val currentTimestamp = exoPlayer.currentPosition
        val formattedTime = String.format(
            "%02d:%02d",
            (currentTimestamp / 1000) / 60,
            (currentTimestamp / 1000) % 60
        )
        var noteText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("Add Note at $formattedTime", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("Key concept, formula, or takeaway...", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            lesson?.let { l ->
                                coroutineScope.launch {
                                    repository.addNote(
                                        lessonId = l.id,
                                        courseId = l.courseId,
                                        timestampMs = currentTimestamp,
                                        text = noteText.trim()
                                    )
                                    showAddNoteDialog = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Save Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun NoteCard(
    note: Note,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkCardHighlight)
                    .clickable { onSeek(note.timestampMs) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "▶ ${note.formattedTimestamp}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = note.text,
                fontSize = 14.sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete Note",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
