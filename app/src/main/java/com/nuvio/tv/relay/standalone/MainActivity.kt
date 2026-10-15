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
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
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
    private lateinit var list: LinearLayout
    private var manualSyncButton: TextView? = null
    private var refreshAddonsButton: TextView? = null
    private var rotationButton: TextView? = null
    private var manualSyncState: TextView? = null
    private var refreshAddonsState: TextView? = null
    private var rotationState: TextView? = null
    private val focusChain = mutableListOf<View>()
    private var manualSyncRunning = false
    private var refreshingAddons = false
    private val catalogs = mutableListOf<Catalog>()
    private val selected = mutableSetOf<String>()
    private val addons = linkedSetOf<String>()
    private val previews = mutableMapOf<String, MutableList<Item>>()
    private val expanded = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("relay", Context.MODE_PRIVATE) }

    data class Catalog(val addon: String, val addonName: String, val type: String, val id: String, val name: String, val platformLogo: String?) {
        val key: String get() = addon + "|" + type + "|" + id
    }
    data class Item(val id: String, val type: String, val name: String, val poster: String?, val background: String?, val platformLogo: String?, val titleLogo: String? = null, val rating: String = "", val overview: String = "")
    private val pinnedNativeInternalId = "nuvio_relay_pinned_native_first"
    private fun nativeContinueCatalog() = Catalog("nuvio-native://continue-watching", "Nuvio Re-Shaped", "native", "continue-watching", "Nuvio Re-Shaped Continue Watching", null)
    private fun withNativeContinue(items: List<Catalog>): List<Catalog> = (items.filterNot { it.key == nativeContinueCatalog().key } + nativeContinueCatalog())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected", emptySet()) ?: emptySet())
        if (prefs.getStringSet("selected", emptySet()).isNullOrEmpty()) selected.add(nativeContinueCatalog().key)
        addons.addAll(prefs.getStringSet("addons", emptySet()) ?: emptySet())
        pageScroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(20))
        }
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
        val connectButton = button("Connect", action = { connectToNuvio() }).apply { id = View.generateViewId() }
        controls.addView(label("ADD-ON SETUP GUIDE", 14, Color.WHITE, true))
        controls.addView(label("1. Open Nuvio Re-Shaped on this TV.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("2. Go to Add-ons → Manage from phone (QR).", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("3. Keep the QR / phone-management screen open.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("4. Return here and select Connect.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("5. Catalogs will load and sync automatically.", 13, Color.rgb(205, 216, 232)))
        controls.addView(label("MANUAL CONTROLS", 13, Color.rgb(94, 190, 255), true))
        controls.addView(label("These buttons are manual confirmations. Existing automatic sync and rotation behavior remains enabled.", 13, Color.rgb(153, 170, 192)))
        manualSyncState = label("Status: Ready", 12, Color.rgb(153, 170, 192))
        manualSyncButton = button("Sync Now", action = { runManualSync() })
        controls.addView(manualSyncState)
        refreshAddonsState = label("Status: Waiting for refresh", 12, Color.rgb(153, 170, 192))
        refreshAddonsButton = button("Refresh Add-ons", action = { refreshAddonsManually() })
        controls.addView(refreshAddonsState)
        rotationOn = prefs.getBoolean("rotation_enabled", true)
        rotationState = label("Status: " + if (rotationOn) "ENABLED" else "DISABLED", 12,
            if (rotationOn) Color.rgb(105, 211, 176) else Color.rgb(153, 170, 192))
        rotationButton = button(rotationCaption(), action = { toggleRotation(); updateRotationControl("Rotation " + if (rotationOn) "enabled" else "disabled") })
        controls.addView(rotationState)
        controls.addView(label("OPEN TITLES IN", 13, Color.rgb(94, 190, 255), true))
        controls.addView(label("Choose which Nuvio app opens when you press Play on a Relay title in the launcher.", 13, Color.rgb(153, 170, 192)))
        val targetStatus = label("", 12, Color.rgb(153, 170, 192))
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.START
        }
        fun refreshTargetStatus() {
            targetStatus.text = "Selected: " + if (NuvioTarget.target(this) == NuvioTarget.LEGACY) "Nuvio Legacy" else "Nuvio Re-Shaped"
        }
        val reshapedTargetButton = button("Nuvio Re-Shaped", parent = targetRow, action = {
            prefs.edit().putString(NuvioTarget.PREF_KEY, NuvioTarget.RESHAPED).apply()
            refreshTargetStatus()
            Toast.makeText(this, "Play target set to Nuvio Re-Shaped", Toast.LENGTH_SHORT).show()
            runManualSync()
        })
        val legacyTargetButton = button("Nuvio Legacy", parent = targetRow, action = {
            prefs.edit().putString(NuvioTarget.PREF_KEY, NuvioTarget.LEGACY).apply()
            refreshTargetStatus()
            Toast.makeText(this, "Play target set to Nuvio Legacy", Toast.LENGTH_SHORT).show()
            runManualSync()
        })
        (reshapedTargetButton.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; rightMargin = dp(8) }
        (legacyTargetButton.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        controls.addView(targetRow)
        controls.addView(targetStatus)
        refreshTargetStatus()
        controls.addView(label("DESCRIPTION LANGUAGE", 13, Color.rgb(94, 190, 255), true))
        controls.addView(label("Choose the language used for the synopsis under the metadata in the Relay artwork.", 13, Color.rgb(153, 170, 192)))
        val descriptionLanguageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.START
        }
        val descriptionLanguageStatus = label("", 12, Color.rgb(153, 170, 192))
        fun updateDescriptionLanguageButtons() {
            val current = prefs.getString("description_language", "en") ?: "en"
            descriptionLanguageStatus.text = "Selected: " + if (current == "ar") "العربية" else "English"
        }
        val arabicDescriptionButton = button("العربية", parent = descriptionLanguageRow, action = {
            prefs.edit().putString("description_language", "ar").apply()
            updateDescriptionLanguageButtons()
            Toast.makeText(this, "Description language: Arabic. Syncing…", Toast.LENGTH_SHORT).show()
            runManualSync()
        })
        val englishDescriptionButton = button("English", parent = descriptionLanguageRow, action = {
            prefs.edit().putString("description_language", "en").apply()
            updateDescriptionLanguageButtons()
            Toast.makeText(this, "Description language: English. Syncing…", Toast.LENGTH_SHORT).show()
            runManualSync()
        })
        (arabicDescriptionButton.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; rightMargin = dp(8) }
        (englishDescriptionButton.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        controls.addView(descriptionLanguageRow)
        controls.addView(descriptionLanguageStatus)
        updateDescriptionLanguageButtons()
        var synopsisToggle: TextView? = null
        synopsisToggle = button("", action = {
            val enabled = !prefs.getBoolean("synopsis_enabled", true)
            prefs.edit().putBoolean("synopsis_enabled", enabled).commit()
            synopsisToggle?.text = "SYNOPSIS: " + (if (enabled) "ON" else "OFF") + "  ·  Press OK to change"
            Toast.makeText(this@MainActivity, if (enabled) "Synopsis ON · syncing titles" else "Synopsis OFF · syncing titles", Toast.LENGTH_SHORT).show()
            runManualSync()
        }).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // Render explicit ON/OFF state; unlike a CheckBox this is reliable with TV D-pad focus.
        synopsisToggle?.text = "SYNOPSIS: " + (if (prefs.getBoolean("synopsis_enabled", true)) "ON" else "OFF") + "  ·  Press OK to change"
        // button() already attaches synopsisToggle to controls; do not add it twice.
        controls.addView(label("TMDB ENRICHMENT", 13, Color.rgb(94, 190, 255), true))
        controls.addView(label("Add a TMDB API Read Access Token to enrich missing posters, backdrops, logos, ratings and release years. The token is stored locally on this device.", 13, Color.rgb(153, 170, 192)))
        val tmdbTokenInput = EditText(this).apply {
            id = View.generateViewId()
            hint = "TMDB API Read Access Token"
            setHintTextColor(Color.rgb(150, 166, 188))
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            minHeight = dp(58)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            showSoftInputOnFocus = true
            setSelectAllOnFocus(false)
            setText(prefs.getString("tmdb_read_token", ""))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.rgb(25, 34, 50), 10)
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) Color.rgb(35, 65, 91) else Color.rgb(25, 34, 50), 10)
            }
            setOnClickListener {
                requestFocus()
                post {
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    postDelayed({
                        requestFocus()
                        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                    }, 200)
                }
            }
        }
        controls.addView(tmdbTokenInput, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(8) })
        tmdbTokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(tmdbTokenInput.windowToken, 0)
                true
            } else false
        }
        val savedTmdbToken = prefs.getString("tmdb_read_token", "").orEmpty()
        val tmdbStatus = label(
            if (savedTmdbToken.isBlank()) "TMDB API token not added yet."
            else "TMDB API token saved; validity has not been checked in this session.",
            13,
            if (savedTmdbToken.isBlank()) Color.rgb(153, 170, 192) else Color.rgb(240, 180, 110)
        )
        val saveTmdbButton = button("Verify & Save TMDB Token", action = {
            val token = tmdbTokenInput.text?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                prefs.edit().remove("tmdb_read_token").apply()
                tmdbStatus.text = "TMDB token removed. Enrichment is disabled."
                tmdbStatus.setTextColor(Color.rgb(240, 180, 110))
                message("TMDB token removed. Enrichment is disabled.")
            } else {
                tmdbStatus.text = "Checking token with TMDB…"
                tmdbStatus.setTextColor(Color.rgb(240, 200, 110))
                tmdbTokenInput.isEnabled = false
                Toast.makeText(this, "Checking TMDB token…", Toast.LENGTH_SHORT).show()
                worker.execute {
                    var valid = false
                    var httpCode = -1
                    var errorMessage: String? = null
                    var connection: HttpURLConnection? = null
                    try {
                        connection = (URL("https://api.themoviedb.org/3/authentication").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 7000
                            readTimeout = 7000
                            requestMethod = "GET"
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                        httpCode = connection.responseCode
                        if (httpCode in 200..299) {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(body)
                            valid = json.optInt("status_code", -1) == 1 || json.optBoolean("success", false)
                            if (!valid) errorMessage = "TMDB did not confirm this token."
                        } else {
                            errorMessage = when (httpCode) {
                                401, 403 -> "TMDB rejected this token. It is invalid or revoked."
                                else -> "TMDB returned HTTP $httpCode. Token validity could not be confirmed."
                            }
                        }
                    } catch (_: Exception) {
                        errorMessage = "Could not reach TMDB. Check the internet connection and try again."
                    } finally {
                        connection?.disconnect()
                    }
                    runOnUiThread {
                        tmdbTokenInput.isEnabled = true
                        if (valid) {
                            prefs.edit().putString("tmdb_read_token", token).apply()
                            tmdbStatus.text = "VALID: TMDB accepted the token. It has been saved on this device."
                            tmdbStatus.setTextColor(Color.rgb(105, 211, 176))
                            Toast.makeText(this, "TMDB token is valid and saved", Toast.LENGTH_LONG).show()
                        } else {
                            tmdbStatus.text = errorMessage ?: "Could not verify token."
                            tmdbStatus.setTextColor(Color.rgb(255, 130, 120))
                            Toast.makeText(this, if (httpCode == 401 || httpCode == 403) "TMDB token is invalid" else "TMDB token verification failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }).apply { id = View.generateViewId() }
        controls.addView(tmdbStatus)
        // Main actions first; render() appends the live catalog checkboxes so D-pad
        // can continue into the catalog library instead of getting trapped at Save.
        focusChain.clear()
        focusChain.addAll(listOfNotNull(connectButton, manualSyncButton, refreshAddonsButton, rotationButton, reshapedTargetButton, legacyTargetButton, arabicDescriptionButton, englishDescriptionButton, synopsisToggle, tmdbTokenInput, saveTmdbButton))
        configureFocusChain()
        connectButton.nextFocusDownId = manualSyncButton?.id ?: connectButton.id
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
        // Start at the top of the action chain. Focusing the token field on launch
        // trapped DOWN navigation below the manual controls on TV remotes.
        connectButton.post {
            pageScroll.scrollTo(0, 0)
            connectButton.requestFocus()
        }
        refresh()
        if (prefs.getBoolean("auto_sync", true)) {
            val service = Intent(this, RelaySyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
        }
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
                        .putBoolean("rotate_all_catalogs", true)
                        .apply()
                    render()
                    message("Connected. Imported " + importedCount + " add-ons and " + loadedCatalogs.size + " catalogs. Sync and rotation start automatically.")
                    val syncService = Intent(this@MainActivity, RelaySyncService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(syncService) else startService(syncService)
                    if (selected.isNotEmpty()) {
                        // The background service now owns automatic publishing. Do not launch
                        // a second catalog fetch/publish pass from the Activity on Connect.
                        rotationOn = prefs.getBoolean("rotation_enabled", true)
                        rotationIndex = 0
                        updateRotationControl("Rotation " + if (rotationOn) "enabled" else "disabled")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { message("Import failed: " + (e.message ?: "unknown error")) }
            }
        }
    }

    private fun refresh() {
        if (addons.isEmpty()) { render(); message("Connect to Nuvio Re-Shaped or add a manifest URL."); return }
        worker.execute {
            val urls = addons.toList()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val loaded = java.util.Collections.synchronizedList(mutableListOf<Catalog>())
            try {
                val tasks = urls.map { url -> pool.submit {
                    try {
                        val m = json(url)
                        loaded.addAll(parseCatalogs(url, m.optString("name", Uri.parse(url).host ?: "Add-on"), m))
                    } catch (_: Exception) { }
                } }
                tasks.forEach { runCatching { it.get() } }
            } finally { pool.shutdownNow() }
            runOnUiThread {
                catalogs.clear(); catalogs.addAll(withNativeContinue(loaded)); previews.clear(); expanded.clear(); render()
                message("Loaded " + loaded.size + " catalogs.")
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
        if (catalogs.isEmpty()) {
            list.addView(label("No catalogs loaded yet.", 15, Color.LTGRAY))
            configureFocusChain()
            return
        }
        val catalogFocusViews = mutableListOf<View>()
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
                tag = "catalog-checkbox"
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
            catalogFocusViews.add(cb)
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
        focusChain.removeAll { it is CheckBox && it.tag == "catalog-checkbox" }
        focusChain.addAll(catalogFocusViews)
        configureFocusChain()
    }

    private fun configureFocusChain() {
        val chain = focusChain.toList()
        chain.forEachIndexed { index, view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.nextFocusDownId = chain.getOrNull(index + 1)?.id ?: view.id
            view.nextFocusUpId = chain.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusForwardId = chain.getOrNull(index + 1)?.id ?: view.id
            // Keep the button helper's existing focus listener: it paints the visible
            // blue focus state. Only catalog checkboxes need custom scroll-into-view.
            if (view is CheckBox) view.setOnFocusChangeListener { focusedView, hasFocus ->
                if (hasFocus) focusedView.postDelayed({
                    if (!focusedView.isFocused || !::pageScroll.isInitialized) return@postDelayed
                    val rect = android.graphics.Rect(0, 0, focusedView.width, focusedView.height)
                    pageScroll.offsetDescendantRectToMyCoords(focusedView, rect)
                    val viewportTop = pageScroll.scrollY + pageScroll.paddingTop + dp(10)
                    val viewportBottom = pageScroll.scrollY + pageScroll.height - pageScroll.paddingBottom - dp(10)
                    when {
                        rect.top < viewportTop -> pageScroll.smoothScrollTo(0, (rect.top - pageScroll.paddingTop - dp(10)).coerceAtLeast(0))
                        rect.bottom > viewportBottom -> pageScroll.smoothScrollTo(0, (rect.bottom - pageScroll.height + pageScroll.paddingBottom + dp(10)).coerceAtLeast(0))
                    }
                }, 70)
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> chain.getOrNull(index + 1)?.let { it.requestFocus(); true } ?: true
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> chain.getOrNull(index - 1)?.let { it.requestFocus(); true } ?: true
                    else -> false
                }
            }
        }
    }

    private fun rotationCaption(): String = if (rotationOn) "Rotation  ·  ENABLED (tap to stop)" else "Rotation  ·  DISABLED (tap to start)"

    private fun updateRotationControl(note: String? = null) {
        rotationButton?.text = rotationCaption()
        rotationState?.text = "Status: " + (if (rotationOn) "ENABLED" else "DISABLED") + (note?.let { " · $it" } ?: "")
        rotationState?.setTextColor(if (rotationOn) Color.rgb(105, 211, 176) else Color.rgb(153, 170, 192))
    }

    private fun runManualSync() {
        if (manualSyncRunning) { message("Sync is already running."); return }
        manualSyncRunning = true
        manualSyncButton?.text = "Sync Now  ·  RUNNING…"
        manualSyncState?.text = "Status: Sync in progress…"
        manualSyncState?.setTextColor(Color.rgb(240, 200, 110))
        val chosen = catalogs.filter { it.key in selected }.distinctBy { it.key }
        if (chosen.isEmpty()) {
            manualSyncRunning = false
            manualSyncButton?.text = "Sync Now"
            manualSyncState?.text = "Status: No selected catalogs"
            manualSyncState?.setTextColor(Color.rgb(255, 130, 120))
            message("No catalogs selected. Select catalogs before syncing.")
            return
        }
        sync()
        // sync() owns the worker; this guard is released after its status is reported.
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val labelText = manualSyncState?.text?.toString().orEmpty()
                if (labelText.contains("SUCCESS") || labelText.contains("FAILED")) manualSyncRunning = false
                else mainHandler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun refreshAddonsManually() {
        if (refreshingAddons) { message("Add-on refresh is already running."); return }
        if (addons.isEmpty()) { refreshAddonsState?.text = "Status: No add-ons connected"; message("Connect to Nuvio Re-Shaped first."); return }
        refreshingAddons = true
        refreshAddonsButton?.text = "Refresh Add-ons  ·  RUNNING…"
        refreshAddonsState?.text = "Status: Fetching latest manifests…"
        refreshAddonsState?.setTextColor(Color.rgb(240, 200, 110))
        worker.execute {
            val loaded = java.util.Collections.synchronizedList(mutableListOf<Catalog>())
            val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, addons.size.coerceAtLeast(1)))
            val tasks = addons.toList().map { manifestUrl -> pool.submit {
                try {
                    val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000; readTimeout = 8000
                        setRequestProperty("Accept", "application/json")
                    }
                    val manifest = try {
                        if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                    loaded.addAll(parseCatalogs(manifestUrl, manifest.optString("name", Uri.parse(manifestUrl).host ?: "Add-on"), manifest))
                } catch (e: Exception) { failed.add((Uri.parse(manifestUrl).host ?: "Add-on") + ": " + (e.message ?: "network error")) }
            } }
            try { tasks.forEach { it.get() } } finally { pool.shutdownNow() }
            val succeeded = addons.size - failed.size
            runOnUiThread {
                refreshingAddons = false
                refreshAddonsButton?.text = if (failed.isEmpty()) "Refresh Add-ons  ·  DONE" else "Refresh Add-ons  ·  RETRY"
                if (succeeded > 0) {
                    val previousSelection = selected.toSet()
                    catalogs.clear(); catalogs.addAll(withNativeContinue(loaded.distinctBy { it.key }))
                    selected.clear(); selected.addAll(catalogs.map { it.key }.filter { it in previousSelection })
                    if (selected.isEmpty() && catalogs.isNotEmpty()) selected.add(nativeContinueCatalog().key)
                    prefs.edit().putStringSet("selected", selected.toSet()).putStringSet("addons", addons.toSet()).apply()
                    previews.clear(); expanded.clear(); render()
                }
                val result = "Add-ons refreshed: $succeeded/${addons.size} manifests; ${loaded.distinctBy { it.key }.size} catalogs."
                refreshAddonsState?.text = if (failed.isEmpty()) "Status: SUCCESS · $succeeded add-ons refreshed" else "Status: PARTIAL · " + failed.size + " failed"
                refreshAddonsState?.setTextColor(if (failed.isEmpty()) Color.rgb(105, 211, 176) else Color.rgb(240, 200, 110))
                message(result + if (failed.isEmpty()) "" else " Details: " + failed.joinToString("; "))
            }
        }
    }

    private fun toggleRotation() {
        rotationOn = !rotationOn
        prefs.edit().putBoolean("rotation_enabled", rotationOn).apply()
        rotationIndex = 0
        updateRotationControl("Rotation " + if (rotationOn) "enabled" else "disabled")
        message(if (rotationOn) "Catalog rotation enabled. The background service owns launcher publishing." else "Catalog rotation paused. The current launcher catalog will continue to refresh.")
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

    private fun sync() {
        val chosen = catalogs.filter { it.key in selected }.distinctBy { it.key }
        if (chosen.isEmpty()) { message("No catalogs selected. Nothing was synced."); return }
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
                        // Reuse already-enriched TMDB cache entries. Otherwise manual sync
                        // used to overwrite a TMDB-updated card with raw add-on artwork/metadata.
                        val cached = cachedTmdb(m, type)
                        val item = Item(id, type, name,
                            firstHttp(cached?.optString("poster"), m.optString("poster")),
                            firstHttp(cached?.optString("background"), m.optString("background"), m.optString("poster")),
                            c.platformLogo,
                            firstHttp(cached?.optString("titleLogo"), m.optString("logo")),
                            ratingLabel(m, cached),
                            firstHttp(cached?.optString("overview"), m.optString("overview")).orEmpty())
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
                runOnUiThread {
                    val result = "Sync completed: published $count titles from ${chosen.size} catalogs."
                    manualSyncState?.text = "Status: SUCCESS · $count titles published"
                    manualSyncState?.setTextColor(Color.rgb(105, 211, 176))
                    manualSyncButton?.text = "Sync Now  ·  DONE"
                    message(result)
                }
            } catch (e: Exception) { runOnUiThread {
                manualSyncState?.text = "Status: FAILED · ${e.message ?: "network error"}"
                manualSyncState?.setTextColor(Color.rgb(255, 130, 120))
                manualSyncButton?.text = "Sync Now  ·  RETRY"
                message("Sync failed: " + (e.message ?: "unknown error"))
            } }
        }
    }

    private fun publish(item: Item, pinnedFirst: Boolean = false): Boolean {
        val programType = if (item.type.equals("movie", true)) TvContractCompat.WatchNextPrograms.TYPE_MOVIE else TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        val internalId = if (pinnedFirst) pinnedNativeInternalId else relayId(item)
        // Store the chosen Nuvio package and compatible deep link in the launcher row.
        val target = NuvioTarget.intent(this, item.type, item.id)
        val launcherIntentUri = Uri.parse(target.toUri(Intent.URI_INTENT_SCHEME))
        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST)
            .setTitle(if (pinnedFirst) item.name else "")
            .setLastEngagementTimeUtcMillis(if (pinnedFirst) System.currentTimeMillis() + 86_400_000L else System.currentTimeMillis() - 60_000L)
            .setInternalProviderId(internalId)
            // Store the full Android intent URI so Google TV launches the exact nuvio:// deep link.
            .setIntentUri(launcherIntentUri)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        val background = highestResolutionImage(item.background) ?: highestResolutionImage(item.poster)
        PosterCacheProvider.heroUriFor(this, background, highestResolutionImage(item.titleLogo), item.rating,
            item.platformLogo, null, item.overview,
            if (prefs.getString("description_language", "en") == "ar") "ar" else "en")
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
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun highestResolutionImage(raw: String?): String? {
        if (raw.isNullOrBlank() || !raw.startsWith("http")) return null
        // TMDB image paths support original-size assets; leave other providers' URLs untouched.
        return Regex("(/t/p/)(w45|w92|w154|w185|w300|w342|w500|w780|w1280)(/)").replace(raw) { match ->
            match.groupValues[1] + "original" + match.groupValues[3]
        }
    }
    private fun cachedTmdb(source: JSONObject, type: String): JSONObject? {
        val title = sequenceOf("name", "title", "original_name", "original_title")
            .map { source.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        if (title.isBlank()) return null
        fun norm(value: String) = value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()
        val year = sequenceOf("year", "releaseInfo", "released", "release_date", "first_air_date")
            .map { source.optString(it) }.firstOrNull { Regex("(19|20)\\d{2}").containsMatchIn(it) }
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value }.orEmpty()
        val imdbId = sequenceOf("imdb_id", "imdbId", "imdbid", "id").map { source.optString(it).trim() }
            .firstOrNull { it.matches(Regex("tt\\d{7,10}")) }.orEmpty()
        val tmdbId = sequenceOf("tmdb_id", "tmdbId", "tmdbid", "tmdb")
            .map { source.optString(it).trim().removePrefix("tmdb:").removePrefix("tmdb_") }
            .firstOrNull { it.matches(Regex("\\d{1,10}")) && (it.toIntOrNull() ?: 0) > 0 }.orEmpty()
        val keyInput = "${type.lowercase(java.util.Locale.ROOT)}|${norm(title)}|$year|$imdbId|$tmdbId"
        val key = MessageDigest.getInstance("SHA-256").digest(keyInput.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "tmdb-cache/$key.json")
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age < 0 || age > 30L * 24 * 60 * 60 * 1000) return null
        return runCatching { JSONObject(file.readText()).takeUnless { it.optBoolean("_no_match") } }.getOrNull()
    }

    private fun firstHttp(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }

    private fun ratingLabel(m: JSONObject, cached: JSONObject? = null): String {
        // Keep the current Apple-TV-style hero metadata format on every publication path.
        // Older code returned only "IMDb ★ 6.4", which made manual sync/rotation revert
        // the artwork to the legacy single-line metadata layout.
        fun score(raw: String): String? = raw.trim().takeIf {
            it.matches(Regex("[0-9](\\.[0-9]{1,2})?")) && (it.toDoubleOrNull()?.let { n -> n in 0.0..10.0 } == true)
        }
        val sourceScore = score(m.optString("imdbRating")) ?: score(m.optString("rating"))
        val cachedScore = cached?.optDouble("vote_average", -1.0) ?: -1.0
        val sourceVotes = m.optDouble("vote_average", -1.0)
        val rating = sourceScore
            ?: cachedScore.takeIf { it in 0.0..10.0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }
            ?: sourceVotes.takeIf { it in 0.0..10.0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }
        val yearSource = sequenceOf(
            cached?.optString("year").orEmpty(), m.optString("year"), m.optString("releaseInfo"),
            m.optString("released"), m.optString("release_date"), m.optString("first_air_date")
        ).firstOrNull { Regex("(19|20)\\d{2}").containsMatchIn(it) }
        val year = yearSource?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        val isSeries = sequenceOf(m.optString("type"), cached?.optString("type").orEmpty())
            .any { it.equals("series", true) || it.equals("tv", true) || it.equals("show", true) }
        val genreNames = mutableListOf<String>()
        fun collectGenres(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                val name = (if (item is JSONObject) item.optString("name") else item?.toString().orEmpty()).trim()
                if (name.isNotBlank() && !name.equals("null", true) && genreNames.size < 2 && name !in genreNames) genreNames.add(name)
            }
        }
        collectGenres(cached?.optJSONArray("genres"))
        if (genreNames.isEmpty()) collectGenres(m.optJSONArray("genres"))
        val details = mutableListOf(if (isSeries) "Series" else "Movie")
        details.addAll(genreNames)
        val badge = listOfNotNull(rating?.let { "★ $it" }, year).joinToString("  ·  ")
        return details.joinToString("  ·  ") + if (badge.isNotBlank()) " ||| $badge" else ""
    }
    private fun relayId(item: Item) = "nuvio_relay_" + item.type.lowercase() + "_" + item.id
    private fun normalize(raw: String): String {
        val s = raw.trim().trimEnd('/')
        return if (s.endsWith("/manifest.json")) s else s + "/manifest.json"
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
    private fun json(url: String): JSONObject {
        val cacheDir = File(cacheDir, "api-cache").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, key + ".json")
        val isCatalog = url.contains("/catalog/")
        val ttl = if (isCatalog) 15 * 60 * 1000L else 24 * 60 * 60 * 1000L
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < ttl) {
            try { return JSONObject(file.readText()) } catch (_: Exception) { }
        }
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = JSONObject(body)
                runCatching { file.writeText(body) }
                return parsed
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            if (file.exists()) try { return JSONObject(file.readText()) } catch (_: Exception) { }
            throw e
        }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun button(caption: String, parent: LinearLayout = controls, action: () -> Unit): TextView {
        val b = TextView(this).apply {
            id = View.generateViewId()
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
        return b
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
