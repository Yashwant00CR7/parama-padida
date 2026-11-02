package com.yashwant.paramapadida.ui.course

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashwant.paramapadida.data.model.Course
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.data.repository.CourseRepository
import com.yashwant.paramapadida.engine.ExternalPlayerHelper
import com.yashwant.paramapadida.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    courseId: String,
    repository: CourseRepository,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val course by repository.getCourse(courseId).collectAsState(initial = null)

    var ascendingOrder by remember { mutableStateOf(true) }
    val lessons by repository.getLessons(courseId, ascendingOrder).collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var selectedLessonForSheet by remember { mutableStateOf<Lesson?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    val filteredLessons = remember(lessons, searchQuery) {
        if (searchQuery.isBlank()) {
            lessons
        } else {
            val q = searchQuery.trim().lowercase()
            lessons.filter {
                it.videoIndex.toString() == q || it.title.lowercase().contains(q)
            }
        }
    }

    val totalCount = course?.totalVideos ?: lessons.size
    val watchedCount = lessons.count { it.isWatched }
    val downloadedCount = lessons.count { it.isDownloaded }
    val progressPercent = if (totalCount > 0) (watchedCount.toFloat() / totalCount.toFloat() * 100).toInt() else 0

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search episode # or title...", color = TextMuted) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(
                                text = course?.title ?: "Learning Folder",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Episodes ($watchedCount/$totalCount watched • $downloadedCount downloaded)",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    // Sort order toggle (Ascending / Descending)
                    IconButton(onClick = { ascendingOrder = !ascendingOrder }) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = if (ascendingOrder) "Sort Descending" else "Sort Ascending",
                            tint = if (!ascendingOrder) AccentBlue else TextPrimary
                        )
                    }

                    // Search Toggle
                    IconButton(onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextPrimary
                        )
                    }

                    // Overflow Menu
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = TextPrimary
                            )
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Download All Episodes", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = AccentBlue) },
                                onClick = {
                                    showOptionsMenu = false
                                    course?.let { c ->
                                        lessons.forEach { lesson ->
                                            if (!lesson.isDownloaded) {
                                                repository.queueDownload(lesson, c.title)
                                            }
                                        }
                                        Toast.makeText(context, "Queued all downloads in background", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Mark All as Watched", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null, tint = AccentGreen) },
                                onClick = {
                                    showOptionsMenu = false
                                    coroutineScope.launch {
                                        lessons.forEach {
                                            repository.updateLessonWatchStatus(it.id, true, courseId)
                                        }
                                        Toast.makeText(context, "Marked all as watched", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Open Course Folder", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, tint = TextSecondary) },
                                onClick = {
                                    showOptionsMenu = false
                                    course?.let { c ->
                                        val folder = File(c.folderPath)
                                        if (folder.exists()) {
                                            Toast.makeText(context, "Folder: ${c.folderPath}", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Folder created when downloading", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 5-Column Grid matching user's screenshot
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredLessons, key = { it.id }) { lesson ->
                    LessonGridTile(
                        lesson = lesson,
                        onClick = { selectedLessonForSheet = lesson }
                    )
                }
            }
        }
    }

    // Bottom Sheet matching the screenshot!
    selectedLessonForSheet?.let { lesson ->
        ModalBottomSheet(
            onDismissRequest = { selectedLessonForSheet = null },
            containerColor = DarkSurface,
            contentColor = TextPrimary,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF3E3E3E))
                )
            }
        ) {
            LessonActionBottomSheet(
                lesson = lesson,
                courseTitle = course?.title ?: "Course",
                onClose = { selectedLessonForSheet = null },
                onPlayInKyotoPlayer = {
                    ExternalPlayerHelper.playDownloadedLesson(context, lesson)
                },
                onDownloadClick = {
                    coroutineScope.launch {
                        if (lesson.isDownloaded) {
                            repository.deleteDownloadedVideo(lesson)
                            Toast.makeText(context, "Deleted downloaded file", Toast.LENGTH_SHORT).show()
                        } else {
                            repository.queueDownload(lesson, course?.title ?: "Course")
                            Toast.makeText(context, "Download queued in background", Toast.LENGTH_SHORT).show()
                        }
                        selectedLessonForSheet = null
                    }
                },
                onToggleWatched = {
                    coroutineScope.launch {
                        repository.updateLessonWatchStatus(lesson.id, !lesson.isWatched, courseId)
                        selectedLessonForSheet = null
                    }
                }
            )
        }
    }
}

/**
 * 5-column square grid tile matching the screenshot aesthetic
 */
@Composable
fun LessonGridTile(
    lesson: Lesson,
    onClick: () -> Unit
) {
    val isWatched = lesson.isWatched
    val isDownloaded = lesson.isDownloaded
    val isDownloading = lesson.downloadStatus == DownloadStatus.DOWNLOADING

    val cardBg = when {
        isWatched -> Color(0xFF1B2A1E)
        isDownloaded -> Color(0xFF1C2430)
        else -> Color(0xFF1A1A1A)
    }

    val borderColor = when {
        isWatched -> AccentGreen
        isDownloaded -> AccentBlue
        else -> Color(0xFF282828)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1.1f)
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(
                width = if (isWatched || isDownloaded) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Episode / Lesson number centered
        Text(
            text = lesson.videoIndex.toString(),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isWatched -> AccentGreen
                isDownloaded -> Color.White
                else -> TextPrimary
            },
            textAlign = TextAlign.Center
        )

        // Watched checkmark badge in top right corner
        if (isWatched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(AccentGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Watched",
                    tint = Color.Black,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        // Offline / Downloaded badge in bottom right corner
        if (isDownloaded) {
            Icon(
                imageVector = Icons.Default.DownloadDone,
                contentDescription = "Downloaded",
                tint = AccentBlue,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(14.dp)
            )
        } else if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(12.dp),
                strokeWidth = 2.dp,
                color = AccentBlue
            )
        }
    }
}

/**
 * Bottom sheet matching the design in user's screenshot
 * (Now streamlined: exclusively Download + Kyoto Player playback!)
 */
@Composable
fun LessonActionBottomSheet(
    lesson: Lesson,
    courseTitle: String,
    onClose: () -> Unit,
    onPlayInKyotoPlayer: () -> Unit,
    onDownloadClick: () -> Unit,
    onToggleWatched: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        // Header Row: Title & Close Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Episode ${lesson.videoIndex}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lesson.title,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Info chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Duration: ${lesson.formattedDuration}",
                fontSize = 12.sp,
                color = TextMuted
            )
            Text(text = "•", fontSize = 12.sp, color = TextMuted)
            Text(
                text = if (lesson.isDownloaded) "Downloaded & Ready" else "Not Downloaded",
                fontSize = 12.sp,
                color = if (lesson.isDownloaded) AccentBlue else Color(0xFFF59E0B),
                fontWeight = FontWeight.Medium
            )
            Text(text = "•", fontSize = 12.sp, color = TextMuted)
            Text(
                text = if (lesson.isWatched) "Watched" else "Unwatched",
                fontSize = 12.sp,
                color = if (lesson.isWatched) AccentGreen else TextSecondary,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action Buttons arranged in pill rows matching the screenshot
        if (lesson.isDownloaded) {
            // Big White Pill button: Play in Kyoto Player!
            Button(
                onClick = onPlayInKyotoPlayer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Play in Kyoto Player",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        } else {
            // Big Blue Pill button: Download Episode!
            Button(
                onClick = onDownloadClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (lesson.downloadStatus == DownloadStatus.DOWNLOADING) "Downloading..." else "Download Episode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Toggle Watched Button
            OutlinedButton(
                onClick = onToggleWatched,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (lesson.isWatched) TextSecondary else AccentGreen
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (lesson.isWatched) Color(0xFF444444) else AccentGreen
                )
            ) {
                Icon(
                    imageVector = if (lesson.isWatched) Icons.Default.Close else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (lesson.isWatched) "Mark Unwatched" else "Mark Watched",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }

            if (lesson.isDownloaded) {
                // Delete Download Button
                OutlinedButton(
                    onClick = onDownloadClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF4444)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delete File",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
