package io.github.design_nexus.nyx.tv.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import io.github.design_nexus.nyx.tv.NyxTvApp
import io.github.design_nexus.nyx.tv.R
import io.github.design_nexus.nyx.tv.core.LauncherConfig
import io.github.design_nexus.nyx.tv.data.AppEntry
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.PlaybackOutcome
import io.github.design_nexus.nyx.tv.media.ProviderStatus
import io.github.design_nexus.nyx.tv.ui.pages.AppsPage
import io.github.design_nexus.nyx.tv.ui.pages.DetailsPage
import io.github.design_nexus.nyx.tv.ui.pages.HomePage
import io.github.design_nexus.nyx.tv.ui.pages.LibraryPage
import io.github.design_nexus.nyx.tv.ui.pages.SearchPage
import io.github.design_nexus.nyx.tv.ui.pages.SettingsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Date

/** A screen inside the launcher. Pages are plain views swapped in one activity. */
abstract class Page(val a: HomeActivity) {
    abstract val view: View
    /** The navigation tab this page belongs to, if any. */
    open val tab: String? = null
    /** Focus the page's natural first element. */
    abstract fun focusDefault()
    open fun onShow() {}
    open fun onHide() {}
    /** Handle Back inside the page (close a mode, move to the top); false to leave the page. */
    open fun onBack(): Boolean = false
    open fun onConfigChanged(c: LauncherConfig) {}
    open fun onAppsChanged(apps: List<AppEntry>) {}
}

class HomeActivity : Activity() {
    val app get() = application as NyxTvApp
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var palette: Palette private set
    var config: LauncherConfig = LauncherConfig()
        private set
    var apps: List<AppEntry> = emptyList()
        private set

    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var scrim: View
    private lateinit var pageHost: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var clock: TextView
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private val tabs = LinkedHashMap<String, TextView>()
    private lateinit var searchBtn: ImageView
    private lateinit var gearBtn: ImageView
    private lateinit var avatar: TextView

    private val stack = ArrayList<Page>()
    private val roots = HashMap<String, Page>()
    private var backdropReq: Disposable? = null
    private var backdropPath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayReturn: View? = null
    private var ambient: AmbientView? = null
    private var lastKeyAt = SystemClock.uptimeMillis()
    private var visible = false
    private var statusJob: Job? = null
    private var firstFrameLogged = false

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            checkIdle()
            handler.postDelayed(this, 30_000)
        }
    }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { app.apps.forget(it) }
            reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            config = app.config.load()
            palette = Palette.of(config.theme, config.accent)
            build()
            showRoot("home", focus = true)
            reloadApps()
            root.post { logStartup() }
            launch { app.config.config.collectLatest { onConfig(it) } }
        }
    }

    private fun logStartup() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        val ms = SystemClock.elapsedRealtime() - app.startedAt
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putLong("last_start_ms", ms).apply()
        Log.i("NyxTV", "start: first frame ${ms} ms after process start")
    }

    private fun build() {
        val p = palette
        root = FrameLayout(this).apply { setBackgroundColor(p.base) }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(backdrop, flp(MATCH, MATCH))
        scrim = View(this)
        root.addView(scrim, flp(MATCH, MATCH))
        paintScrim(full = false)
        pageHost = FrameLayout(this).apply { clipChildren = false }
        root.addView(pageHost, flp(MATCH, MATCH))
        nav = buildNav()
        root.addView(nav, flp(MATCH, dp(66), Gravity.TOP))
        banner = text(11.5f, p.sub1).apply {
            background = rounded(alpha(p.mantle, 0.92f), dp(16).toFloat(), dp(1), alpha(p.yellow, 0.4f))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        root.addView(banner, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(70), dp(48), 0) })
        overlayHost = FrameLayout(this)
        root.addView(overlayHost, flp(MATCH, MATCH))
        setContentView(root)
    }

    private fun buildNav(): LinearLayout {
        val p = palette
        val bar = hlinear(this).apply { setPadding(dp(48), 0, dp(48), 0) }
        val mark = TextView(this).apply {
            text = "N"; textSize = 13f; typeface = BOLD; gravity = Gravity.CENTER; setTextColor(p.onAccent)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.accent, mix(p.accent, 0xFF89B4FA.toInt(), 0.6f))).apply { cornerRadius = dp(6).toFloat() }
        }
        bar.addView(mark, llp(dp(20), dp(20)) { marginEnd = dp(7) })
        bar.addView(text(15f, p.text, BOLD, s = "Nyx"))
        bar.addView(text(15f, p.sub0, s = " TV"), llp(WRAP, WRAP) { marginEnd = dp(26) })
        for ((id, label) in listOf("home" to "Home", "movies" to "Movies", "shows" to "TV Shows", "apps" to "Apps")) {
            val t = text(13.5f, p.sub0, MEDIUM, s = label).apply {
                gravity = Gravity.CENTER
                setPadding(dp(11), 0, dp(11), 0)
                isFocusable = true
                isClickable = true
                setOnClickListener { showRoot(id, focus = true) }
                setOnFocusChangeListener { _, f ->
                    paintTab(this, id, f)
                    if (f) handler.postDelayed({ if (hasFocus() && currentRootTab() != id) showRoot(id, focus = false) }, 300)
                }
            }
            tabs[id] = t
            bar.addView(t, llp(WRAP, dp(30)) { marginEnd = dp(3) })
        }
        bar.addView(View(this), llp(0, 1, 1f))
        status = text(11f, p.sub0).apply { visibility = View.GONE }
        bar.addView(status, llp(WRAP, WRAP) { marginEnd = dp(14) })
        searchBtn = iconButton(R.drawable.ic_search) { showRoot("search", focus = true) }
        gearBtn = iconButton(R.drawable.ic_gear) { openQuickSettings() }
        bar.addView(searchBtn, llp(dp(30), dp(30)) { marginEnd = dp(8) })
        bar.addView(gearBtn, llp(dp(30), dp(30)) { marginEnd = dp(10) })
        avatar = text(12f, 0xFF1E1E2E.toInt(), BOLD, s = "N").apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFAB387.toInt(), 0xFFF5C2E7.toInt())).apply { shape = GradientDrawable.OVAL }
            isFocusable = true
            isClickable = true
            setOnClickListener { push(SettingsPage(this@HomeActivity, SettingsPage.Section.PLEX)) }
            setOnFocusChangeListener { v, f -> v.foreground = if (f) rounded(0, dp(14).toFloat(), dp(2), palette.text) else null }
        }
        bar.addView(avatar, llp(dp(28), dp(28)) { marginEnd = dp(12) })
        clock = text(15f, p.text, MEDIUM)
        bar.addView(clock)
        updateClock()
        return bar
    }

    private fun iconButton(icon: Int, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        imageTintList = ColorStateList.valueOf(palette.sub1)
        isFocusable = true
        isClickable = true
        setOnClickListener { click() }
        setOnFocusChangeListener { _, f ->
            background = if (f) rounded(palette.text, dp(15).toFloat()) else null
            imageTintList = ColorStateList.valueOf(if (f) palette.base else palette.sub1)
        }
    }

    private fun paintTab(t: TextView, id: String, focused: Boolean) {
        val on = currentRootTab() == id
        t.background = when {
            focused -> rounded(palette.text, dp(15).toFloat())
            else -> null
        }
        t.setTextColor(if (focused) palette.base else if (on) palette.text else palette.sub0)
        t.foreground = if (on && !focused) underline() else null
    }

    private fun underline(): LayerDrawable {
        val line = rounded(palette.accent, dp(1).toFloat())
        return LayerDrawable(arrayOf(line)).apply { setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(2)); setLayerInset(0, dp(11), 0, dp(11), dp(1)) }
    }

    private fun refreshTabs() = tabs.forEach { (id, t) -> paintTab(t, id, t.hasFocus()) }

    fun currentRootTab(): String? = stack.firstOrNull()?.tab

    /** Scrim over the backdrop; [full] dims it evenly (library pages), else the Cinema gradients. */
    fun paintScrim(full: Boolean) {
        val base = palette.base
        val layers = if (full) arrayOf(GradientDrawable().apply { setColor(alpha(base, 0.82f)) })
        else arrayOf(
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(alpha(base, 0.96f), alpha(base, 0.78f), alpha(base, 0.1f), 0)),
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(base, alpha(base, 0.9f), alpha(base, 0f), 0)),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(alpha(base, 0.72f), 0, 0, 0)),
        )
        scrim.background = LayerDrawable(layers)
    }

    // ---------- Pages ----------

    fun showRoot(tab: String, focus: Boolean) {
        val page = roots.getOrPut(tab) {
            when (tab) {
                "movies" -> LibraryPage(this, LibraryPage.Kind.MOVIES)
                "shows" -> LibraryPage(this, LibraryPage.Kind.SHOWS)
                "apps" -> AppsPage(this)
                "search" -> SearchPage(this)
                else -> HomePage(this)
            }
        }
        stack.forEach { it.onHide() }
        stack.clear()
        stack.add(page)
        mount(page, focus)
    }

    fun push(page: Page) {
        stack.lastOrNull()?.onHide()
        stack.add(page)
        mount(page, focus = true)
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex).onHide()
        mount(stack.last(), focus = true)
    }

    private fun mount(page: Page, focus: Boolean) {
        pageHost.removeAllViews()
        (page.view.parent as? FrameLayout)?.removeView(page.view)
        pageHost.addView(page.view, flp(MATCH, MATCH))
        refreshTabs()
        page.onShow()
        if (focus) page.view.post { page.focusDefault() }
    }

    fun focusNav() {
        (tabs[currentRootTab()] ?: tabs["home"])?.requestFocus()
    }

    fun setBackdrop(path: String?) {
        if (path == backdropPath) return
        backdropPath = path
        backdropReq?.dispose()
        if (path == null) {
            backdrop.setImageDrawable(null)
            return
        }
        val url = app.plex.imageUrl(path, 1280, 720)
        val key = app.plex.imageCacheKey(path, 1280, 720)
        backdropReq = app.images.enqueue(
            ImageRequest.Builder(this).data(url).size(1280, 720).target(backdrop)
                .apply { if (key != null) { diskCacheKey(key); memoryCacheKey(key) } }.build()
        )
    }

    // ---------- Playback and actions ----------

    fun play(item: MediaItem, fromStart: Boolean = false) {
        scope.launch {
            when (val r = app.playback.play(item, fromStart)) {
                is PlaybackOutcome.Failed -> toast(r.reason)
                is PlaybackOutcome.Fallback -> {}
                is PlaybackOutcome.Started -> {}
            }
        }
    }

    fun openDetails(item: MediaItem) = push(DetailsPage(this, item))

    fun launchApp(entry: AppEntry) {
        if (!app.apps.launch(entry)) toast("${entry.label} couldn't be opened")
    }

    private var voiceResult: ((String) -> Unit)? = null

    /** Speech through the TV's own recognizer (no service of ours keeps running). */
    fun startVoice(intent: Intent, result: (String) -> Unit): Boolean {
        voiceResult = result
        val launched = runCatching { startActivityForResult(intent, VOICE); true }.getOrDefault(false)
        if (!launched) voiceResult = null
        return launched
    }

    fun startSearchVoice() {
        val page = stack.lastOrNull()
        if (page is io.github.design_nexus.nyx.tv.ui.pages.SearchPage) {
            page.startVoiceSearch()
        } else {
            showRoot("search", focus = true)
            pageHost.post {
                (stack.lastOrNull() as? io.github.design_nexus.nyx.tv.ui.pages.SearchPage)?.startVoiceSearch()
            }
        }
    }

    @Deprecated("Activity result APIs need androidx.activity; a launcher doesn't")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { voiceResult?.invoke(it) }
        }
        voiceResult = null
    }

    fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- Overlays ----------

    fun showMenu(title: String?, subtitle: String?, items: List<MenuPanel.Item>, anchorStart: Int = 0, anchorTop: Int = 0) {
        val panel = MenuPanel(this, palette, title, subtitle, items) { closeOverlay() }
        val wrap = FrameLayout(this).apply { setBackgroundColor(alpha(palette.base, 0.62f)); isClickable = true }
        val lpx = flp(dp(280), WRAP).apply {
            if (anchorStart == 0 && anchorTop == 0) gravity = Gravity.CENTER else { leftMargin = anchorStart; topMargin = anchorTop }
        }
        wrap.addView(panel, lpx)
        openOverlay(wrap)
        panel.post { panel.focusFirst() }
    }

    fun openOverlay(v: View) {
        closeOverlay()
        overlayReturn = currentFocus
        overlay = v
        overlayHost.addView(v, flp(MATCH, MATCH))
    }

    fun closeOverlay() {
        val v = overlay ?: return
        overlayHost.removeView(v)
        overlay = null
        overlayReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
        overlayReturn = null
    }

    fun openQuickSettings() = openOverlay(QuickSettings(this).also { it.post { it.focusFirst() } })

    fun startAmbient() {
        if (ambient != null) return
        val v = AmbientView(this, palette, config.ambient.source, config.ambient.showClock, config.clock24h)
        ambient = v
        root.addView(v, flp(MATCH, MATCH))
        v.start(app.cache, app.plex, app.images)
    }

    private fun stopAmbient(): Boolean {
        val v = ambient ?: return false
        v.stop()
        root.removeView(v)
        ambient = null
        return true
    }

    private fun checkIdle() {
        val amb = config.ambient
        if (!visible || !amb.enabled || ambient != null) return
        if (SystemClock.uptimeMillis() - lastKeyAt >= amb.idleMinutes * 60_000L) startAmbient()
    }

    // ---------- Keys ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastKeyAt = SystemClock.uptimeMillis()
        if (ambient != null) {
            if (event.action == KeyEvent.ACTION_UP) stopAmbient()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    startSearchVoice(); return true
                }
                KeyEvent.KEYCODE_SEARCH -> {
                    if (stack.lastOrNull()?.tab == "search") startSearchVoice()
                    else showRoot("search", focus = true)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Activity.onBackPressed is fine for a launcher on API 26+")
    override fun onBackPressed() {
        if (overlay != null) { closeOverlay(); return }
        val top = stack.lastOrNull() ?: return
        if (top.onBack()) return
        if (stack.size > 1) { pop(); return }
        if (top.tab != "home") { showRoot("home", focus = true); return }
        // A launcher never finishes on Back.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::root.isInitialized) return
        stopAmbient()
        closeOverlay()
        if (intent.hasCategory(Intent.CATEGORY_HOME)) showRoot("home", focus = true)
    }

    // ---------- Lifecycle ----------

    override fun onStart() {
        super.onStart()
        visible = true
        app.plex.setActive(true)
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        handler.post(tick)
        statusJob = scope.launch { app.plex.status.collectLatest { showStatus(it) } }
        if (::root.isInitialized) stack.lastOrNull()?.onShow()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        app.plex.setActive(false)
        runCatching { unregisterReceiver(packages) }
        handler.removeCallbacksAndMessages(null)
        statusJob?.cancel()
        stack.lastOrNull()?.onHide()
        stopAmbient()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        const val VOICE = 41
    }

    private fun updateClock() {
        if (!::clock.isInitialized) return
        clock.visibility = if (config.showClock) View.VISIBLE else View.GONE
        val fmt = if (config.clock24h) "H:mm" else "h:mm"
        clock.text = DateFormat.format(fmt, Date())
    }

    private fun showStatus(s: ProviderStatus) {
        if (!::banner.isInitialized) return
        val show = config.showServerStatus
        when (s) {
            is ProviderStatus.Offline -> {
                val mins = ((s.retryAtMs - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                banner.text = if (s.retryAtMs == Long.MAX_VALUE) "${s.serverName}: ${s.reason}"
                else "${s.serverName} is unreachable · showing your cached library · retrying in ${if (mins < 1) "under a minute" else "$mins min"}"
                banner.visibility = if (show) View.VISIBLE else View.GONE
                status.text = "● Offline"
                status.setTextColor(palette.yellow)
                status.visibility = if (show) View.VISIBLE else View.GONE
            }
            else -> {
                banner.visibility = View.GONE
                status.visibility = View.GONE
            }
        }
        val name = app.plex.session?.userName
        avatar.text = name?.firstOrNull()?.uppercase() ?: "N"
        stack.forEach { if (it is HomePage) it.onStatus(s) }
    }

    private fun onConfig(c: LauncherConfig) {
        val old = config
        config = c
        if (c.theme != old.theme || c.accent != old.accent) {
            palette = Palette.of(c.theme, c.accent)
            roots.clear()
            val tab = currentRootTab() ?: "home"
            build()
            showRoot(tab, focus = true)
            if (tab != "home" || stack.size > 0) refreshTabs()
            return
        }
        updateClock()
        roots.values.forEach { it.onConfigChanged(c) }
        stack.filter { it !in roots.values }.forEach { it.onConfigChanged(c) }
    }

    fun reloadApps() {
        scope.launch {
            apps = app.apps.load()
            roots.values.forEach { it.onAppsChanged(apps) }
            stack.filter { it !in roots.values }.forEach { it.onAppsChanged(apps) }
        }
    }
}
