package com.pixelpad.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Looks for a newer PixelPad release on GitHub and installs it (Android asks you to confirm the install). */
object Updater {
    private const val API = "https://api.github.com/repos/nyx-ulrix/pixelpad/releases/latest"

    class Release(val version: String, val apkUrl: String)

    /** Is version a newer than b? Compared number by number, so 1.1.10 is newer than 1.1.9. */
    fun newer(a: String, b: String): Boolean {
        val x = nums(a); val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun nums(v: String) = v.removePrefix("v").split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    /** The latest release, or null if it can't be read (offline, rate limited). Call from a background thread. */
    fun latest(): Release? = try {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "PixelPad")
        }
        if (c.responseCode != 200) null else {
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) assets.getJSONObject(i).let { if (it.getString("name").endsWith(".apk")) apk = it.getString("browser_download_url") }
            apk?.let { Release(j.getString("tag_name").removePrefix("v"), it) }
        }
    } catch (e: Exception) { null }

    /** Downloads the APK and hands it to Android's installer, which asks you to confirm. onFail gets a short message. */
    fun install(a: Activity, r: Release, onFail: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {   // first time: Android wants you to allow installs from this app
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            onFail("ALLOW INSTALLING FROM THIS APP, THEN OPEN THE APP AGAIN TO UPDATE"); return
        }
        Toast.makeText(a, "DOWNLOADING THE UPDATE...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val pi = a.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 20000; conn.setRequestProperty("User-Agent", "PixelPad")
                    s.openWrite("pixelpad.apk", 0, -1).use { out -> conn.inputStream.use { it.copyTo(out) }; s.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(a, id, Intent(a, UpdateReceiver::class.java), flags).intentSender)
                }
            } catch (e: Exception) { a.runOnUiThread { onFail("COULDN'T DOWNLOAD THE UPDATE") } }
        }.apply { isDaemon = true }.start()
    }
}

/** Receives the installer's progress: when it needs you to confirm, it opens that screen. */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, "THE UPDATE DID NOT INSTALL", Toast.LENGTH_LONG).show()
        }
    }
}
