package com.yashwant.paramapadida.data.repository

import android.content.Context
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
    suspend fun queueCourseDownloads(courseId: String, quality: String = "720") = withContext(Dispatchers.IO) {
        lessonDao.markCourseNonDownloadedAs(courseId, DownloadStatus.QUEUED)
        startSequentialDownloader(quality)
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
        if (!lesson.localFilePath.isNullOrBlank()) {
            val file = File(lesson.localFilePath)
            if (file.exists()) {
                file.delete()
            }
        }
        lessonDao.updateDownloadStatus(lesson.id, null, DownloadStatus.NOT_DOWNLOADED, 0)
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
