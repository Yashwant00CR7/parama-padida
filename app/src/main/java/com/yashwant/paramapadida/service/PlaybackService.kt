package com.yashwant.paramapadida.service

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background Media3 playback service.
 * Keeps video/audio playing seamlessly when screen is locked or app is in background.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        var activeSession: MediaSession? = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return activeSession
    }

    override fun onDestroy() {
        activeSession = null
        super.onDestroy()
    }
}
