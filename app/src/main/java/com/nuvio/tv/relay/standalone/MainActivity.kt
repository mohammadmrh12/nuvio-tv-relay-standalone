package com.nuvio.tv.relay.standalone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

class MainActivity : Activity() {
    private val worker = Executors.newFixedThreadPool(8)
    private lateinit var root: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var catalogPanel: LinearLayout
    private lateinit var pageScroll: ScrollView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rotationOn = true
    private var rotationSeconds = 15
    private var rotationIndex = 0
    private val sectionViews = linkedMapOf<String, View>()
    private lateinit var status: TextView
    private lateinit var lastSyncLabel: TextView
    private lateinit var list: LinearLayout
    private val catalogs = mutableListOf<Catalog>()
    private val selected = mutableSetOf<String>()
    private val addons = linkedSetOf<String>()
    private val previews = mutableMapOf<String, MutableList<Item>>()
    private val expanded = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("relay", Context.MODE_PRIVATE) }

    data class Catalog(val addon: String, val addonName: String, val type: String, val id: String, val name: String, val platformLogo: String?) {
        val key: String get() = addon + "|" + type + "|" + id
    }
    data class Item(val id: String, val type: String, val name: String, val poster: String?, val background: String?, val platformLogo: String?, val titleLogo: String? = null, val rating: String = "")
    private val nativeContinueKey = "nuvio_native_continue_watching"
    private val pinnedNativeInternalId = "nuvio_relay_pinned_native_first"
    private fun nativeContinueCatalog() = Catalog("nuvio-native://continue-watching", "Nuvio Re-Shaped", "native", "continue-watching", "Nuvio Re-Shaped Continue Watching", null)
    private fun withNativeContinue(items: List<Catalog>): List<Catalog> = (items.filterNot { it.key == nativeContinueCatalog().key } + nativeContinueCatalog())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.edit()
            .putBoolean("auto_sync", prefs.getBoolean("auto_sync", true))
            .putBoolean("auto_cache", prefs.getBoolean("auto_cache", true))
            .putBoolean("auto_rotation", prefs.getBoolean("auto_rotation", true))
            .putBoolean("rotate_all_catalogs", true)
            .apply()
        rotationOn = prefs.getBoolean("auto_rotation", true)
        selected.addAll(prefs.getStringSet("selected", emptySet()) ?: emptySet())
        if (prefs.getStringSet("selected", emptySet()).isNullOrEmpty()) selected.add(nativeContinueCatalog().key)
        val storedAddons = prefs.getStringSet("addons", emptySet()) ?: emptySet()
        val repairedAddons = storedAddons.associateWith { normalize(it) }
        addons.addAll(repairedAddons.values)
        if (repairedAddons.any { it.key != it.value }) {
            val oldSelected = prefs.getStringSet("selected", emptySet()) ?: emptySet()
            val repairedSelected = oldSelected.map { key ->
                val match = repairedAddons.entries.firstOrNull { key.startsWith(it.key + "|") }
                if (match == null) key else match.value + key.removePrefix(match.key)
            }.toSet()
            prefs.edit().putStringSet("addons", addons.toSet()).putStringSet("selected", repairedSelected).apply()
            selected.clear(); selected.addAll(repairedSelected)
        }
        pageScroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 30, 36, 36)
            setBackgroundColor(Color.rgb(10, 14, 24))
        }
        pageScroll.addView(root)
        setContentView(pageScroll)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = rounded(Color.rgb(20, 31, 49), 18)
        }
        header.addView(label("NUVIO  /  RELAY", 12, Color.rgb(94, 190, 255), true))
        header.addView(label("Nuvio Relay", 32, Color.WHITE, true))
        header.addView(label("Make your Google TV home screen yours.", 17, Color.rgb(205, 216, 232)))
        header.addView(label("Choose catalogs, keep Continue Watching fresh, and open titles directly in Nuvio Re-Shaped.", 14, Color.rgb(153, 170, 192)))
        root.addView(header, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) })
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            background = rounded(Color.rgb(15, 22, 35), 16)
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        controls.addView(label("CONNECT", 13, Color.rgb(94, 190, 255), true))
        button("Connect", action = { connectToNuvio() })
        button("Sync Now / Refresh Artwork", action = { forceRefresh() })
        lastSyncLabel = label(lastSyncText(), 12, Color.rgb(153, 170, 192))
        controls.addView(lastSyncLabel)
        controls.addView(label("ADD-ON SETUP GUIDE", 14, Color.WHITE, true))
        controls.addView(label("1. Open Nuvio Re-Shaped on this TV.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("2. Go to Add-ons → Manage from phone (QR).", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("3. Keep the QR / phone-management screen open.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("4. Return here and select Connect.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("5. Catalogs will load and sync automatically.", 13, Color.rgb(205, 216, 232)))
        catalogPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = rounded(Color.rgb(15, 22, 35), 16)
        }
        root.addView(catalogPanel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        catalogPanel.addView(label("CATALOG LIBRARY", 16, Color.WHITE, true))
        catalogPanel.addView(label("Catalogs from Nuvio Re-Shaped appear here.", 14, Color.rgb(153, 170, 192)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalogPanel.addView(list)
        status = label("Ready · waiting for connection", 14, Color.rgb(130, 220, 180), true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.rgb(18, 43, 42), 10)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(label("Nuvio Re-Shaped is not modified by this app.", 12, Color.rgb(130, 145, 164)))
        refresh()
        updateLastSyncLabel()
        if (prefs.getBoolean("auto_sync", true)) {
            val service = Intent(this, RelaySyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
        }
    }

    private val autoSyncTask = object : Runnable {
        override fun run() {
            if (!prefs.getBoolean("auto_sync", true)) return
            sync()
            mainHandler.postDelayed(this, 6 * 60 * 60 * 1000L)
        }
    }

    private fun scheduleAutoSync() {
        mainHandler.removeCallbacks(autoSyncTask)
        mainHandler.postDelayed(autoSyncTask, 6 * 60 * 60 * 1000L)
    }

    private fun connectToNuvio() {
        message("Looking for Nuvio Re-Shaped local add-on server…")
        worker.execute {
            var connectedPort: Int? = null
            var addonArray: JSONArray? = null
            for (port in 8080..8089) {
                try {
                    addonArray = jsonArray("http://127.0.0.1:" + port + "/api/addons")
                    connectedPort = port
                    break
                } catch (_: Exception) { }
            }
            val arr = addonArray
            if (connectedPort == null || arr == null) {
                runOnUiThread {
                    message("Nuvio Re-Shaped server not found. Open Nuvio Re-Shaped > Add-ons > Manage from phone (QR), keep that screen open, then retry.")
                }
                return@execute
            }
            try {
                val loadedCatalogs = mutableListOf<Catalog>()
                val importedUrls = linkedSetOf<String>()
                var importedCount = 0
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val rawUrl = a.optString("url").trim()
                    if (rawUrl.isBlank()) continue
                    val manifestUrl = normalize(rawUrl)
                    importedUrls.add(manifestUrl)
                    val addonName = a.optString("name").ifBlank { Uri.parse(manifestUrl).host ?: "Add-on" }
                    try {
                        val manifest = json(manifestUrl)
                        loadedCatalogs.addAll(parseCatalogs(manifestUrl, addonName, manifest))
                        importedCount++
                    } catch (_: Exception) { }
                }
                if (importedUrls.isEmpty()) {
                    runOnUiThread { message("Connected to Nuvio Re-Shaped on port " + connectedPort + ", but it returned no installed add-ons.") }
                    return@execute
                }
                addons.clear()
                addons.addAll(importedUrls)
                prefs.edit().putStringSet("addons", addons.toSet()).apply()
                runOnUiThread {
                    catalogs.clear()
                    catalogs.addAll(withNativeContinue(loadedCatalogs))
                    previews.clear(); expanded.clear()
                    selected.clear()
                    selected.addAll(catalogs.map { it.key })
                    prefs.edit().putStringSet("selected", selected.toSet())
                        .putBoolean("auto_sync", true)
                        .putBoolean("auto_cache", true)
                        .putBoolean("auto_rotation", true)
                        .putBoolean("rotate_all_catalogs", true)
                        .apply()
                    rotationOn = true
                    render()
                    message("Connected. Imported " + importedCount + " add-ons and " + loadedCatalogs.size + " catalogs. Sync and rotation start automatically.")
                    val syncService = Intent(this@MainActivity, RelaySyncService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(syncService) else startService(syncService)
                    if (selected.isNotEmpty()) {
                        mainHandler.post { sync() }
                        startAutomaticRotation()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { message("Import failed: " + (e.message ?: "unknown error")) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::lastSyncLabel.isInitialized) updateLastSyncLabel()
        mainHandler.removeCallbacks(syncStatusRefresh)
        mainHandler.post(syncStatusRefresh)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(syncStatusRefresh)
        super.onPause()
    }

    private val syncStatusRefresh = object : Runnable {
        override fun run() {
            if (::lastSyncLabel.isInitialized) updateLastSyncLabel()
            if (!isFinishing) mainHandler.postDelayed(this, 2000L)
        }
    }

    private fun lastSyncText(): String {
        val stamp = prefs.getLong("last_successful_sync", 0L)
        val last = if (stamp <= 0L) "Last successful sync: Never" else "Last successful sync: " +
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(stamp))
        val state = prefs.getString("sync_status", "idle") ?: "idle"
        val error = prefs.getString("sync_error", "") ?: ""
        return when (state) {
            "running" -> "$last\nSync status: Running…"
            "success" -> "$last\nSync status: Successful"
            "partial" -> "$last\nSync status: Partial — $error"
            "failed" -> "$last\nSync status: Failed — $error"
            else -> last + "\nSync status: Not started"
        }
    }

    private fun updateLastSyncLabel() {
        if (::lastSyncLabel.isInitialized) lastSyncLabel.text = lastSyncText()
    }

    private fun forceRefresh() {
        prefs.edit().putString("sync_status", "running").putString("sync_error", "").apply()
        updateLastSyncLabel()
        message("Refreshing catalogs and artwork cache…")
        val service = Intent(this, RelaySyncService::class.java).setAction(RelaySyncService.ACTION_FORCE_SYNC)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            mainHandler.post(object : Runnable {
                override fun run() {
                    updateLastSyncLabel()
                    if ((prefs.getString("sync_status", "idle") ?: "idle") == "running") mainHandler.postDelayed(this, 1500L)
                    else message(if (prefs.getString("sync_status", "") == "success") "Sync completed successfully." else "Sync failed. See status below for the reason.")
                }
            })
            refresh(forceNetwork = true)
        } catch (e: Exception) {
            prefs.edit().putString("sync_status", "failed").putString("sync_error", e.message ?: e.javaClass.simpleName).apply()
            updateLastSyncLabel()
            message("Could not start sync: ${e.message ?: "unknown error"}")
        }
    }

    private fun refresh(forceNetwork: Boolean = false) {
        if (addons.isEmpty()) { render(); message("Connect to Nuvio Re-Shaped or add a manifest URL."); return }
        worker.execute {
            val urls = addons.toList()
            val futures = urls.map { url -> CompletableFuture.supplyAsync({
                try {
                    val m = json(url, forceNetwork)
                    parseCatalogs(url, m.optString("name", Uri.parse(url).host ?: "Add-on"), m)
                } catch (_: Exception) { emptyList<Catalog>() }
            }, worker) }
            val loaded = mutableListOf<Catalog>()
            futures.forEach { future -> runCatching { loaded.addAll(future.get()) } }
            runOnUiThread {
                catalogs.clear(); catalogs.addAll(withNativeContinue(loaded)); previews.clear(); expanded.clear(); render()
                if (prefs.getBoolean("auto_rotation", true) && catalogs.isNotEmpty()) startAutomaticRotation()
                val failed = urls.size - futures.count { it.isDone && !it.isCompletedExceptionally && runCatching { it.get().isNotEmpty() }.getOrDefault(false) }
                message("Loaded " + loaded.size + " catalogs" + if (failed > 0) "; $failed add-ons unavailable (will retry automatically)." else ".")
            }
        }
    }

    private fun parseCatalogs(url: String, addonName: String, manifest: JSONObject): List<Catalog> {
        val out = mutableListOf<Catalog>()
        val arr = manifest.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val type = c.optString("type"); val id = c.optString("id")
            if (type.isNotBlank() && id.isNotBlank()) out.add(Catalog(url, addonName, type, id, c.optString("name").ifBlank { id }, manifest.optString("logo").ifBlank { manifest.optString("icon") }.takeIf { it.startsWith("http") }))
        }
        return out
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        sectionViews.clear()
        if (catalogs.isEmpty()) { list.addView(label("No catalogs loaded yet.", 15, Color.LTGRAY)); return }
        catalogs.distinctBy { it.key }.forEach { c ->
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(14))
                background = rounded(Color.rgb(20, 27, 41), 14)
                isFocusable = false
            }
            sectionViews[c.key] = section
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val cb = CheckBox(this).apply {
                text = c.name
                textSize = 17f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 190, 255))
                isChecked = c.key in selected; isFocusable = true; setPadding(0, 4, 8, 4)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(c.key) else selected.remove(c.key)
                    prefs.edit().putStringSet("selected", selected.toSet()).apply()
                }
            }
            heading.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val typeBadge = TextView(this).apply {
                text = c.type.uppercase()
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(Color.rgb(153, 210, 255))
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(Color.rgb(31, 53, 77), 9)
            }
            heading.addView(typeBadge)
            section.addView(heading)
            section.addView(label(c.addonName, 12, Color.rgb(153, 170, 192)))
            section.addView(label(if (c.key in selected) "Selected · included in sync" else "Not selected · tap to include", 12, if (c.key in selected) Color.rgb(105, 211, 176) else Color.rgb(133, 147, 168)))
            list.addView(section, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            })
        }
    }

    private fun loadCatalog(c: Catalog) {
        if (c.key in previews) { expanded.add(c.key); render(); return }
        message("Loading titles from ${c.name}…")
        worker.execute {
            try {
                val base = c.addon.removeSuffix("/manifest.json").trimEnd('/')
                val url = base + "/catalog/" + enc(c.type) + "/" + enc(c.id) + ".json?skip=0&limit=24"
                val arr = json(url).optJSONArray("metas") ?: JSONArray()
                val result = mutableListOf<Item>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val id = m.optString("id"); val name = m.optString("name")
                    if (id.isBlank() || name.isBlank()) continue
                    result.add(Item(id, m.optString("type", c.type).ifBlank { c.type }, name,
                        m.optString("poster").takeIf { it.startsWith("http") },
                        m.optString("background").takeIf { it.startsWith("http") }, c.platformLogo))
                }
                runOnUiThread { previews[c.key] = result; expanded.add(c.key); render(); message("Loaded ${result.size} titles from ${c.name}.") }
            } catch (e: Exception) { runOnUiThread { message("Catalog load failed: ${e.message ?: "network error"}") } }
        }
    }

    private fun startAutomaticRotation() {
        rotationOn = prefs.getBoolean("auto_rotation", true)
        mainHandler.removeCallbacks(rotationTask)
        if (!rotationOn || catalogs.isEmpty()) return
        rotationIndex = 0
        mainHandler.postDelayed(rotationTask, rotationSeconds * 1000L)
    }

    private fun toggleRotation() {
        rotationOn = !rotationOn
        prefs.edit().putBoolean("auto_rotation", rotationOn).apply()
        mainHandler.removeCallbacks(rotationTask)
        if (rotationOn) {
            rotationIndex = 0
            rotateToNextSection()
            mainHandler.postDelayed(rotationTask, rotationSeconds * 1000L)
            message("Section rotation running across checked catalogs.")
        } else {
            message("Section rotation stopped.")
        }
    }

    private val rotationTask = object : Runnable {
        override fun run() {
            if (!rotationOn) return
            rotateToNextSection()
            mainHandler.postDelayed(this, rotationSeconds * 1000L)
        }
    }

    private fun rotateToNextSection() {
        val chosen = catalogs.distinctBy { it.key }.filter { it.key in selected }
        if (chosen.isEmpty()) {
            message("Choose the catalogs you want to rotate between using their checkboxes.")
            rotationOn = false
            return
        }
        val includeAll = prefs.getBoolean("rotate_all_catalogs", true)
        val totalSteps = chosen.size + if (includeAll) 1 else 0
        if (rotationIndex >= totalSteps) rotationIndex = 0
        if (includeAll && rotationIndex == chosen.size) {
            rotationIndex++
            message("Rotation: Continue Watching — all selected catalogs")
            sync()
            return
        }
        val catalog = chosen[rotationIndex++]
        message("Rotating Continue Watching to: " + catalog.name)
        syncCatalogToContinueWatching(catalog)
        sectionViews[catalog.key]?.let { section ->
            pageScroll.post {
                pageScroll.smoothScrollTo(0, (list.top + section.top).coerceAtLeast(0))
                section.setBackground(rounded(Color.rgb(25, 44, 65), 10))
                mainHandler.postDelayed({ section.setBackgroundColor(Color.TRANSPARENT) }, 700)
            }
        }
    }

    private fun syncCatalogToContinueWatching(catalog: Catalog) {
        worker.execute {
            try {
                if (catalog.key == nativeContinueCatalog().key) {
                    val nativeItems = readNativeContinueWatching()
                    val firstNative = nativeItems.firstOrNull()
                    val wanted = nativeItems.drop(1).take(49)
                    val old = existingIds()
                    val wantedIds = wanted.map { relayId(it) }.toSet() + setOfNotNull(firstNative?.let { pinnedNativeInternalId })
                    (old - wantedIds).forEach { deleteRelay(it) }
                    firstNative?.let { publish(it, pinnedFirst = true) }
                    val count = wanted.count { publish(it) } + if (firstNative != null) 1 else 0
                    runOnUiThread { message("Continue Watching refreshed with $count native Nuvio titles.") }
                    return@execute
                }
                val rootUrl = catalog.addon.removeSuffix("/manifest.json").trimEnd('/')
                val url = rootUrl + "/catalog/" + enc(catalog.type) + "/" + enc(catalog.id) + ".json?skip=0&limit=100"
                val metas = json(url).optJSONArray("metas") ?: JSONArray()
                val items = mutableListOf<Item>()
                for (i in 0 until metas.length()) {
                    val m = metas.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    val name = m.optString("name")
                    if (id.isBlank() || name.isBlank()) continue
                    val type = m.optString("type", catalog.type).ifBlank { catalog.type }
                    items.add(Item(id, type, name, m.optString("poster").takeIf { it.startsWith("http") }, m.optString("background").takeIf { it.startsWith("http") }, catalog.platformLogo))
                }
                val unique = items.distinctBy { it.type.lowercase() + ":" + it.id }.take(49)
                val firstNative = readNativeContinueWatching().firstOrNull()
                val old = existingIds()
                val wantedIds = unique.map { relayId(it) }.toSet() + setOfNotNull(firstNative?.let { pinnedNativeInternalId })
                (old - wantedIds).forEach { deleteRelay(it) }
                firstNative?.let { publish(it, pinnedFirst = true) }
                val count = unique.count { publish(it) } + if (firstNative != null) 1 else 0
                runOnUiThread { message("Continue Watching refreshed with " + count + " titles from " + catalog.name + ".") }
            } catch (e: Exception) {
                runOnUiThread { message("Rotation failed for " + catalog.name + ": " + (e.message ?: "network error")) }
            }
        }
    }

    private fun readNativeContinueWatching(): List<Item> {
        val out = mutableListOf<Item>()
        val projection = arrayOf(
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )
        try {
            contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, projection, null, null,
                TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")?.use { c ->
                val titleIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE)
                val intentIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI)
                val posterIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI)
                val internalIx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                while (c.moveToNext()) {
                    val internal = if (internalIx >= 0) c.getString(internalIx).orEmpty() else ""
                    val title = if (titleIx >= 0) c.getString(titleIx).orEmpty() else ""
                    val rawIntent = if (intentIx >= 0) c.getString(intentIx).orEmpty() else ""
                    if (internal.startsWith("nuvio_relay_")) continue
                    if (title.isBlank() || rawIntent.isBlank()) continue
                    val deepLink = runCatching { Intent.parseUri(rawIntent, Intent.URI_INTENT_SCHEME).data }.getOrNull()
                        ?: runCatching { Uri.parse(rawIntent) }.getOrNull()
                    if (deepLink?.scheme != "nuvio" || deepLink.host != "meta") continue
                    val id = deepLink.getQueryParameter("id") ?: continue
                    val type = deepLink.getQueryParameter("type") ?: "movie"
                    val poster = if (posterIx >= 0) c.getString(posterIx)?.takeIf { it.startsWith("http") } else null
                    out.add(Item(id, type, title, poster, poster, null))
                }
            }
        } catch (_: Exception) { }
        return out.distinctBy { it.type.lowercase() + ":" + it.id }
    }

    private fun loadPoster(url: String, view: ImageView) {
        worker.execute {
            try {
                val cached = PosterCacheProvider.uriFor(this, highestResolutionImage(url) ?: url)
                val bitmap = cached?.let { contentResolver.openInputStream(it)?.use { stream -> android.graphics.BitmapFactory.decodeStream(stream) } }
                if (bitmap != null) runOnUiThread { view.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }
    }

    private fun openInNuvio(item: Item) {
        try {
            val uri = Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", item.type.lowercase()).appendQueryParameter("id", item.id).build()
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.nuvioreshaped.tv"))
        } catch (_: Exception) { message("Could not open this title in Nuvio Re-Shaped.") }
    }

    private fun sync() {
        val chosen = catalogs.filter { it.key in selected }.distinctBy { it.key }
        if (chosen.isEmpty()) { message("Select at least one catalog."); return }
        message("Syncing selected catalogs…")
        worker.execute {
            try {
                val all = linkedMapOf<String, Item>()
                val firstNative = readNativeContinueWatching().firstOrNull()
                for (c in chosen) {
                    if (c.key == nativeContinueCatalog().key) {
                        readNativeContinueWatching().forEach { item -> all.putIfAbsent(item.type.lowercase() + ":" + item.id, item) }
                        continue
                    }
                    val rootUrl = c.addon.removeSuffix("/manifest.json").trimEnd('/')
                    val url = rootUrl + "/catalog/" + enc(c.type) + "/" + enc(c.id) + ".json?skip=0&limit=100"
                    val metas = json(url).optJSONArray("metas") ?: JSONArray()
                    for (i in 0 until metas.length()) {
                        val m = metas.optJSONObject(i) ?: continue
                        val id = m.optString("id"); val name = m.optString("name")
                        if (id.isBlank() || name.isBlank()) continue
                        val type = m.optString("type", c.type).ifBlank { c.type }
                        val item = Item(id, type, name,
                            m.optString("poster").takeIf { it.startsWith("http") },
                            m.optString("background").takeIf { it.startsWith("http") }, c.platformLogo,
                            m.optString("logo").takeIf { it.startsWith("http") }, ratingLabel(m))
                        all.putIfAbsent(type.lowercase() + ":" + id, item)
                    }
                }
                val wanted = all.values.filterNot { firstNative != null && it.type.equals(firstNative.type, true) && it.id == firstNative.id }.take(if (firstNative != null) 49 else 50)
                val existing = existingIds()
                val wantedIds = wanted.map { relayId(it) }.toSet() + setOfNotNull(firstNative?.let { pinnedNativeInternalId })
                (existing - wantedIds).forEach { deleteRelay(it) }
                var count = 0
                wanted.forEach { if (publish(it)) count++ }
                firstNative?.let { if (publish(it, pinnedFirst = true)) count++ }
                prefs.edit().putLong("last_successful_sync", System.currentTimeMillis()).apply()
                runOnUiThread { updateLastSyncLabel(); message("Published " + count + " unique titles from " + chosen.size + " catalogs.") }
            } catch (e: Exception) {
                prefs.edit().putString("sync_status", "failed").putString("sync_error", e.message ?: e.javaClass.simpleName).apply()
                runOnUiThread { updateLastSyncLabel(); message("Sync failed: " + (e.message ?: "unknown error")) }
            }
        }
    }

    private fun publish(item: Item, pinnedFirst: Boolean = false): Boolean {
        val programType = if (item.type.equals("movie", true)) TvContractCompat.WatchNextPrograms.TYPE_MOVIE else TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        val internalId = if (pinnedFirst) pinnedNativeInternalId else relayId(item)
        val uri = Uri.Builder().scheme("nuvio").authority("meta")
            .appendQueryParameter("type", item.type.lowercase()).appendQueryParameter("id", item.id).build()
        // Force a fresh Nuvio task for each launcher selection; otherwise an already-open
        // MainActivity can keep displaying the previous movie and ignore the new deep link.
        val target = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.nuvioreshaped.tv")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val launcherIntentUri = Uri.parse(target.toUri(Intent.URI_INTENT_SCHEME))
        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST)
            .setTitle(item.name)
            .setLastEngagementTimeUtcMillis(if (pinnedFirst) System.currentTimeMillis() + 86_400_000L else System.currentTimeMillis() - 60_000L)
            .setInternalProviderId(internalId)
            // Store the full Android intent URI so Google TV launches the exact nuvio:// deep link.
            .setIntentUri(launcherIntentUri)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        val background = highestResolutionImage(item.background) ?: highestResolutionImage(item.poster)
        PosterCacheProvider.heroUriFor(this, background, highestResolutionImage(item.titleLogo), item.rating)
            ?.let { builder.setPosterArtUri(it) }
        item.platformLogo?.let { builder.setLogoUri(Uri.parse(it)) }
        return try {
            val values = builder.build().toContentValues()
            val existing = findId(internalId)
            if (existing == null) {
                contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) != null
            } else {
                // Recreate the launcher row so Google TV cannot retain a stale cached intent URI.
                contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null)
                contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) != null
            }
        } catch (_: Exception) { false }
    }

    private fun existingIds(): Set<String> {
        val out = mutableSetOf<String>()
        try { contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            val i = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) c.getString(i)?.takeIf { it.startsWith("nuvio_relay_") }?.let(out::add)
        } } catch (_: Exception) { }
        return out
    }

    private fun findId(internalId: String): Long? {
        try { contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            val pk = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
            val key = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) if (c.getString(key) == internalId) return c.getLong(pk)
        } } catch (_: Exception) { }
        return null
    }

    private fun deleteRelay(key: String): Boolean {
        val id = findId(key) ?: return false
        return try { contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) > 0 } catch (_: Exception) { false }
    }
    private fun clearRelay() {
        worker.execute {
            val keys = existingIds(); var count = 0
            keys.forEach { if (deleteRelay(it)) count++ }
            runOnUiThread { message("Removed " + count + " Relay items.") }
        }
    }
    private fun highestResolutionImage(raw: String?): String? {
        if (raw.isNullOrBlank() || !raw.startsWith("http")) return null
        // TMDB image paths support original-size assets; leave other providers' URLs untouched.
        return Regex("(/t/p/)(w45|w92|w154|w185|w300|w342|w500|w780|w1280)(/)").replace(raw) { match ->
            match.groupValues[1] + "original" + match.groupValues[3]
        }
    }
    private fun ratingLabel(m: JSONObject): String {
        val imdb = m.optString("imdbRating").trim()
        if (imdb.matches(Regex("[0-9](\\.[0-9]{1,2})?")) && (imdb.toDoubleOrNull()?.let { it in 0.0..10.0 } == true)) return "IMDb ★ $imdb"
        val generic = m.optString("rating").trim()
        if (generic.matches(Regex("[0-9](\\.[0-9]{1,2})?")) && (generic.toDoubleOrNull()?.let { it in 0.0..10.0 } == true)) return "★ $generic"
        val votes = m.optDouble("vote_average", -1.0)
        if (votes in 0.0..10.0) return "TMDB ★ " + String.format(java.util.Locale.US, "%.1f", votes)
        return ""
    }
    private fun relayId(item: Item) = "nuvio_relay_" + item.type.lowercase() + "_" + item.id
    private fun normalize(raw: String): String {
        val s = raw.trim()
        // Repair malformed add-on URLs where the query was accidentally appended before /manifest.json,
        // e.g. /nuvio?bcv=4/manifest.json -> /nuvio/manifest.json?bcv=4.
        val parsed = runCatching { Uri.parse(s) }.getOrNull() ?: return if (s.endsWith("/manifest.json")) s else s.trimEnd('/') + "/manifest.json"
        val query = parsed.encodedQuery
        if (!query.isNullOrBlank() && query.endsWith("/manifest.json")) {
            return parsed.buildUpon().encodedPath(parsed.encodedPath?.trimEnd('/') + "/manifest.json")
                .encodedQuery(query.removeSuffix("/manifest.json")).build().toString()
        }
        return if (s.endsWith("/manifest.json") || s.substringBefore('?').endsWith("/manifest.json")) s else s.trimEnd('/') + "/manifest.json"
    }
    private fun jsonArray(url: String): JSONArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 1800; conn.readTimeout = 2500
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode)
            return JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
    private fun json(url: String, forceNetwork: Boolean = false): JSONObject {
        val cacheDir = File(cacheDir, "api-cache").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, key + ".json")
        val isCatalog = url.contains("/catalog/")
        val ttl = if (isCatalog) 15 * 60 * 1000L else 24 * 60 * 60 * 1000L
        if (!forceNetwork && file.exists() && System.currentTimeMillis() - file.lastModified() < ttl) {
            try { return JSONObject(file.readText()) } catch (_: Exception) { }
        }
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4500; conn.readTimeout = 6000
                conn.setRequestProperty("Accept", "application/json")
                try {
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode)
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = JSONObject(body)
                    runCatching { file.writeText(body) }
                    return parsed
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(250)
            }
        }
        if (file.exists()) try { return JSONObject(file.readText()) } catch (_: Exception) { }
        throw (lastError ?: IllegalStateException("Network request failed"))
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun button(caption: String, parent: LinearLayout = controls, action: () -> Unit) {
        val b = TextView(this).apply {
            text = caption
            textSize = 16f
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(58)
            isFocusable = true
            isClickable = true
            setTextColor(Color.rgb(232, 239, 250))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(Color.rgb(25, 34, 50), 13)
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(35, 91, 132) else Color.rgb(25, 34, 50), 13)
                if (v is TextView) v.setTextColor(if (focused) Color.WHITE else Color.rgb(232, 239, 250))
                v.scaleX = if (focused) 1.012f else 1f
                v.scaleY = if (focused) 1.012f else 1f
            }
            setOnClickListener {
                action()
            }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = radius * resources.displayMetrics.density
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = true
        typeface = android.graphics.Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) android.graphics.Typeface.NORMAL else android.graphics.Typeface.NORMAL)
        setPadding(0, 8, 0, 8)
    }
    private fun message(value: String) { if (::status.isInitialized) runOnUiThread { status.text = value } }
}
