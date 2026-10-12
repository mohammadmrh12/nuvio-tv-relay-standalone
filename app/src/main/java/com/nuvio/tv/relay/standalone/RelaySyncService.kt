package com.nuvio.tv.relay.standalone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RelaySyncService : Service() {
    companion object { const val ACTION_FORCE_SYNC = "com.nuvio.tv.relay.standalone.FORCE_SYNC" }
    private val handler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val artworkWarmupRunning = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("relay_sync", "Nuvio Relay sync", NotificationManager.IMPORTANCE_LOW))
            startForeground(41, Notification.Builder(this, "relay_sync")
                .setContentTitle("Dash")
                .setContentText("Dash catalog sync is active")
                .setSmallIcon(android.R.drawable.ic_popup_sync).build())
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enforceArtworkLayoutVersion()
        if (intent?.action == ACTION_FORCE_SYNC) {
            val prefs = getSharedPreferences("relay", MODE_PRIVATE)
            prefs.edit().putString("sync_status", "running").putString("sync_error", "").apply()
            Thread { syncNow(forceRefresh = true) }.start()
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    /** One-time migration: discard every previously published relay artwork URI and cached composite. */
    private fun enforceArtworkLayoutVersion() {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val currentVersion = 15
        if (prefs.getInt("artwork_layout_version", 0) >= currentVersion) return
        runCatching {
            existingIds().forEach { id -> deleteRelay(id) }
        }
        runCatching {
            File(cacheDir, "poster-cache").listFiles()?.forEach { file ->
                if (file.isFile) file.delete() else file.deleteRecursively()
            }
        }
        prefs.edit()
            .putInt("artwork_layout_version", currentVersion)
            .putInt("service_rotation_index", 0)
            .apply()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!getSharedPreferences("relay", MODE_PRIVATE).getBoolean("auto_sync", true)) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
            }
            if (!busy.get()) Thread { syncNow() }.start()
            handler.postDelayed(this, 15 * 1000L)
        }
    }
    private fun syncNow(forceRefresh: Boolean = false) {
        if (!busy.compareAndSet(false, true)) return
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        prefs.edit().putString("sync_status", "running").putString("sync_error", "").apply()
        try {
            if (forceRefresh) {
                runCatching { File(cacheDir, "api-cache").deleteRecursively() }
                runCatching { File(cacheDir, "poster-cache").deleteRecursively() }
            }
            val addons = prefs.getStringSet("addons", emptySet()) ?: emptySet()
            val selected = prefs.getStringSet("selected", emptySet()) ?: emptySet()
            val candidates = mutableListOf<Triple<String, JSONObject, String>>()
            var manifestsLoaded = 0
            for (addon in addons) {
                val manifest = try { json(addon).also { manifestsLoaded++ } } catch (_: Exception) { continue }
                val cats = manifest.optJSONArray("catalogs") ?: continue
                for (i in 0 until cats.length()) {
                    val c = cats.optJSONObject(i) ?: continue
                    val type = c.optString("type")
                    val id = c.optString("id")
                    val key = "$addon|$type|$id"
                    if (type.isNotBlank() && id.isNotBlank() && key in selected) {
                        candidates.add(Triple(addon, c, manifest.optString("logo").ifBlank { manifest.optString("icon") }))
                    }
                }
            }
            // Do not interpret a network failure as catalog removal. Only reconcile stale rows
            // when every currently configured add-on manifest was read successfully.
            if (manifestsLoaded != addons.size) {
                val failed = addons.size - manifestsLoaded
                prefs.edit().putString("sync_status", "failed")
                    .putString("sync_error", "$failed of ${addons.size} add-on manifests failed to load; existing launcher rows were kept.").apply()
                return
            }
            if (candidates.isEmpty()) {
                val native = readNativeContinueWatching().firstOrNull()
                existingIds().filter { it != "nuvio_relay_pinned_native_first" }.forEach { deleteRelay(it) }
                native?.let { publish(it, pinnedFirst = true) } ?: deleteRelay("nuvio_relay_pinned_native_first")
                prefs.edit().putLong("last_successful_sync", System.currentTimeMillis())
                    .putString("sync_status", "success").putString("sync_error", "").apply()
                return
            }
            // Stable ordering keeps the persisted cursor aligned with the user's selected catalogs.
            candidates.sortBy { "${it.second.optString("name")}|${it.first}|${it.second.optString("type")}|${it.second.optString("id")}" }
            // Background sync rotates the published catalog and persists the cursor across restarts.
            val cursor = prefs.getInt("service_rotation_index", 0).mod(candidates.size)
            val (addon, catalog, logo) = candidates[cursor]
            prefs.edit().putInt("service_rotation_index", (cursor + 1).mod(candidates.size)).apply()
            val type = catalog.optString("type")
            val id = catalog.optString("id")
            val base = addon.removeSuffix("/manifest.json").trimEnd('/')
            val url = "$base/catalog/${enc(type)}/${enc(id)}.json?skip=0&limit=100"
            val metas = try { json(url).optJSONArray("metas") } catch (e: Exception) {
                prefs.edit().putString("sync_status", "failed")
                    .putString("sync_error", "Catalog load failed: ${e.message ?: e.javaClass.simpleName}").apply()
                null
            } ?: return
            val all = linkedMapOf<String, JSONObject>()
            for (j in 0 until metas.length()) {
                val m = metas.optJSONObject(j) ?: continue
                val mid = m.optString("id")
                val mt = m.optString("type", type).ifBlank { type }
                if (mid.isBlank() || m.optString("name").isBlank()) continue
                all.putIfAbsent("$mt:$mid", JSONObject().put("id", mid).put("type", mt)
                    .put("name", m.optString("name")).put("poster", m.optString("poster"))
                    .put("background", m.optString("background")).put("platformLogo", logo)
                    .put("titleLogo", highestResolutionImage(m.optString("logo")) ?: m.optString("logo"))
                    .put("rating", ratingAndYear(m, mt)))
            }
            val firstNative = readNativeContinueWatching().firstOrNull()
            val wanted = all.values.filterNot {
                firstNative != null && it.optString("type").equals(firstNative.optString("type"), true) &&
                    it.optString("id") == firstNative.optString("id")
            }.take(if (firstNative != null) 49 else 50)
            // One background worker warms each selected catalog without spawning threads every 15 seconds.
            if (artworkWarmupRunning.compareAndSet(false, true)) {
                artworkExecutor.execute {
                    try {
                        wanted.forEach { item ->
                            runCatching {
                                val background = highestResolutionImage(item.optString("background"))
                                    ?: highestResolutionImage(item.optString("poster"))
                                val art = PosterCacheProvider.heroUriFor(
                                    this, background,
                                    highestResolutionImage(item.optString("titleLogo")),
                                    item.optString("rating")
                                )
                                if (art != null) contentResolver.openFileDescriptor(art, "r")?.use { }
                            }
                        }
                    } finally {
                        artworkWarmupRunning.set(false)
                    }
                }
            }
            val wantedIds = wanted.map { relayId(it) }.toSet() +
                setOfNotNull(firstNative?.let { "nuvio_relay_pinned_native_first" })
            existingIds().filter { it !in wantedIds }.forEach { deleteRelay(it) }
            wanted.forEachIndexed { index, item -> publish(item, topOfCatalog = index == 0) }
            firstNative?.let { publish(it, pinnedFirst = true) }
            prefs.edit().putLong("last_successful_sync", System.currentTimeMillis())
                .putString("sync_status", "success").putString("sync_error", "").apply()
        } catch (e: Exception) {
            prefs.edit().putString("sync_status", "failed")
                .putString("sync_error", e.message ?: e.javaClass.simpleName).apply()
        } finally { busy.set(false) }
    }
    private fun publish(m: JSONObject, pinnedFirst: Boolean = false, topOfCatalog: Boolean = false) {
        val type = m.optString("type"); val id = m.optString("id")
        val uri = Uri.Builder().scheme("nuvio").authority("meta").appendQueryParameter("type", type.lowercase()).appendQueryParameter("id", id).build()
        // Clear any existing Nuvio task so a second Continue Watching selection refreshes
        // the destination instead of leaving the first movie detail screen alive.
        val target = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.nuvioreshaped.tv")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val launcherIntentUri = Uri.parse(target.toUri(Intent.URI_INTENT_SCHEME))
        val builder = WatchNextProgram.Builder()
            .setType(if (type.equals("movie", true)) TvContractCompat.WatchNextPrograms.TYPE_MOVIE else TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST)
            .setTitle(if (pinnedFirst) m.optString("name") else "")
            .setLastEngagementTimeUtcMillis(
                when {
                    pinnedFirst -> System.currentTimeMillis() + 2 * 86_400_000L
                    topOfCatalog -> System.currentTimeMillis() + 86_400_000L
                    else -> System.currentTimeMillis() - 60_000L
                }
            )
            .setInternalProviderId(if (pinnedFirst) "nuvio_relay_pinned_native_first" else relayId(m)).setIntentUri(launcherIntentUri)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        val background = highestResolutionImage(m.optString("background")) ?: highestResolutionImage(m.optString("poster"))
        PosterCacheProvider.heroUriFor(this, background, highestResolutionImage(m.optString("titleLogo")), m.optString("rating"))
            ?.let { builder.setPosterArtUri(it) }
        m.optString("platformLogo").takeIf { it.startsWith("http") }?.let { builder.setLogoUri(Uri.parse(it)) }
        try {
            val values = builder.build().toContentValues()
            val internalId = if (pinnedFirst) "nuvio_relay_pinned_native_first" else relayId(m)
            val existing = findId(internalId)
            if (existing == null) contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            else {
                contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null)
                contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            }
        } catch (_: Exception) { }
    }
    private fun readNativeContinueWatching(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val projection = arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_TITLE, TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI, TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
        try {
            contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, projection, null, null, TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")?.use { c ->
                val titleIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE)
                val intentIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI)
                val posterIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI)
                val internalIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                while (c.moveToNext()) {
                    val internal = if (internalIx >= 0) c.getString(internalIx).orEmpty() else ""
                    val title = if (titleIx >= 0) c.getString(titleIx).orEmpty() else ""
                    val raw = if (intentIx >= 0) c.getString(intentIx).orEmpty() else ""
                    if (internal.startsWith("nuvio_relay_")) continue
                    if (title.isBlank() || raw.isBlank()) continue
                    val deepLink = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).data }.getOrNull() ?: runCatching { Uri.parse(raw) }.getOrNull()
                    if (deepLink?.scheme != "nuvio" || deepLink.host != "meta") continue
                    val id = deepLink.getQueryParameter("id") ?: continue
                    val type = deepLink.getQueryParameter("type") ?: "movie"
                    val poster = if (posterIx >= 0) c.getString(posterIx).orEmpty() else ""
                    out.add(JSONObject().put("id", id).put("type", type).put("name", title).put("poster", poster).put("background", poster))
                }
            }
        } catch (_: Exception) { }
        return out.distinctBy { it.optString("type").lowercase() + ":" + it.optString("id") }
    }
    private fun json(url: String): JSONObject {
        val dir = File(cacheDir, "api-cache").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, "$hash.json")
        val ttl = if (url.contains("/catalog/")) 15 * 60 * 1000L else 24 * 60 * 60 * 1000L
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttl) try { return JSONObject(f.readText()) } catch (_: Exception) {}
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000; c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val parsed = JSONObject(body); runCatching { f.writeText(body) }; return parsed
            } finally { c.disconnect() }
        } catch (e: Exception) { if (f.exists()) try { return JSONObject(f.readText()) } catch (_: Exception) {}; throw e }
    }
    private fun highestResolutionImage(raw: String?): String? {
        if (raw.isNullOrBlank() || !raw.startsWith("http")) return null
        return Regex("(/t/p/)(w45|w92|w154|w185|w300|w342|w500|w780|w1280)(/)").replace(raw) { match ->
            match.groupValues[1] + "original" + match.groupValues[3]
        }
    }
    private fun ratingAndYear(m: JSONObject, type: String): String {
        val score = sequenceOf(m.optString("imdbRating"), m.optString("rating"))
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("[0-9](\\.[0-9]{1,2})?")) && (it.toDoubleOrNull()?.let { n -> n in 0.0..10.0 } == true) }
            ?: m.optDouble("vote_average", -1.0).takeIf { it in 0.0..10.0 }
                ?.let { String.format(java.util.Locale.US, "%.1f", it) }
        val yearSource = sequenceOf(m.optString("year"), m.optString("releaseInfo"), m.optString("released"))
            .firstOrNull { Regex("(19|20)\\d{2}").containsMatchIn(it) }
        val year = yearSource?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        val genres = m.optJSONArray("genres")
        val genreNames = mutableListOf<String>()
        if (genres != null) for (i in 0 until minOf(genres.length(), 2)) {
            val g = genres.optString(i).trim()
            if (g.isNotBlank() && g !in genreNames) genreNames.add(g)
        }
        val details = mutableListOf<String>()
        details.add(if (type.equals("series", true) || type.equals("tv", true) || type.equals("show", true)) "Series" else "Movie")
        details.addAll(genreNames)
        val badge = listOfNotNull(score?.let { "★ $it" }, year).joinToString("  ·  ")
        return details.joinToString("  ·  ") + if (badge.isNotBlank()) " ||| $badge" else ""
    }
    private fun relayId(m: JSONObject) = "nuvio_relay_${m.optString("type").lowercase()}_${m.optString("id")}"
    private fun existingIds(): Set<String> {
        val out = mutableSetOf<String>()
        try { contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            val i = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) c.getString(i)?.takeIf { it.startsWith("nuvio_relay_") }?.let(out::add)
        } } catch (_: Exception) {}
        return out
    }
    private fun findId(key: String): Long? {
        try { contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            val pk = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID); val ki = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) if (c.getString(ki) == key) return c.getLong(pk)
        } } catch (_: Exception) {}
        return null
    }
    private fun deleteRelay(key: String) { findId(key)?.let { try { contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) } catch (_: Exception) {} } }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
}
