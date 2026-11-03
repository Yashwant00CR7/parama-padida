package com.yashwant.paramapadida.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.data.repository.CourseRepository
import com.yashwant.paramapadida.engine.ExternalPlayerHelper
import com.yashwant.paramapadida.ui.theme.AccentBlue
import com.yashwant.paramapadida.ui.theme.AccentGreen
import com.yashwant.paramapadida.ui.theme.DarkBackground
import com.yashwant.paramapadida.ui.theme.DarkSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class AspectRatioMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    courseId: String,
    initialLessonId: String,
    repository: CourseRepository,
    onBackClick: () -> Unit,
    onNavigateToLesson: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    val lessons by repository.getLessons(courseId, ascending = true).collectAsState(initial = emptyList())
    var currentLessonId by remember { mutableStateOf(initialLessonId) }
    val currentLesson = lessons.firstOrNull { it.id == currentLessonId }

    val currentLessonIndex = lessons.indexOfFirst { it.id == currentLessonId }
    val hasPrevious = currentLessonIndex > 0
    val hasNext = currentLessonIndex >= 0 && currentLessonIndex < lessons.size - 1
    val previousLesson = if (hasPrevious) lessons[currentLessonIndex - 1] else null
    val nextLesson = if (hasNext) lessons[currentLessonIndex + 1] else null

    // Player State
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var aspectRatioMode by remember { mutableStateOf(AspectRatioMode.FIT) }

    // UI Overlay state
    var showControls by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showEpisodePicker by remember { mutableStateOf(false) }

    // Gesture indicator overlays (VLC style)
    var brightnessOverlayValue by remember { mutableStateOf<Float?>(null) }
    var volumeOverlayValue by remember { mutableStateOf<Float?>(null) }
    var doubleTapSeekFeedback by remember { mutableStateOf<String?>(null) }

    // Auto-advance countdown at video end
    var autoAdvanceCountdown by remember { mutableStateOf<Int?>(null) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // Set up immersive fullscreen mode
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Load media into player when currentLesson changes
    LaunchedEffect(currentLessonId, currentLesson?.localFilePath, currentLesson?.directStreamUrl) {
        val lesson = currentLesson ?: return@LaunchedEffect
        autoAdvanceCountdown = null
        isBuffering = true

        val localPath = lesson.localFilePath
        val mediaUri: Uri? = if (!localPath.isNullOrBlank() && File(localPath).exists()) {
            Uri.fromFile(File(localPath))
        } else if (!lesson.directStreamUrl.isNullOrBlank()) {
            Uri.parse(lesson.directStreamUrl)
        } else {
            val resolved = repository.resolveStreamUrl(lesson)
            if (resolved != null) Uri.parse(resolved) else null
        }

        if (mediaUri != null) {
            val mediaItem = MediaItem.fromUri(mediaUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()

            val resumePos = lesson.watchProgressMs
            if (resumePos > 0) {
                exoPlayer.seekTo(resumePos)
            }
            exoPlayer.play()
        } else {
            Toast.makeText(context, "Resolving video source...", Toast.LENGTH_SHORT).show()
        }
    }

    // Attach ExoPlayer event listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        // Mark current as completed
                        coroutineScope.launch {
                            repository.updateLessonWatchStatus(currentLessonId, true, courseId)
                        }
                        // Trigger auto advance if next episode is available
                        if (hasNext) {
                            autoAdvanceCountdown = 5
                        }
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity?.setPictureInPictureParams(
                        PictureInPictureParams.Builder()
                            .setAutoEnterEnabled(playing)
                            .build()
                    )
                }
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            // Save progress on exit
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (pos > 0) {
                coroutineScope.launch {
                    repository.updateLessonProgress(currentLessonId, pos, dur, courseId)
                }
            }
            exoPlayer.release()
        }
    }

    // Periodic progress tracking loop (updates UI and DB)
    LaunchedEffect(isPlaying, currentLessonId) {
        var lastDbSave = 0L
        while (true) {
            if (exoPlayer.playbackState == Player.STATE_READY || isPlaying) {
                currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (durationMs <= 0L && exoPlayer.duration > 0L) {
                    durationMs = exoPlayer.duration
                }

                // Save to database every 3 seconds
                val now = System.currentTimeMillis()
                if (now - lastDbSave >= 3000L && currentPositionMs > 0) {
                    lastDbSave = now
                    repository.updateLessonProgress(currentLessonId, currentPositionMs, durationMs, courseId)
                }
            }
            delay(250L)
        }
    }

    // Auto-advance countdown timer
    LaunchedEffect(autoAdvanceCountdown) {
        val countdown = autoAdvanceCountdown ?: return@LaunchedEffect
        if (countdown > 0) {
            delay(1000L)
            autoAdvanceCountdown = countdown - 1
        } else if (countdown == 0 && nextLesson != null) {
            autoAdvanceCountdown = null
            currentLessonId = nextLesson.id
            onNavigateToLesson(nextLesson.id)
        }
    }

    // Controls auto-hide timer (hides controls after 4 seconds of inactivity)
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying && !isControlsLocked) {
            delay(4000L)
            showControls = false
        }
    }

    // Handle back button press
    BackHandler {
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        coroutineScope.launch {
            if (pos > 0) {
                repository.updateLessonProgress(currentLessonId, pos, dur, courseId)
            }
            onBackClick()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isControlsLocked) {
                if (isControlsLocked) {
                    detectTapGestures(
                        onTap = { showControls = !showControls }
                    )
                } else {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            val screenWidth = size.width
                            val isRightSide = offset.x > (screenWidth / 2f)
                            if (isRightSide) {
                                val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration)
                                exoPlayer.seekTo(newPos)
                                doubleTapSeekFeedback = "+10s >>"
                            } else {
                                val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                                doubleTapSeekFeedback = "<< 10s"
                            }
                            coroutineScope.launch {
                                delay(800L)
                                doubleTapSeekFeedback = null
                            }
                        }
                    )
                }
            }
            // Vertical Drag Gesture for VLC-style Brightness (Left half) & Volume (Right half)
            .pointerInput(isControlsLocked) {
                if (!isControlsLocked) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val screenWidth = size.width
                            val isRightSide = change.position.x > (screenWidth / 2f)
                            val deltaY = -dragAmount.y / size.height * 2.5f

                            if (isRightSide) {
                                // Volume Control
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val volumeChange = (deltaY * maxVolume).toInt()
                                val targetVol = (currentVol + volumeChange).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                volumeOverlayValue = targetVol.toFloat() / maxVolume.toFloat()
                            } else {
                                // Brightness Control
                                val window = activity?.window
                                val currentBrightness = window?.attributes?.screenBrightness.let {
                                    if (it == null || it < 0f) 0.5f else it
                                }
                                val newBrightness = (currentBrightness + deltaY).coerceIn(0.02f, 1.0f)
                                window?.attributes = window?.attributes?.apply {
                                    screenBrightness = newBrightness
                                }
                                brightnessOverlayValue = newBrightness
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                delay(1200L)
                                volumeOverlayValue = null
                                brightnessOverlayValue = null
                            }
                        }
                    )
                }
            }
    ) {
        // Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = aspectRatioMode.resizeMode
                }
            },
            update = { playerView ->
                playerView.resizeMode = aspectRatioMode.resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // VLC Gesture Indicators (Center Overlays)
        volumeOverlayValue?.let { volFraction ->
            VlcGestureOverlay(
                icon = if (volFraction > 0f) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                label = "Volume ${(volFraction * 100).toInt()}%",
                fraction = volFraction
            )
        }

        brightnessOverlayValue?.let { brightFraction ->
            VlcGestureOverlay(
                icon = Icons.Default.BrightnessMedium,
                label = "Brightness ${(brightFraction * 100).toInt()}%",
                fraction = brightFraction
            )
        }

        doubleTapSeekFeedback?.let { feedback ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = feedback,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        // Auto-advance Countdown Banner
        autoAdvanceCountdown?.let { count ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp, start = 20.dp, end = 20.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF142419)),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Next: Ep ${nextLesson?.videoIndex} in ${count}s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = nextLesson?.title ?: "",
                            color = Color(0xFFA7F3D0),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { autoAdvanceCountdown = null }
                        ) {
                            Text("Cancel", color = Color(0xFF9E9E9E))
                        }
                        Button(
                            onClick = {
                                if (nextLesson != null) {
                                    autoAdvanceCountdown = null
                                    currentLessonId = nextLesson.id
                                    onNavigateToLesson(nextLesson.id)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Play Now", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Screen Lock Indicator (Only button visible when locked)
        if (isControlsLocked) {
            Box(
                modifier = Modifier
                    .padding(24.dp)
                    .align(Alignment.TopEnd)
            ) {
                IconButton(
                    onClick = { isControlsLocked = false },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Unlock Controls",
                        tint = AccentGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Full VLC Controls Overlay (Top Bar, Center Play, Bottom Scrubber)
        AnimatedVisibility(
            visible = showControls && !isControlsLocked,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        val pos = exoPlayer.currentPosition
                        val dur = exoPlayer.duration
                        coroutineScope.launch {
                            if (pos > 0) repository.updateLessonProgress(currentLessonId, pos, dur, courseId)
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Episode ${currentLesson?.videoIndex ?: 1}",
                            fontSize = 12.sp,
                            color = AccentBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = currentLesson?.title ?: "Video Player",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Playback Speed Button (0.5x - 2.0x)
                    Box {
                        TextButton(
                            onClick = { showSpeedMenu = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Text(
                                text = "${playbackSpeed}x",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${speed}x",
                                            color = if (playbackSpeed == speed) AccentBlue else Color.White,
                                            fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        playbackSpeed = speed
                                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                                        showSpeedMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // PiP Button
                    IconButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build()
                            activity?.enterPictureInPictureMode(params)
                        } else {
                            Toast.makeText(context, "PiP not supported on this Android version", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Picture in Picture",
                            tint = Color.White
                        )
                    }

                    // Launch in External Player (VLC / MX Player)
                    currentLesson?.let { lesson ->
                        IconButton(onClick = {
                            val pos = exoPlayer.currentPosition
                            exoPlayer.pause()
                            ExternalPlayerHelper.playDownloadedLesson(context, lesson, pos)
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Play in VLC",
                                tint = Color.White
                            )
                        }
                    }

                    // Lock Screen Touch Button
                    IconButton(onClick = {
                        isControlsLocked = true
                        showControls = false
                        Toast.makeText(context, "Controls locked. Tap top-right lock icon to unlock.", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Lock Screen",
                            tint = Color.White
                        )
                    }
                }

                // Center Play / Pause & Prev / Next Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Episode Button
                    IconButton(
                        onClick = {
                            if (hasPrevious && previousLesson != null) {
                                currentLessonId = previousLesson.id
                                onNavigateToLesson(previousLesson.id)
                            }
                        },
                        enabled = hasPrevious,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Episode",
                            tint = if (hasPrevious) Color.White else Color(0xFF666666),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Play / Pause Circle
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(34.dp),
                                color = Color.Black,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    // Next Episode Button
                    IconButton(
                        onClick = {
                            if (hasNext && nextLesson != null) {
                                currentLessonId = nextLesson.id
                                onNavigateToLesson(nextLesson.id)
                            }
                        },
                        enabled = hasNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Episode",
                            tint = if (hasNext) Color.White else Color(0xFF666666),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Bottom Scrubber Bar & Secondary Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Time Labels & Aspect Ratio
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currentFormatted = formatMs(currentPositionMs)
                        val totalFormatted = formatMs(durationMs)

                        Text(
                            text = "$currentFormatted / $totalFormatted",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Aspect Ratio Toggle (Fit / Fill / 16:9)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    aspectRatioMode = when (aspectRatioMode) {
                                        AspectRatioMode.FIT -> AspectRatioMode.FILL
                                        AspectRatioMode.FILL -> AspectRatioMode.STRETCH
                                        AspectRatioMode.STRETCH -> AspectRatioMode.FIT
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = aspectRatioMode.label,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Progress Scrubber Slider
                    Slider(
                        value = if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            val targetMs = (frac * durationMs).toLong()
                            currentPositionMs = targetMs
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo(currentPositionMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentBlue,
                            activeTrackColor = AccentBlue,
                            inactiveTrackColor = Color(0xFF444444)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * VLC-style gesture pill indicator overlay for Brightness and Volume
 */
@Composable
private fun BoxScope.VlcGestureOverlay(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    fraction: Float
) {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(110.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = AccentBlue,
                trackColor = Color(0xFF333333)
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remMinutes = minutes % 60
        String.format("%d:%02d:%02d", hours, remMinutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
