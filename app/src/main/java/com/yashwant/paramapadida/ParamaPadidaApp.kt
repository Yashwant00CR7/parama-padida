package com.yashwant.paramapadida

import android.app.Application
import com.yashwant.paramapadida.data.local.AppDatabase
import com.yashwant.paramapadida.engine.YouTubeEngine

class ParamaPadidaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDatabase.getInstance(this)
        YouTubeEngine.init(this)
    }
}
