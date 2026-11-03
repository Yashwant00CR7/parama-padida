package com.yashwant.paramapadida.ui.course

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yashwant.paramapadida.data.model.Course
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.data.repository.CourseRepository
import com.yashwant.paramapadida.engine.ExternalPlayerHelper
import com.yashwant.paramapadida.engine.KyotoStorageManager
import com.yashwant.paramapadida.ui.components.DebugLogsDialog
import com.yashwant.paramapadida.ui.theme.*
import com.yashwant.paramapadida.util.AppLogger
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
    var showLogsDialog by remember { mutableStateOf(false) }
    var activeLessonForLogs by remember { mutableStateOf<Lesson?>(null) }
    var currentSheetError by remember { mutableStateOf<String?>(null) }

    var lessonToDelete by remember { mutableStateOf<Lesson?>(null) }
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Session playback tracker to remember progress across ANY player
    var activePlaybackLessonId by remember { mutableStateOf<String?>(null) }
    var activePlaybackStartTime by remember { mutableStateOf(0L) }
    var activePlaybackInitialPos by remember { mutableStateOf(0L) }
    var activePlaybackDurationMs by remember { mutableStateOf(0L) }

    // Result contract listener for players like MX Player / VLC that return exact playback position
    val playerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val lessonId = activePlaybackLessonId
        if (lessonId != null) {
            val returnedPos = ExternalPlayerHelper.extractPositionFromResult(result.data)
            if (returnedPos != null && returnedPos > 0) {
                coroutineScope.launch {
                    repository.updateLessonProgress(lessonId, returnedPos, activePlaybackDurationMs, courseId)
                }
                val sec = returnedPos / 1000
                val formatted = String.format("%d:%02d", sec / 60, sec % 60)
                Toast.makeText(context, "Resumed progress saved: $formatted", Toast.LENGTH_SHORT).show()
                activePlaybackLessonId = null
                activePlaybackStartTime = 0L
            }
        }
    }

    // Universal playback resume listener: When user returns to Parama Padida
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val lessonId = activePlaybackLessonId
                val startTime = activePlaybackStartTime
                if (lessonId != null && startTime > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    // If user watched for at least 3 seconds
                    if (elapsed > 3000) {
                        val durationMs = activePlaybackDurationMs
                        val calculatedProgress = if (durationMs > 0) {
                            (activePlaybackInitialPos + elapsed).coerceAtMost(durationMs)
                        } else {
                            activePlaybackInitialPos + elapsed
                        }
                        coroutineScope.launch {
                            repository.updateLessonProgress(lessonId, calculatedProgress, durationMs, courseId)
                        }
                        val sec = calculatedProgress / 1000
                        val formatted = String.format("%d:%02d", sec / 60, sec % 60)
                        Toast.makeText(context, "Resumed progress saved: $formatted", Toast.LENGTH_SHORT).show()
                    }
                    activePlaybackLessonId = null
                    activePlaybackStartTime = 0L
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun startVideoPlayback(lesson: Lesson, resume: Boolean) {
        val targetPos = if (resume) lesson.watchProgressMs else 0L
        activePlaybackLessonId = lesson.id
        activePlaybackStartTime = System.currentTimeMillis()
        activePlaybackInitialPos = targetPos
        activePlaybackDurationMs = lesson.durationMs
        currentSheetError = null

        ExternalPlayerHelper.playDownloadedLesson(
            context = context,
            lesson = lesson,
            resumePositionMs = targetPos,
            launcher = playerLauncher
        ) { err ->
            currentSheetError = err
        }
    }

    fun startVideoPlaybackWithChooser(lesson: Lesson, resume: Boolean) {
        val targetPos = if (resume) lesson.watchProgressMs else 0L
        activePlaybackLessonId = lesson.id
        activePlaybackStartTime = System.currentTimeMillis()
        activePlaybackInitialPos = targetPos
        activePlaybackDurationMs = lesson.durationMs
        currentSheetError = null

        val ok = ExternalPlayerHelper.playWithSystemChooser(
            context = context,
            lesson = lesson,
            resumePositionMs = targetPos,
            launcher = playerLauncher
        )
        if (!ok) {
            currentSheetError = "No external video player installed on device."
        }
    }

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

                    // Bug Logs & Diagnostics
                    IconButton(onClick = {
                        activeLessonForLogs = selectedLessonForSheet
                        showLogsDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Bug Logs & Diagnostics",
                            tint = Color(0xFFFF5370)
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
                                text = { Text("Bug Logs & Diagnostics", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = Color(0xFFFF5370)) },
                                onClick = {
                                    showOptionsMenu = false
                                    activeLessonForLogs = selectedLessonForSheet
                                    showLogsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Download All (Sequential Batch)", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = AccentBlue) },
                                onClick = {
                                    showOptionsMenu = false
                                    course?.let { c ->
                                        coroutineScope.launch {
                                            repository.queueCourseDownloads(c.id)
                                            Toast.makeText(context, "Queued all episodes for sequential batch download", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Pause / Cancel Downloads", color = Color(0xFFEF4444)) },
                                leadingIcon = { Icon(Icons.Default.PauseCircle, contentDescription = null, tint = Color(0xFFEF4444)) },
                                onClick = {
                                    showOptionsMenu = false
                                    coroutineScope.launch {
                                        repository.cancelAllDownloads()
                                        Toast.makeText(context, "Cancelled pending downloads", Toast.LENGTH_SHORT).show()
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

                            DropdownMenuItem(
                                text = { Text("Delete Watched Downloads", color = Color(0xFFEF4444)) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFEF4444)) },
                                onClick = {
                                    showOptionsMenu = false
                                    coroutineScope.launch {
                                        val deletedCount = repository.deleteWatchedDownloads(courseId)
                                        Toast.makeText(context, "Deleted $deletedCount watched videos from storage", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Delete All Downloads", color = Color(0xFFEF4444)) },
                                leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444)) },
                                onClick = {
                                    showOptionsMenu = false
                                    showDeleteAllConfirmDialog = true
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

    // Confirmation Dialog for deleting a single downloaded video
    if (lessonToDelete != null) {
        val target = lessonToDelete!!
        AlertDialog(
            onDismissRequest = { lessonToDelete = null },
            title = { Text("Delete Download?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Delete downloaded video for Episode ${target.videoIndex} (\"${target.title}\") to free up storage?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            repository.deleteDownloadedVideo(target)
                            Toast.makeText(context, "Deleted Episode ${target.videoIndex} download", Toast.LENGTH_SHORT).show()
                            lessonToDelete = null
                            selectedLessonForSheet = null
                        }
                    }
                ) {
                    Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { lessonToDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Confirmation Dialog for deleting all downloaded videos in this course
    if (showDeleteAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmDialog = false },
            title = { Text("Delete All Downloads?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete all downloaded video files for this course from your device storage?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllConfirmDialog = false
                        coroutineScope.launch {
                            val deletedCount = repository.deleteAllCourseDownloads(courseId)
                            Toast.makeText(context, "Deleted $deletedCount downloaded files", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Delete All", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Bottom Sheet with Universal Playback Memory & Deletion
    val activeLesson = filteredLessons.firstOrNull { it.id == selectedLessonForSheet?.id } ?: selectedLessonForSheet
    activeLesson?.let { lesson ->
        ModalBottomSheet(
            onDismissRequest = {
                selectedLessonForSheet = null
                currentSheetError = null
            },
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
                errorMessage = currentSheetError,
                onClose = {
                    selectedLessonForSheet = null
                    currentSheetError = null
                },
                onPlayVideo = { resume ->
                    startVideoPlayback(lesson, resume)
                },
                onPlayInSystemChooser = { resume ->
                    startVideoPlaybackWithChooser(lesson, resume)
                },
                onResetProgress = {
                    coroutineScope.launch {
                        repository.updateLessonProgress(lesson.id, 0L, lesson.durationMs, courseId)
                        Toast.makeText(context, "Progress reset to beginning", Toast.LENGTH_SHORT).show()
                    }
                },
                onRequestDeleteDownload = {
                    lessonToDelete = lesson
                },
                onOpenLogs = {
                    activeLessonForLogs = lesson
                    showLogsDialog = true
                },
                onDownloadClick = {
                    coroutineScope.launch {
                        if (lesson.isDownloaded) {
                            lessonToDelete = lesson
                        } else {
                            repository.queueDownload(lesson)
                            Toast.makeText(context, "Queued Ep #${lesson.videoIndex} for batch download", Toast.LENGTH_SHORT).show()
                            selectedLessonForSheet = null
                            currentSheetError = null
                        }
                    }
                },
                onToggleWatched = {
                    coroutineScope.launch {
                        repository.updateLessonWatchStatus(lesson.id, !lesson.isWatched, courseId)
                    }
                }
            )
        }
    }

    if (showLogsDialog) {
        DebugLogsDialog(
            onDismissRequest = { showLogsDialog = false },
            currentLesson = activeLessonForLogs ?: selectedLessonForSheet
        )
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
        } else if (lesson.downloadStatus == DownloadStatus.QUEUED) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Queued in Batch",
                tint = Color(0xFFF59E0B),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(12.dp)
            )
        }

        // Playback progress line along bottom edge
        if (lesson.watchProgressMs > 0 && !isWatched && lesson.progressFraction > 0f) {
            LinearProgressIndicator(
                progress = { lesson.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)),
                color = AccentGreen,
                trackColor = Color(0xFF2C2C2C)
            )
        }
    }
}

/**
 * Bottom sheet matching the design in user's screenshot
 * (Streamlined: Playback Resume, Choose Player, and One-Tap Download Deletion)
 */
@Composable
fun LessonActionBottomSheet(
    lesson: Lesson,
    courseTitle: String,
    errorMessage: String? = null,
    onClose: () -> Unit,
    onPlayVideo: (resume: Boolean) -> Unit,
    onPlayInSystemChooser: (resume: Boolean) -> Unit,
    onResetProgress: () -> Unit,
    onRequestDeleteDownload: () -> Unit,
    onOpenLogs: () -> Unit,
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
                text = when {
                    lesson.isDownloaded -> "Downloaded & Ready"
                    lesson.downloadStatus == DownloadStatus.DOWNLOADING -> "Downloading (${lesson.downloadProgress}%)"
                    lesson.downloadStatus == DownloadStatus.QUEUED -> "Queued in Batch"
                    else -> "Not Downloaded"
                },
                fontSize = 12.sp,
                color = when {
                    lesson.isDownloaded || lesson.downloadStatus == DownloadStatus.DOWNLOADING -> AccentBlue
                    else -> Color(0xFFF59E0B)
                },
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

        if (lesson.isDownloaded && !lesson.localFilePath.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                val parentName = File(lesson.localFilePath).parentFile?.name ?: "Movies"
                Text(
                    text = "Saved in: $parentName",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        // Playback Memory & Resume Status Card
        if (lesson.watchProgressMs > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF142419)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A5934))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Last watched: ${lesson.formattedProgress} / ${lesson.formattedDuration} (${(lesson.progressFraction * 100).toInt()}%)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFA7F3D0)
                            )
                        }

                        Text(
                            text = "Reset",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier
                                .clickable(onClick = onResetProgress)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { lesson.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentGreen,
                        trackColor = Color(0xFF243B2A)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Playback Action Buttons
        if (lesson.isDownloaded) {
            if (lesson.watchProgressMs > 0) {
                // Resume Primary Button
                Button(
                    onClick = { onPlayVideo(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
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
                        text = "Resume at ${lesson.formattedProgress}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Start from Beginning Button
                OutlinedButton(
                    onClick = { onPlayVideo(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E))
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start from Beginning (0:00)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            } else {
                // Play Video from Beginning
                Button(
                    onClick = { onPlayVideo(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
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
                        text = "Play Video",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary Outlined Pill: Choose Player
            OutlinedButton(
                onClick = { onPlayInSystemChooser(true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E))
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Choose Player...",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextSecondary
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
                    text = when (lesson.downloadStatus) {
                        DownloadStatus.DOWNLOADING -> "Downloading (${lesson.downloadProgress}%)..."
                        DownloadStatus.QUEUED -> "Queued in Batch..."
                        else -> "Download Episode"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLogs),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1618)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B2635))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = Color(0xFFFF5370),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Playback Issue",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = errorMessage,
                            fontSize = 11.sp,
                            color = Color(0xFFFFB4BC),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Logs >",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue
                    )
                }
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
                // Delete Download Button with Red Accent
                OutlinedButton(
                    onClick = onRequestDeleteDownload,
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
