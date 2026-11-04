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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseDetailScreen(
    courseId: String,
    repository: CourseRepository,
    onBackClick: () -> Unit,
    onPlayLesson: ((Lesson) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val course by repository.getCourse(courseId).collectAsState(initial = null)

    var ascendingOrder by remember { mutableStateOf(true) }
    val lessons by repository.getLessons(courseId, ascendingOrder).collectAsState(initial = emptyList())
    val storageStats = remember(lessons) { repository.getCourseStorageStats(lessons) }
    var selectedQuality by remember { mutableStateOf(repository.getCourseQuality(courseId)) }

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

    // Session playback tracker to remember progress across ANY external player
    var activePlaybackLesson by remember { mutableStateOf<Lesson?>(null) }
    var activePlaybackStartTime by remember { mutableStateOf(0L) }
    var activePlaybackInitialPos by remember { mutableStateOf(0L) }
    var activePlaybackDurationMs by remember { mutableStateOf(0L) }
    var pendingPlaybackPrompt by remember { mutableStateOf<PlaybackReturnPrompt?>(null) }

    // Result contract listener for players like MX Player / VLC that return exact playback position
    val playerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val lesson = activePlaybackLesson
        if (lesson != null) {
            val playbackResult = ExternalPlayerHelper.extractPlaybackResult(result.data)
            if (playbackResult != null) {
                coroutineScope.launch {
                    if (playbackResult.isCompleted) {
                        repository.updateLessonWatchStatus(lesson.id, true, courseId)
                    } else if (playbackResult.positionMs > 0) {
                        repository.updateLessonProgress(
                            lesson.id,
                            playbackResult.positionMs,
                            playbackResult.durationMs ?: activePlaybackDurationMs,
                            courseId
                        )
                    }
                }
                val sec = playbackResult.positionMs / 1000
                val formatted = String.format("%d:%02d", sec / 60, sec % 60)
                val msg = if (playbackResult.isCompleted) "Episode ${lesson.videoIndex} completed! 🎉" else "Progress saved: $formatted"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                // Clear active session so ON_RESUME doesn't prompt
                activePlaybackLesson = null
                activePlaybackStartTime = 0L
            }
        }
    }

    // Universal playback resume listener: When user returns to Parama Padida
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val lesson = activePlaybackLesson
                val startTime = activePlaybackStartTime
                if (lesson != null && startTime > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    // If user was in player for at least 5 seconds, prompt for progress confirmation
                    // instead of blindly writing wall-clock time (which corrupts progress on pause)
                    if (elapsed >= 5000) {
                        val dur = if (activePlaybackDurationMs > 0) activePlaybackDurationMs else lesson.durationMs
                        pendingPlaybackPrompt = PlaybackReturnPrompt(
                            lesson = lesson,
                            initialPosMs = activePlaybackInitialPos,
                            elapsedMs = elapsed,
                            durationMs = dur
                        )
                    }
                    activePlaybackLesson = null
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
        activePlaybackLesson = lesson
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
        activePlaybackLesson = lesson
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

    var filterMode by remember { mutableStateOf(LessonFilterMode.ALL) }

    val filteredLessons = remember(lessons, searchQuery, filterMode) {
        val base = when (filterMode) {
            LessonFilterMode.ALL -> lessons
            LessonFilterMode.UNWATCHED -> lessons.filter { !it.isWatched }
            LessonFilterMode.DOWNLOADED -> lessons.filter { it.isDownloaded }
        }
        if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim().lowercase()
            base.filter {
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
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$watchedCount / $totalCount completed • $progressPercent%",
                                fontSize = 12.sp,
                                color = AccentGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                            repository.queueCourseDownloads(c.id, selectedQuality)
                                            Toast.makeText(context, "Queued all episodes ($selectedQuality) for sequential batch download", Toast.LENGTH_SHORT).show()
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
            val continueTargetLesson = remember(filteredLessons) {
                filteredLessons.firstOrNull { it.watchProgressMs > 0 && !it.isWatched }
                    ?: filteredLessons.firstOrNull { !it.isWatched }
            }

            // 5-Column Grid with Hero Resume Card matching user's layout
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (searchQuery.isBlank()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(5) }) {
                        CourseStorageDashboardCard(
                            storageStats = storageStats,
                            selectedQuality = selectedQuality,
                            onQualityChanged = { q ->
                                selectedQuality = q
                                repository.setCourseQuality(courseId, q)
                                Toast.makeText(context, "Download quality set to ${getQualityDisplayName(q)}", Toast.LENGTH_SHORT).show()
                            },
                            onCleanWatched = {
                                coroutineScope.launch {
                                    val count = repository.deleteWatchedDownloads(courseId)
                                    Toast.makeText(context, if (count > 0) "Cleaned $count watched video(s) from storage" else "No watched videos to delete", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDownloadRemaining = {
                                coroutineScope.launch {
                                    repository.queueCourseDownloads(courseId, selectedQuality)
                                    Toast.makeText(context, "Queued remaining episodes ($selectedQuality)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                if (searchQuery.isBlank() && continueTargetLesson != null) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(5) }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                                .clickable {
                                    startVideoPlayback(continueTargetLesson, resume = true)
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3E50))
                        ) {
                            Row(
                                modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (continueTargetLesson.watchProgressMs > 0) "RESUME EPISODE ${continueTargetLesson.videoIndex}" else "START EPISODE ${continueTargetLesson.videoIndex}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentBlue
                                        )
                                        if (continueTargetLesson.watchProgressMs > 0) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "• ${continueTargetLesson.formattedProgress}",
                                                fontSize = 11.sp,
                                                color = AccentGreen,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = continueTargetLesson.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (continueTargetLesson.watchProgressMs > 0) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { continueTargetLesson.progressFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(1.5.dp)),
                                            color = AccentGreen,
                                            trackColor = Color(0xFF2A2A2A)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.Black,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Filter & Organization Chips
                if (searchQuery.isBlank() && lessons.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(5) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = filterMode == LessonFilterMode.ALL,
                                onClick = { filterMode = LessonFilterMode.ALL },
                                label = { Text("All (${lessons.size})", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentBlue,
                                    selectedLabelColor = Color.White,
                                    containerColor = DarkCard,
                                    labelColor = TextSecondary
                                )
                            )
                            FilterChip(
                                selected = filterMode == LessonFilterMode.UNWATCHED,
                                onClick = { filterMode = LessonFilterMode.UNWATCHED },
                                label = { Text("Unwatched (${lessons.count { !it.isWatched }})", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF1E3A5F),
                                    selectedLabelColor = AccentBlue,
                                    containerColor = DarkCard,
                                    labelColor = TextSecondary
                                )
                            )
                            FilterChip(
                                selected = filterMode == LessonFilterMode.DOWNLOADED,
                                onClick = { filterMode = LessonFilterMode.DOWNLOADED },
                                label = { Text("Downloaded ($downloadedCount)", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF1B2A1E),
                                    selectedLabelColor = AccentGreen,
                                    containerColor = DarkCard,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }

                if (filteredLessons.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(5) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) "No episodes match \"$searchQuery\"" else "No episodes in this filter",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(filteredLessons, key = { it.id }) { lesson ->
                        LessonGridTile(
                            lesson = lesson,
                            onClick = { selectedLessonForSheet = lesson }
                        )
                    }
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
                    if (!resume) {
                        coroutineScope.launch {
                            repository.updateLessonProgress(lesson.id, 0L, lesson.durationMs, courseId)
                        }
                    }
                    selectedLessonForSheet = null
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
                        val newWatchedState = !lesson.isWatched
                        repository.updateLessonWatchStatus(lesson.id, newWatchedState, courseId)
                        val msg = if (newWatchedState) "Marked Episode ${lesson.videoIndex} as completed" else "Marked Episode ${lesson.videoIndex} as incomplete"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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

    pendingPlaybackPrompt?.let { prompt ->
        PlaybackProgressConfirmationDialog(
            prompt = prompt,
            onDismiss = { pendingPlaybackPrompt = null },
            onConfirmProgress = { confirmedPos ->
                coroutineScope.launch {
                    repository.updateLessonProgress(
                        prompt.lesson.id,
                        confirmedPos,
                        prompt.durationMs,
                        courseId
                    )
                }
                val formatted = formatProgressMs(confirmedPos)
                Toast.makeText(context, "Progress saved: $formatted", Toast.LENGTH_SHORT).show()
                pendingPlaybackPrompt = null
            },
            onMarkCompleted = {
                coroutineScope.launch {
                    repository.updateLessonWatchStatus(prompt.lesson.id, true, courseId)
                }
                Toast.makeText(context, "Marked Episode ${prompt.lesson.videoIndex} as completed! 🎉", Toast.LENGTH_SHORT).show()
                pendingPlaybackPrompt = null
            }
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
@OptIn(ExperimentalLayoutApi::class)
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

        Spacer(modifier = Modifier.height(10.dp))

        // Responsive Info Badges (FlowRow prevents word breaking like "Unwatch/ed")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Duration Badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF222222)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lesson.formattedDuration,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Download Status Badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when {
                    lesson.isDownloaded -> Color(0xFF132838)
                    lesson.downloadStatus == DownloadStatus.DOWNLOADING -> Color(0xFF132838)
                    lesson.downloadStatus == DownloadStatus.QUEUED -> Color(0xFF2E2211)
                    else -> Color(0xFF222222)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            lesson.isDownloaded -> Icons.Default.DownloadDone
                            lesson.downloadStatus == DownloadStatus.DOWNLOADING -> Icons.Default.Download
                            lesson.downloadStatus == DownloadStatus.QUEUED -> Icons.Default.Schedule
                            else -> Icons.Default.CloudDownload
                        },
                        contentDescription = null,
                        tint = when {
                            lesson.isDownloaded || lesson.downloadStatus == DownloadStatus.DOWNLOADING -> AccentBlue
                            lesson.downloadStatus == DownloadStatus.QUEUED -> Color(0xFFF59E0B)
                            else -> TextMuted
                        },
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            lesson.isDownloaded -> "Downloaded"
                            lesson.downloadStatus == DownloadStatus.DOWNLOADING -> "Downloading ${lesson.downloadProgress}%"
                            lesson.downloadStatus == DownloadStatus.QUEUED -> "Queued"
                            else -> "Not Downloaded"
                        },
                        fontSize = 12.sp,
                        color = when {
                            lesson.isDownloaded || lesson.downloadStatus == DownloadStatus.DOWNLOADING -> AccentBlue
                            lesson.downloadStatus == DownloadStatus.QUEUED -> Color(0xFFF59E0B)
                            else -> TextMuted
                        },
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Completion / Watched Status Badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (lesson.isWatched) Color(0xFF172C1E) else Color(0xFF222222)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (lesson.isWatched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (lesson.isWatched) AccentGreen else TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (lesson.isWatched) "Completed" else "Unwatched",
                        fontSize = 12.sp,
                        color = if (lesson.isWatched) AccentGreen else TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (lesson.isDownloaded && !lesson.localFilePath.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
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
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                color = Color(0xFFA7F3D0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "Reset",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier
                                .clickable(onClick = onResetProgress)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
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
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Choose Player...",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

        Spacer(modifier = Modifier.height(14.dp))

        // Bottom Action Controls (Responsive flex row with custom content padding)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggle Mark Completed Button
            OutlinedButton(
                onClick = onToggleWatched,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (lesson.isWatched) Color(0xFF142918) else Color.Transparent,
                    contentColor = if (lesson.isWatched) AccentGreen else Color.White
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (lesson.isWatched) AccentGreen else Color(0xFF444444)
                )
            ) {
                Icon(
                    imageVector = if (lesson.isWatched) Icons.Default.CheckCircle else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (lesson.isWatched) AccentGreen else Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (lesson.isWatched) "Completed" else "Mark Completed",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (lesson.isDownloaded) {
                // Delete Download File Button
                OutlinedButton(
                    onClick = onRequestDeleteDownload,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF261214),
                        contentColor = Color(0xFFFF5252)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6B242A))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFFFF5252)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delete File",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseStorageDashboardCard(
    storageStats: com.yashwant.paramapadida.data.repository.CourseStorageStats,
    selectedQuality: String,
    onQualityChanged: (String) -> Unit,
    onCleanWatched: () -> Unit,
    onDownloadRemaining: () -> Unit
) {
    var showQualityMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF233544))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${storageStats.formattedCourseSize} • ${storageStats.downloadedCount} downloaded",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Text(
                    text = "${storageStats.formattedDeviceFree} free on device",
                    fontSize = 12.sp,
                    color = AccentGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Storage bar
            LinearProgressIndicator(
                progress = { storageStats.deviceUsedPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = AccentBlue,
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quality Chip & Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quality Selector Chip
                Box {
                    Surface(
                        onClick = { showQualityMenu = true },
                        shape = RoundedCornerShape(8.dp),
                        color = DarkCardHighlight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = getQualityDisplayName(selectedQuality),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showQualityMenu,
                        onDismissRequest = { showQualityMenu = false },
                        modifier = Modifier.background(DarkSurface)
                    ) {
                        listOf(
                            "1080" to "1080p HD (For coding/charts)",
                            "720" to "720p HD (Balanced default)",
                            "480" to "480p SD (Space saver)",
                            "audio" to "Audio Only (M4A Podcast)"
                        ).forEach { (qKey, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        color = if (selectedQuality == qKey) AccentBlue else TextPrimary,
                                        fontWeight = if (selectedQuality == qKey) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onQualityChanged(qKey)
                                    showQualityMenu = false
                                }
                            )
                        }
                    }
                }

                // Quick Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Clean Watched
                    OutlinedButton(
                        onClick = onCleanWatched,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clean Watched", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Download Remaining
                    Button(
                        onClick = onDownloadRemaining,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

private fun getQualityDisplayName(quality: String): String {
    return when (quality) {
        "1080" -> "1080p HD"
        "720" -> "720p HD"
        "480" -> "480p SD"
        "audio" -> "Audio M4A"
        else -> quality
    }
}

data class PlaybackReturnPrompt(
    val lesson: Lesson,
    val initialPosMs: Long,
    val elapsedMs: Long,
    val durationMs: Long
)

enum class LessonFilterMode {
    ALL,
    UNWATCHED,
    DOWNLOADED
}

private fun formatProgressMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
private fun PlaybackProgressConfirmationDialog(
    prompt: PlaybackReturnPrompt,
    onDismiss: () -> Unit,
    onConfirmProgress: (Long) -> Unit,
    onMarkCompleted: () -> Unit
) {
    val durationMs = if (prompt.durationMs > 0) prompt.durationMs else (prompt.initialPosMs + prompt.elapsedMs + 60_000L)
    val estimatedMs = (prompt.initialPosMs + prompt.elapsedMs).coerceIn(0L, durationMs)

    var currentSliderMs by remember { mutableStateOf(estimatedMs.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF1E3A5F),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "EPISODE ${prompt.lesson.videoIndex}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "Update Watch Progress?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = prompt.lesson.title,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Current Position Display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Position: ${formatProgressMs(currentSliderMs.toLong())}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen
                    )
                    if (prompt.durationMs > 0) {
                        Text(
                            text = "of ${formatProgressMs(prompt.durationMs)}",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Fine-tune Slider
                Slider(
                    value = currentSliderMs,
                    onValueChange = { currentSliderMs = it },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentGreen,
                        activeTrackColor = AccentGreen,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                // Quick Nudge Buttons (-1 min / +1 min)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            currentSliderMs = (currentSliderMs - 60_000f).coerceAtLeast(0f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentPadding = PaddingValues(0.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E))
                    ) {
                        Text("-1 min", fontSize = 12.sp, color = TextSecondary)
                    }

                    OutlinedButton(
                        onClick = {
                            currentSliderMs = (currentSliderMs + 60_000f).coerceAtMost(durationMs.toFloat())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentPadding = PaddingValues(0.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E3E3E))
                    ) {
                        Text("+1 min", fontSize = 12.sp, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Save Button
                Button(
                    onClick = { onConfirmProgress(currentSliderMs.toLong()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Save (${formatProgressMs(currentSliderMs.toLong())})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Mark Completed Button
                OutlinedButton(
                    onClick = onMarkCompleted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(21.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen)
                ) {
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mark as 100% Completed", color = AccentGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep Previous (${formatProgressMs(prompt.initialPosMs)})", color = TextSecondary)
            }
        },
        confirmButton = {},
        containerColor = DarkSurface
    )
}
