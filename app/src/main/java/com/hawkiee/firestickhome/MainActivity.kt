package com.hawkiee.firestickhome

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** An installed app we can open. */
data class AppEntry(val pkg: String, val label: String, val info: ActivityInfo)

/**
 * An app we want by default. Matched by package name first, then by its label,
 * because the same app can have different package names on different devices.
 */
data class Wanted(val pkgs: List<String>, val labels: List<String>)

class MainActivity : Activity() {

    companion object {
        // Weather and place search: Open-Meteo, no account or key. The place is chosen on the TV.
        const val WEATHER_URL = "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&timezone=auto"
        const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name="
        const val WEATHER_REFRESH_MS = 30L * 60L * 1000L

        // In-app updates come from this repo's "latest" release (the repo must be public).
        const val RELEASE_URL = "https://api.github.com/repos/Hawkiee11/firestick-home/releases/tags/latest"
        const val UPDATE_CHECK_MS = 6L * 60L * 60L * 1000L
        const val ACTION_INSTALL_STATUS = "com.hawkiee.firestickhome.INSTALL_STATUS"

        // The Favourites row on first run. After that, the hold-OK menu edits it.
        val DEFAULT_FAVOURITES = listOf(
            Wanted(listOf("com.netflix.ninja"), listOf("netflix")),
            Wanted(listOf("com.nowtv.ui", "com.nowtv.ui.firetv"), listOf("now", "now tv")),
            Wanted(listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv"), listOf("youtube")),
        )

        // App drawer groups, in the order they appear. The Kids row on Home shows the Kids group.
        const val G_STREAM = "Streaming"
        const val G_KIDS = "Kids"
        const val G_MUSIC = "Music & audio"
        const val G_GAMES = "Games"
        const val G_TOOLS = "Tools & settings"
        const val G_AMAZON = "Amazon"
        const val G_OTHER = "Other"
        val GROUP_ORDER = listOf(G_STREAM, G_KIDS, G_MUSIC, G_GAMES, G_TOOLS, G_AMAZON, G_OTHER)

        // Matched against the app's name (lower case). First match wins, in the order of groupOf().
        val KIDS_WORDS = listOf("kids", "cbeebies", "cbbc", "nick jr", "pbs kids", "disney+", "iplayer")
        val MUSIC_WORDS = listOf("spotify", "music", "audible", "bbc sounds", "tunein", "podcast", "deezer", "tidal", "radio")
        val GAME_WORDS = listOf("luna", "game", "xbox", "geforce", "steam link", "arcade")
        val STREAM_WORDS = listOf(
            "netflix", "youtube", "prime video", "disney", "iplayer", "itvx", "channel 4", "my5", "apple tv",
            "paramount", "plex", "pluto", "discovery", "britbox", "uktv", "jellyfin", "kodi", "stremio",
            "twitch", "dazn", "tnt sports", "sky ", "rakuten", "mubi", "crunchyroll", "hayu", "freely", "vlc",
            "mx player", "emby", "freevee", "tubi"
        )
        val TOOL_WORDS = listOf("downloader", "browser", "silk", "file", "vpn", "speed", "send files", "settings", "analiti")

        // Things that should never appear as a tile.
        val HIDDEN = setOf("com.amazon.tv.launcher")

        // The Kids row goes above Favourites between these hours.
        const val KIDS_FIRST_FROM = 6
        const val KIDS_FIRST_UNTIL = 19

        // Colours from the Family Hub+ design.
        val BG = Color.parseColor("#14161F")
        val CARD = Color.parseColor("#262A3A")
        val CARD_DIM = Color.parseColor("#1F2230")
        val TEXT = Color.parseColor("#F4F1EA")
        val MUTED = Color.parseColor("#C9C3B6")
        val ACCENT = Color.parseColor("#F3C98B")

        // App tiles are the same shape as the apps' TV banners (16:9), so the art fills them.
        const val ART_W = 156
        const val ART_H = 88
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var homeColumn: LinearLayout
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherView: TextView
    private lateinit var updatePill: TextView
    private lateinit var jumpRow: LinearLayout
    private lateinit var favSection: LinearLayout
    private lateinit var favRow: LinearLayout
    private lateinit var kidsSection: LinearLayout
    private lateinit var kidsRow: LinearLayout
    private lateinit var allPanel: LinearLayout
    private lateinit var allGrid: LinearLayout
    private var firstGridRow: LinearLayout? = null

    private var apps: Map<String, AppEntry> = emptyMap()
    private var recentTimes: Map<String, Long> = emptyMap()
    private var lastWeatherFetch = 0L
    private var lastUpdateCheck = 0L
    private var updateUrl: String? = null

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            val now = System.currentTimeMillis()
            if (now - lastWeatherFetch > WEATHER_REFRESH_MS) fetchWeather()
            if (now - lastUpdateCheck > UPDATE_CHECK_MS) checkForUpdate()
            main.postDelayed(this, 15_000L)
        }
    }

    // Results from the system installer when an update is installed.
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(confirm)
                        } catch (e: Exception) {
                            toast("Couldn't start the installer.")
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    toast("Installed.")
                    buildRows()
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    toast("The update didn't install. $msg")
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        window.decorView.setBackgroundColor(BG)
        setContentView(buildScreen())
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(installReceiver)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    override fun onResume() {
        super.onResume()
        allPanel.visibility = View.GONE
        homeColumn.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        buildRows()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    // This is a home screen: Back only closes the drawer.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (allPanel.visibility == View.VISIBLE) closeAllApps()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Up from the drawer's first row closes it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            allPanel.visibility == View.VISIBLE
        ) {
            val firstRow = firstGridRow
            val focused = currentFocus
            if (firstRow != null && focused != null && focused.parent == firstRow) {
                closeAllApps()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- layout

    private fun buildScreen(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)
        root.clipChildren = false

        val column = LinearLayout(this)
        homeColumn = column
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        column.setPadding(dp(40), dp(22), dp(40), dp(14))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top bar: clock, date and greeting, weather, update, settings.
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.clipChildren = false

        clockView = text("--:--", 34f, TEXT, light = true)
        top.addView(clockView)

        // Date and greeting: press OK on it to set the name it greets.
        dateView = pill("", BG, MUTED) { showNameDialog() }
        dateView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        top.addView(dateView, margins(dp(4), 0, dp(4), 0))

        // The weather pill: press OK on it to change the place.
        weatherView = pill("Set weather location", CARD_DIM, TEXT) { showLocationDialog() }
        weatherView.background = rounded(CARD_DIM, false, 999)
        weatherView.setOnFocusChangeListener { v, hasFocus -> v.background = rounded(CARD_DIM, hasFocus, 999) }
        top.addView(weatherView)

        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        updatePill = pill("Update ready", ACCENT, BG) { installUpdate() }
        updatePill.visibility = View.GONE
        top.addView(updatePill, margins(0, 0, dp(10), 0))

        top.addView(pill("Add app", CARD, TEXT) { showAddAppDialog() }, margins(0, 0, dp(10), 0))
        top.addView(pill("Settings", CARD, TEXT) { openSettings() })

        column.addView(top, fullWidth(0))

        // Jump back in: the last three apps opened, whatever they are.
        jumpRow = row()
        column.addView(section("Jump back in", jumpRow), fullWidth(dp(14)))

        // Favourites and Kids. Their order changes with the time of day (see orderRows).
        favRow = row()
        favSection = section("Favourites", favRow)
        column.addView(favSection, fullWidth(dp(10)))

        kidsRow = row()
        kidsSection = section("Kids", kidsRow)
        column.addView(kidsSection, fullWidth(dp(10)))

        // Push the drawer handle to the bottom of the screen.
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Drawer handle: landing on it (pressing down from the last row) slides the app drawer up.
        val handle = text("↓   All apps", 14f, MUTED, bold = true)
        handle.gravity = Gravity.CENTER
        handle.setPadding(dp(18), dp(6), dp(18), dp(6))
        handle.isFocusable = true
        handle.isClickable = true
        handle.setOnClickListener { openAllApps() }
        handle.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) openAllApps() }
        val handleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        handleLp.gravity = Gravity.CENTER_HORIZONTAL
        column.addView(handle, handleLp)

        // The app drawer: a full-screen panel on top, closed with Up or Back.
        allPanel = LinearLayout(this)
        allPanel.orientation = LinearLayout.VERTICAL
        allPanel.setBackgroundColor(BG)
        allPanel.setPadding(dp(40), dp(22), dp(40), 0)
        allPanel.visibility = View.GONE
        allPanel.clipChildren = false
        allPanel.addView(text("All apps", 22f, TEXT, bold = true))
        allPanel.addView(
            text("Hold OK on an app for options  ·  Up or Back to close", 12f, MUTED),
            fullWidth(dp(2))
        )
        allGrid = LinearLayout(this)
        allGrid.orientation = LinearLayout.VERTICAL
        allGrid.clipChildren = false
        allGrid.clipToPadding = false
        allGrid.setPadding(dp(6), dp(10), dp(6), dp(24))
        val gridScroll = ScrollView(this)
        gridScroll.isVerticalScrollBarEnabled = false
        gridScroll.clipChildren = false
        gridScroll.clipToPadding = false
        gridScroll.addView(allGrid)
        allPanel.addView(
            gridScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dp(6) }
        )
        root.addView(allPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        return root
    }

    private fun buildRows() {
        apps = installedApps()
        val hiddenByUser = hiddenSet()
        val visible = apps.values.filter { !HIDDEN.contains(it.pkg) && !hiddenByUser.contains(it.pkg) }

        // Jump back in: the last three apps opened, any app at all.
        // Until there is any history, it shows the favourites instead.
        jumpRow.removeAllViews()
        val favs = favourites().mapNotNull { apps[it] }
        val recent = ArrayList<AppEntry>()
        for (pkg in recentPackages()) {
            val app = apps[pkg] ?: continue
            if (HIDDEN.contains(pkg)) continue
            recent.add(app)
            if (recent.size == 3) break
        }
        for (app in favs) {
            if (recent.size == 3) break
            if (recent.none { it.pkg == app.pkg }) recent.add(app)
        }
        recent.forEachIndexed { i, app ->
            val lp = LinearLayout.LayoutParams(0, dp(100), 1f)
            if (i < recent.size - 1) lp.marginEnd = dp(16)
            jumpRow.addView(jumpTile(app), lp)
        }

        // Favourites: up to five, in the order you set.
        fillArtRow(favRow, favs.take(5), "Hold OK on any app and choose \"Add to Favourites\"")

        // Kids: the apps in the Kids group, most recently used first.
        val kids = visible.filter { groupOf(it) == G_KIDS }
            .sortedWith(compareByDescending<AppEntry> { lastOpened(it) }.thenBy { it.label.lowercase(Locale.UK) })
        fillArtRow(kidsRow, kids.take(5), null)
        kidsSection.visibility = if (kids.isEmpty()) View.GONE else View.VISIBLE

        orderRows()
        buildDrawer(visible, hiddenByUser.size)

        if (allPanel.visibility == View.VISIBLE) {
            firstGridTile()?.requestFocus()
        } else {
            // Start on the most recently used app, so one press of OK gets you back in.
            jumpRow.getChildAt(0)?.requestFocus()
        }
    }

    /** A row of banner tiles, left-aligned; [emptyHint] shows when there are none. */
    private fun fillArtRow(r: LinearLayout, list: List<AppEntry>, emptyHint: String?) {
        r.removeAllViews()
        if (list.isEmpty() && emptyHint != null) {
            r.addView(text(emptyHint, 13f, MUTED), margins(0, dp(8), 0, dp(8)))
            return
        }
        for (app in list) {
            r.addView(artTile(app), LinearLayout.LayoutParams(dp(ART_W), dp(ART_H)).also { it.marginEnd = dp(16) })
        }
    }

    /** Kids above Favourites in the daytime, below in the evening. */
    private fun orderRows() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val kidsFirst = hour in KIDS_FIRST_FROM until KIDS_FIRST_UNTIL
        val favIndex = homeColumn.indexOfChild(favSection)
        val kidsIndex = homeColumn.indexOfChild(kidsSection)
        val wantSwap = (kidsFirst && kidsIndex > favIndex) || (!kidsFirst && favIndex > kidsIndex)
        if (!wantSwap) return
        val first = minOf(favIndex, kidsIndex)
        homeColumn.removeView(favSection)
        homeColumn.removeView(kidsSection)
        if (kidsFirst) {
            homeColumn.addView(kidsSection, first, fullWidth(dp(10)))
            homeColumn.addView(favSection, first + 1, fullWidth(dp(10)))
        } else {
            homeColumn.addView(favSection, first, fullWidth(dp(10)))
            homeColumn.addView(kidsSection, first + 1, fullWidth(dp(10)))
        }
    }

    /** The drawer: grouped (Streaming, Kids, Music…), A–Z within each group, five per row. */
    private fun buildDrawer(visible: List<AppEntry>, hiddenCount: Int) {
        allGrid.removeAllViews()
        firstGridRow = null
        val groups = LinkedHashMap<String, ArrayList<View>>()
        for (g in GROUP_ORDER) groups[g] = ArrayList()
        for (app in visible.sortedBy { it.label.lowercase(Locale.UK) }) {
            groups.getValue(groupOf(app)).add(artTile(app))
        }
        val tools = groups.getValue(G_TOOLS)
        tools.add(tile("Settings", CARD_DIM, TEXT) { openSettings() })
        if (hiddenCount > 0) {
            tools.add(tile("Show hidden ($hiddenCount)", CARD_DIM, MUTED) {
                prefs.edit().remove("hidden").apply()
                buildRows()
            })
        }
        for ((title, views) in groups) {
            if (views.isEmpty()) continue
            allGrid.addView(text(title, 15f, TEXT, bold = true), fullWidth(if (allGrid.childCount == 0) 0 else dp(20)))
            var gridRow: LinearLayout? = null
            views.forEachIndexed { i, v ->
                if (i % 5 == 0) {
                    val r = row()
                    allGrid.addView(r, fullWidth(dp(10)))
                    if (firstGridRow == null) firstGridRow = r
                    gridRow = r
                }
                gridRow?.addView(v, LinearLayout.LayoutParams(dp(ART_W), dp(ART_H)).also { it.marginEnd = dp(16) })
            }
        }
    }

    private fun firstGridTile(): View? = firstGridRow?.getChildAt(0)

    private fun openAllApps() {
        if (allPanel.visibility == View.VISIBLE) return
        allPanel.visibility = View.VISIBLE
        // Keep the remote inside the drawer while it's open.
        homeColumn.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        allPanel.translationY = resources.displayMetrics.heightPixels.toFloat()
        allPanel.animate().translationY(0f).setDuration(220L).start()
        firstGridTile()?.requestFocus()
    }

    private fun closeAllApps() {
        homeColumn.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        lastHomeTile()?.requestFocus()
        allPanel.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(180L).withEndAction {
            allPanel.visibility = View.GONE
            allPanel.translationY = 0f
        }.start()
    }

    /** The first tile of the lowest row on the home screen, where focus lands when the drawer closes. */
    private fun lastHomeTile(): View? {
        val candidates = listOf(favSection, kidsSection)
            .filter { it.visibility == View.VISIBLE }
            .sortedByDescending { homeColumn.indexOfChild(it) }
        for (s in candidates) {
            val r = if (s == favSection) favRow else kidsRow
            val first = r.getChildAt(0)
            if (first != null && first.isFocusable) return first
        }
        return jumpRow.getChildAt(0)
    }

    // ---------------------------------------------------------------- the hold-OK menu

    private fun showAppMenu(app: AppEntry) {
        val favs = favourites()
        val isFav = favs.contains(app.pkg)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (isFav) {
            if (favs.indexOf(app.pkg) > 0) {
                labels.add("Move to front of Favourites")
                actions.add {
                    val list = favs.toMutableList()
                    list.remove(app.pkg)
                    list.add(0, app.pkg)
                    setFavourites(list)
                }
            }
            labels.add("Remove from Favourites")
            actions.add { setFavourites(favs.filter { it != app.pkg }) }
        } else {
            labels.add("Add to Favourites")
            actions.add {
                if (favs.size >= 5) toast("Favourites holds five. Remove one first.")
                else setFavourites(favs + app.pkg)
            }
        }
        labels.add("Move to group…  (now: ${groupOf(app)})")
        actions.add { showGroupMenu(app) }
        labels.add("Hide")
        actions.add {
            val next = HashSet(hiddenSet())
            next.add(app.pkg)
            prefs.edit().putStringSet("hidden", next).apply()
            setFavourites(favourites().filter { it != app.pkg })
            toast("Hid ${app.label}. \"Show hidden\" in All apps › Tools brings it back.")
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which]()
                buildRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupMenu(app: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle("Move ${app.label} to")
            .setItems(GROUP_ORDER.toTypedArray()) { _, which ->
                prefs.edit().putString("group_${app.pkg}", GROUP_ORDER[which]).apply()
                buildRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- saved choices

    private fun hiddenSet(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()

    /** Favourite package names, in order. Seeded from DEFAULT_FAVOURITES the first time. */
    private fun favourites(): List<String> {
        val saved = prefs.getString("favs", null)
        if (saved != null) return saved.split(",").filter { it.isNotBlank() }
        val seeded = DEFAULT_FAVOURITES.mapNotNull { find(it, apps)?.pkg }.distinct()
        if (apps.isNotEmpty()) setFavourites(seeded)
        return seeded
    }

    private fun setFavourites(list: List<String>) {
        prefs.edit().putString("favs", list.distinct().joinToString(",")).apply()
    }

    // ---------------------------------------------------------------- apps

    private fun installedApps(): Map<String, AppEntry> {
        val pm = packageManager
        val out = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(query, 0)
            for (info in found) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || out.containsKey(pkg)) continue
                out[pkg] = AppEntry(pkg, info.loadLabel(pm).toString(), info.activityInfo)
            }
        }
        return out
    }

    private fun find(w: Wanted, apps: Map<String, AppEntry>): AppEntry? {
        for (pkg in w.pkgs) {
            val hit = apps[pkg]
            if (hit != null) return hit
        }
        return apps.values.firstOrNull { app ->
            val label = app.label.lowercase(Locale.UK).trim()
            w.labels.any { label == it }
        }
    }

    /** Which drawer group an app belongs in: your choice first, then its name, then its declared category. */
    private fun groupOf(app: AppEntry): String {
        val chosen = prefs.getString("group_${app.pkg}", null)
        if (chosen != null && GROUP_ORDER.contains(chosen)) return chosen
        val l = app.label.lowercase(Locale.UK).trim()
        fun has(words: List<String>) = words.any { l.contains(it) }
        if (has(KIDS_WORDS)) return G_KIDS
        if (has(MUSIC_WORDS)) return G_MUSIC
        if (has(GAME_WORDS)) return G_GAMES
        if (l == "now" || l.startsWith("now ") || has(STREAM_WORDS)) return G_STREAM
        if (has(TOOL_WORDS)) return G_TOOLS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (app.info.applicationInfo.category) {
                ApplicationInfo.CATEGORY_VIDEO -> return G_STREAM
                ApplicationInfo.CATEGORY_AUDIO -> return G_MUSIC
                ApplicationInfo.CATEGORY_GAME -> return G_GAMES
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return G_TOOLS
            }
        }
        if (app.pkg.startsWith("com.amazon.")) return G_AMAZON
        return G_OTHER
    }

    private fun launch(pkg: String) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("Couldn't open that app.")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        prefs.edit().putLong("last_$pkg", System.currentTimeMillis()).apply()
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open that app.")
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Couldn't open Settings.")
        }
    }

    private fun lastOpened(app: AppEntry?): Long {
        if (app == null) return -1L
        return recentTimes[app.pkg] ?: prefs.getLong("last_${app.pkg}", 0L)
    }

    /**
     * Apps by when they were last opened, newest first.
     * Always knows about apps opened from this screen. If usage access has been granted
     * (one ADB command, see README), it also sees apps opened any other way.
     */
    private fun recentPackages(): List<String> {
        val times = HashMap<String, Long>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("last_") && value is Long) times[key.removePrefix("last_")] = value
        }
        if (hasUsageAccess()) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14L * 24L * 3600L * 1000L, now)
                for (st in stats) {
                    if (st.totalTimeInForeground <= 0L) continue
                    val before = times[st.packageName] ?: 0L
                    if (st.lastTimeUsed > before) times[st.packageName] = st.lastTimeUsed
                }
            } catch (e: Exception) {
                // Fall back to what this screen knows.
            }
        }
        times.remove(packageName)
        recentTimes = times
        return times.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun lastOpenedText(app: AppEntry): String {
        val t = lastOpened(app)
        if (t <= 0L) return "Press OK to open"
        val then = Calendar.getInstance()
        then.timeInMillis = t
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            "Last watched " + SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))
        } else {
            "Last watched " + SimpleDateFormat("EEEE", Locale.UK).format(Date(t))
        }
    }

    // ---------------------------------------------------------------- updates

    private fun installedVersion(): Long {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            code
        } catch (e: Exception) {
            0L
        }
    }

    /** Looks for a newer build on GitHub. Shows the "Update ready" button if there is one. */
    private fun checkForUpdate() {
        lastUpdateCheck = System.currentTimeMillis()
        val mine = installedVersion()
        Thread {
            try {
                val conn = URL(RELEASE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "FamilyHub")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val build = Regex("Build (\\d+)").find(json.optString("body"))?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                main.post {
                    if (build > mine && url != null) {
                        updateUrl = url
                        updatePill.text = "Update ready"
                        updatePill.visibility = View.VISIBLE
                    } else {
                        updatePill.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                // Offline, rate-limited or the repo is private: try again later.
            }
        }.start()
    }

    /** Installs the newer build of Family Hub itself. */
    private fun installUpdate() {
        val url = updateUrl ?: return
        installFromUrl(url, isUpdate = true)
    }

    /** Asks for a link to an APK, then downloads and installs it. */
    private fun showAddAppDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setSingleLine(true)
        input.setText(prefs.getString("last_add_url", "https://"))
        input.setSelection(input.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Add an app")
            .setMessage("Type or paste the link to the app's .apk file. Only use links you trust; Fire OS will ask before installing.")
            .setView(box)
            .setPositiveButton("Download and install") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("That doesn't look like a link. It should start with https://")
                } else {
                    prefs.edit().putString("last_add_url", url).apply()
                    installFromUrl(url, isUpdate = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    /** Downloads an APK and hands it to the system installer, which asks before installing. */
    private fun installFromUrl(url: String, isUpdate: Boolean) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow Family Hub to install apps: Settings › My Fire TV › Developer Options › Install unknown apps.")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Fire OS may not have this page; the toast says where to go.
            }
            return
        }
        if (isUpdate) {
            updatePill.text = "Downloading…"
        } else {
            toast("Downloading…")
        }
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "FamilyHub")
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                conn.inputStream.use { input ->
                    session.openWrite("download.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                conn.disconnect()
                val status = Intent(ACTION_INSTALL_STATUS).setPackage(packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(this, sessionId, status, flags)
                session.commit(pending.intentSender)
                session.close()
                main.post {
                    if (isUpdate) {
                        updatePill.text = "Installing…"
                    } else {
                        toast("Installing…")
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (isUpdate) {
                        updatePill.text = "Update ready"
                        toast("Couldn't download the update. Try again in a minute.")
                    } else {
                        toast("Couldn't download that link. Check it and try again.")
                    }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- clock and weather

    private fun updateClock() {
        val now = Date()
        clockView.text = SimpleDateFormat("HH:mm", Locale.UK).format(now)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 5 -> "Late night"
            hour < 12 -> "Morning"
            hour < 18 -> "Afternoon"
            else -> "Evening"
        }
        val name = prefs.getString("owner_name", null)?.trim().orEmpty()
        val hello = if (name.isEmpty()) greeting else "$greeting, $name"
        dateView.text = SimpleDateFormat("EEE d MMM", Locale.UK).format(now) + "  ·  " + hello
        // Swap Kids and Favourites when the hour changes, unless the drawer is open.
        if (allPanel.visibility != View.VISIBLE) orderRows()
    }

    private fun fetchWeather() {
        lastWeatherFetch = System.currentTimeMillis()
        val place = prefs.getString("loc_name", null)
        val lat = prefs.getString("loc_lat", null)
        val lon = prefs.getString("loc_lon", null)
        if (place == null || lat == null || lon == null) {
            weatherView.text = "Set weather location"
            return
        }
        if (weatherView.text.toString().let { it.isBlank() || it == "Set weather location" }) weatherView.text = place
        Thread {
            try {
                val url = "$WEATHER_URL&latitude=$lat&longitude=$lon"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val current = JSONObject(body).getJSONObject("current")
                val temp = Math.round(current.getDouble("temperature_2m"))
                val code = current.getInt("weather_code")
                val line = "$place  $temp°  " + describeWeather(code)
                main.post { weatherView.text = line }
            } catch (e: Exception) {
                // No weather is fine; try again next refresh.
                lastWeatherFetch = 0L
            }
        }.start()
    }

    /** The name in the greeting. Blank means just "Evening". */
    private fun showNameDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.setSingleLine(true)
        input.hint = "Name (or leave blank)"
        input.setText(prefs.getString("owner_name", ""))
        input.setSelection(input.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Who should it greet?")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("owner_name", input.text.toString().trim()).apply()
                updateClock()
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    /** Type a town, pick the right one from the matches, and the weather follows it. */
    private fun showLocationDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.setSingleLine(true)
        input.hint = "Town or city"
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Weather location")
            .setView(box)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) searchPlaces(q)
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun searchPlaces(query: String) {
        toast("Searching…")
        Thread {
            try {
                val conn = URL(GEOCODE_URL + Uri.encode(query)).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val results = JSONObject(body).optJSONArray("results")
                val names = ArrayList<String>()
                val shortNames = ArrayList<String>()
                val lats = ArrayList<Double>()
                val lons = ArrayList<Double>()
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val r = results.getJSONObject(i)
                        val name = r.optString("name")
                        val parts = listOf(name, r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }
                        names.add(parts.joinToString(", "))
                        shortNames.add(name)
                        lats.add(r.getDouble("latitude"))
                        lons.add(r.getDouble("longitude"))
                    }
                }
                main.post {
                    if (names.isEmpty()) {
                        toast("No places called \"$query\". Try another spelling.")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Which one?")
                            .setItems(names.toTypedArray()) { _, which ->
                                prefs.edit()
                                    .putString("loc_name", shortNames[which])
                                    .putString("loc_lat", String.format(Locale.US, "%.4f", lats[which]))
                                    .putString("loc_lon", String.format(Locale.US, "%.4f", lons[which]))
                                    .apply()
                                weatherView.text = shortNames[which]
                                fetchWeather()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                main.post { toast("Couldn't search right now. Check the internet and try again.") }
            }
        }.start()
    }

    private fun describeWeather(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Storms"
        else -> ""
    }

    // ---------------------------------------------------------------- app artwork

    /** The app's wide TV banner if it has one, else null. */
    private fun bannerOf(app: AppEntry): Drawable? {
        val pm = packageManager
        return try {
            app.info.loadBanner(pm) ?: pm.getApplicationBanner(app.pkg)
        } catch (e: Exception) {
            null
        }
    }

    private fun iconOf(app: AppEntry): Drawable? {
        return try {
            app.info.loadIcon(packageManager)
        } catch (e: Exception) {
            null
        }
    }

    /** Ring and grow on focus, OK to open, hold OK for the menu. */
    private fun makeTile(v: View, bg: Int, radiusDp: Int, onClick: () -> Unit, onHold: (() -> Unit)?) {
        v.isFocusable = true
        v.isClickable = true
        v.background = rounded(bg, false, radiusDp)
        v.clipToOutline = true
        v.setOnClickListener { onClick() }
        if (onHold != null) {
            v.setOnLongClickListener {
                onHold()
                true
            }
        }
        v.setOnFocusChangeListener { view, hasFocus ->
            view.background = rounded(bg, hasFocus, radiusDp)
            val scale = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
            view.translationZ = if (hasFocus) dp(8).toFloat() else 0f
        }
    }

    /**
     * A 16:9 tile filled edge to edge by the app's banner. Apps without one get their icon
     * and name instead. The 3 dp inset leaves room for the white focus ring.
     */
    private fun artTile(app: AppEntry): View {
        val banner = bannerOf(app)
        if (banner != null) {
            val box = FrameLayout(this)
            box.setPadding(dp(3), dp(3), dp(3), dp(3))
            val img = ImageView(this)
            img.setImageDrawable(banner)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.clipToOutline = true
            img.background = rounded(Color.TRANSPARENT, false, 9)
            box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            box.contentDescription = app.label
            makeTile(box, CARD, 12, { launch(app.pkg) }, { showAppMenu(app) })
            return box
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        val icon = iconOf(app)
        if (icon != null) {
            val img = ImageView(this)
            img.setImageDrawable(icon)
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            box.addView(img, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val label = text(app.label, 12f, TEXT, bold = true)
        label.maxLines = 1
        label.gravity = Gravity.CENTER
        box.addView(label, margins(0, dp(4), 0, 0))
        box.contentDescription = app.label
        makeTile(box, CARD, 12, { launch(app.pkg) }, { showAppMenu(app) })
        return box
    }

    /** A big Jump back in tile: banner on the left at its own 16:9 shape, name and when on the right. */
    private fun jumpTile(app: AppEntry): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(3), dp(3), dp(12), dp(3))
        val art = bannerOf(app)
        if (art != null) {
            val img = ImageView(this)
            img.setImageDrawable(art)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.clipToOutline = true
            img.background = rounded(Color.TRANSPARENT, false, 12)
            // 94 dp tall inside the 100 dp tile, so 167 dp wide keeps it 16:9.
            box.addView(img, LinearLayout.LayoutParams(dp(167), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val icon = iconOf(app)
            if (icon != null) {
                val img = ImageView(this)
                img.setImageDrawable(icon)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                box.addView(img, LinearLayout.LayoutParams(dp(64), dp(64)).also { it.marginStart = dp(12) })
            }
        }
        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        words.gravity = Gravity.CENTER_VERTICAL
        words.setPadding(dp(14), 0, 0, 0)
        val name = text(app.label, 17f, TEXT, bold = true)
        name.maxLines = 2
        words.addView(name)
        words.addView(text(lastOpenedText(app), 11f, MUTED))
        box.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        box.contentDescription = app.label
        makeTile(box, CARD, 16, { launch(app.pkg) }, { showAppMenu(app) })
        return box
    }

    // ---------------------------------------------------------------- view helpers

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun fullWidth(topMargin: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = topMargin
        return lp
    }

    private fun margins(start: Int, top: Int, end: Int, bottom: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = start
        lp.topMargin = top
        lp.marginEnd = end
        lp.bottomMargin = bottom
        return lp
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, light: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.setTextColor(color)
        tv.typeface = when {
            bold -> Typeface.create("sans-serif", Typeface.BOLD)
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
        return tv
    }

    private fun rounded(color: Int, focused: Boolean, radiusDp: Int = 16): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (focused) d.setStroke(dp(3), Color.WHITE)
        return d
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.clipChildren = false
        r.clipToPadding = false
        return r
    }

    private fun section(title: String, content: View): LinearLayout {
        val s = LinearLayout(this)
        s.orientation = LinearLayout.VERTICAL
        s.clipChildren = false
        s.clipToPadding = false
        s.addView(text(title, 14f, TEXT, bold = true), margins(0, 0, 0, dp(6)))
        s.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return s
    }

    /** A plain text tile (Settings, Show hidden). */
    private fun tile(title: String, bg: Int, fg: Int, onClick: () -> Unit): View {
        val label = text(title, 14f, fg, bold = true)
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        makeTile(label, bg, 12, onClick, null)
        return label
    }

    private fun pill(title: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        val tv = text(title, 13f, fg, bold = true)
        tv.setPadding(dp(14), dp(8), dp(14), dp(8))
        tv.isFocusable = true
        tv.isClickable = true
        tv.background = rounded(bg, false, 12)
        tv.setOnClickListener { onClick() }
        tv.setOnFocusChangeListener { v, hasFocus -> v.background = rounded(bg, hasFocus, 12) }
        return tv
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
