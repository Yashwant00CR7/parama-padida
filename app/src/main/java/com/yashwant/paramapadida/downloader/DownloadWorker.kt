package com.yashwant.paramapadida.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yashwant.paramapadida.R
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.data.model.DownloadStatus
import com.yashwant.paramapadida.engine.YouTubeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "DownloadWorker"
        const val CHANNEL_ID = "parama_download_channel"
        const val KEY_LESSON_ID = "key_lesson_id"
        const val KEY_COURSE_ID = "key_course_id"
        const val KEY_VIDEO_URL = "key_video_url"
        const val KEY_VIDEO_INDEX = "key_video_index"
        const val KEY_VIDEO_TITLE = "key_video_title"
        const val KEY_COURSE_TITLE = "key_course_title"
        const val KEY_QUALITY = "key_quality" // e.g. "720", "1080", "best"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lessonId = inputData.getString(KEY_LESSON_ID) ?: return@withContext Result.failure()
        val courseId = inputData.getString(KEY_COURSE_ID) ?: return@withContext Result.failure()
        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: return@withContext Result.failure()
        val videoIndex = inputData.getInt(KEY_VIDEO_INDEX, 1)
        val videoTitle = inputData.getString(KEY_VIDEO_TITLE) ?: "Lesson $videoIndex"
        val courseTitle = inputData.getString(KEY_COURSE_TITLE) ?: "Course"
        val quality = inputData.getString(KEY_QUALITY) ?: "best"

        val database = AppDatabase.getInstance(context)
        val lessonDao = database.lessonDao()

        createNotificationChannel()

        val notificationId = lessonId.hashCode()
        val initialNotification = createNotification(videoTitle, 0, "Starting download...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(notificationId, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(notificationId, initialNotification))
        }

        lessonDao.updateDownloadStatus(lessonId, null, DownloadStatus.DOWNLOADING, 0)

        val courseFolder = YouTubeEngine.getCourseFolder(context, courseTitle)
        val sanitizedTitle = videoTitle.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        val filename = String.format("%02d - %s.mp4", videoIndex, sanitizedTitle)
        val outputFile = File(courseFolder, filename)

        val formatOption = when (quality) {
            "720" -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best"
            "1080" -> "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best"
            "audio" -> "bestaudio[ext=m4a]/bestaudio"
            else -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
        }

        return@withContext try {
            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-o", outputFile.absolutePath)
                addOption("-f", formatOption)
                addOption("--no-mtime")
                addOption("--no-warnings")
            }

            var lastProgress = 0
            YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
                val intProgress = progress.toInt().coerceIn(0, 100)
                if (intProgress != lastProgress) {
                    lastProgress = intProgress
                    notificationManager.notify(
                        notificationId,
                        createNotification(videoTitle, intProgress, "Downloading: $intProgress% (ETA: ${etaInSeconds}s)")
                    )
                    setProgressAsync(workDataOf("progress" to intProgress))
                }
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                lessonDao.updateDownloadStatus(
                    lessonId,
                    outputFile.absolutePath,
                    DownloadStatus.DOWNLOADED,
                    100
                )
                notificationManager.notify(
                    notificationId,
                    createNotification(videoTitle, 100, "Download completed!")
                )
                Result.success()
            } else {
                lessonDao.updateDownloadStatus(lessonId, null, DownloadStatus.FAILED, 0)
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $videoUrl", e)
            lessonDao.updateDownloadStatus(lessonId, null, DownloadStatus.FAILED, 0)
            notificationManager.notify(
                notificationId,
                createNotification(videoTitle, 0, "Download failed: ${e.localizedMessage}")
            )
            Result.failure()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of downloading course videos"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, progress: Int, contentText: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .build()
}
