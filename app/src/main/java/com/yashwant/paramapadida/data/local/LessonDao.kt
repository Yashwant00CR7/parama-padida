package com.yashwant.paramapadida.data.local

import androidx.room.*
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.data.model.Lesson
import kotlinx.coroutines.flow.Flow

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons WHERE courseId = :courseId ORDER BY videoIndex ASC")
    fun getLessonsForCourseAsc(courseId: String): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons WHERE courseId = :courseId ORDER BY videoIndex DESC")
    fun getLessonsForCourseDesc(courseId: String): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons WHERE id = :id LIMIT 1")
    fun getLessonById(id: String): Flow<Lesson?>

    @Query("SELECT * FROM lessons WHERE id = :id LIMIT 1")
    suspend fun getLessonByIdSync(id: String): Lesson?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLessons(lessons: List<Lesson>)

    @Update
    suspend fun updateLesson(lesson: Lesson)

    @Query("UPDATE lessons SET isWatched = :isWatched WHERE id = :lessonId")
    suspend fun updateWatchStatus(lessonId: String, isWatched: Boolean)

    @Query("UPDATE lessons SET watchProgressMs = :progressMs, lastWatchedAt = :timestamp WHERE id = :lessonId")
    suspend fun updateProgress(lessonId: String, progressMs: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE lessons SET directStreamUrl = :streamUrl WHERE id = :lessonId")
    suspend fun updateDirectStreamUrl(lessonId: String, streamUrl: String)

    @Query("UPDATE lessons SET localFilePath = :filePath, downloadStatus = :status, downloadProgress = :progress WHERE id = :lessonId")
    suspend fun updateDownloadStatus(
        lessonId: String,
        filePath: String?,
        status: DownloadStatus,
        progress: Int
    )

    @Query("SELECT COUNT(*) FROM lessons WHERE courseId = :courseId AND isWatched = 1")
    suspend fun countWatchedLessons(courseId: String): Int

    // Batch & Sequential Download Queries
    @Query("SELECT * FROM lessons WHERE downloadStatus = 'DOWNLOADED'")
    suspend fun getAllDownloadedLessons(): List<Lesson>

    @Query("SELECT * FROM lessons WHERE downloadStatus = 'QUEUED' ORDER BY courseId ASC, videoIndex ASC LIMIT 1")
    suspend fun getNextQueuedLesson(): Lesson?

    @Query("SELECT COUNT(*) FROM lessons WHERE downloadStatus IN ('QUEUED', 'DOWNLOADING')")
    suspend fun countQueuedLessons(): Int

    @Query("SELECT COUNT(*) FROM lessons WHERE courseId = :courseId AND downloadStatus IN ('QUEUED', 'DOWNLOADING')")
    suspend fun countQueuedLessonsForCourse(courseId: String): Int

    @Query("UPDATE lessons SET downloadStatus = :status, downloadProgress = 0 WHERE courseId = :courseId AND downloadStatus != 'DOWNLOADED'")
    suspend fun markCourseNonDownloadedAs(courseId: String, status: DownloadStatus)

    @Query("UPDATE lessons SET downloadStatus = :status, downloadProgress = 0 WHERE id = :lessonId AND downloadStatus != 'DOWNLOADED'")
    suspend fun markLessonDownloadStatus(lessonId: String, status: DownloadStatus)

    @Query("UPDATE lessons SET downloadStatus = 'QUEUED' WHERE downloadStatus = 'DOWNLOADING'")
    suspend fun resetInterruptedDownloads()

    @Query("UPDATE lessons SET downloadStatus = 'NOT_DOWNLOADED', downloadProgress = 0 WHERE downloadStatus IN ('QUEUED', 'DOWNLOADING')")
    suspend fun resetAllQueuedLessons()
}
