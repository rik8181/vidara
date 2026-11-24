package com.rik.vidaraplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lock-screen / notification media controls for the web player (Vidara and VidaraMusic):
 * artwork, title, artist, previous / play-pause / next and a seek bar.
 * The page reports its state once a second (WebLayer.Bridge.media) and the buttons act inside the
 * page (WebLayer.mediaCmd).
 */
object MediaCtl {
    const val CH = "webtube"
    const val NID = 77

    var layer: WeakReference<WebLayer>? = null
    @Volatile var serviceOn = false

    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())

    private var title = ""
    private var artist = ""
    private var vid = ""
    private var dur = 0L
    private var pos = 0L
    private var playing = false
    private var art: Bitmap? = null
    private var artId = ""

    fun cmd(c: String, arg: Long = 0L) {
        main.post { layer?.get()?.mediaCmd(c, arg) }
    }

    private fun ensure(ctx: Context): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx.applicationContext, "vidara-web")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { cmd("play") }
            override fun onPause() { cmd("pause") }
            override fun onSkipToNext() { cmd("next") }
            override fun onSkipToPrevious() { cmd("prev") }
            override fun onSeekTo(p: Long) { cmd("seek", p) }
            override fun onStop() { cmd("pause") }
        })
        s.setSessionActivity(openApp(ctx))
        s.isActive = true
        session = s
        return s
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** A lap jelentése: mi szól, mennyi az idő, megy-e. */
    fun update(ctx: Context, t: String, a: String, id: String, d: Long, p: Long, pl: Boolean) {
        if (t.isEmpty() && id.isEmpty()) { playing = false; pushState(ctx); return }
        val changed = t != title || a != artist || id != vid || d != dur
        title = t; artist = a; vid = id; dur = d; pos = p; playing = pl
        val s = ensure(ctx)
        if (changed) pushMeta(s)
        pushState(ctx)
        if (id.isNotEmpty() && id != artId) loadArt(ctx, id)
    }

    private fun pushMeta(s: MediaSession) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifEmpty { "Vidara" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title.ifEmpty { "Vidara" })
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
        if (dur > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
        art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it); b.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        s.setMetadata(b.build())
    }

    private fun pushState(ctx: Context) {
        val s = session ?: return
        val st = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
            )
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build()
        s.setPlaybackState(st)
        notifyNow(ctx)
    }

    /** Borítókép a háttérben (16:9-es kivágás, a YouTube képek fekete csíkjai nélkül). */
    private fun loadArt(ctx: Context, id: String) {
        artId = id
        val app = ctx.applicationContext
        Thread {
            val bmp = runCatching {
                val c = URL("https://i.ytimg.com/vi/$id/hqdefault.jpg").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { b ->
                val h = b.width * 9 / 16
                if (b.height > h) Bitmap.createBitmap(b, 0, (b.height - h) / 2, b.width, h) else b
            }
            main.post {
                if (artId != id || bmp == null) return@post
                art = bmp
                session?.let { pushMeta(it) }
                notifyNow(app)
            }
        }.start()
    }

    private fun action(ctx: Context, icon: Int, label: String, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            ctx, req, Intent(ctx, KeepAliveService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, pi).build()
    }

    fun buildNotification(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        })
        val s = ensure(ctx)
        val b = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifEmpty { "Vidara" })
            .setContentText(artist.ifEmpty { ctx.getString(R.string.media_playing) })
            .setContentIntent(openApp(ctx))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(ctx, android.R.drawable.ic_media_previous, ctx.getString(R.string.media_prev), "vd.prev", 1))
            .addAction(
                if (playing) action(ctx, android.R.drawable.ic_media_pause, ctx.getString(R.string.media_pause), "vd.pause", 2)
                else action(ctx, android.R.drawable.ic_media_play, ctx.getString(R.string.media_play), "vd.play", 3)
            )
            .addAction(action(ctx, android.R.drawable.ic_media_next, ctx.getString(R.string.media_next), "vd.next", 4))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
        art?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun notifyNow(ctx: Context) {
        if (!serviceOn) return
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NID, buildNotification(ctx)) }
    }

    /** Lejátszás vége / bezárás: a vezérlő eltűnik a lezárt képernyőről. */
    fun stop() {
        serviceOn = false
        runCatching { session?.isActive = false; session?.release() }
        session = null
        title = ""; artist = ""; vid = ""; dur = 0; pos = 0; playing = false; art = null; artId = ""
    }
}
