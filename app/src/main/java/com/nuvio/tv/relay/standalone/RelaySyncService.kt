package com.nuvio.tv.relay.standalone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RelaySyncService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val tmdbExecutor = Executors.newSingleThreadExecutor()
    private val tmdbInFlight = ConcurrentHashMap.newKeySet<String>()
    private val artworkWarmupRunning = AtomicBoolean(false)
    private val syncRunning = AtomicBoolean(false)
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
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    /** One-time migration: discard every previously published relay artwork URI and cached composite. */
    private fun enforceArtworkLayoutVersion() {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val currentVersion = 20
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
            if (syncRunning.compareAndSet(false, true)) {
                Thread {
                    try { syncNow() }
                    finally { syncRunning.set(false) }
                }.start()
            }
            handler.postDelayed(this, 15 * 1000L)
        }
    }
    private fun syncNow() {
        try {
            val prefs = getSharedPreferences("relay", MODE_PRIVATE)
            val addons = prefs.getStringSet("addons", emptySet()) ?: emptySet()
            val selected = prefs.getStringSet("selected", emptySet()) ?: emptySet()
            val candidates = mutableListOf<Triple<String, JSONObject, String>>()
            for (addon in addons) {
                val manifest = try { json(addon) } catch (_: Exception) { continue }
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
            if (candidates.isEmpty()) return
            // Stable ordering keeps the persisted cursor aligned with the user's selected catalogs.
            candidates.sortBy { "${it.second.optString("name")}|${it.first}|${it.second.optString("type")}|${it.second.optString("id")}" }
            // Background sync rotates the published catalog and persists the cursor across restarts.
            val rotationEnabled = prefs.getBoolean("rotation_enabled", true)
            val cursor = prefs.getInt("service_rotation_index", 0).mod(candidates.size)
            val (addon, catalog, logo) = candidates[cursor]
            if (rotationEnabled) prefs.edit().putInt("service_rotation_index", (cursor + 1).mod(candidates.size)).apply()
            val type = catalog.optString("type")
            val id = catalog.optString("id")
            val base = addon.removeSuffix("/manifest.json").trimEnd('/')
            val url = "$base/catalog/${enc(type)}/${enc(id)}.json?skip=0&limit=100"
            val metas = try { json(url).optJSONArray("metas") } catch (_: Exception) { null } ?: return
            val all = linkedMapOf<String, JSONObject>()
            // Process a bounded batch per catalog visit. Two items per visit made a 100-title
            // catalog take hours to enrich when many catalogs were selected.
            val tmdbBatchSize = 18
            val offsetIdentity = "$addon|${catalog.optString("type")}|${catalog.optString("id")}"
            val offsetDigest = MessageDigest.getInstance("SHA-256").digest(offsetIdentity.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            val tmdbOffsetKey = "tmdb_batch_offset_$offsetDigest"
            val tmdbBatchStart = prefs.getInt(tmdbOffsetKey, 0).mod(maxOf(1, metas.length()))
            prefs.edit().putInt(tmdbOffsetKey, (tmdbBatchStart + tmdbBatchSize).mod(maxOf(1, metas.length()))).apply()
            for (j in 0 until metas.length()) {
                val m = metas.optJSONObject(j) ?: continue
                val mid = sequenceOf("id", "_id", "imdb_id", "imdbId")
                    .map { m.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                val mt = m.optString("type", type).ifBlank { type }
                val sourceName = sequenceOf("name", "title", "original_name", "original_title")
                    .map { m.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                if (mid.isBlank() || sourceName.isBlank()) continue
                val tmdbBatchPosition = (j - tmdbBatchStart + metas.length()) % maxOf(1, metas.length())
                val sourcePoster = firstHttp(m.optString("poster"), m.optString("poster_url"), m.optString("posterUrl"))
                val sourceBackground = firstHttp(m.optString("background"), m.optString("backdrop"), m.optString("backdropUrl"), sourcePoster)
                val sourceLogo = firstHttp(m.optString("logo"), m.optString("logo_url"), m.optString("logoUrl"))
                val sourceOverview = sequenceOf("overview", "description", "plot", "synopsis", "summary")
                    .map { m.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                // Prioritize titles missing synopsis even when they fall outside this visit's regular batch.
                val missingSynopsis = sourceOverview.isBlank()
                val enriched = enrichFromTmdb(m, mt, allowFetch = tmdbBatchPosition < tmdbBatchSize || missingSynopsis) { updated ->
                    // TMDB responds asynchronously; republish this exact item with its fresh metadata,
                    // rather than waiting for a future catalog rotation to refresh the launcher artwork.
                    val updatedItem = JSONObject().put("id", mid).put("type", mt).put("name", sourceName)
                        .put("poster", firstHttp(updated.optString("poster"), sourcePoster))
                        .put("background", firstHttp(updated.optString("background"), sourceBackground, sourcePoster))
                        .put("platformLogo", firstHttp(updated.optString("platformLogo")))
                        .put("platformIcon", updated.optString("platformIcon"))
                        .put("platformName", updated.optString("platformName"))
                        .put("overview", updated.optString("overview"))
                        .put("titleLogo", firstHttp(updated.optString("titleLogo"), highestResolutionImage(sourceLogo), sourceLogo))
                        .put("rating", ratingAndYear(updated, mt, m))
                    handler.post {
                        // An enrichment response can arrive after rotation removed this title.
                        // Update existing rows only; never re-add an item from an old catalog visit.
                        if (relayId(updatedItem) in existingIds()) publish(updatedItem)
                    }
                }
                all.putIfAbsent("$mt:$mid", JSONObject().put("id", mid).put("type", mt)
                    .put("name", sourceName).put("poster", firstHttp(enriched.optString("poster"), sourcePoster))
                    .put("background", firstHttp(enriched.optString("background"), sourceBackground, sourcePoster))
                    .put("platformLogo", firstHttp(enriched.optString("platformLogo")))
                    .put("platformIcon", enriched.optString("platformIcon"))
                    .put("platformName", enriched.optString("platformName"))
                    .put("overview", enriched.optString("overview"))
                    .put("titleLogo", firstHttp(enriched.optString("titleLogo"), highestResolutionImage(sourceLogo), sourceLogo))
                    .put("rating", ratingAndYear(enriched, mt, m)))
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
                        wanted.take(8).forEach { item ->
                            runCatching {
                                val background = highestResolutionImage(item.optString("background"))
                                    ?: highestResolutionImage(item.optString("poster"))
                                val art = PosterCacheProvider.heroUriFor(
                                    this, background,
                                    highestResolutionImage(item.optString("titleLogo")),
                                    item.optString("rating"),
                                    item.optString("platformIcon").takeIf { it.startsWith("asset:") }
                                        ?: highestResolutionImage(item.optString("platformLogo")),
                                    item.optString("platformName"), item.optString("overview"), descriptionLanguage()
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
        } catch (e: Exception) {
            android.util.Log.w("NuvioRelay", "Background catalog sync failed (${e.javaClass.simpleName})", e)
        }
    }
    private fun publish(m: JSONObject, pinnedFirst: Boolean = false, topOfCatalog: Boolean = false) {
        val type = m.optString("type"); val id = m.optString("id")
        // Honor the target chosen in Relay settings for every published launcher card.
        val target = NuvioTarget.intent(this, type, id)
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
        PosterCacheProvider.heroUriFor(this, background, highestResolutionImage(m.optString("titleLogo")), m.optString("rating"),
            m.optString("platformIcon").takeIf { it.startsWith("asset:") } ?: highestResolutionImage(m.optString("platformLogo")),
            m.optString("platformName"), m.optString("overview"), descriptionLanguage())
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
        } catch (e: Exception) {
            android.util.Log.w("NuvioRelay", "Watch Next publish failed (${e.javaClass.simpleName})")
        }
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
    private fun firstHttp(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }.orEmpty()

    /** Merges only validated TMDB fields over source metadata; source fields remain the fallback. */
    private fun mergeTmdb(source: JSONObject, enrichment: JSONObject): JSONObject {
        val merged = JSONObject(source.toString())
        for (field in arrayOf("poster", "background", "titleLogo", "platformLogo")) {
            val value = enrichment.optString(field)
            if (value.startsWith("https://") && value.length > 12) merged.put(field, value)
        }
        for (field in arrayOf("platformIcon", "platformName", "vote_average", "year", "overview", "runtime", "number_of_seasons", "genres")) {
            if (enrichment.has(field) && !enrichment.isNull(field)) merged.put(field, enrichment.get(field))
        }
        return merged
    }

    private fun descriptionLanguage(): String = when (getSharedPreferences("relay", MODE_PRIVATE).getString("description_language", "en")) {
        "ar", "fr", "de" -> getSharedPreferences("relay", MODE_PRIVATE).getString("description_language", "en")!!
        else -> "en"
    }

    private fun normalizedTitle(value: String): String =
        value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun sourceYear(source: JSONObject): String =
        sequenceOf("year", "releaseInfo", "released", "release_date", "first_air_date")
            .map { source.optString(it) }
            .firstOrNull { Regex("(19|20)\\d{2}").containsMatchIn(it) }
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value }.orEmpty()

    /** Cache reads are merged with source metadata. Network fetches are serialized and bounded. */
    private fun enrichFromTmdb(source: JSONObject, type: String, allowFetch: Boolean, onEnriched: (JSONObject) -> Unit = {}): JSONObject {
        val token = getSharedPreferences("relay", MODE_PRIVATE).getString("tmdb_read_token", "").orEmpty().trim()
        if (token.isBlank()) return source
        val title = sequenceOf("name", "title", "original_name", "original_title")
            .map { source.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        if (title.isBlank()) return source

        val isTv = type.equals("series", true) || type.equals("tv", true) || type.equals("show", true)
        val endpoint = if (isTv) "tv" else "movie"
        val year = sourceYear(source)
        val imdbId = sequenceOf("imdb_id", "imdbId", "imdbid", "id")
            .map { source.optString(it).trim() }.firstOrNull { it.matches(Regex("tt\\d{7,10}")) }.orEmpty()
        val explicitTmdbId = sequenceOf("tmdb_id", "tmdbId", "tmdbid", "tmdb")
            .map { source.optString(it).trim().removePrefix("tmdb:").removePrefix("tmdb_") }
            .firstOrNull { it.matches(Regex("\\d{1,10}")) && (it.toIntOrNull() ?: 0) > 0 }.orEmpty()
        val cacheDir = File(cacheDir, "tmdb-cache").apply { mkdirs() }
        val descriptionLanguage = descriptionLanguage()
        val tmdbLanguage = when (descriptionLanguage) { "ar" -> "ar-SA"; "fr" -> "fr-FR"; "de" -> "de-DE"; else -> "en-US" }
        // English is the consistent fallback when the selected localized synopsis is unavailable.
        val fallbackLanguage = "en-US"
        val keyInput = "provider-icons-v4-synopsis-$descriptionLanguage|${type.lowercase(java.util.Locale.ROOT)}|${normalizedTitle(title)}|$year|$imdbId|$explicitTmdbId"
        val key = MessageDigest.getInstance("SHA-256").digest(keyInput.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "$key.json")
        if (file.exists()) {
            val age = System.currentTimeMillis() - file.lastModified()
            // Negative matches expire quickly: title-only matching can miss alternate/original titles,
            // and a stale miss must not suppress enrichment for days after the matching logic improves.
            val cachedTtl = if (runCatching { JSONObject(file.readText()).optBoolean("_no_match") }.getOrDefault(false))
                6L * 60 * 60 * 1000 else 30L * 24 * 60 * 60 * 1000
            if (age in 0..cachedTtl) {
                try {
                    val cached = JSONObject(file.readText())
                    if (cached.optBoolean("_no_match")) return source
                    // Older positive cache entries predate watch-provider metadata. Refresh them
                    // asynchronously instead of hiding platform logos for the full cache lifetime.
                    val cachedOverviewMissing = cached.optString("overview").isBlank()
                    if ((cached.has("platformLogo") && !(allowFetch && cachedOverviewMissing)) || !allowFetch) return mergeTmdb(source, cached)
                } catch (_: Exception) { }
            }
        }
        if (!allowFetch || !tmdbInFlight.add(key)) return source

        tmdbExecutor.execute {
            try {
                var match: JSONObject? = null
                var details: JSONObject? = null

                // Prefer exact TMDB IDs, then exact IMDb IDs. Never trust a title-only top result.
                if (explicitTmdbId.isNotBlank()) {
                    details = tmdbGet("https://api.themoviedb.org/3/$endpoint/$explicitTmdbId?language=$tmdbLanguage&append_to_response=images&include_image_language=en,null", token)
                    match = details
                } else if (imdbId.isNotBlank()) {
                    val found = tmdbGet("https://api.themoviedb.org/3/find/$imdbId?external_source=imdb_id", token)
                    val results = found.optJSONArray(if (isTv) "tv_results" else "movie_results")
                    match = results?.optJSONObject(0)
                    val exactId = match?.optInt("id", 0) ?: 0
                    if (exactId > 0) details = tmdbGet("https://api.themoviedb.org/3/$endpoint/$exactId?language=$tmdbLanguage&append_to_response=images&include_image_language=en,null", token)
                } else {
                    val query = URLEncoder.encode(title, "UTF-8")
                    val yearParam = if (year.isNotBlank()) (if (isTv) "&first_air_date_year=$year" else "&year=$year") else ""
                    val search = tmdbGet("https://api.themoviedb.org/3/search/$endpoint?query=$query$yearParam&language=$tmdbLanguage&include_adult=false", token)
                    val results = search.optJSONArray("results")
                    if (results != null) {
                        val sourceTitle = normalizedTitle(title)
                        for (i in 0 until results.length()) {
                            val candidate = results.optJSONObject(i) ?: continue
                            val candidateTitles = sequenceOf("name", "title", "original_name", "original_title")
                                .map { candidate.optString(it).trim() }.filter { it.isNotBlank() }
                                .map { normalizedTitle(it) }.toSet()
                            val sourceTitles = sequenceOf("name", "title", "original_name", "original_title")
                                .map { source.optString(it).trim() }.filter { it.isNotBlank() }
                                .map { normalizedTitle(it) }.toSet() + sourceTitle
                            if (candidateTitles.intersect(sourceTitles).isEmpty()) continue
                            val date = sequenceOf("release_date", "first_air_date")
                                .map { candidate.optString(it) }.firstOrNull { it.length >= 4 }.orEmpty()
                            if (year.isNotBlank() && (date.length < 4 || date.take(4) != year)) continue
                            match = candidate
                            break
                        }
                    }
                    val matchedId = match?.optInt("id", 0) ?: 0
                    if (matchedId > 0) details = tmdbGet("https://api.themoviedb.org/3/$endpoint/$matchedId?language=$tmdbLanguage&append_to_response=images&include_image_language=en,null", token)
                }

                val chosen = details ?: match
                val sourceTitleSet = sequenceOf("name", "title", "original_name", "original_title")
                    .map { source.optString(it).trim() }.filter { it.isNotBlank() }
                    .map { normalizedTitle(it) }.toSet() + normalizedTitle(title)
                val chosenTitleSet = chosen?.let { item ->
                    sequenceOf("name", "title", "original_name", "original_title")
                        .map { item.optString(it).trim() }.filter { it.isNotBlank() }
                        .map { normalizedTitle(it) }.toSet()
                }.orEmpty()
                val titleMismatch = explicitTmdbId.isBlank() && imdbId.isBlank() &&
                    sourceTitleSet.intersect(chosenTitleSet).isEmpty()
                if (chosen == null || titleMismatch) {
                    file.writeText(JSONObject().put("_no_match", true).toString())
                    return@execute
                }
                val result = JSONObject()
                val posterPath = chosen.optString("poster_path").takeIf { it.startsWith("/") }
                    ?: match?.optString("poster_path")?.takeIf { it.startsWith("/") }
                val backdropPath = chosen.optString("backdrop_path").takeIf { it.startsWith("/") }
                    ?: match?.optString("backdrop_path")?.takeIf { it.startsWith("/") }
                if (posterPath != null) result.put("poster", "https://image.tmdb.org/t/p/original$posterPath")
                if (backdropPath != null) result.put("background", "https://image.tmdb.org/t/p/original$backdropPath")
                // Resolve the actual streaming platform for the configured US region. Prefer subscription providers;
                // free/ad-supported services are fallbacks. Rental and purchase storefronts are not platforms.
                val providerEndpoint = if (isTv) "tv" else "movie"
                val contentId = chosen.optInt("id", 0)
                if (contentId > 0) {
                    try {
                        val providersResponse = tmdbGet("https://api.themoviedb.org/3/$providerEndpoint/$contentId/watch/providers", token)
                        val country = providersResponse.optJSONObject("results")?.optJSONObject("US")
                        val provider = sequenceOf("flatrate", "free", "ads", "rent", "buy")
                            .mapNotNull { category ->
                                val providers = country?.optJSONArray(category) ?: return@mapNotNull null
                                (0 until providers.length()).asSequence().mapNotNull { providers.optJSONObject(it) }
                                    .filter { it.optString("logo_path").startsWith("/") }
                                    .map { candidate -> candidate to providerDisplayName(candidate.optString("provider_name")) }
                                    .filter { (_, displayName) -> displayName.isNotBlank() }
                                    .sortedBy { (candidate, _) -> if (providerIconKey(candidate.optString("provider_name")) != null) 0 else 1 }
                                    .firstOrNull()
                            }.firstOrNull()
                        provider?.let { (p, displayName) ->
                            result.put("platformName", displayName)
                            p.optString("logo_path").takeIf { it.startsWith("/") }?.let {
                                result.put("platformLogo", "https://image.tmdb.org/t/p/w185$it")
                            }
                            providerIconKey(p.optString("provider_name"))?.let { result.put("platformIcon", "asset:$it") }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("NuvioRelay", "TMDB watch provider unavailable for ${title.take(60)}")
                    }
                }
                val logos = chosen.optJSONObject("images")?.optJSONArray("logos")
                if (logos != null) {
                    var selectedPath: String? = null
                    var selectedRank = Int.MAX_VALUE
                    for (i in 0 until logos.length()) {
                        val candidate = logos.optJSONObject(i) ?: continue
                        val path = candidate.optString("file_path")
                        if (!path.startsWith("/")) continue
                        val lang = candidate.optString("iso_639_1")
                        val languageRank = when (lang) { "en" -> 0; "null", "" -> 1; else -> 2 }
                        // Prefer English, then language-neutral logos; within a language prefer
                        // the largest uploaded asset instead of whichever row TMDB returns first.
                        val width = candidate.optInt("width", 0).coerceIn(0, 999_999)
                        val rank = languageRank * 1_000_000 - width
                        if (rank < selectedRank) { selectedPath = path; selectedRank = rank }
                    }
                    selectedPath?.let { result.put("titleLogo", "https://image.tmdb.org/t/p/original$it") }
                }
                val score = chosen.optDouble("vote_average", -1.0)
                if (score in 0.0..10.0) result.put("vote_average", score)
                val date = if (isTv) chosen.optString("first_air_date") else chosen.optString("release_date")
                if (date.matches(Regex("(19|20)\\d{2}.*"))) result.put("year", date.take(4))
                val genres = chosen.optJSONArray("genres")
                if (genres != null) {
                    val names = org.json.JSONArray()
                    for (i in 0 until minOf(2, genres.length())) {
                        val name = genres.optJSONObject(i)?.optString("name").orEmpty()
                        if (name.isNotBlank()) names.put(name)
                    }
                    result.put("genres", names)
                }
                var overview = chosen.optString("overview").trim()
                if (overview.isBlank() && contentId > 0 && descriptionLanguage != "en") {
                    // If the requested localized synopsis is missing, use English rather than an unrelated language.
                    overview = runCatching {
                        tmdbGet("https://api.themoviedb.org/3/$endpoint/$contentId?language=$fallbackLanguage", token).optString("overview").trim()
                    }.getOrDefault("")
                }
                if (overview.isNotBlank()) result.put("overview", overview)
                if (chosen.optInt("runtime", 0) > 0) result.put("runtime", chosen.optInt("runtime"))
                if (chosen.optInt("number_of_seasons", 0) > 0) result.put("number_of_seasons", chosen.optInt("number_of_seasons"))
                if (result.length() == 0) result.put("_no_match", true)
                file.writeText(result.toString())
                if (!result.optBoolean("_no_match")) {
                    val merged = mergeTmdb(source, result)
                    handler.post { runCatching { onEnriched(merged) } }
                }
            } catch (e: Exception) {
                // Never log the token or full request URL. Source metadata remains the fallback.
                android.util.Log.w("NuvioRelay", "TMDB enrichment failed for ${title.take(80)} (${e.javaClass.simpleName})")
            } finally {
                tmdbInFlight.remove(key)
            }
        }
        return source
    }

    private fun tmdbGet(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 7000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("TMDB HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    private fun ratingAndYear(m: JSONObject, type: String, fallback: JSONObject = JSONObject()): String {
        // Different addons use different field names and sometimes send null/empty TMDB fields.
        // Prefer valid TMDB values, then fall back to the original catalog item.
        fun validScore(obj: JSONObject): String? {
            val keys = arrayOf("vote_average", "imdbRating", "imdb_rating", "rating", "score")
            for (key in keys) {
                val raw = obj.opt(key)?.toString()?.trim().orEmpty()
                if (raw.isBlank() || raw.equals("null", true) || raw.equals("N/A", true)) continue
                val number = raw.toDoubleOrNull() ?: continue
                if (number in 0.1..10.0) return String.format(java.util.Locale.US, "%.1f", number)
            }
            return null
        }
        val score = validScore(m) ?: validScore(fallback)
        val yearKeys = arrayOf("year", "releaseInfo", "released", "release_date", "first_air_date", "releaseDate", "premiered", "date")
        fun validYear(obj: JSONObject): String? {
            for (key in yearKeys) {
                val raw = obj.opt(key)?.toString()?.trim().orEmpty()
                val year = Regex("(19|20)\\d{2}").find(raw)?.value
                if (year != null) return year
            }
            return null
        }
        val year = validYear(m) ?: validYear(fallback)
        val genres = mutableListOf<String>()
        fun collectGenres(obj: JSONObject) {
            val arr = obj.optJSONArray("genres") ?: obj.optJSONArray("genre")
            if (arr != null) for (i in 0 until arr.length()) {
                val raw = arr.opt(i)
                val value = (if (raw is JSONObject) raw.optString("name") else raw?.toString()).orEmpty().trim()
                if (value.isNotBlank() && !value.equals("null", true) && value !in genres && genres.size < 2) genres.add(value)
            }
            if (genres.size < 2) {
                val raw = obj.optString("genre").ifBlank { obj.optString("genres") }
                raw.trim().removePrefix("[").removeSuffix("]")
                    .split(Regex("[,|/•]"))
                    .map { it.trim().trim('\'', '\"', '[', ']').removePrefix("[").removeSuffix("]").trim() }
                    .filter { it.isNotBlank() && !it.equals("null", true) }
                    .forEach { value ->
                        val cleaned = value.replace(Regex("\\s+"), " ").trim()
                        if (cleaned !in genres && genres.none { it.equals(cleaned, true) } && genres.size < 2) genres.add(cleaned)
                    }
            }
        }
        collectGenres(m)
        collectGenres(fallback)
        val details = mutableListOf<String>()
        details.add(if (type.equals("series", true) || type.equals("tv", true) || type.equals("show", true)) "Series" else "Movie")
        details.addAll(genres)
        val badge = listOfNotNull(score?.let { "★ $it" }, year).joinToString("  ·  ")
        return details.joinToString("  ·  ") + if (badge.isNotBlank()) " ||| $badge" else ""
    }
    private fun providerDisplayName(rawName: String): String {
        val n = rawName.lowercase(java.util.Locale.ROOT).replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim()
        return when {
            n.contains("apple tv") -> "Apple TV"
            n.contains("prime video") || n == "amazon prime video" || n == "amazon prime" -> "Prime Video"
            n.contains("peacock") -> "Peacock"
            n.contains("paramount") -> "Paramount"
            n == "hbo" || n.contains("hbo max") -> "HBO"
            n == "max" || n.startsWith("max ") -> "Max"
            n.contains("disney") -> "Disney"
            n.contains("netflix") -> "Netflix"
            n == "cbs" || n.contains("cbs all access") -> "CBS"
            n.contains("hulu") -> "Hulu"
            n.contains("crunchyroll") -> "Crunchyroll"
            n == "viu" || n.contains("viu premium") -> "Viu"
            n.contains("youtube") -> "YouTube"
            n.contains("starz") -> "Starz"
            n.contains("mgm") -> "MGM"
            n.contains("showtime") -> "Showtime"
            n.contains("discovery") -> "Discovery"
            n.contains("fubo") -> "Fubo"
            n.contains("tubi") -> "Tubi"
            n.contains("pluto") -> "Pluto TV"
            n.contains("tnt") -> "TNT"
            n.contains("criterion") -> "Criterion"
            n.contains("kanopy") -> "Kanopy"
            n.contains("mubi") -> "MUBI"
            n.contains("roku") -> "Roku"
            n.contains("britbox") -> "BritBox"
            n.contains("acorn") -> "Acorn TV"
            n.contains("shudder") -> "Shudder"
            n.contains("plex") -> "Plex"
            else -> rawName.trim().replace(Regex("(?i)\\s+(premium|plus|channel|channels|subscription)$"), "").trim()
        }
    }

    private fun providerIconKey(name: String): String? {
        val n = name.lowercase(java.util.Locale.ROOT).replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim()
        return when {
            n.contains("apple tv") -> "appletv"
            n.contains("prime video") || n == "amazon prime video" || n == "amazon prime" -> "primevideo"
            n.contains("peacock") -> "peacock"
            n.contains("paramount") -> "paramountplus"
            n == "hbo" || n.contains("hbo max") -> "hbo"
            n == "max" || n.startsWith("max ") -> "max"
            n.contains("disney") -> "disneyplus"
            n.contains("netflix") -> "netflix"
            n == "cbs" || n.contains("cbs all access") -> "cbs"
            n.contains("hulu") -> "hulu"
            n.contains("crunchyroll") -> "crunchyroll"
            n == "viu" || n.contains("viu premium") -> "viu"
            else -> null
        }
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
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        artworkExecutor.shutdownNow()
        tmdbExecutor.shutdownNow()
        super.onDestroy()
    }
}
