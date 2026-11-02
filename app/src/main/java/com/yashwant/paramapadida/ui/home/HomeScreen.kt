package com.yashwant.paramapadida.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yashwant.paramapadida.data.model.Course
import com.yashwant.paramapadida.data.repository.CourseRepository
import com.yashwant.paramapadida.ui.components.DebugLogsDialog
import com.yashwant.paramapadida.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: CourseRepository,
    onCourseClick: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val courses by repository.allCourses.collectAsState(initial = emptyList())

    var showImportDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    val totalVideos = courses.sumOf { it.totalVideos }
    val watchedVideos = courses.sumOf { it.watchedVideos }
    val overallPercent = if (totalVideos > 0) (watchedVideos.toFloat() / totalVideos.toFloat() * 100).toInt() else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Parama Padida",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Self-Paced Learning Hub",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Bug Logs & Diagnostics",
                            tint = Color(0xFFFF5370)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImportDialog = true },
                containerColor = AccentBlue,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = "Import Playlist") },
                text = { Text("Import Playlist") }
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Stats Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Study Progress",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$watchedVideos / $totalVideos Lessons Watched",
                            fontSize = 18.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (totalVideos > 0) watchedVideos.toFloat() / totalVideos.toFloat() else 0f },
                            modifier = Modifier
                                .width(200.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentGreen,
                            trackColor = Color(0xFF333333)
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(27.dp))
                            .background(DarkCardHighlight)
                    ) {
                        Text(
                            text = "$overallPercent%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AccentGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "My Learning Folders (${courses.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (courses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No course folders yet",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap '+ Import Playlist' below to paste a YouTube playlist URL and auto-generate your learning folder.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(courses, key = { it.id }) { course ->
                        CourseCard(
                            course = course,
                            onClick = { onCourseClick(course.id) },
                            onDelete = {
                                coroutineScope.launch {
                                    repository.deleteCourse(course.id)
                                    Toast.makeText(context, "Course deleted", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        ImportPlaylistDialog(
            onDismiss = { showImportDialog = false },
            onImport = { url, category, downloadImmediately ->
                coroutineScope.launch {
                    try {
                        Toast.makeText(context, "Parsing playlist with yt-dlp...", Toast.LENGTH_SHORT).show()
                        val course = repository.importPlaylist(url, category)
                        showImportDialog = false
                        Toast.makeText(context, "Course '${course.title}' created!", Toast.LENGTH_SHORT).show()

                        if (downloadImmediately) {
                            repository.queueCourseDownloads(course.id)
                            Toast.makeText(context, "Queued all episodes for sequential batch download", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error importing: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showLogsDialog) {
        DebugLogsDialog(
            onDismissRequest = { showLogsDialog = false }
        )
    }
}

@Composable
fun CourseCard(
    course: Course,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Course Thumbnail
            AsyncImage(
                model = course.thumbnailUrl,
                contentDescription = course.title,
                modifier = Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkCardHighlight),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (course.channelTitle.isNotBlank()) {
                    Text(
                        text = course.channelTitle,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${course.watchedVideos} / ${course.totalVideos} watched",
                        fontSize = 12.sp,
                        color = AccentGreen,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "${(course.progressPercent * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { course.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AccentGreen,
                    trackColor = Color(0xFF333333)
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete Course", color = Color(0xFFEF4444)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ImportPlaylistDialog(
    onDismiss: () -> Unit,
    onImport: (url: String, category: String, downloadAll: Boolean) -> Unit
) {
    var playlistUrl by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Coding") }
    var downloadAll by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                text = "Import YouTube Playlist",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Paste a YouTube playlist link below. Parama Padida will auto-create a learning folder, extract all lessons, and organize them for tracking and playback.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    label = { Text("YouTube Playlist URL") },
                    placeholder = { Text("https://www.youtube.com/playlist?list=...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Subject / Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { if (!isLoading) downloadAll = !downloadAll }
                ) {
                    Checkbox(
                        checked = downloadAll,
                        onCheckedChange = { downloadAll = it },
                        enabled = !isLoading,
                        colors = CheckboxDefaults.colors(checkedColor = AccentBlue)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download all videos immediately (offline mode)",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                }

                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = AccentBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Extracting playlist...",
                            fontSize = 13.sp,
                            color = AccentBlue
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (playlistUrl.isNotBlank()) {
                        isLoading = true
                        onImport(playlistUrl.trim(), category.trim(), downloadAll)
                    }
                },
                enabled = playlistUrl.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text("Create Course")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = DarkSurface
    )
}
