package com.rik.vidaraplus

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/// Playback keeps running in the background and on the lock screen, with a notification and lock-screen controls.
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, App.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
