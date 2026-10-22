package com.nuvio.tv.relay.standalone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update from this project's GitHub Releases.
 * Only APKs from this repository's release downloads are accepted, and Android itself refuses to
 * install an update that is not signed with the same key as the installed app.
 */
object AppUpdater {
    private const val REPO = "mohammadmrh12/nuvio-tv-relay-standalone"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val RETRY_AFTER_FAILURE_MS = 30L * 60 * 1000
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val ACTION_INSTALL_RESULT = "com.nuvio.tv.relay.standalone.UPDATE_RESULT"

    private val busy = AtomicBoolean(false)
    @Volatile var pendingConfirmation: Intent? = null
    @Volatile var uiForeground: Boolean = false

    data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty().ifBlank { "0" }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode.toLong() }.getOrDefault(0L)

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('-', '+')[0].split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Release saved by the last successful check, if it is newer than the installed version. */
    fun latestAvailable(ctx: Context): Release? {
        val p = prefs(ctx)
        val version = p.getString("update_latest_version", "").orEmpty()
        val url = p.getString("update_latest_url", "").orEmpty()
        if (version.isBlank() || url.isBlank() || !isNewer(version, currentVersion(ctx))) return null
        return Release(version, url, p.getLong("update_latest_size", 0L), p.getString("update_latest_sha", null), p.getString("update_latest_notes", "").orEmpty())
    }

    fun latestKnownVersion(ctx: Context): String = prefs(ctx).getString("update_latest_version", "").orEmpty()

    /** Blocking network call: asks GitHub for the latest release and stores the result. */
    fun checkNow(ctx: Context): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "NuvioTVRelay-Updater")
        val body = try {
            if (conn.responseCode != 200) throw IOException("GitHub returned HTTP " + conn.responseCode)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val json = JSONObject(body)
        val version = json.optString("tag_name").trim().removePrefix("v")
        if (version.isBlank()) throw IOException("Release has no tag")
        var apkUrl = ""; var size = 0L; var sha: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (!a.optString("name").endsWith(".apk")) continue
            apkUrl = a.optString("browser_download_url"); size = a.optLong("size", 0L)
            sha = a.optString("digest", "").removePrefix("sha256:").takeIf { it.length == 64 }
            break
        }
        val ok = apkUrl.startsWith(DOWNLOAD_PREFIX)
        prefs(ctx).edit()
            .putString("update_latest_version", version)
            .putString("update_latest_url", if (ok) apkUrl else "")
            .putLong("update_latest_size", size)
            .putString("update_latest_sha", sha)
            .putString("update_latest_notes", json.optString("body").trim())
            .putLong("update_last_check_ms", System.currentTimeMillis())
            .apply()
        return latestAvailable(ctx)
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system page where "Install unknown apps" can be allowed for Relay. Returns false if the TV has no such page. */
    fun openInstallPermissionSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    /** Downloads the release APK, verifies it and hands it to the system installer. Blocking. */
    fun downloadAndInstall(ctx: Context, release: Release, progress: (Int) -> Unit = {}) {
        if (!busy.compareAndSet(false, true)) throw IllegalStateException("An update is already in progress")
        try {
            val apk = download(ctx, release, progress)
            install(ctx, apk)
        } finally { busy.set(false) }
    }

    private fun download(ctx: Context, release: Release, progress: (Int) -> Unit): File {
        require(release.apkUrl.startsWith(DOWNLOAD_PREFIX)) { "Unexpected download address" }
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "relay-" + release.version + ".apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "NuvioTVRelay-Updater")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download failed: HTTP " + conn.responseCode)
            val host = conn.url.host.lowercase()
            require(host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")) { "Unexpected download host" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var lastPct = -1
            conn.inputStream.use { input -> out.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    done += n
                    if (done > MAX_APK_BYTES) throw IOException("Update is too large")
                    digest.update(buffer, 0, n); sink.write(buffer, 0, n)
                    if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != lastPct) { lastPct = pct; progress(pct) } }
                }
            } }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !release.sha256.equals(hex, true)) { out.delete(); throw IOException("Checksum mismatch; download discarded") }
            if (release.size > 0 && out.length() != release.size) { out.delete(); throw IOException("Incomplete download") }
        } finally { conn.disconnect() }
        val info = ctx.packageManager.getPackageArchiveInfo(out.path, 0)
        if (info == null || info.packageName != ctx.packageName) { out.delete(); throw IOException("Downloaded file is not a Relay APK") }
        @Suppress("DEPRECATION")
        if (info.versionCode.toLong() <= currentVersionCode(ctx)) { out.delete(); throw IOException("Downloaded version is not newer") }
        return out
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("relay.apk", 0, apk.length()).use { sink ->
                input.copyTo(sink); session.fsync(sink)
            } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = Intent(ctx, UpdateResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT).setPackage(ctx.packageName)
            session.commit(PendingIntent.getBroadcast(ctx, sessionId, callback, flags).intentSender)
        }
        prefs(ctx).edit().putString("update_status", "Installing update…").apply()
    }

    /** Periodic check from the sync service; downloads and installs automatically when allowed. */
    fun backgroundCheck(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("auto_update_check", true)) return
        if (System.currentTimeMillis() - p.getLong("update_last_check_ms", 0L) < CHECK_INTERVAL_MS) return
        if (busy.get()) return
        // Reserve the next check slot immediately so a slow or failing check is not repeated every 15 seconds.
        p.edit().putLong("update_last_check_ms", System.currentTimeMillis() - CHECK_INTERVAL_MS + RETRY_AFTER_FAILURE_MS).apply()
        val app = ctx.applicationContext
        Thread {
            try {
                val release = checkNow(app)
                if (release != null && p.getBoolean("auto_update_install", true) && canInstall(app)) {
                    downloadAndInstall(app, release)
                }
            } catch (e: Exception) {
                p.edit().putString("update_status", "Update check failed: " + (e.message ?: e.javaClass.simpleName)).apply()
            }
        }.start()
    }
}
