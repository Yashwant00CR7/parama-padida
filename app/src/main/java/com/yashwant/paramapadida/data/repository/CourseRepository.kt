package com.yashwant.paramapadida.data.repository

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import androidx.work.*
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.data.model.Course
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.data.model.Note
import com.yashwant.paramapadida.downloader.DownloadWorker
import com.yashwant.paramapadida.engine.YouTubeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class CourseRepository(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val courseDao = database.courseDao()
    private val lessonDao = database.lessonDao()
    private val noteDao = database.noteDao()
    private val workManager = WorkManager.getInstance(context)
    private val qualityPrefs = context.getSharedPreferences("parama_quality_prefs", Context.MODE_PRIVATE)

    val allCourses: Flow<List<Course>> = courseDao.getAllCourses()

    fun getCourse(id: String): Flow<Course?> = courseDao.getCourseById(id)

    fun getLessons(courseId: String, ascending: Boolean = true): Flow<List<Lesson>> {
        return if (ascending) {
            lessonDao.getLessonsForCourseAsc(courseId)
        } else {
            lessonDao.getLessonsForCourseDesc(courseId)
        }
    }

    fun getLesson(id: String): Flow<Lesson?> = lessonDao.getLessonById(id)

    fun getNotesForLesson(lessonId: String): Flow<List<Note>> = noteDao.getNotesForLesson(lessonId)

    suspend fun importPlaylist(playlistUrl: String, category: String = "General"): Course =
        withContext(Dispatchers.IO) {
            val metadata = YouTubeEngine.fetchPlaylist(playlistUrl)
            val folder = YouTubeEngine.getCourseFolder(context, metadata.title)

            val course = Course(
                id = metadata.id,
                title = metadata.title,
                channelTitle = metadata.uploader,
                playlistUrl = playlistUrl,
                thumbnailUrl = metadata.entries.firstOrNull()?.thumbnail ?: "",
                totalVideos = metadata.entries.size,
                watchedVideos = 0,
                category = category,
                folderPath = folder.absolutePath
            )

            courseDao.insertCourse(course)

            val lessons = metadata.entries.map { entry ->
                Lesson(
                    id = "${metadata.id}_${entry.id}",
                    courseId = metadata.id,
                    videoIndex = entry.index,
                    title = entry.title,
                    durationSeconds = entry.durationSeconds,
                    thumbnailUrl = entry.thumbnail,
                    webUrl = entry.url
                )
            }

            lessonDao.insertLessons(lessons)
            course
        }

    suspend fun updateLessonWatchStatus(lessonId: String, isWatched: Boolean, courseId: String) =
        withContext(Dispatchers.IO) {
            lessonDao.updateWatchStatus(lessonId, isWatched)
            courseDao.refreshWatchedCount(courseId)
        }

    suspend fun updateLessonProgress(lessonId: String, progressMs: Long, durationMs: Long, courseId: String) =
        withContext(Dispatchers.IO) {
            lessonDao.updateProgress(lessonId, progressMs)
            // Auto mark completed at 90%
            if (durationMs > 0 && progressMs >= (durationMs * 0.90f).toLong()) {
                lessonDao.updateWatchStatus(lessonId, true)
                courseDao.refreshWatchedCount(courseId)
            }
        }

    suspend fun resolveStreamUrl(lesson: Lesson): String? = withContext(Dispatchers.IO) {
        if (!lesson.directStreamUrl.isNullOrBlank()) {
            return@withContext lesson.directStreamUrl
        }
        val streamUrl = YouTubeEngine.resolveStreamUrl(lesson.webUrl)
        if (streamUrl != null) {
            lessonDao.updateDirectStreamUrl(lesson.id, streamUrl)
        }
        streamUrl
    }

    /**
     * Enqueues a single lesson for sequential batch download.
     */
    suspend fun queueDownload(lesson: Lesson, quality: String = "720") = withContext(Dispatchers.IO) {
        lessonDao.markLessonDownloadStatus(lesson.id, DownloadStatus.QUEUED)
        startSequentialDownloader(quality)
    }

    /**
     * Enqueues all un-downloaded lessons in a course for sequential batch download.
     * Only 1 video will download at a time to keep RAM and CPU footprint minimal.
     */
    suspend fun queueCourseDownloads(courseId: String, quality: String? = null) = withContext(Dispatchers.IO) {
        val selectedQuality = quality ?: getCourseQuality(courseId)
        lessonDao.markCourseNonDownloadedAs(courseId, DownloadStatus.QUEUED)
        startSequentialDownloader(selectedQuality)
    }

    /**
     * Cancels any active download queue and resets remaining queued items.
     */
    suspend fun cancelAllDownloads() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
        lessonDao.resetAllQueuedLessons()
    }

    private fun startSequentialDownloader(quality: String = "720") {
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_QUALITY to quality))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("sequential_batch_download")
            .build()

        workManager.enqueueUniqueWork(
            DownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    suspend fun deleteDownloadedVideo(lesson: Lesson) = withContext(Dispatchers.IO) {
        val path = lesson.localFilePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
            // Deregister from system MediaScanner so VLC and Gallery immediately remove ghost entries
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)

            // Remove associated subtitle files (.vtt, .srt)
            val parentDir = file.parentFile
            if (parentDir != null && parentDir.exists()) {
                val baseName = file.nameWithoutExtension
                parentDir.listFiles()?.filter {
                    it.name.startsWith(baseName) && (it.extension.equals("vtt", ignoreCase = true) || it.extension.equals("srt", ignoreCase = true))
                }?.forEach { subFile ->
                    try {
                        subFile.delete()
                        MediaScannerConnection.scanFile(context, arrayOf(subFile.absolutePath), null, null)
                    } catch (_: Exception) {}
                }
            }

            // Remove any mirror copies with matching filename
            val fileName = file.name
            if (fileName.isNotBlank()) {
                val candidateDirs = listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    File(Environment.getExternalStorageDirectory(), "Android/data/com.kyotoplayer/files/Downloads"),
                    File(Environment.getExternalStorageDirectory(), "KyotoPlayer"),
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                )
                for (base in candidateDirs) {
                    if (base == null || !base.exists()) continue
                    base.walkTopDown().maxDepth(3).filter { it.name == fileName }.forEach { mirrorFile ->
                        try {
                            mirrorFile.delete()
                            MediaScannerConnection.scanFile(context, arrayOf(mirrorFile.absolutePath), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.NOT_DOWNLOADED, 0)
    }

    suspend fun deleteWatchedDownloads(courseId: String): Int = withContext(Dispatchers.IO) {
        val lessons = lessonDao.getLessonsForCourseSync(courseId)
        var count = 0
        for (lesson in lessons) {
            if (lesson.isWatched && lesson.isDownloaded) {
                deleteDownloadedVideo(lesson)
                count++
            }
        }
        count
    }

    suspend fun deleteAllCourseDownloads(courseId: String): Int = withContext(Dispatchers.IO) {
        val lessons = lessonDao.getLessonsForCourseSync(courseId)
        var count = 0
        for (lesson in lessons) {
            if (lesson.isDownloaded) {
                deleteDownloadedVideo(lesson)
                count++
            }
        }
        count
    }

    /**
     * Calculates storage metrics for a course:
     * - Space occupied by course files
     * - Free storage on internal device partition
     * - Total internal device capacity
     */
    fun getCourseStorageStats(lessons: List<Lesson>): CourseStorageStats {
        var totalBytes = 0L
        var count = 0
        for (lesson in lessons) {
            val path = lesson.localFilePath
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists()) {
                    totalBytes += file.length()
                    count++
                }
            }
        }

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val totalDeviceBytes = statFs.blockCountLong * statFs.blockSizeLong

        return CourseStorageStats(
            courseBytes = totalBytes,
            downloadedCount = count,
            deviceFreeBytes = freeBytes,
            deviceTotalBytes = totalDeviceBytes
        )
    }

    fun getCourseQuality(courseId: String): String {
        return qualityPrefs.getString("quality_$courseId", "720") ?: "720"
    }

    fun setCourseQuality(courseId: String, quality: String) {
        qualityPrefs.edit().putString("quality_$courseId", quality).apply()
    }

    suspend fun addNote(lessonId: String, courseId: String, timestampMs: Long, text: String): Long =
        withContext(Dispatchers.IO) {
            noteDao.insertNote(
                Note(
                    lessonId = lessonId,
                    courseId = courseId,
                    timestampMs = timestampMs,
                    text = text
                )
            )
        }

    suspend fun deleteNote(noteId: Long) = withContext(Dispatchers.IO) {
        noteDao.deleteNoteById(noteId)
    }

    suspend fun deleteCourse(courseId: String) = withContext(Dispatchers.IO) {
        courseDao.deleteCourseById(courseId)
    }
}

/**
 * Encapsulates course and device storage metrics.
 */
data class CourseStorageStats(
    val courseBytes: Long,
    val downloadedCount: Int,
    val deviceFreeBytes: Long,
    val deviceTotalBytes: Long
) {
    val formattedCourseSize: String
        get() = formatBytes(courseBytes)

    val formattedDeviceFree: String
        get() = formatBytes(deviceFreeBytes)

    val formattedDeviceTotal: String
        get() = formatBytes(deviceTotalBytes)

    val deviceUsedPercent: Float
        get() = if (deviceTotalBytes > 0) {
            ((deviceTotalBytes - deviceFreeBytes).toFloat() / deviceTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 MB"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                else -> String.format(java.util.Locale.US, "%.0f KB", kb)
            }
        }
    }
}
