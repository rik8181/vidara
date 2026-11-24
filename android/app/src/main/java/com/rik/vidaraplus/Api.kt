package com.rik.vidaraplus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Item(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val duration: Int,
    val views: Long,
    val date: String,
    val thumbnail: String,
    /** A csatorna profilképe, ha a YouTube a listával együtt elküldte. */
    val avatar: String = "",
)

data class Stream(
    val kind: String,
    val height: Int,
    val ext: String,
    val vcodec: String?,
    val bitrate: Double,
    val url: String,
)

data class VideoDetail(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val thumbnail: String,
    val live: Boolean,
    val views: Long,
    val likes: Long,
    val date: String,
    val description: String,
    val commentCount: Long,
    val streams: List<Stream>,
)

data class Comment(val author: String, val text: String, val likes: Long, val avatar: String, val time: String)

data class ChannelInfo(val avatar: String?, val subs: Long, val name: String)

/// Server settings (address + key) and the calls. Every call must run on a background thread.
class Api(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = context.getSharedPreferences("vidara", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(value) {
            var v = value.trim().trimEnd('/')
            if (v.isNotEmpty() && !v.startsWith("http")) v = "http://$v"
            prefs.edit().putString("server", v).apply()
        }

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(value) = prefs.edit().putString("key", value.trim()).apply()

    val configured get() = server.isNotEmpty()

    /// A kért legnagyobb felbontás (magasság képpontban); alapértelmezés 1080.
    var quality: Int
        get() = prefs.getInt("quality", 1080)
        set(value) = prefs.edit().putInt("quality", value).apply()

    private fun get(path: String): String {
        val conn = URL(server + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 90000
        if (key.isNotEmpty()) conn.setRequestProperty("X-Vidara-Key", key)
        conn.setRequestProperty("X-Vidara-Lang", java.util.Locale.getDefault().language)
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try { JSONObject(text).optString("detail") } catch (e: Exception) { text }
                throw RuntimeException(if (code == 401) ctx.getString(R.string.err_key) else ctx.getString(R.string.err_server, code, detail.take(200)))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun str(o: JSONObject, name: String): String =
        if (o.isNull(name)) "" else o.optString(name, "")

    private fun lng(o: JSONObject, name: String): Long =
        if (o.isNull(name)) 0L else o.optLong(name, 0L)

    private fun parseItems(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = str(o, "id")
            if (id.isEmpty()) continue
            out.add(
                Item(
                    id = id,
                    title = str(o, "title"),
                    channel = str(o, "channel"),
                    channelId = str(o, "channel_id"),
                    duration = if (o.isNull("duration")) 0 else o.optInt("duration", 0),
                    views = lng(o, "views"),
                    date = str(o, "date"),
                    thumbnail = str(o, "thumbnail"),
                    avatar = str(o, "avatar"),
                )
            )
        }
        return out
    }

    fun search(query: String, music: Boolean): List<Item> {
        // Szerver nélkül: a keresés közvetlenül a YouTube-tól jön (beépített WebView + youtubei).
        if (!configured) return YtData.search(query)
        val q = URLEncoder.encode(query, "UTF-8")
        val path = if (music) "/api/music/search?q=$q" else "/api/search?q=$q"
        return parseItems(JSONObject(get(path)).optJSONArray("items"))
    }

    /// A listák utolsó válaszát a telefon is megjegyzi: induláskor azonnal látszik, a frissítés utána jön.
    private fun fetchStore(path: String): String {
        val text = get(path)
        prefs.edit().putString("cache:" + java.util.Locale.getDefault().language + path, text).apply()
        return text
    }

    private fun stored(path: String): String? =
        prefs.getString("cache:" + java.util.Locale.getDefault().language + path, null)

    private fun homePath(category: String) = "/api/home?cat=" + URLEncoder.encode(category, "UTF-8")

    fun home(category: String): List<Item> {
        if (!configured) {
            val items = YtData.home(category)
            saveItems(homePath(category), items)
            return items
        }
        return parseItems(JSONObject(fetchStore(homePath(category))).optJSONArray("items"))
    }

    /// A helyi (YouTube-s) listát ugyanabba a gyorsítótárba mentjük, így induláskor azonnal látszik.
    private fun saveItems(path: String, items: List<Item>) {
        val arr = JSONArray()
        for (i in items) arr.put(itemJson(i))
        val text = JSONObject().put("items", arr).toString()
        prefs.edit().putString("cache:" + java.util.Locale.getDefault().language + path, text).apply()
    }

    private fun itemJson(i: Item): JSONObject = JSONObject()
        .put("id", i.id)
        .put("title", i.title)
        .put("channel", i.channel)
        .put("channel_id", i.channelId)
        .put("duration", if (i.duration > 0) i.duration else JSONObject.NULL)
        .put("views", if (i.views > 0) i.views else JSONObject.NULL)
        .put("date", i.date)
        .put("thumbnail", i.thumbnail)
        .put("avatar", i.avatar)

    fun homeCached(category: String): List<Item> =
        stored(homePath(category))?.let { runCatching { parseItems(JSONObject(it).optJSONArray("items")) }.getOrNull() }
            ?: emptyList()

    fun shortsCached(): List<Item> =
        stored("/api/shorts")?.let { runCatching { parseItems(JSONObject(it).optJSONArray("items")) }.getOrNull() }
            ?: emptyList()

    fun musicHomeCached(): List<Item> =
        stored("/api/music/home")?.let { runCatching { parseSections(it) }.getOrNull() } ?: emptyList()

    fun musicHome(): List<Item> {
        if (!configured) {
            val items = YtData.music()
            val arr = JSONArray()
            for (i in items) arr.put(itemJson(i))
            val text = JSONObject().put("sections", JSONArray().put(JSONObject().put("items", arr))).toString()
            prefs.edit().putString("cache:" + java.util.Locale.getDefault().language + "/api/music/home", text).apply()
            return items
        }
        return parseSections(fetchStore("/api/music/home"))
    }

    private fun parseSections(text: String): List<Item> {
        val sections = JSONObject(text).optJSONArray("sections") ?: return emptyList()
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (s in 0 until sections.length()) {
            for (item in parseItems(sections.getJSONObject(s).optJSONArray("items"))) {
                if (seen.add(item.id)) out.add(item)
            }
        }
        return out
    }

    fun shorts(): List<Item> {
        if (!configured) {
            val items = YtData.shorts()
            saveItems("/api/shorts", items)
            return items
        }
        return parseItems(JSONObject(fetchStore("/api/shorts")).optJSONArray("items"))
    }

    fun related(id: String): List<Item> {
        if (!configured) return emptyList()
        return parseItems(JSONObject(get("/api/related/$id")).optJSONArray("items"))
    }

    fun comments(id: String): Pair<Long, List<Comment>> {
        val o = JSONObject(get("/api/comments/$id"))
        val arr = o.optJSONArray("items")
        val out = ArrayList<Comment>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                out.add(Comment(str(c, "author"), str(c, "text"), lng(c, "likes"), str(c, "avatar"), str(c, "time")))
            }
        }
        return Pair(lng(o, "count"), out)
    }

    fun channel(id: String): ChannelInfo {
        // szerver nélkül a lista már hozza a csatorna képét: nincs szükség külön kérésre
        if (!configured) return ChannelInfo(YtData.avatarOf(id), 0L, "")
        val o = JSONObject(get("/api/channel/$id"))
        return ChannelInfo(
            avatar = if (o.isNull("avatar")) null else o.optString("avatar"),
            subs = lng(o, "subs"),
            name = str(o, "name"),
        )
    }

    fun video(id: String): VideoDetail {
        val o = JSONObject(get("/api/video/$id"))
        val arr = o.optJSONArray("streams")
        val streams = ArrayList<Stream>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                streams.add(
                    Stream(
                        kind = s.optString("kind"),
                        height = if (s.isNull("height")) 0 else s.optInt("height", 0),
                        ext = s.optString("ext"),
                        vcodec = if (s.isNull("vcodec")) null else s.optString("vcodec"),
                        bitrate = if (s.isNull("bitrate")) 0.0 else s.optDouble("bitrate", 0.0),
                        url = s.optString("url"),
                    )
                )
            }
        }
        return VideoDetail(
            id = id,
            title = str(o, "title"),
            channel = str(o, "channel"),
            channelId = str(o, "channel_id"),
            thumbnail = str(o, "thumbnail"),
            live = o.optBoolean("live", false),
            views = lng(o, "views"),
            likes = lng(o, "likes"),
            date = str(o, "date"),
            description = str(o, "description"),
            commentCount = lng(o, "comment_count"),
            streams = streams,
        )
    }

    fun diag(): String {
        val o = JSONObject(get("/api/diag"))
        return if (o.optBoolean("ok")) ctx.getString(R.string.diag_ok, o.optInt("formatumok"))
        else ctx.getString(R.string.diag_fail, o.optString("hiba").take(200))
    }

    /// Az utoljára nézett videó (a kezdőlap tetején jelenik meg).
    fun saveLast(item: Item, music: Boolean, posMs: Long) {
        val o = JSONObject()
            .put("id", item.id).put("title", item.title).put("channel", item.channel)
            .put("channel_id", item.channelId).put("duration", item.duration).put("views", item.views)
            .put("date", item.date).put("thumbnail", item.thumbnail).put("music", music).put("pos", posMs)
        prefs.edit().putString("last", o.toString()).apply()
    }

    fun last(): Triple<Item, Boolean, Long>? {
        val text = prefs.getString("last", null) ?: return null
        return try {
            val o = JSONObject(text)
            val item = Item(
                id = o.getString("id"), title = o.optString("title"), channel = o.optString("channel"),
                channelId = o.optString("channel_id"), duration = o.optInt("duration"), views = o.optLong("views"),
                date = o.optString("date"), thumbnail = o.optString("thumbnail"),
            )
            Triple(item, o.optBoolean("music"), o.optLong("pos"))
        } catch (e: Exception) { null }
    }

    // ---- követett csatornák és megnézett videók (az app megjegyzi, mit nézel és kitől)
    fun noteChannel(id: String, name: String) {
        if (id.isEmpty()) return
        val arr = try { JSONArray(prefs.getString("chan", "[]")) } catch (e: Exception) { JSONArray() }
        var n = 1
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) n = o.optInt("n") + 1 else out.put(o)
        }
        val list = ArrayList<JSONObject>()
        list.add(JSONObject().put("id", id).put("name", name).put("n", n))   // a legutóbbi elöl
        for (i in 0 until out.length()) list.add(out.getJSONObject(i))
        val trimmed = JSONArray()
        list.take(12).forEach { trimmed.put(it) }
        prefs.edit().putString("chan", trimmed.toString()).apply()
    }

    /// A leggyakrabban / legutóbb nézett csatornák (legfeljebb `limit`).
    fun topChannels(limit: Int): List<Pair<String, String>> {
        val arr = try { JSONArray(prefs.getString("chan", "[]")) } catch (e: Exception) { return emptyList() }
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        // a legutóbbi kettő mindig, a többi a nézettség szerint
        val recent = all.take(2)
        val rest = all.drop(2).sortedByDescending { it.optInt("n") }
        return (recent + rest).take(limit).map { Pair(it.getString("id"), it.optString("name")) }
    }

    fun markSeen(id: String) {
        val arr = try { JSONArray(prefs.getString("seen", "[]")) } catch (e: Exception) { JSONArray() }
        val list = ArrayList<String>()
        list.add(id)
        for (i in 0 until arr.length()) if (arr.getString(i) != id) list.add(arr.getString(i))
        val out = JSONArray()
        list.take(300).forEach { out.put(it) }
        prefs.edit().putString("seen", out.toString()).apply()
    }

    fun seenIds(): Set<String> {
        val arr = try { JSONArray(prefs.getString("seen", "[]")) } catch (e: Exception) { return emptySet() }
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    private fun channelPath(id: String) = "/api/channel_videos/$id"

    private fun withChannel(items: List<Item>, id: String, name: String) =
        items.map { if (it.channel.isEmpty()) it.copy(channel = name, channelId = id) else it }

    fun channelVideos(id: String, name: String): List<Item> {
        if (!configured) return YtData.channelVideos(id, name)
        return withChannel(parseItems(JSONObject(fetchStore(channelPath(id))).optJSONArray("items")), id, name)
    }

    fun channelVideosCached(id: String, name: String): List<Item> =
        stored(channelPath(id))?.let {
            runCatching { withChannel(parseItems(JSONObject(it).optJSONArray("items")), id, name) }.getOrNull()
        } ?: emptyList()

    fun prefetch(id: String) {
        if (!configured) return   // a lejátszás a webes lejátszóban megy, nincs mit előkészíteni
        get("/api/prefetch/$id")
    }
}
