package io.github.design_nexus.nyx.tv.ui.pages

import android.graphics.drawable.GradientDrawable
import android.os.Debug
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.design_nexus.nyx.tv.BuildConfig
import io.github.design_nexus.nyx.tv.core.AmbientSource
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.LauncherConfig
import io.github.design_nexus.nyx.tv.core.RowConfig
import io.github.design_nexus.nyx.tv.core.RowType
import io.github.design_nexus.nyx.tv.data.AppEntry
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.device.HomeKeyFallbackService
import io.github.design_nexus.nyx.tv.device.TvSystem
import io.github.design_nexus.nyx.tv.media.Library
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.ProviderStatus
import io.github.design_nexus.nyx.tv.plex.PlexPin
import io.github.design_nexus.nyx.tv.plex.PlexServer
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MEDIUM
import io.github.design_nexus.nyx.tv.ui.MenuPanel
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.PillButton
import io.github.design_nexus.nyx.tv.ui.SettingRow
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.alpha
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.mix
import io.github.design_nexus.nyx.tv.ui.rounded
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsPage(a: HomeActivity, private var section: Section = Section.HOME) : Page(a) {
    enum class Section(val title: String) {
        HOME("Home screen"), ROWS("Content rows"), APPEARANCE("Appearance"), APPS("Apps"), PLEX("Plex"),
        SEERR("Seerr"), PLAYBACK("Playback"), AMBIENT("Ambient mode"), TV("TV and inputs"), ABOUT("About"),
    }

    private val p: Palette get() = a.palette
    private val root = FrameLayout(a)
    override val view: View get() = root
    private val nav = vlinear(a)
    private val navRows = HashMap<Section, SettingRow>()
    private val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; isFillViewport = false }
    private val body = vlinear(a)
    private var pinJob: Job? = null
    private var movingRow: String? = null
    private var clearing = false
    /** Appearance preview (not committed until Apply). */
    private var previewTheme: String? = null
    private var previewAccent: String? = null
    private var restoreFocusTag: Any? = null
    private var appearancePreviewHost: FrameLayout? = null
    private var appearanceStatus: TextView? = null
    private var appearanceHint: TextView? = null
    private var appearanceApply: PillButton? = null
    private var appearanceCancel: PillButton? = null
    private var appearanceAccentLabel: View? = null
    private var appearanceAccentRow: View? = null
    private val appearanceCards = LinkedHashMap<String, LinearLayout>()
    private val appearanceStateLabels = LinkedHashMap<String, TextView>()
    private val appearanceAccentViews = LinkedHashMap<String, View>()

    /** Empties the panel without letting the focus it loses land on another section (which would redraw it mid-clear). */
    private fun clearBody() {
        clearing = true
        if (body.hasFocus()) navRows[section]?.requestFocus()
        body.removeAllViews()
        appearancePreviewHost = null
        appearanceStatus = null
        appearanceHint = null
        appearanceApply = null
        appearanceCancel = null
        appearanceAccentLabel = null
        appearanceAccentRow = null
        appearanceCards.clear()
        appearanceStateLabels.clear()
        appearanceAccentViews.clear()
        clearing = false
    }

    init {
        val title = a.text(22f, p.text, BOLD, s = "Settings")
        title.contentDescription = "Settings"
        nav.addView(title, llp(WRAP, WRAP) { setMargins(a.dp(14), 0, 0, a.dp(12)) })
        val firstSection = Section.entries.first()
        for (s in Section.entries) {
            val r = SettingRow(a, p, s.title)
            r.contentDescription = s.title
            r.setOnFocusChangeListener(chain(r.onFocusChangeListener) { if (it && !clearing && section != s) { section = s; renderBody() } })
            r.setOnClickListener { focusBody() }
            r.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { focusBody(); true }
                    // Top of Settings: never leak Up into the Home tab bar.
                    KeyEvent.KEYCODE_DPAD_UP -> s == firstSection
                    else -> false
                }
            }
            navRows[s] = r
            nav.addView(r, llp(MATCH, WRAP))
        }
        root.addView(nav, flp(a.dp(250), WRAP).apply { setMargins(a.dp(44), a.dp(76), 0, 0) })
        body.setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(40))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        scroll.background = rounded(alpha(p.mantle, 0.92f), a.dp(14).toFloat(), a.dp(1), alpha(p.o0, 0.18f))
        root.addView(scroll, flp(MATCH, MATCH).apply { setMargins(a.dp(320), a.dp(80), a.dp(48), a.dp(30)) })
        renderBody()
    }

    private fun chain(prev: View.OnFocusChangeListener?, f: (Boolean) -> Unit) = View.OnFocusChangeListener { v, has -> prev?.onFocusChange(v, has); f(has) }

    override fun focusDefault() { navRows[section]?.requestFocus() }

    override fun onShow() {
        a.paintScrim(full = true)
    }

    override fun onHide() { pinJob?.cancel() }

    override fun onBack(): Boolean {
        if (movingRow != null) { movingRow = null; renderBody(); return true }
        if (body.hasFocus()) { navRows[section]?.requestFocus(); return true }
        return false
    }

    override fun onConfigChanged(c: LauncherConfig) {
        if (section == Section.APPEARANCE) {
            previewTheme = c.theme
            previewAccent = c.accent
        }
        if (section != Section.PLEX) renderBody(keepFocus = true)
    }

    override fun onAppsChanged(apps: List<AppEntry>) {
        if (section == Section.APPS) renderBody(keepFocus = true)
    }

    private fun focusBody() {
        (0 until body.childCount).map { body.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
            ?: firstFocusable(body)?.requestFocus()
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---------- Building blocks ----------

    private fun heading(t: String, sub: String? = null) {
        body.addView(a.text(17f, p.text, BOLD, s = t), llp(WRAP, WRAP) { setMargins(a.dp(14), 0, 0, a.dp(4)) })
        if (sub != null) body.addView(a.text(11.5f, p.sub0, lines = 3, s = sub), llp(MATCH, WRAP) { setMargins(a.dp(14), 0, a.dp(14), a.dp(10)) })
    }

    private fun group(t: String) {
        body.addView(a.text(10.5f, p.sub0, BOLD, s = t.uppercase()).apply { letterSpacing = 0.1f }, llp(WRAP, WRAP) { setMargins(a.dp(14), a.dp(14), 0, a.dp(4)) })
    }

    /** Leave the body only via Left (or Back) — back to this section's sidebar entry. */
    private fun leaveBodyToSidebar() {
        navRows[section]?.requestFocus()
    }

    private fun inBody(v: View?): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === body || cur === scroll) return true
            cur = cur.parent as? View
        }
        return false
    }

    /**
     * Body focus contract: Up/Down never leave the page. Left returns to this section's
     * sidebar row. [also] can handle extra keys (and may consume Right, etc.).
     */
    private fun View.trapBodyKeys(also: ((code: Int, e: KeyEvent) -> Boolean)? = null) {
        setOnKeyListener { v, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Allow a page to send Left somewhere else first (e.g. accents → theme list).
                    if (also?.invoke(code, e) == true) true
                    else { leaveBodyToSidebar(); true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (also?.invoke(code, e) == true) return@setOnKeyListener true
                    val next = v.focusSearch(View.FOCUS_UP)
                    !inBody(next) // consume when Up would leave the page
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (also?.invoke(code, e) == true) return@setOnKeyListener true
                    val next = v.focusSearch(View.FOCUS_DOWN)
                    !inBody(next)
                }
                else -> also?.invoke(code, e) == true
            }
        }
    }

    private fun toggle(t: String, d: String?, on: Boolean, set: (LauncherConfig, Boolean) -> LauncherConfig): SettingRow {
        val r = SettingRow(a, p, t, d).apply { this.on = on }
        r.setOnClickListener {
            val next = !(r.on ?: false)
            r.on = next
            a.app.config.update { c -> set(c, next) }
        }
        r.trapBodyKeys()
        body.addView(r, llp(MATCH, WRAP))
        return r
    }

    private fun action(t: String, d: String? = null, value: String? = null, click: () -> Unit): SettingRow {
        val r = SettingRow(a, p, t, d)
        value?.let { r.value.text = it }
        r.setOnClickListener { click() }
        r.trapBodyKeys()
        body.addView(r, llp(MATCH, WRAP))
        return r
    }

    private fun <T> choice(t: String, d: String?, options: List<Pair<T, String>>, current: T, set: (T) -> Unit): SettingRow {
        val r = SettingRow(a, p, t, d)
        r.value.text = options.firstOrNull { it.first == current }?.second ?: ""
        r.setOnClickListener {
            val i = options.indexOfFirst { it.first == current }
            set(options[(i + 1) % options.size].first)
        }
        r.trapBodyKeys()
        body.addView(r, llp(MATCH, WRAP))
        return r
    }

    private fun renderBody(keepFocus: Boolean = false) {
        val focusedIndex = if (keepFocus && body.hasFocus() && restoreFocusTag == null) {
            (0 until body.childCount).indexOfFirst { body.getChildAt(it).hasFocus() }
        } else -1
        val tag = restoreFocusTag
        restoreFocusTag = null
        clearBody()
        pinJob?.cancel()
        val c = a.config
        when (section) {
            Section.HOME -> home(c)
            Section.ROWS -> rows(c)
            Section.APPEARANCE -> appearance(c)
            Section.APPS -> apps(c)
            Section.PLEX -> plex()
            Section.SEERR -> seerr()
            Section.PLAYBACK -> playback()
            Section.AMBIENT -> ambient(c)
            Section.TV -> tv()
            Section.ABOUT -> about()
        }
        when {
            tag != null -> body.post { body.findViewWithTag<View>(tag)?.requestFocus() }
            focusedIndex >= 0 -> body.post {
                body.getChildAt(focusedIndex.coerceAtMost(body.childCount - 1))?.let { (firstFocusable(it) ?: it).requestFocus() }
            }
        }
    }

    // ---------- Sections ----------

    private fun home(c: LauncherConfig) {
        heading("Home screen", "What Home shows and how it starts.")
        group("Featured")
        toggle("Featured hero", "A large backdrop for one title at the top of Home", c.heroEnabled) { x, v -> x.copy(heroEnabled = v) }
        action("Trailers and video backgrounds", "Not available yet — heroes stay still", "Coming later") {}
        group("Layout")
        val appsIndex = c.rows.indexOfFirst { it.type == RowType.FAVORITE_APPS }
        val place = when (appsIndex) { 0 -> "Top"; c.rows.lastIndex -> "Bottom"; -1 -> "Off"; else -> "After row $appsIndex" }
        action("Favorite apps", "Where the apps row sits among the media rows", place) {
            a.app.config.update { x ->
                val i = x.rows.indexOfFirst { it.type == RowType.FAVORITE_APPS }
                if (i < 0) x else x.moveRow(x.rows[i].id, if (i >= x.rows.lastIndex) -x.rows.size else 1)
            }
        }
        toggle("Clock", null, c.showClock) { x, v -> x.copy(showClock = v) }
        toggle("24-hour clock", null, c.clock24h) { x, v -> x.copy(clock24h = v) }
        toggle("Show server status", "A quiet note when Plex can't be reached", c.showServerStatus) { x, v -> x.copy(showServerStatus = v) }
        toggle("Reduce motion", "No focus zoom or smooth scrolling", c.reduceMotion) { x, v -> x.copy(reduceMotion = v) }
    }

    private fun rowTitle(r: RowConfig) = r.title ?: when (r.type) {
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

    private fun styleName(s: CardStyle) = when (s) {
        CardStyle.LANDSCAPE -> "Landscape"; CardStyle.POSTER -> "Poster"; CardStyle.SQUARE -> "Square"; CardStyle.APP_TILE -> "App tiles"
    }

    private fun rows(c: LauncherConfig) {
        heading("Content rows", if (movingRow != null) "Up and down move the row · OK or Back to drop it" else "OK shows or hides a row. Hold OK (or press Menu) to move it, change its cards or remove it.")
        for (r in c.rows) {
            val moving = r.id == movingRow
            val row = SettingRow(a, p, (if (moving) "↕  " else "") + rowTitle(r), "${styleName(r.style)} · ${r.limit} items" + (r.libraryId?.let { " · library $it" } ?: ""))
            row.on = r.visible
            row.setOnClickListener {
                if (movingRow != null) { movingRow = null; renderBody(keepFocus = true) }
                else a.app.config.update { it.updateRow(r.id) { x -> x.copy(visible = !x.visible) } }
            }
            row.setOnLongClickListener { rowMenu(r); true }
            row.setOnKeyListener { v, code, e ->
                if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) { rowMenu(r); return@setOnKeyListener true }
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { leaveBodyToSidebar(); return@setOnKeyListener true }
                if (movingRow == r.id && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    restoreFocusTag = r.id
                    a.app.config.update { it.moveRow(r.id, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) }
                    body.post { focusRow(r.id) }
                    return@setOnKeyListener true
                }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> !inBody(v.focusSearch(View.FOCUS_UP))
                    KeyEvent.KEYCODE_DPAD_DOWN -> !inBody(v.focusSearch(View.FOCUS_DOWN))
                    else -> false
                }
            }
            if (moving) row.background = rounded(0, a.dp(10).toFloat(), a.dp(2), p.accent)
            row.tag = r.id
            body.addView(row, llp(MATCH, WRAP))
        }
        action("Add a row", "A library, a genre, or a built-in row you removed") { addRowMenu() }
    }

    private fun focusRow(id: String) {
        for (i in 0 until body.childCount) if (body.getChildAt(i).tag == id) { body.getChildAt(i).requestFocus(); return }
    }

    private fun rowMenu(r: RowConfig) {
        val items = ArrayList<MenuPanel.Item>()
        items += MenuPanel.Item("Move") { movingRow = r.id; renderBody(); body.post { focusRow(r.id) } }
        if (r.type != RowType.FAVORITE_APPS) {
            for (s in listOf(CardStyle.LANDSCAPE, CardStyle.POSTER, CardStyle.SQUARE)) if (s != r.style)
                items += MenuPanel.Item("Cards: ${styleName(s)}") { a.app.config.update { it.updateRow(r.id) { x -> x.copy(style = s) } } }
        }
        for (n in listOf(6, 12, 20, 40)) if (n != r.limit) items += MenuPanel.Item("Show $n items") { a.app.config.update { it.updateRow(r.id) { x -> x.copy(limit = n) } } }
        if (r.type == RowType.COLLECTIONS) items += MenuPanel.Item("Pin collections…") { pinCollections(r) }
        items += MenuPanel.Item("Remove row", danger = true) { a.app.config.update { it.copy(rows = it.rows.filter { x -> x.id != r.id }) } }
        a.showMenu(rowTitle(r), null, items)
    }

    private fun pinCollections(r: RowConfig) {
        val cached = a.app.home.cached(r).orEmpty()
        if (cached.isEmpty()) { a.toast("Open Home once so the collections load"); return }
        a.showMenu("Pin to the front", null, cached.take(20).map { col ->
            val pinned = col.id in r.pinned
            MenuPanel.Item(col.title, if (pinned) "Pinned" else null) {
                a.app.config.update { it.updateRow(r.id) { x -> x.copy(pinned = if (pinned) x.pinned - col.id else x.pinned + col.id) } }
            }
        })
    }

    private fun addRowMenu() {
        a.scope.launch {
            val c = a.config
            val items = ArrayList<MenuPanel.Item>()
            for (t in listOf(
                RowType.CONTINUE_WATCHING,
                RowType.FAVORITE_APPS,
                RowType.RECENT_MOVIES,
                RowType.RECENT_SHOWS,
                RowType.NEXT_EPISODES,
                RowType.COLLECTIONS,
                RowType.PLAYLISTS,
            )) {
                if (c.rows.none { it.type == t }) {
                    items += MenuPanel.Item(rowTitle(RowConfig("", t))) {
                        addRow(RowConfig(t.name.lowercase(), t))
                    }
                }
            }
            val libs: List<Library> = runCatching { a.app.plex.libraries() }.getOrDefault(emptyList())
            for (l in libs) items += MenuPanel.Item("Library: ${l.title}") { addRow(RowConfig("lib-${l.id}-${System.currentTimeMillis()}", RowType.LIBRARY, title = l.title, libraryId = l.id)) }
            libs.firstOrNull { it.kind == MediaKind.MOVIE }?.let { l ->
                items += MenuPanel.Item("Genre row…") {
                    a.scope.launch {
                        val genres = runCatching { a.app.plex.genres(l.id) }.getOrDefault(emptyList())
                        a.showMenu("Genre", l.title, genres.take(30).map { g -> MenuPanel.Item(g) { addRow(RowConfig("genre-$g-${System.currentTimeMillis()}", RowType.GENRE, genre = g)) } })
                    }
                }
            }
            if (items.isEmpty()) a.toast("Sign in to Plex to add library rows") else a.showMenu("Add a row", null, items)
        }
    }

    private fun addRow(r: RowConfig) = a.app.config.update { it.copy(rows = it.rows + r) }

    private fun appearance(c: LauncherConfig) {
        if (previewTheme == null) previewTheme = c.theme
        if (previewAccent == null) previewAccent = c.accent

        heading("Appearance", "↑↓ themes · → side · OK on Apply saves")

        val split = hlinear(a).apply { gravity = Gravity.TOP }
        val listCol = vlinear(a)
        val sideCol = vlinear(a)

        val themeRows = ArrayList<View>()
        for (t in Palette.ALL) {
            val row = themeListRow(t)
            themeRows += row
            listCol.addView(row, llp(MATCH, a.dp(48)) { bottomMargin = a.dp(6) })
        }

        appearanceStatus = a.text(12f, p.sub1, MEDIUM, s = "")
        appearanceHint = a.text(11f, p.sub0, lines = 2, s = "")
        sideCol.addView(appearanceStatus!!, llp(WRAP, WRAP) { bottomMargin = a.dp(4) })
        sideCol.addView(appearanceHint!!, llp(MATCH, WRAP) { bottomMargin = a.dp(10) })

        val previewHost = FrameLayout(a)
        appearancePreviewHost = previewHost
        sideCol.addView(previewHost, llp(MATCH, a.dp(180)) { bottomMargin = a.dp(12) })

        val accentLabel = a.text(10.5f, p.sub0, BOLD, s = "ACCENT").apply { letterSpacing = 0.1f }
        appearanceAccentLabel = accentLabel
        sideCol.addView(accentLabel, llp(WRAP, WRAP) { bottomMargin = a.dp(8) })

        val accents = hlinear(a).apply { gravity = Gravity.CENTER_VERTICAL }
        appearanceAccentRow = accents
        val accentViews = ArrayList<View>()
        val accentNames = Palette.ACCENTS.keys.toList()
        for (name in accentNames) {
            val circle = View(a).apply {
                id = View.generateViewId()
                tag = "accent-$name"
                isFocusable = true
                isClickable = true
                contentDescription = "Accent ${name.replaceFirstChar { it.uppercase() }}"
            }
            appearanceAccentViews[name] = circle
            accentViews += circle
            val last = name == accentNames.last()
            accents.addView(circle, llp(a.dp(22), a.dp(22)) {
                if (!last) marginEnd = a.dp(5)
            })
        }
        sideCol.clipChildren = false
        sideCol.addView(accents, llp(MATCH, WRAP) { bottomMargin = a.dp(14) })

        val footer = hlinear(a).apply { clipChildren = false }
        val cancel = PillButton(a, p, "Cancel").also { it.id = View.generateViewId(); it.contentDescription = "Cancel" }
        val apply = PillButton(a, p, "Apply", primary = true).also { it.id = View.generateViewId(); it.contentDescription = "Apply" }
        appearanceCancel = cancel
        appearanceApply = apply
        apply.isEnabled = true
        footer.addView(cancel, llp(WRAP, a.dp(38)) { marginEnd = a.dp(10) })
        footer.addView(apply, llp(WRAP, a.dp(38)))
        sideCol.addView(footer)

        fun themeRow(): View = appearanceCards[previewTheme ?: c.theme] ?: themeRows.first()
        fun themeUsesAccent(): Boolean =
            (Palette.ALL.firstOrNull { it.id == (previewTheme ?: c.theme) } ?: Palette.MOCHA).usesAccent
        fun sideTarget(): View = when {
            themeUsesAccent() -> appearanceAccentViews[previewAccent ?: c.accent] ?: accentViews.first()
            else -> apply
        }

        cancel.setOnClickListener {
            previewTheme = c.theme
            previewAccent = c.accent
            paintAppearanceChrome()
            themeRow().requestFocus()
        }
        apply.setOnClickListener {
            val themeId = previewTheme ?: c.theme
            val accentId = previewAccent ?: c.accent
            val uses = (Palette.ALL.firstOrNull { it.id == themeId } ?: Palette.MOCHA).usesAccent
            val sameTheme = themeId == c.theme
            val sameAccent = !uses || accentId == c.accent
            if (sameTheme && sameAccent) {
                a.toast("Already applied")
                return@setOnClickListener
            }
            a.app.config.update {
                if (uses) it.copy(theme = themeId, accent = accentId)
                else it.copy(theme = themeId)
            }
            a.toast("Applied")
        }

        themeRows.forEachIndexed { i, row ->
            val prev = themeRows.getOrNull(i - 1)
            val next = themeRows.getOrNull(i + 1)
            row.nextFocusUpId = prev?.id ?: row.id
            row.nextFocusDownId = next?.id ?: row.id
            row.trapBodyKeys { code, _ ->
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    sideTarget().requestFocus(); true
                } else false
            }
        }

        accentViews.forEachIndexed { i, circle ->
            val name = accentNames[i]
            val left = accentViews.getOrNull(i - 1)
            val right = accentViews.getOrNull(i + 1)
            circle.setOnFocusChangeListener { _, f ->
                paintAccentCircle(name, f)
                if (f && previewAccent != name) {
                    previewAccent = name
                    paintAppearanceChrome()
                }
            }
            circle.setOnClickListener {
                previewAccent = name
                paintAppearanceChrome()
            }
            circle.trapBodyKeys { code, _ ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (left != null) left.requestFocus() else themeRow().requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        (right ?: apply).requestFocus(); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        apply.requestFocus(); true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        themeRow().requestFocus(); true
                    }
                    else -> false
                }
            }
        }

        cancel.trapBodyKeys { code, _ ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { themeRow().requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { apply.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { sideTarget().requestFocus(); true }
                else -> false
            }
        }
        apply.trapBodyKeys { code, _ ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { cancel.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (themeUsesAccent()) {
                        (appearanceAccentViews[previewAccent ?: c.accent] ?: accentViews.first()).requestFocus()
                    } else {
                        themeRow().requestFocus()
                    }
                    true
                }
                else -> false
            }
        }

        // Panel content ≈ 552dp at 320dpi. Accents need 9×22 + 8×5 = 238dp.
        // List 280 + gap 16 leaves ≈ 256dp for the side — just enough.
        split.addView(listCol, llp(a.dp(280), WRAP) { marginEnd = a.dp(16) })
        split.addView(sideCol, llp(0, WRAP, 1f))
        body.addView(split, llp(MATCH, WRAP))
        paintAppearanceChrome()
    }

    private fun paintAccentCircle(name: String, focused: Boolean) {
        val circle = appearanceAccentViews[name] ?: return
        val pair = Palette.ACCENTS[name] ?: return
        val preview = Palette.of(previewTheme ?: a.config.theme, previewAccent ?: a.config.accent)
        val color = if (preview.light) pair.second else pair.first
        val selected = name == (previewAccent ?: a.config.accent)
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            when {
                focused -> setStroke(a.dp(3), p.text)
                selected -> setStroke(a.dp(2), alpha(p.text, 0.75f))
                else -> setStroke(a.dp(1), alpha(p.o0, 0.35f))
            }
        }
    }

    private fun themeListRow(theme: Palette): LinearLayout {
        val row = hlinear(a).apply {
            id = View.generateViewId()
            tag = "theme-${theme.id}"
            isFocusable = true
            isClickable = true
            contentDescription = theme.name
            setPadding(a.dp(12), 0, a.dp(12), 0)
            minimumHeight = a.dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatch = FrameLayout(a).apply {
            addView(View(a).apply { setBackgroundColor(theme.base) }, flp(MATCH, MATCH))
            addView(View(a).apply { setBackgroundColor(theme.mantle) }, flp(a.dp(10), MATCH, Gravity.START))
        }
        val name = a.text(13.5f, p.text, MEDIUM, s = theme.name)
        val state = a.text(11f, p.sub0, s = " ")
        appearanceStateLabels[theme.id] = state
        row.addView(swatch, llp(a.dp(28), a.dp(28)) { marginEnd = a.dp(12) })
        row.addView(name, llp(0, WRAP, 1f))
        row.addView(state, llp(WRAP, WRAP) { marginStart = a.dp(8) })
        row.setOnFocusChangeListener { _, f ->
            paintThemeListRow(row, theme.id, f)
            if (f && previewTheme != theme.id) {
                previewTheme = theme.id
                paintAppearanceChrome()
            }
        }
        row.setOnClickListener {
            previewTheme = theme.id
            paintAppearanceChrome()
        }
        appearanceCards[theme.id] = row
        return row
    }

    private fun paintThemeListRow(row: LinearLayout, themeId: String, focused: Boolean) {
        val c = a.config
        val theme = Palette.ALL.firstOrNull { it.id == (previewTheme ?: c.theme) } ?: Palette.MOCHA
        val dirty = (previewTheme ?: c.theme) != c.theme ||
            (theme.usesAccent && (previewAccent ?: c.accent) != c.accent)
        val inUse = themeId == c.theme
        val previewing = themeId == previewTheme && dirty
        when {
            focused -> {
                row.background = rounded(p.accent, a.dp(12).toFloat())
                (row.getChildAt(1) as? TextView)?.setTextColor(p.onAccent)
                (row.getChildAt(2) as? TextView)?.setTextColor(alpha(p.onAccent, 0.85f))
            }
            previewing -> {
                row.background = rounded(alpha(p.accent, 0.22f), a.dp(12).toFloat(), a.dp(2), p.accent)
                (row.getChildAt(1) as? TextView)?.setTextColor(p.text)
                (row.getChildAt(2) as? TextView)?.setTextColor(p.accent)
            }
            inUse -> {
                row.background = rounded(alpha(p.o0, 0.18f), a.dp(12).toFloat(), a.dp(1), alpha(p.o0, 0.4f))
                (row.getChildAt(1) as? TextView)?.setTextColor(p.text)
                (row.getChildAt(2) as? TextView)?.setTextColor(p.sub0)
            }
            else -> {
                row.background = rounded(alpha(p.s0, 0.55f), a.dp(12).toFloat())
                (row.getChildAt(1) as? TextView)?.setTextColor(p.text)
                (row.getChildAt(2) as? TextView)?.setTextColor(p.sub0)
            }
        }
    }

    private fun paintAppearanceChrome() {
        val c = a.config
        val themeId = previewTheme ?: c.theme
        val accentId = previewAccent ?: c.accent
        val theme = Palette.ALL.firstOrNull { it.id == themeId } ?: Palette.MOCHA
        val preview = Palette.of(themeId, accentId)
        val committedName = Palette.ALL.firstOrNull { it.id == c.theme }?.name ?: c.theme
        val previewName = theme.name
        val dirty = themeId != c.theme || (theme.usesAccent && accentId != c.accent)

        appearanceStatus?.text = if (dirty) "Previewing $previewName" else "In use · $committedName"
        appearanceHint?.text = when {
            dirty -> "→ Apply, then OK"
            theme.usesAccent -> "↑↓ themes · → accents"
            else -> "↑↓ themes · → Apply"
        }
        appearanceApply?.alpha = if (dirty) 1f else 0.45f

        val accentVis = if (theme.usesAccent) View.VISIBLE else View.GONE
        appearanceAccentLabel?.visibility = accentVis
        appearanceAccentRow?.visibility = accentVis
        for (circle in appearanceAccentViews.values) {
            circle.visibility = accentVis
            circle.isFocusable = theme.usesAccent
        }
        // If focus was on an accent that just hid, land on Apply.
        if (!theme.usesAccent && appearanceAccentViews.values.any { it.hasFocus() }) {
            appearanceApply?.requestFocus()
        }

        val host = appearancePreviewHost ?: return
        host.removeAllViews()
        host.addView(miniHomePreview(preview), flp(MATCH, MATCH))

        for ((id, row) in appearanceCards) {
            paintThemeListRow(row, id, row.hasFocus())
            val label = appearanceStateLabels[id] ?: continue
            when {
                id == themeId && dirty -> { label.text = "Previewing"; if (!row.hasFocus()) label.setTextColor(p.accent) }
                id == c.theme -> { label.text = "In use"; if (!row.hasFocus()) label.setTextColor(p.sub0) }
                else -> { label.text = " "; if (!row.hasFocus()) label.setTextColor(alpha(p.sub0, 0.01f)) }
            }
        }
        if (theme.usesAccent) {
            for (name in appearanceAccentViews.keys) {
                paintAccentCircle(name, appearanceAccentViews[name]?.hasFocus() == true)
            }
        }
    }

    private fun miniHomePreview(pv: Palette): View {
        val stage = vlinear(a).apply {
            background = rounded(pv.base, a.dp(14).toFloat(), a.dp(1), alpha(pv.o0, 0.3f))
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val bar = hlinear(a)
        for (w in listOf(36, 52, 44)) {
            bar.addView(View(a).apply { setBackgroundColor(alpha(pv.text, 0.35f)) }, llp(a.dp(w), a.dp(10)) { marginEnd = a.dp(8) })
        }
        stage.addView(bar, llp(MATCH, WRAP) { bottomMargin = a.dp(12) })
        val hero = FrameLayout(a).apply {
            background = rounded(pv.s0, a.dp(10).toFloat())
            addView(View(a).apply { setBackgroundColor(alpha(pv.accent, 0.4f)) }, flp(a.dp(70), MATCH, Gravity.END))
        }
        stage.addView(hero, llp(MATCH, a.dp(72)) { bottomMargin = a.dp(12) })
        val cards = hlinear(a)
        for (i in 0 until 4) {
            cards.addView(View(a).apply {
                background = rounded(if (i == 0) mix(pv.s1, pv.accent, 0.3f) else pv.s0, a.dp(8).toFloat())
            }, llp(a.dp(56), a.dp(78)) { marginEnd = a.dp(8) })
        }
        stage.addView(cards)
        return stage
    }

    private fun apps(c: LauncherConfig) {
        heading("Apps", "Favorites, hidden apps and folders. Long-press an app anywhere for more.")
        action("Reset favorites", "Back to the suggested streaming apps") { a.app.config.update { it.copy(favorites = emptyList()) } }
        group("Hidden apps")
        val hidden = a.apps.filter { it.pkg in c.hiddenApps }
        if (hidden.isEmpty()) body.addView(a.text(12f, p.sub0, s = "No hidden apps"), llp(WRAP, WRAP) { setMargins(a.dp(14), a.dp(4), 0, 0) })
        for (h in hidden) action(h.label, "OK to show it again") { a.app.config.update { it.setHidden(h.pkg, false) } }
        group("Folders")
        if (c.folders.isEmpty()) body.addView(a.text(12f, p.sub0, s = "Long-press an app in Apps and choose “Add to a folder”."), llp(WRAP, WRAP) { setMargins(a.dp(14), a.dp(4), 0, 0) })
        for (f in c.folders) action(f.name, "${f.packages.size} apps", "Remove") { a.app.config.update { x -> x.copy(folders = x.folders.filter { it.id != f.id }) } }
    }

    // ---------- Plex ----------

    private fun plex() {
        val plex = a.app.plex
        val s = plex.session
        heading("Plex", "Nyx TV reads your library straight from your Plex server. Tokens are kept encrypted on this TV and never leave it.")
        when (val st = plex.status.value) {
            is ProviderStatus.Online -> action("Connected to ${st.serverName}", "${s?.userName?.let { "Signed in as $it · " } ?: ""}via ${st.address}") {}
            is ProviderStatus.Offline -> action("${st.serverName} is unreachable", st.reason) {}
            ProviderStatus.Connecting -> action("Connecting to ${s?.serverName ?: "Plex"}…") {}
            ProviderStatus.Unconfigured -> action("Not signed in", "Your apps work without Plex") {}
        }
        if (s == null || s.accountToken == null) action("Sign in with a code", "Enter a code at plex.tv/link on your phone or computer") { startPin() }
        if (s?.accountToken != null) action("Choose another server", null) { a.scope.launch { listServers(s.accountToken!!) } }
        action("Add a server by address", "For a server your account can't list, with its token") { manualServer() }
        if (s != null) {
            action("Refresh the library now") { a.app.home.clear(); a.toast("Home will reload from the server") ; a.showRoot("home", true) }
            action("Sign out", "Removes the tokens and cached library from this TV") {
                a.app.plex.signOut(); a.app.home.clear(); renderBody()
            }
        }
    }

    // ---------- Seerr ----------

    private fun seerr() {
        val seerr = a.app.seerr
        val s = seerr.session
        heading("Seerr", "Discover and request movies and TV shows. Credentials are kept encrypted on this TV and Discover is hidden when Seerr is not configured.")
        when (val st = seerr.status.value) {
            is ProviderStatus.Online -> action("Connected (${st.serverName})", "${s?.displayName?.let { "Signed in as $it · " } ?: ""}${st.address}") {}
            is ProviderStatus.Offline -> action("Seerr is unreachable", st.reason) {}
            ProviderStatus.Connecting -> action("Connecting to Seerr…", s?.serverUrl) {}
            ProviderStatus.Unconfigured -> action("Not configured", "Add your Seerr server below to enable the Discover tab") {}
        }
        val currentUrl = s?.serverUrl
        if (currentUrl != null) {
            action("Server URL", "Local IP, HTTPS, or Tailscale hostname", currentUrl) { seerrServerUrlForm(currentUrl) }
            action("Test Connection", "Verify reachability without changing credentials") {
                a.scope.launch {
                    runCatching { seerr.testConnection(currentUrl) }
                        .onSuccess { a.toast("Connected to Seerr ${it.version}") }
                        .onFailure { a.toast(it.message ?: "Could not reach Seerr") }
                }
            }
        }
        val plexToken = a.app.plex.session?.accountToken
        if (plexToken != null) {
            action(
                if (s != null) "Re-authenticate with Plex" else "Sign in with Plex",
                "Uses your linked Plex account (recommended for shared TVs)",
            ) {
                seerrPlexSignInForm(currentUrl ?: "", plexToken)
            }
        }
        action(
            if (s != null) "Switch to Seerr local account" else "Sign in with Seerr account",
            "Email or username and password",
        ) {
            seerrLocalSignInForm(currentUrl ?: "")
        }
        action(
            "Sign in with scoped API key",
            "Fallback only — avoid unrestricted admin API keys on a shared TV",
        ) {
            seerrApiKeyForm(currentUrl ?: "")
        }
        if (s != null) {
            action("Disconnect Seerr", "Clears Seerr credentials and hides the Discover tab") {
                seerr.disconnect()
                a.syncSeerrTab()
                renderBody()
                a.toast("Seerr disconnected")
            }
        }
    }

    private fun seerrUrlField(initial: String): EditText = EditText(a).apply {
        setText(initial)
        hint = "http://192.168.1.20:5055 or https://seerr.ts.net"
        setTextColor(p.text); setHintTextColor(p.sub0); textSize = 15f; typeface = MEDIUM; isSingleLine = true
        background = rounded(alpha(p.o0, 0.22f), a.dp(10).toFloat()); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
        trapBodyKeys()
    }

    private fun seerrInputField(hintText: String, password: Boolean = false): EditText = EditText(a).apply {
        hint = hintText
        if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(p.text); setHintTextColor(p.sub0); textSize = 15f; typeface = MEDIUM; isSingleLine = true
        background = rounded(alpha(p.o0, 0.22f), a.dp(10).toFloat()); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
        trapBodyKeys()
    }

    private fun addSeerrButtonRow(primary: PillButton, secondary: PillButton, above: View) {
        val row = hlinear(a)
        primary.id = View.generateViewId()
        secondary.id = View.generateViewId()
        above.nextFocusDownId = primary.id
        primary.nextFocusUpId = above.id
        secondary.nextFocusUpId = above.id
        primary.trapBodyKeys { code, _ ->
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { secondary.requestFocus(); true } else false
        }
        secondary.trapBodyKeys { code, _ ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { primary.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        row.addView(primary, llp(WRAP, a.dp(38)) { setMargins(a.dp(14), 0, a.dp(10), 0) })
        row.addView(secondary, llp(WRAP, a.dp(38)))
        body.addView(row)
    }

    private fun seerrServerUrlForm(initial: String) {
        clearBody()
        heading("Seerr Server URL", "Enter your Seerr server address (LAN IP, HTTPS, or Tailscale hostname).")
        val urlField = seerrUrlField(initial)
        urlField.id = View.generateViewId()
        body.addView(urlField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), a.dp(8), 0, a.dp(14)) })
        val test = PillButton(a, p, "Test Connection", primary = true)
        val back = PillButton(a, p, "Back")
        addSeerrButtonRow(test, back, urlField)
        back.setOnClickListener { renderBody() }
        test.setOnClickListener {
            a.scope.launch {
                runCatching { a.app.seerr.testConnection(urlField.text.toString()) }
                    .onSuccess { a.toast("Reachable: Seerr ${it.version}") }
                    .onFailure { a.toast(it.message ?: "Could not reach Seerr") }
            }
        }
        urlField.requestFocus()
    }

    private fun seerrPlexSignInForm(initialUrl: String, plexToken: String) {
        clearBody()
        heading("Sign in to Seerr with Plex", "Uses your existing Plex account token to create a scoped Seerr session.")
        val urlField = seerrUrlField(initialUrl)
        urlField.id = View.generateViewId()
        body.addView(urlField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), a.dp(8), 0, a.dp(14)) })
        val go = PillButton(a, p, "Connect", primary = true)
        val cancel = PillButton(a, p, "Cancel")
        addSeerrButtonRow(go, cancel, urlField)
        cancel.setOnClickListener { renderBody() }
        go.setOnClickListener {
            a.scope.launch {
                runCatching { a.app.seerr.signInWithPlex(urlField.text.toString(), plexToken) }
                    .onSuccess {
                        a.syncSeerrTab()
                        a.toast("Connected to Seerr as ${it.displayName}")
                        renderBody()
                    }
                    .onFailure { a.toast(it.message ?: "Seerr Plex sign-in failed") }
            }
        }
        urlField.requestFocus()
    }

    private fun seerrLocalSignInForm(initialUrl: String) {
        clearBody()
        heading("Sign in with Seerr Account", "Credentials are used once to obtain a session cookie and are never stored.")
        val urlField = seerrUrlField(initialUrl)
        val emailField = seerrInputField("Email or username")
        val passField = seerrInputField("Password", password = true)
        passField.id = View.generateViewId()
        body.addView(urlField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), a.dp(8), 0, a.dp(10)) })
        body.addView(emailField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), 0, 0, a.dp(10)) })
        body.addView(passField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), 0, 0, a.dp(14)) })
        val go = PillButton(a, p, "Sign in", primary = true)
        val cancel = PillButton(a, p, "Cancel")
        addSeerrButtonRow(go, cancel, passField)
        cancel.setOnClickListener { renderBody() }
        go.setOnClickListener {
            a.scope.launch {
                runCatching {
                    a.app.seerr.signInLocal(
                        urlField.text.toString(),
                        emailField.text.toString(),
                        passField.text.toString(),
                    )
                }.onSuccess {
                    a.syncSeerrTab()
                    a.toast("Connected to Seerr as ${it.displayName}")
                    renderBody()
                }.onFailure { a.toast(it.message ?: "Sign-in failed") }
            }
        }
        urlField.requestFocus()
    }

    private fun seerrApiKeyForm(initialUrl: String) {
        clearBody()
        heading("Seerr API Key (Scoped)", "Avoid unrestricted admin API keys on a shared TV. Prefer Plex or local user login when possible.")
        val urlField = seerrUrlField(initialUrl)
        val keyField = seerrInputField("API key", password = true)
        keyField.id = View.generateViewId()
        body.addView(urlField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), a.dp(8), 0, a.dp(10)) })
        body.addView(keyField, llp(a.dp(520), WRAP) { setMargins(a.dp(14), 0, 0, a.dp(14)) })
        val go = PillButton(a, p, "Connect", primary = true)
        val cancel = PillButton(a, p, "Cancel")
        addSeerrButtonRow(go, cancel, keyField)
        cancel.setOnClickListener { renderBody() }
        go.setOnClickListener {
            a.scope.launch {
                runCatching { a.app.seerr.signInWithApiKey(urlField.text.toString(), keyField.text.toString()) }
                    .onSuccess {
                        a.syncSeerrTab()
                        a.toast("Connected to Seerr")
                        renderBody()
                    }
                    .onFailure { a.toast(it.message ?: "API key connection failed") }
            }
        }
        urlField.requestFocus()
    }

    private fun playback() {
        val prefs = a.app.playbackPrefs
        heading("Playback", "Built-in player defaults. Theme stays under Appearance.")
        val builtin = SettingRow(a, p, "Play in Nyx TV", "Gradient player. Turn off to open the Plex app instead.").apply { on = prefs.useBuiltIn }
        builtin.setOnClickListener { prefs.useBuiltIn = !prefs.useBuiltIn; builtin.on = prefs.useBuiltIn }
        builtin.trapBodyKeys()
        body.addView(builtin, llp(MATCH, WRAP))
        val directPlay = SettingRow(a, p, "Prefer direct play", "Fall back to converting on the server when needed.").apply { on = prefs.preferDirectPlay }
        directPlay.setOnClickListener { prefs.preferDirectPlay = !prefs.preferDirectPlay; directPlay.on = prefs.preferDirectPlay }
        directPlay.trapBodyKeys()
        body.addView(directPlay, llp(MATCH, WRAP))
        val autoplay = SettingRow(a, p, "Autoplay next episode", "After credits or the end of an episode.").apply { on = prefs.autoplayNext }
        autoplay.setOnClickListener { prefs.autoplayNext = !prefs.autoplayNext; autoplay.on = prefs.autoplayNext }
        autoplay.trapBodyKeys()
        body.addView(autoplay, llp(MATCH, WRAP))
        val skipIntro = SettingRow(a, p, "Auto-skip intros", "Automatically skip intro markers after the countdown.").apply { on = prefs.autoSkipIntro }
        skipIntro.setOnClickListener { prefs.autoSkipIntro = !prefs.autoSkipIntro; skipIntro.on = prefs.autoSkipIntro }
        skipIntro.trapBodyKeys()
        body.addView(skipIntro, llp(MATCH, WRAP))
        val skipCredits = SettingRow(a, p, "Auto-skip credits", "Automatically skip end credits when Up Next is not shown.").apply { on = prefs.autoSkipCredits }
        skipCredits.setOnClickListener { prefs.autoSkipCredits = !prefs.autoSkipCredits; skipCredits.on = prefs.autoSkipCredits }
        skipCredits.trapBodyKeys()
        body.addView(skipCredits, llp(MATCH, WRAP))
        val skipDelayLabel = if (prefs.autoSkipDelaySec == 0) "Instant (0s)" else "${prefs.autoSkipDelaySec} seconds"
        action(
            "Auto-skip countdown",
            "Now $skipDelayLabel before skipping intros or credits."
        ) {
            val choices = listOf(0, 3, 5, 10, 15).map { sec ->
                val label = if (sec == 0) "Instant (0s)" else "$sec seconds"
                MenuPanel.Item(label) { prefs.autoSkipDelaySec = sec; renderBody() }
            }
            a.showMenu("Auto-skip countdown", null, choices)
        }
        action(
            "Up Next countdown",
            "Now ${prefs.upNextDelaySec} seconds before starting the next episode."
        ) {
            val choices = listOf(5, 10, 15, 20, 30).map { sec ->
                MenuPanel.Item("$sec seconds") { prefs.upNextDelaySec = sec; renderBody() }
            }
            a.showMenu("Up Next countdown", null, choices)
        }
        action(
            "Audio sync offset",
            "Now ${prefs.audioOffsetMs} ms. Negative advances audio (often −500 ms when dialogue lags)."
        ) {
            val choices = listOf(-500, -250, -100, 0, 100, 250, 500).map { ms ->
                val label = when {
                    ms == 0 -> "0 ms (none)"
                    ms < 0 -> "$ms ms"
                    else -> "+$ms ms"
                }
                MenuPanel.Item(label) { prefs.audioOffsetMs = ms; renderBody() }
            }
            a.showMenu("Audio sync offset", null, choices)
        }
        val bridge = a.app.playback.plexApp
        val direct = SettingRow(a, p, "Open titles directly in Plex", "Only when Play in Nyx TV is off. Experimental deep links.").apply { on = bridge.directLinks }
        direct.setOnClickListener { bridge.directLinks = !bridge.directLinks; direct.on = bridge.directLinks }
        direct.trapBodyKeys()
        body.addView(direct, llp(MATCH, WRAP))
        if (!bridge.installed) action("The Plex app isn't installed", "Needed only if you turn off Play in Nyx TV") {}
    }

    private fun startPin() {
        clearBody()
        heading("Sign in to Plex")
        val code = a.text(46f, p.text, BOLD, s = "····").apply { letterSpacing = 0.3f }
        val steps = a.text(14f, p.sub1, lines = 4, s = "On your phone or computer, go to plex.tv/link and enter this code:")
        val wait = a.text(12f, p.sub0, lines = 2, s = "Getting a code…")
        body.addView(steps, llp(MATCH, WRAP) { setMargins(a.dp(14), a.dp(6), a.dp(14), a.dp(14)) })
        body.addView(code, llp(WRAP, WRAP) { setMargins(a.dp(14), 0, 0, a.dp(14)) })
        body.addView(wait, llp(MATCH, WRAP) { setMargins(a.dp(14), 0, a.dp(14), a.dp(14)) })
        val cancel = PillButton(a, p, "Cancel")
        cancel.setOnClickListener { renderBody() }
        body.addView(cancel, llp(WRAP, a.dp(38)) { setMargins(a.dp(14), 0, 0, 0) })
        cancel.requestFocus()
        pinJob = a.scope.launch {
            val pin: PlexPin = try { a.app.plex.createPin() } catch (e: Exception) { wait.text = "plex.tv couldn't be reached. Check the network and try again."; return@launch }
            code.text = pin.code
            wait.text = "Waiting for you to sign in · the code works for ${pin.expiresInSec / 60} minutes"
            val end = System.currentTimeMillis() + pin.expiresInSec * 1000L
            while (System.currentTimeMillis() < end) {
                delay(2000)
                val token = runCatching { a.app.plex.checkPin(pin) }.getOrNull() ?: continue
                wait.text = "Signed in. Finding your servers…"
                listServers(token)
                return@launch
            }
            wait.text = "The code expired. Choose Cancel and try again."
        }
    }

    private suspend fun listServers(token: String) {
        val servers: List<PlexServer> = try { a.app.plex.servers(token) } catch (e: Exception) { a.toast("Your servers couldn't be listed"); return }
        if (servers.isEmpty()) { a.toast("This account has no servers"); return }
        a.showMenu("Choose a server", "Nyx TV prefers a local connection and falls back to remote", servers.map { s ->
            val local = s.connections.any { it.local }
            MenuPanel.Item(s.name, (if (s.owned) "Owned" else "Shared") + if (local) " · local" else " · remote") {
                a.scope.launch {
                    a.toast("Connecting to ${s.name}…")
                    runCatching { a.app.plex.choose(s, token) }
                        .onSuccess { a.app.home.clear(); section = Section.PLEX; renderBody(); a.toast("Connected to $it") }
                        .onFailure { a.toast(it.message ?: "${s.name} can't be reached") }
                }
            }
        })
    }

    private fun manualServer() {
        clearBody()
        heading("Add a server by address", "The address and an X-Plex-Token for that server.")
        fun field(hint: String): EditText = EditText(a).apply {
            this.hint = hint; textSize = 15f; setTextColor(p.text); setHintTextColor(p.sub0); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            background = rounded(alpha(p.o0, 0.2f), a.dp(10).toFloat())
            setPadding(a.dp(14), 0, a.dp(14), 0)
            setOnFocusChangeListener { _, f -> background = rounded(alpha(p.o0, 0.2f), a.dp(10).toFloat(), if (f) a.dp(2) else 0, p.accent) }
        }
        val addr = field("192.168.1.20:32400")
        val token = field("Token")
        body.addView(addr, llp(MATCH, a.dp(44)) { setMargins(a.dp(14), a.dp(6), a.dp(14), a.dp(10)) })
        body.addView(token, llp(MATCH, a.dp(44)) { setMargins(a.dp(14), 0, a.dp(14), a.dp(14)) })
        val row = hlinear(a)
        val connect = PillButton(a, p, "Connect", primary = true)
        val cancel = PillButton(a, p, "Cancel")
        row.addView(connect, llp(WRAP, a.dp(38)) { marginEnd = a.dp(10) })
        row.addView(cancel, llp(WRAP, a.dp(38)))
        body.addView(row, llp(WRAP, WRAP) { marginStart = a.dp(14) })
        cancel.setOnClickListener { renderBody() }
        connect.setOnClickListener {
            a.scope.launch {
                runCatching { a.app.plex.connectManual(addr.text.toString(), token.text.toString().trim()) }
                    .onSuccess { a.app.home.clear(); renderBody(); a.toast("Connected to $it") }
                    .onFailure { a.toast(it.message ?: "That server couldn't be reached") }
            }
        }
        addr.requestFocus()
    }

    // ---------- Ambient, TV, About ----------

    private fun ambient(c: LauncherConfig) {
        val tv = TvSystem(a)
        heading("Ambient mode", "Controls both Nyx TV Ambient and the TV's system screensaver (when granted secure settings permission). It never keeps the TV awake: the TV's own sleep timer still applies.")
        val row = SettingRow(a, p, "Start when Home is idle", null).apply { on = c.ambient.enabled }
        row.setOnClickListener {
            val next = row.on != true
            row.on = next
            a.app.config.update { x -> x.copy(ambient = x.ambient.copy(enabled = next)) }
            tv.syncScreensaver(next)
        }
        row.trapBodyKeys()
        body.addView(row, llp(MATCH, WRAP))
        choice("Idle time", null, listOf(5, 10, 15, 30, 60).map { it to "$it min" }, c.ambient.idleMinutes) { v -> a.app.config.update { it.copy(ambient = it.ambient.copy(idleMinutes = v)) } }
        choice("Show", null, listOf(AmbientSource.ARTWORK to "Library artwork", AmbientSource.BLACK to "Black with a clock"), c.ambient.source) { v -> a.app.config.update { it.copy(ambient = it.ambient.copy(source = v)) } }
        toggle("Clock", null, c.ambient.showClock) { x, v -> x.copy(ambient = x.ambient.copy(showClock = v)) }
        action("Start now") { a.startAmbient() }
        if (tv.canOpen(TvSystem.Screen.SCREENSAVER)) action("Use as the TV's screensaver", "Choose “Nyx TV Ambient” in the TV's screensaver settings") { tv.open(TvSystem.Screen.SCREENSAVER) }
    }

    private fun tv() {
        val tv = TvSystem(a)
        heading("TV and inputs", "These open the TV's own controls; nothing here changes system settings by itself.")
        group("Inputs")
        val inputs = tv.inputs().filter { it.passthrough }
        if (inputs.isEmpty()) body.addView(a.text(12f, p.sub0, lines = 2, s = "This TV doesn't list its HDMI inputs to apps. Use the remote's input button or All TV settings."), llp(MATCH, WRAP) { setMargins(a.dp(14), a.dp(4), a.dp(14), 0) })
        for (i in inputs) action(i.label, "HDMI") { if (!tv.switchTo(i)) a.toast("${i.label} couldn't be opened") }
        group("Settings")
        // Picture / Date are noisy on TCL and rarely useful from the launcher.
        for ((label, screen) in listOf("Network" to TvSystem.Screen.WIFI, "Bluetooth" to TvSystem.Screen.BLUETOOTH, "Sound" to TvSystem.Screen.SOUND, "All TV settings" to TvSystem.Screen.ALL))
            if (tv.canOpen(screen)) action(label) { tv.open(screen) }
        group("Remote helper")
        val serviceOn = HomeKeyFallbackService.isServiceEnabled(a)
        action(
            "Settings/gear → Quick Settings",
            "Enable “Nyx TV remote helper” in Accessibility. On some Google TVs SystemUI still eats the gear; the nav gear always opens Quick Settings.",
            if (serviceOn) "On" else "Off",
        ) {
            if (!serviceOn) {
                a.toast("Enable “Nyx TV remote helper” in Accessibility")
                HomeKeyFallbackService.openAccessibilitySettings(a)
            } else {
                HomeKeyFallbackService.openAccessibilitySettings(a)
            }
        }
        if (!serviceOn) action("Open Accessibility settings", "Enable Nyx TV remote helper") {
            HomeKeyFallbackService.openAccessibilitySettings(a)
        }
        val wants = HomeKeyFallbackService.userWantsFallback(a)
        val helperValue = when {
            wants && serviceOn -> "On"
            wants && !serviceOn -> "Allowed here · turn on in Accessibility"
            else -> "Off"
        }
        action(
            "Bring Nyx TV back if stock Home opens",
            "Same Accessibility service. Off by default; only needed if Home still opens Google TV.",
            helperValue,
        ) {
            if (!wants) {
                HomeKeyFallbackService.setUserWantsFallback(a, true)
                if (!serviceOn) {
                    a.toast("Enable “Nyx TV remote helper” in Accessibility")
                    HomeKeyFallbackService.openAccessibilitySettings(a)
                }
            } else {
                HomeKeyFallbackService.setUserWantsFallback(a, false)
            }
            renderBody(keepFocus = true)
        }
    }

    private fun about() {
        heading("About")
        val prefs = a.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        action("Nyx TV ${BuildConfig.VERSION_NAME}", "Build ${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) " · debug" else ""}") {}
        val homeLine = when {
            AppRepository.isHomeDefault(a) -> "Nyx TV is the TV's home app"
            HomeKeyFallbackService.needed(a) -> "Another app is home — use launcher scripts or the remote helper under TV and inputs"
            else -> "Another app is the home app"
        }
        action("Home app", homeLine) {}
        action("This TV", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})") {}
        group("Diagnostics")
        action("Memory", "Proportional set size of Nyx TV right now", "${mem.totalPss / 1024} MB") {}
        action("Last start", "Process start to first frame", "${prefs.getLong("last_start_ms", 0)} ms") {}
        action("Artwork cache", null, "${(a.app.images.diskCache?.size ?: 0) / (1024 * 1024)} MB on disk · ${(a.app.images.memoryCache?.size ?: 0) / (1024 * 1024)} MB in memory") {}
        action("Clear artwork cache") { a.app.images.memoryCache?.clear(); a.app.images.diskCache?.clear(); renderBody(keepFocus = true) }
        group("Privacy")
        body.addView(a.text(12f, p.sub0, lines = 4, s = "No telemetry, no ads, no accounts other than Plex. Settings backups made by TV Manager never include your Plex sign-in."), llp(MATCH, WRAP) { setMargins(a.dp(14), a.dp(4), a.dp(14), 0) })
    }
}
