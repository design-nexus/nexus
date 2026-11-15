package io.github.design_nexus.nyx.tv.ui.pages

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.R
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.core.LauncherConfig
import io.github.design_nexus.nyx.tv.core.RowConfig
import io.github.design_nexus.nyx.tv.core.RowType
import io.github.design_nexus.nyx.tv.data.AppEntry
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.ProviderStatus
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.Cell
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MEDIUM
import io.github.design_nexus.nyx.tv.ui.MenuPanel
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.PillButton
import io.github.design_nexus.nyx.tv.ui.QuickSettings
import io.github.design_nexus.nyx.tv.ui.RowData
import io.github.design_nexus.nyx.tv.ui.RowHost
import io.github.design_nexus.nyx.tv.ui.RowView
import io.github.design_nexus.nyx.tv.ui.RowsAdapter
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.alpha
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.formatRuntime
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.rounded
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Ratings as small chips. */
fun ratingChips(a: HomeActivity, item: MediaItem): LinearLayout {
    val p = a.palette
    val row = hlinear(a)
    for (r in item.ratings.take(3)) {
        val label = when (r.source) {
            "rt_critic" -> "● Critics ${r.percent}%"
            "rt_audience" -> "Audience ${r.percent}%"
            "imdb" -> "IMDb ${"%.1f".format(r.value)}"
            "tmdb" -> "TMDB ${"%.1f".format(r.value)}"
            else -> continue
        }
        val t = a.text(10.5f, p.text, MEDIUM, s = label).apply {
            setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3))
            background = rounded(alpha(p.o0, 0.3f), a.dp(5).toFloat())
        }
        row.addView(t, llp(WRAP, WRAP) { marginEnd = a.dp(6) })
    }
    return row
}

fun metaLine(item: MediaItem): String = listOfNotNull(
    item.episodeCode,
    item.year?.toString(),
    item.genres.take(2).joinToString(", ").ifEmpty { null },
    item.durationMs.takeIf { it > 0 }?.let { formatRuntime(it) },
    item.contentRating,
).joinToString("  ·  ")

/** Home in direction A (Cinema): the focused title's backdrop behind a hero and rows. */
class HomePage(a: HomeActivity) : Page(a), RowHost {
    override val tab = "home"
    override val palette: Palette get() = a.palette
    override val provider: MediaProvider get() = a.app.plex
    override val images: ImageLoader get() = a.app.images
    override val appRepo: AppRepository get() = a.app.apps
    override val reduceMotion: Boolean get() = a.config.reduceMotion
    override val focus = FocusMemory()
    override val scope: CoroutineScope get() = a.scope

    private val handler = Handler(Looper.getMainLooper())
    private val root: FrameLayout = object : FrameLayout(a) {
        override fun focusSearch(focused: View?, direction: Int): View? {
            if (direction == View.FOCUS_UP && !heroMode && showingHero() && focused != null) {
                var cur: View? = focused
                var inList = false
                while (cur != null && cur !== this) {
                    if (cur === list) { inList = true; break }
                    cur = cur.parent as? View
                }
                if (inList && focus.currentRow == adapter.rows.firstOrNull()?.id) {
                    enterHero()
                    return playBtn
                }
            }
            return super.focusSearch(focused, direction)
        }
    }.apply { clipChildren = false }
    override val view: View get() = root

    private val info = vlinear(a)
    private val eyebrow = a.text(11f, palette.accent, BOLD).apply { letterSpacing = 0.14f }
    private val title = a.text(44f, palette.text, BOLD, 2)
    private val meta = a.text(13f, palette.sub1, MEDIUM)
    private var ratings: LinearLayout = hlinear(a)
    private val summary = a.text(13.5f, palette.sub1, lines = 3).apply { setLineSpacing(0f, 1.15f) }
    private val buttons = hlinear(a)
    private val playBtn = PillButton(a, palette, "Play", R.drawable.ic_play, primary = true, reduceMotion = reduceMotion)
    private val infoBtn = PillButton(a, palette, "More info", R.drawable.ic_info, reduceMotion = reduceMotion)
    private val list = RecyclerView(a)
    private val adapter = RowsAdapter(this)

    private var featured: MediaItem? = null
    private var focusedItem: MediaItem? = null
    private var unconfigured = false
    private var heroMode = true
    private val rowData = LinkedHashMap<String, RowData>()
    private val jobs = ArrayList<Job>()
    private var lastRefresh = 0L
    private var configRows: List<RowConfig> = emptyList()
    private var heroEnabled = true

    init {
        info.addView(eyebrow)
        info.addView(title, llp(MATCH, WRAP) { topMargin = a.dp(8); bottomMargin = a.dp(10) })
        info.addView(meta)
        info.addView(ratings, llp(WRAP, WRAP) { topMargin = a.dp(10) })
        info.addView(summary, llp(a.dp(470), WRAP) { topMargin = a.dp(12) })
        buttons.addView(playBtn, llp(WRAP, a.dp(38)) { marginEnd = a.dp(10) })
        buttons.addView(infoBtn, llp(WRAP, a.dp(38)))
        buttons.clipChildren = false
        info.addView(buttons, llp(WRAP, WRAP) { topMargin = a.dp(18) })
        info.clipChildren = false
        root.addView(info, flp(a.dp(600), WRAP).apply { setMargins(a.dp(48), a.dp(84), 0, 0) })

        list.layoutManager = object : LinearLayoutManager(a, VERTICAL, false) {
            // Keep the focused row at the top of the rows area instead of scrolling the minimum.
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                val dy = child.top - parent.paddingTop
                if (dy == 0) return false
                if (immediate || reduceMotion) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
                return true
            }
        }
        list.adapter = adapter
        list.itemAnimator = null
        list.clipChildren = false
        list.clipToPadding = false
        list.setPadding(0, 0, 0, a.dp(200))
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        root.addView(list, flp(MATCH, MATCH).apply { topMargin = a.dp(ROWS_TOP) })

        playBtn.setOnClickListener {
            if (unconfigured) a.push(SettingsPage(a, SettingsPage.Section.PLEX)) else featured?.let { f -> a.play(f) }
        }
        infoBtn.setOnClickListener { featured?.let(a::openDetails) }
        val toRows = View.OnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { focusFirstRow(); true }
            else if (code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN) { a.focusNav(); true } else false
        }
        playBtn.setOnKeyListener(toRows)
        infoBtn.setOnKeyListener(toRows)
        playBtn.setOnFocusChangeListener(chain(playBtn.onFocusChangeListener) { if (it) enterHero() })
        infoBtn.setOnFocusChangeListener(chain(infoBtn.onFocusChangeListener) { if (it) enterHero() })
        applyConfig(a.config)
    }

    private fun chain(prev: View.OnFocusChangeListener?, f: (Boolean) -> Unit) = View.OnFocusChangeListener { v, has -> prev?.onFocusChange(v, has); f(has) }

    override fun focusDefault() {
        if (showingHero()) playBtn.requestFocus() else focusRows()
    }

    private fun showingHero() = heroEnabled && (featured != null || unconfigured)

    private fun focusRows() {
        val lm = list.layoutManager as LinearLayoutManager
        val ids = adapter.rows.map { it.id }
        val target = focus.rowToFocus(ids) ?: return
        val pos = ids.indexOf(target)
        val v = lm.findViewByPosition(pos) as? RowView
        if (v != null) v.focusRemembered(focus) else {
            lm.scrollToPositionWithOffset(pos, 0)
            list.post { (lm.findViewByPosition(pos) as? RowView)?.focusRemembered(focus) }
        }
    }

    /** Down from the hero: first row, first cell — not the pre-Home focus. */
    private fun focusFirstRow() {
        if (adapter.rows.isEmpty()) return
        val lm = list.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(0, 0)
        list.post { (lm.findViewByPosition(0) as? RowView)?.focusFirst() }
    }

    private fun enterHero() {
        if (!showingHero()) return
        heroMode = true
        focus.clear()
        list.scrollToPosition(0)
        moveRows(HERO_SHIFT)
        showInfo(featured, hero = true)
        a.setBackdrop(featured?.backdrop)
    }

    private fun moveRows(shiftDp: Int) {
        val ty = a.dp(shiftDp).toFloat()
        if (reduceMotion) list.translationY = ty else list.animate().translationY(ty).setDuration(160).start()
    }

    private fun showInfo(item: MediaItem?, hero: Boolean) {
        buttons.visibility = if (hero) View.VISIBLE else View.GONE
        title.textSize = if (hero) 44f else 30f
        summary.maxLines = if (hero) 3 else 2
        info.removeView(ratings)
        if (unconfigured && hero) {
            eyebrow.text = "PLEX"
            eyebrow.visibility = View.VISIBLE
            title.text = "Your library, front and centre"
            meta.text = "Sign in to see Continue Watching, new movies and shows here"
            summary.text = "Nyx TV works without Plex too: your apps are below."
            playBtn.setText("Sign in to Plex")
            infoBtn.visibility = View.GONE
            ratings = hlinear(a)
            info.addView(ratings, 3)
            return
        }
        infoBtn.visibility = View.VISIBLE
        if (item == null) {
            eyebrow.text = ""; eyebrow.visibility = View.GONE
            title.text = ""; meta.text = ""; summary.text = ""
            ratings = hlinear(a)
            info.addView(ratings, 3)
            return
        }
        val listKind = item.kind == MediaKind.COLLECTION || item.kind == MediaKind.PLAYLIST
        eyebrow.text = when {
            listKind && item.kind == MediaKind.COLLECTION -> "COLLECTION"
            listKind && item.kind == MediaKind.PLAYLIST -> "PLAYLIST"
            hero && item.kind == MediaKind.SHOW -> "NEW IN TV SHOWS"
            hero -> "NEW IN MOVIES"
            else -> ""
        }
        eyebrow.visibility = if (eyebrow.text.isNotEmpty()) View.VISIBLE else View.GONE
        title.text = if (item.kind == MediaKind.EPISODE) item.seriesTitle ?: item.title else item.title
        meta.text = when {
            listKind -> item.childCount?.let { "$it titles" }.orEmpty()
            else -> {
                val left = if (item.progress > 0) "${formatRuntime(item.remainingMs)} left" else null
                listOfNotNull(metaLine(item).ifEmpty { null }, left).joinToString("  ·  ")
            }
        }
        ratings = if (hero && !listKind) ratingChips(a, item) else hlinear(a)
        info.addView(ratings, 3)
        summary.text = item.summary
        playBtn.setText(if (item.progress > 0) "Resume" else "Play")
    }

    /** App identity in the strip — name/meta only; OK opens the app, no Open CTA. */
    private fun showAppStrip(entry: AppEntry) {
        buttons.visibility = View.GONE
        title.textSize = 30f
        summary.maxLines = 2
        info.removeView(ratings)
        ratings = hlinear(a)
        info.addView(ratings, 3)
        eyebrow.text = "APP"
        eyebrow.visibility = View.VISIBLE
        title.text = entry.label
        val ver = a.app.apps.versionName(entry.pkg)
        meta.text = listOfNotNull(
            if (entry.tv) "Android TV app" else "App",
            ver?.let { "Version $it" },
        ).joinToString("  ·  ")
        summary.text = if (entry.system) "System app on this TV." else "Installed on this TV. Press OK to open."
    }

    // ---------- RowHost ----------

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {
        when (cell) {
            is Cell.Media -> {
                focusedItem = cell.item
                if (heroMode) {
                    heroMode = false
                    moveRows(0)
                }
                handler.removeCallbacksAndMessages(null)
                showInfo(cell.item, hero = false)
                // Collections/playlists: keep the last title backdrop — composite/poster
                // as a 1280 backdrop thrashing decode was the Home lag.
                val listKind = cell.item.kind == MediaKind.COLLECTION || cell.item.kind == MediaKind.PLAYLIST
                if (!listKind) {
                    handler.postDelayed({ a.setBackdrop(cell.item.backdrop ?: featured?.backdrop) }, 250)
                }
            }
            is Cell.App -> {
                if (heroMode) {
                    heroMode = false
                    moveRows(0)
                }
                handler.removeCallbacksAndMessages(null)
                showAppStrip(cell.entry)
                // Hold the last title backdrop — apps have no art for Home.
            }
        }
    }

    override fun onCellClicked(row: RowData, cell: Cell) {
        when (cell) {
            is Cell.App -> a.launchApp(cell.entry)
            is Cell.Media -> {
                val type = configRows.firstOrNull { it.id == row.id }?.type
                if (type == RowType.CONTINUE_WATCHING || type == RowType.NEXT_EPISODES) a.play(cell.item) else a.openDetails(cell.item)
            }
        }
    }

    override fun onCellMenu(row: RowData, cell: Cell, view: View) {
        when (cell) {
            is Cell.App -> appMenu(a, cell.entry)
            is Cell.Media -> mediaMenu(cell.item, row)
        }
    }

    private fun mediaMenu(i: MediaItem, row: RowData) {
        val items = ArrayList<MenuPanel.Item>()
        if (i.kind == MediaKind.MOVIE || i.kind == MediaKind.EPISODE) {
            if (i.progress > 0) {
                items += MenuPanel.Item("Resume", formatRuntime(i.viewOffsetMs), icon = R.drawable.ic_play) { a.play(i) }
                items += MenuPanel.Item("Play from the beginning") { a.play(i, fromStart = true) }
            } else items += MenuPanel.Item("Play", icon = R.drawable.ic_play) { a.play(i) }
            items += MenuPanel.Item(if (i.watched) "Mark as unwatched" else "Mark as watched", icon = R.drawable.ic_check) { setWatched(i, !i.watched) }
        }
        if (i.kind == MediaKind.EPISODE && i.seriesId != null) {
            items += MenuPanel.Item("Go to show", icon = R.drawable.ic_tv) {
                a.openDetails(i.copy(id = i.seriesId!!, kind = MediaKind.SHOW, title = i.seriesTitle ?: i.title, thumb = null, seasonNumber = null, episodeNumber = null))
            }
        }
        items += MenuPanel.Item("Details", icon = R.drawable.ic_info) { a.openDetails(i) }
        items += MenuPanel.Item("Hide this row") { a.app.config.update { it.updateRow(row.id) { r -> r.copy(visible = false) } } }
        val title = if (i.kind == MediaKind.EPISODE) i.seriesTitle ?: i.title else i.title
        a.showMenu(title, listOfNotNull(i.episodeCode, i.year?.toString()).joinToString(" · ").ifEmpty { null }, items)
    }

    private fun setWatched(i: MediaItem, watched: Boolean) {
        a.scope.launch {
            runCatching { provider.setWatched(i, watched) }.onFailure { a.toast("The server couldn't be reached") }
            refresh(force = true)
        }
    }

    // ---------- Data ----------

    override fun onShow() {
        a.paintScrim(full = false)
        a.setBackdrop(if (heroMode) featured?.backdrop else (focusedItem?.backdrop ?: featured?.backdrop))
        if (SystemClock.elapsedRealtime() - lastRefresh > REFRESH_MS) refresh(force = false)
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBack(): Boolean {
        if (!heroMode && showingHero()) {
            enterHero()
            playBtn.post { playBtn.requestFocus() }
            return true
        }
        if (!showingHero() && list.computeVerticalScrollOffset() > 0) { list.scrollToPosition(0); focus.forget(focus.currentRow ?: ""); focusRows(); return true }
        return false
    }

    override fun onConfigChanged(c: LauncherConfig) = applyConfig(c)
    override fun onAppsChanged(apps: List<AppEntry>) = rebuildApps()

    fun onStatus(s: ProviderStatus) {
        val was = unconfigured
        unconfigured = s is ProviderStatus.Unconfigured
        if (was != unconfigured) { updateHero(); refresh(force = true) }
        else if (s is ProviderStatus.Online && SystemClock.elapsedRealtime() - lastRefresh > 30_000) refresh(force = true)
    }

    private fun applyConfig(c: LauncherConfig) {
        val changed = c.rows != configRows || c.heroEnabled != heroEnabled
        configRows = c.rows
        heroEnabled = c.heroEnabled
        unconfigured = a.app.plex.status.value is ProviderStatus.Unconfigured
        if (changed) {
            rowData.keys.retainAll(c.rows.filter { it.visible }.map { it.id }.toSet())
            publish()
            updateHero()
            refresh(force = true)
        } else rebuildApps()
    }

    private fun updateHero() {
        val hero = showingHero()
        info.visibility = View.VISIBLE
        heroMode = hero
        list.translationY = if (hero) a.dp(HERO_SHIFT).toFloat() else 0f
        if (hero) showInfo(featured, hero = true) else if (rowData.isEmpty()) showInfo(null, false)
        a.setBackdrop(featured?.backdrop)
    }

    private fun rebuildApps() {
        val appsRow = configRows.firstOrNull { it.type == RowType.FAVORITE_APPS && it.visible } ?: return
        rowData[appsRow.id] = RowData(appsRow.id, appsRow.title ?: "Favorite Apps", CardStyle.APP_TILE, favoriteApps(appsRow.limit).map { Cell.App(it) })
        publish()
    }

    private fun favoriteApps(limit: Int): List<AppEntry> {
        val apps = a.apps.filter { it.pkg !in a.config.hiddenApps }
        val byPkg = apps.associateBy { it.pkg }
        val favs = a.config.favorites.mapNotNull { byPkg[it] }
        val list = favs.ifEmpty { LauncherConfig.DEFAULT_FAVORITES.mapNotNull { byPkg[it] } + apps.filter { it.tv && it.pkg !in LauncherConfig.DEFAULT_FAVORITES } }
        return list.take(limit)
    }

    private fun titleFor(r: RowConfig): String = r.title ?: when (r.type) {
        RowType.CONTINUE_WATCHING -> "Continue Watching"
        RowType.FAVORITE_APPS -> "Favorite Apps"
        RowType.RECENT_MOVIES -> "Recently Added Movies"
        RowType.RECENT_SHOWS -> "Recently Added TV Shows"
        RowType.NEXT_EPISODES -> "Next Episodes"
        RowType.COLLECTIONS -> "Collections"
        RowType.PLAYLISTS -> "Playlists"
        RowType.LIBRARY -> "Library"
        RowType.GENRE -> r.genre ?: "Genre"
    }

    fun refresh(force: Boolean) {
        if (!force && SystemClock.elapsedRealtime() - lastRefresh < REFRESH_MS) return
        lastRefresh = SystemClock.elapsedRealtime()
        jobs.forEach { it.cancel() }
        jobs.clear()
        rebuildApps()
        for (r in configRows.filter { it.visible && it.type != RowType.FAVORITE_APPS }) {
            jobs += a.scope.launch {
                a.app.home.row(r, refresh = !unconfigured).collect { items ->
                    rowData[r.id] = RowData(r.id, titleFor(r), r.style, items.map { Cell.Media(it) })
                    publish()
                    if (r.type == RowType.RECENT_MOVIES || r.type == RowType.RECENT_SHOWS) pickFeatured()
                }
            }
        }
        jobs += a.scope.launch { pickFeatured() }
    }

    private suspend fun pickFeatured() {
        val f = a.app.home.featured(configRows)
        if (f?.key != featured?.key) {
            val first = featured == null
            featured = f
            if (heroMode || (first && heroEnabled && !list.hasFocus())) {
                updateHero()
                if (first && root.isAttachedToWindow && !a.isOverlayFocus()) playBtn.requestFocus()
            }
        }
    }

    private fun publish() {
        val order = configRows.filter { it.visible }.map { it.id }
        val rows = order.mapNotNull { rowData[it] }.filter { it.cells.isNotEmpty() }
        val focusedRow = focus.currentRow
        adapter.submit(rows)
        if (rows.isNotEmpty() && !showingHero() && !list.hasFocus() && !a.isOverlayFocus() && focusedRow == null && root.isAttachedToWindow) list.post { focusRows() }
    }

    companion object {
        const val ROWS_TOP = 236
        const val HERO_SHIFT = 150
        const val REFRESH_MS = 5 * 60_000L
    }
}

private fun HomeActivity.isOverlayFocus(): Boolean = currentFocus?.let { f -> generateSequence(f.parent) { it.parent }.any { it is MenuPanel || it is QuickSettings } } ?: false

/** The menu for an app tile, shared by Home and Apps. */
fun appMenu(a: HomeActivity, e: AppEntry) {
    val fav = e.pkg in a.config.favorites
    val items = ArrayList<MenuPanel.Item>()
    items += MenuPanel.Item("Open", icon = R.drawable.ic_play) { a.launchApp(e) }
    if (fav) {
        items += MenuPanel.Item("Move left", "◀") { a.app.config.update { it.moveFavorite(e.pkg, -1) } }
        items += MenuPanel.Item("Move right", "▶") { a.app.config.update { it.moveFavorite(e.pkg, 1) } }
    }
    items += MenuPanel.Item(if (fav) "Remove from favorites" else "Add to favorites") {
        a.app.config.update { c -> if (c.favorites.isEmpty() && !fav) c.copy(favorites = a.apps.filter { it.pkg in LauncherConfig.DEFAULT_FAVORITES }.map { it.pkg } + e.pkg) else c.toggleFavorite(e.pkg) }
    }
    items += MenuPanel.Item("Hide") { a.app.config.update { it.setHidden(e.pkg, true) } }
    items += MenuPanel.Item("App info", icon = R.drawable.ic_info) { a.app.apps.openInfo(e.pkg) }
    if (!e.system) items += MenuPanel.Item("Uninstall", danger = true) { a.app.apps.uninstall(e.pkg) }
    a.showMenu(e.label, a.app.apps.versionName(e.pkg)?.let { "Version $it" }, items)
}
