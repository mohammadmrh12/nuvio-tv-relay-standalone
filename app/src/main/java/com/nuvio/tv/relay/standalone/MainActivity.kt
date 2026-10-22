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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rotationOn = true
    private var rotationSeconds = 15
    private var rotationIndex = 0
    private val sectionViews = linkedMapOf<String, View>()
    private lateinit var status: TextView
    private lateinit var selectionSummary: TextView
    private var selectAllButton: TextView? = null
    private var selectNoneButton: TextView? = null
    private lateinit var list: LinearLayout
    private var manualSyncButton: TextView? = null
    private var refreshAddonsButton: TextView? = null
    private var rotationButton: TextView? = null
    private var manualSyncState: TextView? = null
    private var refreshAddonsState: TextView? = null
    private var rotationState: TextView? = null
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

    private val tabCaptions = listOf("Home", "Catalogs", "Settings", "Updates")
    private lateinit var pagesHost: android.widget.FrameLayout
    private val pages = mutableListOf<View>()
    private val tabViews = mutableListOf<LinearLayout>()
    private var currentTab = 0
    private var connectButtonRef: TextView? = null
    private var homeAddons: TextView? = null
    private var homeCatalogs: TextView? = null
    private var homeTarget: TextView? = null
    private var updateBanner: TextView? = null
    private var updCurrent: TextView? = null
    private var updLatest: TextView? = null
    private var updNotes: TextView? = null
    private var updStatus: TextView? = null
    private var updInstallButton: TextView? = null
    private var updCheckButton: TextView? = null
    private var targetStatusRef: TextView? = null
    private var firstSettingsRow: LinearLayout? = null
    private var phoneDialog: android.app.Dialog? = null
    private var phoneServer: PhoneAddonServer? = null
    private var phoneDialogStatus: TextView? = null
    private var phoneAddButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected", emptySet()) ?: emptySet())
        if (prefs.getStringSet("selected", emptySet()).isNullOrEmpty()) selected.add(nativeContinueCatalog().key)
        addons.addAll(prefs.getStringSet("addons", emptySet()) ?: emptySet())
        window.decorView.setBackgroundColor(Ui.BG)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        setContentView(root)
        root.addView(buildTopBar(), lp(-1, -2))
        pagesHost = android.widget.FrameLayout(this)
        root.addView(pagesHost, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("Ready · waiting for connection", 13, Ui.TEXT2).apply {
            setPadding(dp(56), dp(10), dp(56), dp(16))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(status, lp(-1, -2))
        addPage(buildHomePage())
        addPage(buildCatalogsPage())
        addPage(buildSettingsPage())
        addPage(buildUpdatesPage())
        rewireHomeFocus()
        wireTabFocus(1, listOfNotNull(selectAllButton, selectNoneButton))
        wireTabFocus(2, firstSettingsRow?.let { row -> (0 until row.childCount).map { row.getChildAt(it) } } ?: emptyList())
        wireTabFocus(3, listOfNotNull(updCheckButton, updInstallButton))
        showTab(0)
        refresh()
        connectButtonRef?.post { connectButtonRef?.requestFocus() }
        if (prefs.getBoolean("auto_sync", true)) {
            val service = Intent(this, RelaySyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
        }
        updateHome()
        refreshUpdatesPage()
    }

    // ---------- layout: top bar, tabs, pages ----------

    private fun addPage(page: View) {
        pagesHost.addView(page, android.widget.FrameLayout.LayoutParams(-1, -1))
        pages.add(page)
    }

    private fun newPage(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isSmoothScrollingEnabled = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(8), dp(56), dp(28))
        }
        scroll.addView(column)
        return scroll to column
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(22), dp(56), dp(10))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("Nuvio Relay", 24, Ui.TEXT1, true).apply { setPadding(0, 0, 0, 0) })
        brand.addView(label("v" + AppUpdater.currentVersion(this), 11, Ui.TEXT3).apply { setPadding(0, dp(2), 0, 0) })
        bar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        tabCaptions.forEachIndexed { index, caption ->
            val tab = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isClickable = true
                setPadding(dp(18), dp(8), dp(18), dp(6))
            }
            tab.addView(TextView(this).apply {
                text = caption
                textSize = 16f
                maxLines = 1
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-2, -2))
            tab.addView(View(this), LinearLayout.LayoutParams(dp(24), dp(3)).apply { topMargin = dp(6) })
            tab.setOnClickListener { showTab(index) }
            tab.setOnFocusChangeListener { _, focused -> if (focused) showTab(index) else styleTabs() }
            tabViews.add(tab)
            bar.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
        return bar
    }

    private fun styleTabs() {
        tabViews.forEachIndexed { index, tab ->
            val active = index == currentTab
            val label = tab.getChildAt(0) as TextView
            label.setTextColor(if (active || tab.isFocused) Ui.TEXT1 else Ui.TEXT3)
            (tab.getChildAt(1)).background = rounded(if (active) Ui.ACCENT else Color.TRANSPARENT, 2)
            tab.background = rounded(if (tab.isFocused) Ui.SURFACE2 else Color.TRANSPARENT, 12)
        }
    }

    /** Pressing UP on a page's first controls returns to that page's own tab instead of jumping to a neighbouring tab. */
    private fun wireTabFocus(index: Int, firstControls: List<View>) {
        val tab = tabViews.getOrNull(index) ?: return
        firstControls.forEach { v ->
            if (v.id == View.NO_ID) v.id = View.generateViewId()
            v.nextFocusUpId = tab.id
        }
        firstControls.firstOrNull()?.let { tab.nextFocusDownId = it.id }
    }

    /** Home's first row is the update banner when it is shown, otherwise the Connect / Add from phone / Sync now buttons. */
    private fun rewireHomeFocus() {
        val tab = tabViews.getOrNull(0) ?: return
        val banner = updateBanner
        val bannerShown = banner != null && banner.visibility == View.VISIBLE
        val controls = listOfNotNull(connectButtonRef, phoneAddButton, manualSyncButton)
        controls.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        val up = if (bannerShown && banner != null) banner.id else tab.id
        controls.forEach { it.nextFocusUpId = up }
        banner?.nextFocusUpId = tab.id
        banner?.nextFocusDownId = connectButtonRef?.id ?: View.NO_ID
        tab.nextFocusDownId = if (bannerShown && banner != null) banner.id else (connectButtonRef?.id ?: View.NO_ID)
    }

    private fun showTab(index: Int) {
        currentTab = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        styleTabs()
        if (index == 0) updateHome()
        if (index == 3) refreshUpdatesPage()
    }

    // ---------- Home ----------

    private fun buildHomePage(): View {
        val (scroll, col) = newPage()
        col.addView(label("Your Nuvio catalogs on the Google TV home screen.", 16, Ui.TEXT2).apply { setPadding(0, dp(4), 0, dp(18)) })
        val banner = TextView(this).apply {
            id = View.generateViewId()
            textSize = 14f
            setTextColor(Ui.ON_ACCENT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(Ui.ACCENT, 12)
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener { showTab(3) }
        }
        updateBanner = banner
        col.addView(banner, lp(-1, -2, bottom = 14))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = rounded(Ui.SURFACE, 16)
        }
        homeAddons = summaryLine(card, "Add-ons")
        homeCatalogs = summaryLine(card, "Catalogs")
        homeTarget = summaryLine(card, "Opens titles in")
        col.addView(card, lp(-1, -2, bottom = 16))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val connect = button("Connect", primary = true, action = { connectToNuvio() })
        connectButtonRef = connect
        val phone = button("Add from phone", action = { showPhoneAddDialog() })
        val sync = button("Sync now", action = { runManualSync() })
        manualSyncButton = sync
        phoneAddButton = phone
        actions.addView(connect, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        actions.addView(phone, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        actions.addView(sync, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(actions)
        manualSyncState = label("Status: Ready", 12, Ui.TEXT3).apply { setPadding(dp(4), dp(8), 0, 0) }
        col.addView(manualSyncState)
        col.addView(sectionTitle("HOW TO CONNECT"))
        listOf(
            "A  Open Nuvio (NuvioTV or Re-Shaped) > Add-ons > Manage from phone (QR), keep it open, then press Connect.",
            "B  Press Add from phone, scan the QR code and paste your add-on links from the phone. No Nuvio app needed."
        ).forEach { col.addView(label(it, 14, Ui.TEXT2).apply { setPadding(dp(4), dp(3), 0, dp(3)) }) }
        return scroll
    }

    private fun summaryLine(parent: LinearLayout, caption: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(label(caption, 14, Ui.TEXT2).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("", 15, Ui.TEXT1, true).apply { setPadding(0, 0, 0, 0) }
        row.addView(value)
        parent.addView(row)
        return value
    }

    private fun targetInfo(): Pair<String, Boolean> {
        val legacy = NuvioTarget.target(this) == NuvioTarget.LEGACY
        val installed = runCatching { packageManager.getPackageInfo(NuvioTarget.packageName(this), 0); true }.getOrDefault(false)
        return (if (legacy) "Nuvio Legacy" else "Nuvio Re-Shaped") to installed
    }

    private fun updateHome() {
        val keys = catalogs.map { it.key }.toSet()
        homeAddons?.text = addons.size.toString()
        homeCatalogs?.text = if (keys.isEmpty()) "none yet" else selected.count { it in keys }.toString() + " of " + keys.size + " selected"
        val (name, installed) = targetInfo()
        homeTarget?.text = name + if (installed) "" else "  ·  not installed"
        homeTarget?.setTextColor(if (installed) Ui.TEXT1 else Ui.WARN)
    }

    // ---------- Catalogs ----------

    private fun buildCatalogsPage(): View {
        val (scroll, col) = newPage()
        selectionSummary = label("", 14, Ui.TEXT2).apply { setPadding(dp(4), dp(4), 0, dp(10)) }
        col.addView(selectionSummary)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val all = button("Select all", action = { setAllCatalogsSelected(true) })
        val none = button("Select none", action = { setAllCatalogsSelected(false) })
        selectAllButton = all; selectNoneButton = none
        row.addView(all, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        row.addView(none, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row, lp(-1, -2, bottom = 12))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        return scroll
    }

    // ---------- Settings ----------

    private fun buildSettingsPage(): View {
        val (scroll, col) = newPage()

        col.addView(sectionTitle("OPEN TITLES IN"))
        col.addView(hint("Which Nuvio app opens when you press Play on a Relay title."))
        val targetStatus = label("", 12, Ui.TEXT3).apply { setPadding(dp(4), dp(6), 0, 0) }
        targetStatusRef = targetStatus
        fun refreshTargetStatus() {
            val (name, installed) = targetInfo()
            targetStatus.text = name + if (installed) "  ·  installed" else "  ·  NOT installed on this TV"
            targetStatus.setTextColor(if (installed) Ui.SUCCESS else Ui.WARN)
            updateHome()
        }
        val targetRow = segmented(
            listOf(NuvioTarget.RESHAPED to "Nuvio Re-Shaped", NuvioTarget.LEGACY to "Nuvio Legacy"),
            { NuvioTarget.target(this) }
        ) { key ->
            prefs.edit().putString(NuvioTarget.PREF_KEY, key).apply()
            refreshTargetStatus()
            Toast.makeText(this, "Play target: " + targetInfo().first, Toast.LENGTH_SHORT).show()
            runManualSync()
        }
        firstSettingsRow = targetRow
        col.addView(targetRow, lp(-1, -2, top = 10))
        col.addView(targetStatus)
        refreshTargetStatus()

        col.addView(sectionTitle("DESCRIPTION LANGUAGE"))
        col.addView(hint("Language of the synopsis drawn under the metadata in the artwork."))
        col.addView(segmented(
            listOf("ar" to "العربية", "en" to "English", "fr" to "Français", "de" to "Deutsch"),
            { prefs.getString("description_language", "en") ?: "en" }
        ) { code ->
            prefs.edit().putString("description_language", code).apply()
            Toast.makeText(this, "Description language updated. Syncing…", Toast.LENGTH_SHORT).show()
            runManualSync()
        }, lp(-1, -2, top = 10))

        col.addView(sectionTitle("ARTWORK"))
        var synopsisPill: TextView? = null
        val synopsisRow = toggleRow("Synopsis", "Show the synopsis text on the artwork.", { prefs.getBoolean("synopsis_enabled", true) }) {
            val enabled = !prefs.getBoolean("synopsis_enabled", true)
            prefs.edit().putBoolean("synopsis_enabled", enabled).commit()
            synopsisPill?.let { paintPill(it, enabled) }
            Toast.makeText(this, if (enabled) "Synopsis ON · syncing titles" else "Synopsis OFF · syncing titles", Toast.LENGTH_SHORT).show()
            runManualSync()
        }
        synopsisPill = synopsisRow.pill
        col.addView(synopsisRow.row, lp(-1, -2, bottom = 8))
        rotationOn = prefs.getBoolean("rotation_enabled", true)
        val rotationRow = toggleRow("Catalog rotation", "Cycle through your selected catalogs on the home screen.", { rotationOn }) { toggleRotation() }
        rotationButton = rotationRow.pill
        rotationState = rotationRow.subtitle
        col.addView(rotationRow.row)

        col.addView(sectionTitle("TMDB ENRICHMENT"))
        col.addView(hint("Add a TMDB API Read Access Token to fill in missing posters, backdrops, logos, ratings and years. It is stored only on this device."))
        val tmdbTokenInput = EditText(this).apply {
            id = View.generateViewId()
            hint = "TMDB API Read Access Token"
            setHintTextColor(Ui.TEXT3)
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
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
            background = rounded(Ui.SURFACE2, 10)
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) Ui.ACCENT_FOCUS else Ui.SURFACE2, 10)
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
        col.addView(tmdbTokenInput, lp(-1, -2, top = 10, bottom = 8))
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
        col.addView(saveTmdbButton, lp(-1, -2))
        col.addView(tmdbStatus.apply { setPadding(dp(4), dp(8), 0, 0) })

        col.addView(sectionTitle("ADD-ONS"))
        col.addView(hint("Reload the manifests of the add-ons you connected."))
        val refreshButton = button("Refresh add-ons", action = { refreshAddonsManually() })
        refreshAddonsButton = refreshButton
        col.addView(refreshButton, lp(-1, -2, top = 8))
        refreshAddonsState = label("Status: Waiting for refresh", 12, Ui.TEXT3).apply { setPadding(dp(4), dp(6), 0, 0) }
        col.addView(refreshAddonsState)
        return scroll
    }

    // ---------- Updates ----------

    private fun buildUpdatesPage(): View {
        val (scroll, col) = newPage()
        col.addView(sectionTitle("VERSION"))
        updCurrent = label("", 18, Ui.TEXT1, true).apply { setPadding(dp(4), dp(8), 0, 0) }
        updLatest = label("", 14, Ui.TEXT2).apply { setPadding(dp(4), dp(4), 0, 0) }
        col.addView(updCurrent); col.addView(updLatest)
        updNotes = label("", 12, Ui.TEXT3).apply { setPadding(dp(4), dp(10), 0, dp(6)) }
        col.addView(updNotes)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val check = button("Check for updates", action = { checkForUpdatesNow() })
        val install = button("Install update", primary = true, action = { installUpdateNow() }).apply { visibility = View.GONE }
        updCheckButton = check; updInstallButton = install
        actions.addView(check, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        actions.addView(install, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(actions, lp(-1, -2, top = 8))
        updStatus = label("", 13, Ui.TEXT2).apply { setPadding(dp(4), dp(8), 0, 0) }
        col.addView(updStatus)

        col.addView(sectionTitle("AUTOMATIC UPDATES"))
        var checkPill: TextView? = null
        val checkRow = toggleRow("Check for updates", "Look for a new GitHub release every few hours.", { prefs.getBoolean("auto_update_check", true) }) {
            val on = !prefs.getBoolean("auto_update_check", true)
            prefs.edit().putBoolean("auto_update_check", on).apply()
            checkPill?.let { paintPill(it, on) }
        }
        checkPill = checkRow.pill
        col.addView(checkRow.row, lp(-1, -2, bottom = 8))
        var installPill: TextView? = null
        val installRow = toggleRow("Install automatically", "Download and install new releases without asking. Android may still ask you to confirm once.", { prefs.getBoolean("auto_update_install", true) }) {
            val on = !prefs.getBoolean("auto_update_install", true)
            prefs.edit().putBoolean("auto_update_install", on).apply()
            installPill?.let { paintPill(it, on) }
        }
        installPill = installRow.pill
        col.addView(installRow.row)
        col.addView(hint("Updates come from github.com/mohammadmrh12/nuvio-tv-relay-standalone and must be signed with the same key as this app."))
        return scroll
    }

    private fun refreshUpdatesPage() {
        updCurrent?.text = "Installed  v" + AppUpdater.currentVersion(this)
        val release = AppUpdater.latestAvailable(this)
        val known = AppUpdater.latestKnownVersion(this)
        updLatest?.text = when {
            release != null -> "New version available  v" + release.version
            known.isNotBlank() -> "You are up to date  (latest v$known)"
            else -> "Not checked yet"
        }
        updLatest?.setTextColor(if (release != null) Ui.ACCENT else Ui.TEXT2)
        updNotes?.text = release?.notes?.take(420).orEmpty()
        updInstallButton?.visibility = if (release != null) View.VISIBLE else View.GONE
        updStatus?.text = prefs.getString("update_status", "").orEmpty()
        updateBanner?.apply {
            visibility = if (release != null) View.VISIBLE else View.GONE
            text = if (release != null) "Update available: v" + release.version + "  ·  press to review" else ""
        }
        rewireHomeFocus()
    }

    private fun checkForUpdatesNow() {
        updStatus?.text = "Checking GitHub…"
        Thread {
            val result = runCatching { AppUpdater.checkNow(this) }
            runOnUiThread {
                result.onFailure { updStatus?.text = "Could not check: " + (it.message ?: it.javaClass.simpleName) }
                result.onSuccess { prefs.edit().remove("update_status").apply() }
                refreshUpdatesPage()
            }
        }.start()
    }

    private fun installUpdateNow() {
        val release = AppUpdater.latestAvailable(this) ?: return
        AppUpdater.pendingConfirmation?.let { confirm ->
            runCatching { startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        if (!AppUpdater.canInstall(this)) {
            updStatus?.text = "Allow Relay to install apps: Settings → Apps → Security & restrictions → Unknown sources → Nuvio Relay, then try again."
            if (!AppUpdater.openInstallPermissionSettings(this)) message("Open the TV Settings and allow Nuvio Relay under Unknown sources.")
            return
        }
        updStatus?.text = "Downloading v" + release.version + "…"
        updInstallButton?.isEnabled = false
        Thread {
            val result = runCatching { AppUpdater.downloadAndInstall(this, release) { pct -> runOnUiThread { updStatus?.text = "Downloading v" + release.version + "…  " + pct + "%" } } }
            runOnUiThread {
                updInstallButton?.isEnabled = true
                result.onFailure { updStatus?.text = "Update failed: " + (it.message ?: it.javaClass.simpleName) }
                result.onSuccess { updStatus?.text = prefs.getString("update_status", "Installing update…") }
            }
        }.start()
    }

    // ---------- reusable widgets ----------

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun sectionTitle(text: String) = label(text, 12, Ui.ACCENT, true).apply {
        letterSpacing = 0.12f
        setPadding(dp(4), dp(26), 0, dp(2))
    }

    private fun hint(text: String) = label(text, 13, Ui.TEXT3).apply { setPadding(dp(4), dp(2), 0, 0) }

    private fun segmented(options: List<Pair<String, String>>, current: () -> String, onPick: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = mutableListOf<Pair<String, TextView>>()
        fun style(tv: TextView, selectedItem: Boolean, focused: Boolean) {
            tv.background = rounded(when { focused -> Ui.ACCENT_FOCUS; selectedItem -> Ui.ACCENT_SOFT; else -> Ui.SURFACE2 }, 12)
            tv.setTextColor(if (selectedItem || focused) Color.WHITE else Ui.TEXT2)
        }
        fun restyle() { items.forEach { (key, tv) -> style(tv, key == current(), tv.isFocused) } }
        options.forEachIndexed { index, (key, caption) ->
            val tv = TextView(this).apply {
                text = caption
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                minHeight = dp(50)
                isFocusable = true
                isClickable = true
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnFocusChangeListener { v, focused -> style(v as TextView, key == current(), focused) }
                setOnClickListener { onPick(key); restyle() }
            }
            items.add(key to tv)
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index < options.lastIndex) marginEnd = dp(8) })
        }
        restyle()
        return row
    }

    private class ToggleRow(val row: LinearLayout, val pill: TextView, val subtitle: TextView)

    private fun paintPill(pill: TextView, on: Boolean) {
        pill.text = if (on) "ON" else "OFF"
        pill.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT2)
        pill.background = rounded(if (on) Ui.SUCCESS else Ui.SURFACE2, 14)
    }

    private fun toggleRow(title: String, subtitleText: String, get: () -> Boolean, onClick: () -> Unit): ToggleRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            isClickable = true
            background = rounded(Ui.SURFACE, 12)
            setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) Ui.SURFACE_FOCUS else Ui.SURFACE, 12) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 16, Ui.TEXT1, true).apply { setPadding(0, 0, 0, 0) })
        val subtitle = label(subtitleText, 12, Ui.TEXT3).apply { setPadding(0, dp(2), 0, 0) }
        texts.addView(subtitle)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = TextView(this).apply {
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            minWidth = dp(64)
        }
        paintPill(pill, get())
        row.addView(pill)
        row.setOnClickListener { onClick() }
        return ToggleRow(row, pill, subtitle)
    }

    // ---------- add-ons from the phone ----------

    private fun localIpAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun randomToken(): String {
        val bytes = ByteArray(9)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    /** Runs [block] on the UI thread and waits for its result, so server threads never touch UI state directly. */
    private fun <T> onUi(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<Result<T>>()
        runOnUiThread { holder.set(runCatching(block)); latch.countDown() }
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("The TV did not respond")
        return holder.get().getOrThrow()
    }

    private fun closePhoneDialog() { phoneDialog?.dismiss() }

    private fun showPhoneAddDialog() {
        val ip = localIpAddress()
        if (ip == null) { message("The TV is not connected to a network."); return }
        closePhoneDialog()
        val token = randomToken()
        val server = PhoneAddonServer(token, object : PhoneAddonServer.Host {
            override fun list(): List<PhoneAddonServer.AddonRow> = onUi { addons.toList() }.map { PhoneAddonServer.AddonRow(it, cachedManifestName(it)) }
            override fun add(rawUrl: String): String = phoneAdd(rawUrl)
            override fun remove(url: String): String = phoneRemove(url)
        })
        val port = server.start()
        if (port == null) { message("Could not open a local port for the phone page."); return }
        val url = "http://$ip:$port/?t=$token"
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Ui.BG)
            setPadding(dp(56), dp(32), dp(56), dp(32))
        }
        box.addView(label("Add add-ons from your phone", 26, Ui.TEXT1, true).apply { gravity = android.view.Gravity.CENTER })
        box.addView(label("Scan the code with your phone camera (same Wi-Fi), then paste an add-on link.", 15, Ui.TEXT2).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
        box.addView(android.widget.ImageView(this).apply {
            setImageBitmap(QrCode.generate(url, dp(240)))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(256), dp(256)))
        box.addView(label(url, 12, Ui.TEXT3).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
        val statusLine = label("Waiting for your phone…", 15, Ui.ACCENT).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(8), 0, dp(14)) }
        phoneDialogStatus = statusLine
        box.addView(statusLine)
        val close = button("Close", action = { dialog.dismiss() })
        box.addView(close, LinearLayout.LayoutParams(dp(260), -2))
        dialog.setContentView(box)
        dialog.setOnDismissListener {
            server.stop()
            if (phoneServer === server) { phoneServer = null; phoneDialog = null; phoneDialogStatus = null }
        }
        phoneServer = server
        phoneDialog = dialog
        dialog.show()
        close.requestFocus()
    }

    private fun cachedManifestName(url: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val name = runCatching { JSONObject(File(cacheDir, "api-cache/$key.json").readText()).optString("name") }.getOrDefault("")
        return name.ifBlank { Uri.parse(url).host ?: "Add-on" }
    }

    private fun normalizePhoneUrl(raw: String): String {
        var link = raw.trim().lines().first().trim()
        if (link.startsWith("stremio://", true)) link = "https://" + link.substring("stremio://".length)
        val uri = Uri.parse(link)
        require(uri.scheme == "http" || uri.scheme == "https") { "Enter an http(s) or stremio:// link" }
        require(!uri.host.isNullOrBlank()) { "Invalid link" }
        return normalize(link)
    }

    private fun phoneAdd(rawUrl: String): String {
        val url = normalizePhoneUrl(rawUrl)
        val manifest = try { json(url) } catch (e: Exception) { throw IllegalStateException("Could not load this add-on: " + (e.message ?: "network error")) }
        if (manifest.optString("id").isBlank()) throw IllegalStateException("This link is not an add-on manifest")
        val name = manifest.optString("name").ifBlank { Uri.parse(url).host ?: "Add-on" }
        val found = parseCatalogs(url, name, manifest)
        val alreadyAdded = onUi {
            val known = url in addons
            addons.add(url)
            val manual = prefs.getStringSet("manual_addons", emptySet()).orEmpty().toMutableSet().apply { add(url) }
            val knownKeys = catalogs.map { it.key }.toSet()
            val fresh = found.filter { it.key !in knownKeys }
            val merged = withNativeContinue((catalogs + fresh).distinctBy { it.key })
            catalogs.clear(); catalogs.addAll(merged)
            selected.addAll(fresh.map { it.key })
            prefs.edit().putStringSet("addons", addons.toSet()).putStringSet("manual_addons", manual).putStringSet("selected", selected.toSet()).apply()
            render()
            phoneDialogStatus?.text = "Added " + name
            known
        }
        return if (alreadyAdded) "$name was already added" else "Added $name · ${found.size} catalogs"
    }

    private fun phoneRemove(url: String): String = onUi {
        if (!addons.remove(url)) return@onUi "That add-on is not in the list"
        val manual = prefs.getStringSet("manual_addons", emptySet()).orEmpty().toMutableSet().apply { remove(url) }
        catalogs.removeAll { it.addon == url }
        selected.removeAll { it.startsWith("$url|") }
        prefs.edit().putStringSet("addons", addons.toSet()).putStringSet("manual_addons", manual).putStringSet("selected", selected.toSet()).apply()
        render()
        phoneDialogStatus?.text = "Removed an add-on"
        "Removed"
    }

    private fun connectToNuvio() {
        message("Looking for the Nuvio local add-on server…")
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
                    message("Nuvio add-on server not found. Open Nuvio (NuvioTV or Re-Shaped) > Add-ons > Manage from phone (QR), keep that screen open, then retry.")
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
                    runOnUiThread { message("Connected to Nuvio on port " + connectedPort + ", but it returned no installed add-ons.") }
                    return@execute
                }
                // Keep add-ons added from the phone page alongside the ones imported from Nuvio.
                for (manualUrl in prefs.getStringSet("manual_addons", emptySet()).orEmpty()) {
                    if (importedUrls.add(manualUrl)) {
                        try {
                            val manifest = json(manualUrl)
                            loadedCatalogs.addAll(parseCatalogs(manualUrl, manifest.optString("name").ifBlank { Uri.parse(manualUrl).host ?: "Add-on" }, manifest))
                            importedCount++
                        } catch (_: Exception) { }
                    }
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
        if (addons.isEmpty()) { render(); message("Connect to Nuvio or add a manifest URL."); return }
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
            list.addView(label("No catalogs yet. Open the Home tab and press Connect.", 15, Ui.TEXT3).apply { setPadding(dp(4), dp(10), 0, 0) })
            updateSelectionSummary()
            return
        }
        catalogs.distinctBy { it.key }.forEach { c -> list.addView(catalogRow(c), lp(-1, -2, bottom = 6)) }
        updateSelectionSummary()
    }

    private fun catalogRow(c: Catalog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            isClickable = true
            background = rounded(Ui.SURFACE, 12)
            setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) Ui.SURFACE_FOCUS else Ui.SURFACE, 12) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(c.name, 16, Ui.TEXT1, true).apply { setPadding(0, 0, 0, 0) })
        texts.addView(label(c.addonName + "  ·  " + c.type.uppercase(), 12, Ui.TEXT3).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val check = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(Ui.ON_ACCENT)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        fun paint() {
            val on = c.key in selected
            check.text = if (on) "✓" else ""
            check.background = rounded(if (on) Ui.SUCCESS else Ui.SURFACE2, 16)
        }
        paint()
        row.addView(check, LinearLayout.LayoutParams(dp(32), dp(32)))
        row.setOnClickListener {
            if (c.key in selected) selected.remove(c.key) else selected.add(c.key)
            prefs.edit().putStringSet("selected", selected.toSet()).apply()
            paint()
            updateSelectionSummary()
        }
        return row
    }

    private fun updateSelectionSummary() {
        if (::selectionSummary.isInitialized) {
            val keys = catalogs.map { it.key }.toSet()
            selectionSummary.text = if (keys.isEmpty()) "No catalogs loaded yet."
                else selected.count { it in keys }.toString() + " of " + keys.size + " catalogs selected"
        }
        updateHome()
    }

    private fun setAllCatalogsSelected(all: Boolean) {
        if (catalogs.isEmpty()) { message("No catalogs loaded yet."); return }
        selected.clear()
        if (all) selected.addAll(catalogs.map { it.key })
        prefs.edit().putStringSet("selected", selected.toSet()).apply()
        render()
        message(if (all) "All catalogs selected." else "All catalogs deselected.")
    }

    private fun updateRotationControl(note: String? = null) {
        rotationButton?.let { paintPill(it, rotationOn) }
        val base = "Cycle through your selected catalogs on the home screen."
        rotationState?.text = if (note.isNullOrBlank()) base else "$base  ·  $note"
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
        if (addons.isEmpty()) { refreshAddonsState?.text = "Status: No add-ons connected"; message("Connect to Nuvio first."); return }
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
                val nativeContinue = readNativeContinueWatching()
                val firstNative = nativeContinue.firstOrNull()
                for (c in chosen) {
                    if (c.key == nativeContinueCatalog().key) {
                        nativeContinue.forEach { item -> all.putIfAbsent(item.type.lowercase() + ":" + item.id, item) }
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
                            sequenceOf(cached?.optString("overview"), m.optString("overview"), m.optString("description"), m.optString("plot"), m.optString("synopsis"), m.optString("summary")).map { it?.trim().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty())
                        all.putIfAbsent(type.lowercase() + ":" + id, item)
                    }
                }
                val wanted = all.values.filterNot { firstNative != null && it.type.equals(firstNative.type, true) && it.id == firstNative.id }.take(if (firstNative != null) 49 else 50)
                val wantedIds = wanted.map { relayId(it) }.toSet() + setOfNotNull(firstNative?.let { pinnedNativeInternalId })
                var count = 0
                synchronized(PublishLock.lock) {
                    val rows = existingRows()
                    (rows.keys - wantedIds).forEach { deleteRelay(it, rows) }
                    wanted.forEach { if (publish(it, rows = rows)) count++ }
                    firstNative?.let { if (publish(it, pinnedFirst = true, rows = rows)) count++ }
                }
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

    private fun publish(item: Item, pinnedFirst: Boolean = false, rows: MutableMap<String, Long>? = null): Boolean {
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
            prefs.getString("description_language", "en")?.takeIf { it in setOf("ar", "en", "fr", "de") } ?: "en")
            ?.let { builder.setPosterArtUri(it) }
        item.platformLogo?.let { builder.setLogoUri(Uri.parse(it)) }
        return try {
            val values = builder.build().toContentValues()
            val existing = findId(internalId, rows)
            if (existing == null) {
                insertRow(values, internalId, rows) != null
            } else {
                // Recreate the launcher row so Google TV cannot retain a stale cached intent URI.
                contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null)
                insertRow(values, internalId, rows) != null
            }
        } catch (_: Exception) { false }
    }

    /** Inserts a Watch Next row and records its id so later publishes in the same pass see it. */
    private fun insertRow(values: android.content.ContentValues, internalId: String, rows: MutableMap<String, Long>?): Uri? {
        val uri = contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
        if (rows != null && uri != null) runCatching { android.content.ContentUris.parseId(uri) }.getOrNull()?.let { rows[internalId] = it }
        return uri
    }

    /** One provider scan returning relay row ids by internal provider id. */
    private fun existingRows(): MutableMap<String, Long> {
        val out = linkedMapOf<String, Long>()
        try { contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            val pk = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
            val ki = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) c.getString(ki)?.takeIf { it.startsWith("nuvio_relay_") }?.let { out[it] = c.getLong(pk) }
        } } catch (_: Exception) { }
        return out
    }

    private fun findId(internalId: String, rows: Map<String, Long>? = null): Long? {
        if (rows != null) return rows[internalId]
        try { contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            val pk = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
            val key = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) if (c.getString(key) == internalId) return c.getLong(pk)
        } } catch (_: Exception) { }
        return null
    }

    private fun deleteRelay(key: String, rows: Map<String, Long>? = null): Boolean {
        val id = findId(key, rows) ?: return false
        return try { contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) > 0 } catch (_: Exception) { false }
    }
    override fun onResume() {
        super.onResume()
        AppUpdater.uiForeground = true
        refreshUpdatesPage()
    }

    override fun onPause() {
        AppUpdater.uiForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        closePhoneDialog()
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
        val identity = "${type.lowercase(java.util.Locale.ROOT)}|${norm(title)}|$year|$imdbId|$tmdbId"
        val language = prefs.getString("description_language", "en") ?: "en"
        // Match the service's language-scoped cache first; retain legacy lookup for older installs.
        val keyInputs = listOf("provider-icons-v4-synopsis-$language|$identity", identity)
        for (keyInput in keyInputs) {
            val key = MessageDigest.getInstance("SHA-256").digest(keyInput.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(cacheDir, "tmdb-cache/$key.json")
            if (!file.exists()) continue
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < 0 || age > 30L * 24 * 60 * 60 * 1000) continue
            val cached = runCatching { JSONObject(file.readText()).takeUnless { it.optBoolean("_no_match") } }.getOrNull()
            if (cached != null) {
                // Legacy cache entries are not language-scoped; never reuse their synopsis for ar/fr/de.
                if (keyInput == identity && language != "en") cached.remove("overview")
                return cached
            }
        }
        return null
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
        // Genre/type follow the selected metadata language, but Arabic synopsis keeps metadata English.
        val metadataLanguage = prefs.getString("description_language", "en") ?: "en"
        val typeLabel = when (metadataLanguage) {
            "fr" -> if (isSeries) "Série" else "Film"
            "de" -> if (isSeries) "Serie" else "Film"
            else -> if (isSeries) "Series" else "Movie"
        }
        val details = mutableListOf(typeLabel)
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
    private fun button(caption: String, parent: LinearLayout? = null, primary: Boolean = false, action: () -> Unit): TextView {
        val normal = if (primary) Ui.ACCENT else Ui.SURFACE2
        val focusedBg = if (primary) Ui.ACCENT_BRIGHT else Ui.ACCENT_FOCUS
        val b = TextView(this).apply {
            id = View.generateViewId()
            text = caption
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            minHeight = dp(54)
            isFocusable = true
            isClickable = true
            setTextColor(if (primary) Ui.ON_ACCENT else Ui.TEXT1)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(normal, 12)
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) focusedBg else normal, 12)
                (v as TextView).setTextColor(if (primary) Ui.ON_ACCENT else if (focused) Color.WHITE else Ui.TEXT1)
            }
            setOnClickListener { action() }
        }
        parent?.addView(b, lp(-1, -2, top = 4, bottom = 4))
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

/** Colour tokens for the minimalist Relay UI. */
private object Ui {
    val BG = Color.rgb(11, 15, 23)
    val SURFACE = Color.rgb(18, 24, 38)
    val SURFACE2 = Color.rgb(27, 35, 52)
    val SURFACE_FOCUS = Color.rgb(32, 52, 78)
    val ACCENT = Color.rgb(94, 190, 255)
    val ACCENT_BRIGHT = Color.rgb(150, 214, 255)
    val ACCENT_SOFT = Color.rgb(30, 70, 104)
    val ACCENT_FOCUS = Color.rgb(35, 91, 132)
    val ON_ACCENT = Color.rgb(8, 22, 36)
    val TEXT1 = Color.rgb(232, 238, 248)
    val TEXT2 = Color.rgb(150, 164, 186)
    val TEXT3 = Color.rgb(104, 118, 140)
    val SUCCESS = Color.rgb(105, 211, 176)
    val WARN = Color.rgb(240, 180, 110)
}
