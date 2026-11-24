package com.rik.vidaraplus

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * YouTube data straight from YouTube, without a server or an API key.
 *
 * It calls the same internal API as the YouTube mobile site (MWEB client): one request, about
 * 0.3-0.6 s, and the response carries the channel avatars together with the videos.
 */
object YtData {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val VERSION = "2.20241126.00.00"
    private const val TTL = 5 * 60_000L

    private val cache = HashMap<String, Pair<Long, List<Item>>>()
    private val avatars = HashMap<String, String>()

    private fun lang() = Locale.getDefault().language

    private fun context(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "MWEB")
            .put("clientVersion", VERSION)
            .put("hl", lang())
            .put("gl", Locale.getDefault().country.ifEmpty { "US" })
    )

    private fun post(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("https://www.youtube.com/youtubei/v1/" + path + "?prettyPrint=false")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", lang() + ",en;q=0.8")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
            val payload = JSONObject(body.toString()).put("context", context())
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                WebTube.log("YT " + path + " HTTP " + code)
                null
            } else runCatching { JSONObject(text) }.getOrNull()
        } catch (e: Exception) {
            WebTube.log("YT " + path + " hiba: " + (e.message ?: ""))
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun cached(key: String, load: () -> List<Item>): List<Item> {
        synchronized(cache) {
            cache[key]?.let { if (System.currentTimeMillis() - it.first < TTL) return it.second }
        }
        val items = load()
        if (items.isNotEmpty()) synchronized(cache) { cache[key] = System.currentTimeMillis() to items }
        return items
    }

    private fun homeQuery(cat: String): String {
        val hu = lang() == "hu"
        return when (cat) {
            "music" -> if (hu) "népszerű zene" else "popular music"
            "games" -> if (hu) "játék végigjátszás" else "gameplay"
            "news" -> if (hu) "hírek" else "news"
            "sport" -> if (hu) "sport összefoglaló" else "sports highlights"
            "cooking" -> if (hu) "főzés receptek" else "cooking recipes"
            "tech" -> if (hu) "technológia" else "technology"
            "movies" -> if (hu) "teljes film magyarul" else "full movie"
            "comedy" -> if (hu) "vicces videók" else "funny videos"
            else -> if (hu) "népszerű videók" else "popular videos"
        }
    }

    fun search(query: String): List<Item> = cached("s:" + query) {
        parseVideos(post("search", JSONObject().put("query", query)))
    }

    fun home(category: String): List<Item> {
        val first = search(homeQuery(category))
        if (first.size >= 22) return first
        // kevés találat: még egy témából hozzávesszük (egyetlen plusz kérés, ~0,5 mp)
        val more = search(if (lang() == "hu") "trend videók" else "trending videos")
        val seen = first.map { it.id }.toMutableSet()
        val out = ArrayList(first)
        for (i in more) if (seen.add(i.id)) out.add(i)
        return out
    }

    fun music(): List<Item> = search(homeQuery("music"))

    fun shorts(): List<Item> = cached("shorts:" + lang()) {
        parseVideos(post("search", JSONObject().put("query", "shorts")))
            .filter { it.duration == 0 || it.duration <= 180 }
    }

    /** Egy csatorna videói: a csatorna nevére keresünk, majd csak az adott csatornáét tartjuk meg. */
    fun channelVideos(id: String, name: String): List<Item> {
        if (id.isEmpty() || name.isEmpty()) return emptyList()
        return cached("cv:" + id) { search(name).filter { it.channelId == id }.take(12) }
    }

    /** A listákból összegyűjtött profilképek (nincs külön kérés). */
    fun avatarOf(channelId: String): String? = synchronized(avatars) { avatars[channelId] }

    // ------------------------------------------------------------------ JSON feldolgozás

    private fun walk(o: Any?, depth: Int = 0, visit: (JSONObject) -> Unit) {
        if (depth > 40) return
        when (o) {
            is JSONObject -> {
                visit(o)
                val keys = o.keys()
                while (keys.hasNext()) walk(o.opt(keys.next()), depth + 1, visit)
            }
            is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i), depth + 1, visit)
        }
    }

    private fun text(n: JSONObject?): String {
        if (n == null) return ""
        val simple = n.optString("simpleText")
        if (simple.isNotEmpty()) return simple
        val runs = n.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
            if (sb.isNotEmpty()) return sb.toString()
        }
        return n.optString("content")
    }

    private fun byline(v: JSONObject): JSONObject? =
        v.optJSONObject("shortBylineText") ?: v.optJSONObject("longBylineText") ?: v.optJSONObject("ownerText")

    private fun browseIdOf(n: JSONObject?): String =
        n?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""

    private fun seconds(t: String): Int {
        if (t.isEmpty()) return 0
        var total = 0
        for (part in t.split(":")) {
            val v = part.filter { it.isDigit() }.toIntOrNull() ?: return 0
            total = total * 60 + v
        }
        return total
    }

    private fun count(s: String): Long {
        if (s.isEmpty()) return 0L
        val m = Regex("([0-9][0-9\\s.,]*)\\s*([A-Za-zÁÉÍÓÖŐÚÜŰáéíóöőúüű]+)?").find(s) ?: return 0L
        val numRaw = m.groupValues[1].trim().replace(" ", "").replace("\u00A0", "")
        val num = if (numRaw.contains(',') && numRaw.length - numRaw.indexOf(',') <= 2) {
            numRaw.replace(".", "").replace(',', '.').toDoubleOrNull()
        } else {
            numRaw.replace(".", "").replace(",", "").toDoubleOrNull()
        } ?: return 0L
        val suffix = m.groupValues.getOrNull(2).orEmpty().uppercase(Locale.ROOT)
        val mult = when {
            suffix.startsWith("MR") -> 1_000_000_000.0
            suffix.startsWith("E") || suffix.startsWith("K") -> 1_000.0
            suffix.startsWith("M") -> 1_000_000.0
            suffix.startsWith("B") -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    /** A sorban lévő csatorna profilképének URL-je (a YouTube a videóval együtt küldi). */
    private fun avatarIn(v: JSONObject): String {
        val link = v.optJSONObject("channelThumbnail")?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?: v.optJSONObject("channelThumbnailWithLinkRenderer") ?: return ""
        val th = link.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return ""
        if (th.length() == 0) return ""
        val url = th.optJSONObject(th.length() - 1)?.optString("url") ?: return ""
        return url
    }

    private fun item(v: JSONObject): Item? {
        var id = v.optString("videoId")
        if (id.isEmpty()) id = v.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""
        if (id.length != 11) return null
        val title = text(v.optJSONObject("headline")).ifEmpty { text(v.optJSONObject("title")) }
        if (title.isEmpty()) return null
        val by = byline(v)
        val runs = by?.optJSONArray("runs")
        val channel = if (runs != null && runs.length() > 0) runs.optJSONObject(0)?.optString("text") ?: "" else ""
        val channelId = if (runs != null && runs.length() > 0) browseIdOf(runs.optJSONObject(0)) else ""
        val duration = seconds(text(v.optJSONObject("lengthText")))
        val views = count(
            text(v.optJSONObject("shortViewCountText")).ifEmpty { text(v.optJSONObject("viewCountText")) }
        )
        val avatar = avatarIn(v)
        if (channelId.isNotEmpty() && avatar.isNotEmpty()) synchronized(avatars) { avatars[channelId] = avatar }
        return Item(
            id = id,
            title = title,
            channel = channel,
            channelId = channelId,
            duration = duration,
            views = views,
            date = "",
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = avatar,
        )
    }

    fun parseVideos(root: JSONObject?): List<Item> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, Item>()
        val types = arrayOf(
            "videoWithContextRenderer", "videoRenderer", "compactVideoRenderer",
            "gridVideoRenderer", "reelItemRenderer", "playlistVideoRenderer"
        )
        walk(root) { o ->
            for (t in types) {
                val v = o.optJSONObject(t) ?: continue
                val it = item(v) ?: continue
                if (!out.containsKey(it.id)) out[it.id] = it
            }
        }
        return out.values.toList()
    }
}
