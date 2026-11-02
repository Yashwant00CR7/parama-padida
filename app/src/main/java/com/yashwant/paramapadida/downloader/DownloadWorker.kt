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
        val indexPrefix = String.format("%02d - ", videoIndex)
        val outputTemplate = "${courseFolder.absolutePath}/$indexPrefix$sanitizedTitle.%(ext)s"

        // YouTube streams: 'b' selects the best pre-muxed audio+video stream (e.g. 720p or 1080p HLS)
        val formatOption = when (quality) {
            "720" -> "b[height<=720]/best[height<=720]/b/best"
            "1080" -> "b[height<=1080]/best[height<=1080]/b/best"
            "audio" -> "ba/bestaudio"
            else -> "b/best"
        }

        return@withContext try {
            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-o", outputTemplate)
                addOption("-f", formatOption)
                addOption("--no-mtime")
                addOption("--no-part")
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

            // Find downloaded file matching the index prefix in the folder
            val downloadedFile = courseFolder.listFiles()?.firstOrNull { file ->
                file.name.startsWith(indexPrefix) && file.length() > 0 && !file.name.endsWith(".part") && !file.name.endsWith(".ytdl")
            }

            if (downloadedFile != null && downloadedFile.exists()) {
                lessonDao.updateDownloadStatus(
                    lessonId,
                    downloadedFile.absolutePath,
                    DownloadStatus.DOWNLOADED,
                    100
                )
                notificationManager.notify(
                    notificationId,
                    createNotification(videoTitle, 100, "Download completed!")
                )
                Result.success()
            } else {
                Log.e(TAG, "File not found after download for index $videoIndex in ${courseFolder.absolutePath}")
                lessonDao.updateDownloadStatus(lessonId, null, DownloadStatus.FAILED, 0)
                Result.failure()
            }
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: "Unknown error"
            Log.e(TAG, "Download failed for $videoUrl: $errorMsg", e)
            lessonDao.updateDownloadStatus(lessonId, null, DownloadStatus.FAILED, 0)
            notificationManager.notify(
                notificationId,
                createNotification(videoTitle, 0, "Download failed: $errorMsg")
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
