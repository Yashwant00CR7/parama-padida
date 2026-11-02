package com.yashwant.paramapadida.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = Lesson::class,
            parentColumns = ["id"],
            childColumns = ["lessonId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["lessonId"])]
)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val lessonId: String,
    val courseId: String,
    val timestampMs: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedTimestamp: String
        get() {
            val totalSeconds = timestampMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes >= 60) {
                val hours = minutes / 60
                val remMinutes = minutes % 60
                String.format("%02d:%02d:%02d", hours, remMinutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
}
