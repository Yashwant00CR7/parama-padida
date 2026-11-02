package com.yashwant.paramapadida.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey
    val id: String, // Playlist ID or unique hash
    val title: String,
    val channelTitle: String = "",
    val playlistUrl: String,
    val thumbnailUrl: String = "",
    val totalVideos: Int = 0,
    val watchedVideos: Int = 0,
    val category: String = "General",
    val folderPath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0
) {
    val progressPercent: Float
        get() = if (totalVideos > 0) (watchedVideos.toFloat() / totalVideos.toFloat()).coerceIn(0f, 1f) else 0f
}
