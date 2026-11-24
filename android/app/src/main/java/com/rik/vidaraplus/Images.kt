package com.rik.vidaraplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/// Simple image loader with an in-memory cache (no external dependency).
object Images {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(8)
    private val main = Handler(Looper.getMainLooper())

    /** Benne van-e már a gyorsítótárban (azonnali megjelenítéshez). */
    fun cached(url: String?): Boolean = !url.isNullOrEmpty() && cache.get(url) != null

    /** Kép előtöltése a gyorsítótárba (nézet nélkül). */
    fun preload(url: String?) {
        if (url.isNullOrEmpty()) return
        if (cache.get(url) != null) return
        val busy = running.putIfAbsent(url, true)
        if (busy != null) return
        pool.execute {
            try {
                if (cache.get(url) == null) {
                    val bmp = try { URL(url).openStream().use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
                    if (bmp != null) cache.put(url, bmp)
                }
            } finally { running.remove(url) }
        }
    }

    private val running = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun load(view: ImageView, url: String?, fallback: String? = null) {
        view.tag = url
        if (url.isNullOrEmpty()) { view.setImageDrawable(null); return }
        val hit = cache.get(url)
        if (hit != null) { view.setImageBitmap(hit); return }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = decode(url) ?: (if (!fallback.isNullOrEmpty()) decode(fallback) else null)
            if (bmp != null) {
                cache.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }

    private fun decode(url: String): android.graphics.Bitmap? = try {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }
}

/// Channel avatars and subscriber counts: fetched once per channel, then served from the cache.
object Channels {
    private val cache = HashMap<String, ChannelInfo?>()
    private val failedAt = HashMap<String, Long>()
    private val waiting = HashMap<String, MutableList<(ChannelInfo?) -> Unit>>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun get(api: Api, id: String, callback: (ChannelInfo?) -> Unit) {
        if (id.isEmpty()) { callback(null); return }
        if (cache.containsKey(id)) { callback(cache[id]); return }
        // Ha nemrég nem sikerült (pl. nem érhető el a szerver), egy percig ne próbáljuk újra:
        // így nem ragad be véglegesen a kezdőbetű, de nem is indítunk kérést minden sornál.
        val f = failedAt[id]
        if (f != null && System.currentTimeMillis() - f < 60_000) { callback(null); return }
        val list = waiting[id]
        if (list != null) { list.add(callback); return }
        waiting[id] = mutableListOf(callback)
        pool.execute {
            val info = try { api.channel(id) } catch (e: Exception) { null }
            main.post {
                if (info?.avatar != null) { cache[id] = info; failedAt.remove(id) }
                else failedAt[id] = System.currentTimeMillis()
                waiting.remove(id)?.forEach { it(info) }
            }
        }
    }
}
