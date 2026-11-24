package com.rik.vidaraplus

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/// A single player for the whole app: the activity and the background service (lock screen,
/// notification) share it, so playback does not stop when you leave the app.
class App : Application() {
    companion object {
        lateinit var player: ExoPlayer
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Fmt.res = resources
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setLoadControl(
                // gyors indulás és gyors folytatás (alapérték: 2,5 mp / 5 mp)
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 50000, 800, 1500)
                    .build()
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}
