package com.rik.vidaraplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from GitHub Releases.
 *
 * 1. reads the tag of the latest release (for example "v1.0") and its .apk asset,
 * 2. compares it with the installed version,
 * 3. if newer: offers it, downloads into the cache and opens the system installer.
 *
 * The APK is signed with the same key as the installed app, so it installs as an update and the
 * user's data stays. "Search for updates" can also be started by hand from the You tab.
 */
object Updater {
    private const val REPO = "rik8181/vidara"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "vidara_glass"
    private const val KEY_LAST = "update_last_check"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L          // automatikusan 6 óránként
    private const val MIN_APK_BYTES = 500_000L                      // ennél kisebb fájl hibás letöltés

    private class Release(val version: String, val apkUrl: String)

    private val pool = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** A telepített verzió (pl. "1.34"). */
    fun currentVersion(act: Activity): String = runCatching {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /**
     * Ellenőrzés. [manual] = a felhasználó kérte (mindig lefut, és mindig ad visszajelzést).
     */
    fun check(act: Activity, manual: Boolean) {
        val prefs = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST, 0L) < CHECK_EVERY_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()
        val current = currentVersion(act)

        pool.execute {
            val res = runCatching { fetchLatest() }
            ui.post {
                if (act.isFinishing) return@post
                val rel = res.getOrNull()
                if (rel == null) {
                    if (manual) toast(act, act.getString(R.string.update_none, current))
                    return@post
                }
                if (!isNewer(rel.version, current)) {
                    if (manual) toast(act, act.getString(R.string.update_none, current))
                    return@post
                }
                AlertDialog.Builder(act)
                    .setTitle(act.getString(R.string.update_title, rel.version))
                    .setMessage(act.getString(R.string.update_msg, current, rel.version))
                    .setPositiveButton(R.string.update_download) { _, _ -> download(act, rel) }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ hálózat

    private fun fetchLatest(): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VidaraGlass")
        }
        val body = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val url = a.optString("browser_download_url", "")
                if (tag.isNotEmpty() && url.isNotEmpty()) return Release(tag, url)
            }
        }
        return null
    }

    private fun downloadTo(url: String, out: File): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VidaraGlass")
        }
        return try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                out.outputStream().use { o -> input.copyTo(o, 64 * 1024) }
            }
            out.length() >= MIN_APK_BYTES
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ letöltés + telepítés

    private fun download(act: Activity, rel: Release) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(act,24), dp(act,18), dp(act,24), dp(act,6))
        }
        box.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(act,6)))
        box.addView(TextView(act).apply {
            text = act.getString(R.string.update_wait)
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(act,10), 0, 0)
        })
        val dlg = AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.update_downloading, rel.version))
            .setView(box)
            .setNegativeButton(R.string.update_later, null)
            .show()

        pool.execute {
            // korábbi, félbemaradt letöltések törlése
            runCatching {
                act.cacheDir.listFiles { f -> f.name.startsWith("Vidara-") && f.name.endsWith(".apk") }
                    ?.forEach { it.delete() }
            }
            val out = File(act.cacheDir, "Vidara-${rel.version}.apk")
            val ok = runCatching { downloadTo(rel.apkUrl, out) }.getOrDefault(false)
            ui.post {
                runCatching { dlg.dismiss() }
                if (act.isFinishing) return@post
                if (!ok) {
                    runCatching { out.delete() }
                    toast(act, act.getString(R.string.update_failed))
                    return@post
                }
                install(act, out)
            }
        }
    }

    private fun install(act: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !act.packageManager.canRequestPackageInstalls()
        ) {
            toast(act, act.getString(R.string.update_allow))
            runCatching {
                act.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                )
            }
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(act, act.packageName + ".fileprovider", apk)
        }.getOrNull()
        if (uri == null) {
            toast(act, act.getString(R.string.update_failed))
            return
        }
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { act.startActivity(i) }
            .onFailure { toast(act, act.getString(R.string.update_allow)) }
    }

    // ------------------------------------------------------------------ segédek

    /** "1.34" > "1.33" összevetés számonként (a tag és a versionName is ilyen alakú). */
    private fun isNewer(remote: String, current: String): Boolean {
        val a = remote.split('.', '-', '+').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val b = current.split('.', '-', '+').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun dp(act: Activity, v: Int) = (act.resources.displayMetrics.density * v).toInt()

    private fun toast(act: Activity, msg: String) {
        runCatching { Toast.makeText(act, msg, Toast.LENGTH_LONG).show() }
    }
}
