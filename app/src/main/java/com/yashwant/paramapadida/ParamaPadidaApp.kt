package com.yashwant.paramapadida

import android.app.Application
import android.util.Log
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.engine.YouTubeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ParamaPadidaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDatabase.getInstance(this)
        YouTubeEngine.init(this)

        // Asynchronously check and update yt-dlp extractors in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YouTubeEngine.updateYoutubeDL(this@ParamaPadidaApp)
            } catch (e: Exception) {
                Log.w("ParamaPadidaApp", "yt-dlp background update check: ${e.message}")
            }
        }
    }
}
