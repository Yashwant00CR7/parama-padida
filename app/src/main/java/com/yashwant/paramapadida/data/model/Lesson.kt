package com.yashwant.paramapadida.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lessons",
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["courseId"]), Index(value = ["courseId", "videoIndex"])]
)
data class Lesson(
    @PrimaryKey
    val id: String, // Video ID or composite
    val courseId: String,
    val videoIndex: Int, // 1-based index (e.g. 1, 2, 3...)
    val title: String,
    val durationSeconds: Long = 0,
    val thumbnailUrl: String = "",
    val webUrl: String = "",
    val directStreamUrl: String? = null,
    val localFilePath: String? = null,
    val isWatched: Boolean = false,
    val watchProgressMs: Long = 0,
    val lastWatchedAt: Long = 0,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadProgress: Int = 0
) {
    val isDownloaded: Boolean
        get() = downloadStatus == DownloadStatus.DOWNLOADED && !localFilePath.isNullOrBlank()

    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return "--:--"
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return if (minutes >= 60) {
                val hours = minutes / 60
                val remMinutes = minutes % 60
                String.format("%d:%02d:%02d", hours, remMinutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }
}

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}
