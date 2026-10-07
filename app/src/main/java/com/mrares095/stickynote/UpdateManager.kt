package com.mrares095.stickynote

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object UpdateManager {
    private const val RELEASES_URL = "https://api.github.com/repos/MrAres095/Sticky-note/releases/latest"

    fun check(context: Context, onResult: (String, String?) -> Unit) {
        thread {
            try {
                val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val release = JSONObject(json)
                val tag = release.optString("tag_name").removePrefix("v")
                val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
                if (!isNewer(tag, current)) {
                    onResult("Aplikacija je već ažurirana (" + current + ").", null)
                    return@thread
                }
                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl == null) onResult("Nova verzija postoji (" + tag + "), ali APK nije pronađen.", null)
                else onResult("Dostupna je nova verzija " + tag + ".", apkUrl)
            } catch (e: Exception) {
                onResult("Provjera ažuriranja nije uspjela: " + (e.message ?: "greška"), null)
            }
        }
    }

    fun downloadAndInstall(context: Context, apkUrl: String, onStatus: (String) -> Unit) {
        thread {
            try {
                onStatus("Preuzimam novu verziju…")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                val file = File(context.cacheDir, "sticky-note-update.apk")
                if (file.exists()) file.delete()
                conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                conn.disconnect()
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Handler(Looper.getMainLooper()).post {
                    context.startActivity(intent)
                    onStatus("APK je spreman za instalaciju.")
                }
            } catch (e: Exception) {
                onStatus("Preuzimanje ažuriranja nije uspjelo: " + (e.message ?: "greška"))
            }
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
