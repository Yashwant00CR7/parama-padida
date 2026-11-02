package com.yashwant.paramapadida.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object YouTubeEngine {
    private const val TAG = "YouTubeEngine"
    private var isInitialized = false
    private val gson = Gson()

    fun init(context: Context) {
        if (!isInitialized) {
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                isInitialized = true
                Log.d(TAG, "YoutubeDL initialized successfully")
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Failed to initialize YoutubeDL", e)
            }
        }
    }

    suspend fun updateYoutubeDL(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating YoutubeDL", e)
            false
        }
    }

    data class PlaylistMetadata(
        val id: String,
        val title: String,
        val uploader: String,
        val entries: List<PlaylistVideoEntry>
    )

    data class PlaylistVideoEntry(
        val id: String,
        val index: Int,
        val title: String,
        val durationSeconds: Long,
        val url: String,
        val thumbnail: String
    )

    /**
     * Extracts playlist metadata and video entries quickly using yt-dlp flat-playlist json
     */
    suspend fun fetchPlaylist(playlistUrl: String): PlaylistMetadata = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(playlistUrl).apply {
            addOption("--dump-single-json")
            addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--skip-download")
        }

        val response: YoutubeDLResponse = YoutubeDL.getInstance().execute(request)
        val jsonOutput = response.out
        val root = gson.fromJson(jsonOutput, JsonObject::class.java)

        val playlistId = root.get("id")?.asString ?: "playlist_${System.currentTimeMillis()}"
        val playlistTitle = root.get("title")?.asString ?: "YouTube Playlist"
        val uploader = root.get("uploader")?.asString ?: root.get("channel")?.asString ?: ""

        val entriesList = mutableListOf<PlaylistVideoEntry>()
        val entriesArray = root.getAsJsonArray("entries")

        if (entriesArray != null) {
            for (i in 0 until entriesArray.size()) {
                val entryObj = entriesArray[i].asJsonObject
                val vid = entryObj.get("id")?.asString ?: "$i"
                val vTitle = entryObj.get("title")?.asString ?: "Lesson ${i + 1}"
                val duration = entryObj.get("duration")?.asLong ?: 0L
                val url = entryObj.get("url")?.asString ?: "https://www.youtube.com/watch?v=$vid"
                val fullUrl = if (url.startsWith("http")) url else "https://www.youtube.com/watch?v=$vid"
                val thumb = entryObj.get("thumbnail")?.asString
                    ?: "https://img.youtube.com/vi/$vid/hqdefault.jpg"

                entriesList.add(
                    PlaylistVideoEntry(
                        id = vid,
                        index = i + 1,
                        title = vTitle,
                        durationSeconds = duration,
                        url = fullUrl,
                        thumbnail = thumb
                    )
                )
            }
        }

        PlaylistMetadata(
            id = playlistId,
            title = playlistTitle,
            uploader = uploader,
            entries = entriesList
        )
    }

    /**
     * Resolves a direct playable stream URL for ExoPlayer or Kyoto Player streaming
     */
    suspend fun resolveStreamUrl(videoUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-f", "best[ext=mp4]/best")
                addOption("-g") // Print direct stream URL
                addOption("--no-warnings")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val streamUrl = response.out.trim().lines().firstOrNull { it.isNotBlank() }
            streamUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream URL for $videoUrl", e)
            null
        }
    }

    /**
     * Returns the standardized learning directory for a playlist.
     * Uses context.getExternalFilesDir to guarantee 100% POSIX read/write access
     * on all Android versions (Android 10 - 15+) without scoped storage EACCES errors.
     */
    fun getCourseFolder(context: Context, courseTitle: String): File {
        val sanitizedTitle = courseTitle.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val courseDir = File(baseDir, "ParamaPadida/$sanitizedTitle")
        if (!courseDir.exists()) {
            courseDir.mkdirs()
        }
        return courseDir
    }
}
