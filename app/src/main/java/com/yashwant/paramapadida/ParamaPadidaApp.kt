package com.yashwant.paramapadida

import android.app.Application
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.engine.YouTubeEngine
import com.yashwant.paramapadida.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ParamaPadidaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppDatabase.getInstance(this)
        YouTubeEngine.init(this)

        // Asynchronously check and update yt-dlp extractors in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YouTubeEngine.updateYoutubeDL(this@ParamaPadidaApp)
                AppLogger.i("ParamaPadidaApp", "yt-dlp extractors up to date")
            } catch (e: Exception) {
                AppLogger.w("ParamaPadidaApp", "yt-dlp background update check: ${e.message}")
            }
        }
    }
}
