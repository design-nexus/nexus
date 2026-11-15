package io.github.design_nexus.nyx.tv.ui.pages

import android.os.Debug
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.design_nexus.nyx.tv.BuildConfig
import io.github.design_nexus.nyx.tv.core.AmbientSource
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.LauncherConfig
import io.github.design_nexus.nyx.tv.core.RowConfig
import io.github.design_nexus.nyx.tv.core.RowType
import io.github.design_nexus.nyx.tv.data.AppEntry
import io.github.design_nexus.nyx.tv.data.AppRepository
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
import io.github.design_nexus.nyx.tv.ui.rounded
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsPage(a: HomeActivity, private var section: Section = Section.HOME) : Page(a) {
    enum class Section(val title: String) {
        HOME("Home screen"), ROWS("Content rows"), APPEARANCE("Appearance"), APPS("Apps"), PLEX("Plex"),
        PLAYBACK("Playback"), AMBIENT("Ambient mode"), TV("TV and inputs"), ABOUT("About"),
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

    /** Empties the panel without letting the focus it loses land on another section (which would redraw it mid-clear). */
    private fun clearBody() {
        clearing = true
        if (body.hasFocus()) navRows[section]?.requestFocus()
        body.removeAllViews()
        clearing = false
    }

    init {
        val title = a.text(22f, p.text, BOLD, s = "Settings")
        nav.addView(title, llp(WRAP, WRAP) { setMargins(a.dp(14), 0, 0, a.dp(12)) })
        for (s in Section.entries) {
            val r = SettingRow(a, p, s.title)
            r.setOnFocusChangeListener(chain(r.onFocusChangeListener) { if (it && !clearing && section != s) { section = s; renderBody() } })
            r.setOnClickListener { focusBody() }
            r.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN) { focusBody(); true } else false }
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

    private fun toggle(t: String, d: String?, on: Boolean, set: (LauncherConfig, Boolean) -> LauncherConfig): SettingRow {
        val r = SettingRow(a, p, t, d).apply { this.on = on }
        r.setOnClickListener { a.app.config.update { c -> set(c, !(r.on ?: false)) } }
        body.addView(r, llp(MATCH, WRAP))
        return r
    }

    private fun action(t: String, d: String? = null, value: String? = null, click: () -> Unit): SettingRow {
        val r = SettingRow(a, p, t, d)
        value?.let { r.value.text = it }
        r.setOnClickListener { click() }
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
        body.addView(r, llp(MATCH, WRAP))
        return r
    }

    private fun renderBody(keepFocus: Boolean = false) {
        val focusedIndex = if (keepFocus && body.hasFocus()) (0 until body.childCount).indexOfFirst { body.getChildAt(it).hasFocus() } else -1
        clearBody()
        pinJob?.cancel()
        val c = a.config
        when (section) {
            Section.HOME -> home(c)
            Section.ROWS -> rows(c)
            Section.APPEARANCE -> appearance(c)
            Section.APPS -> apps(c)
            Section.PLEX -> plex()
            Section.PLAYBACK -> playback()
            Section.AMBIENT -> ambient(c)
            Section.TV -> tv()
            Section.ABOUT -> about()
        }
        if (focusedIndex >= 0) body.post { body.getChildAt(focusedIndex.coerceAtMost(body.childCount - 1))?.let { (firstFocusable(it) ?: it).requestFocus() } }
    }

    // ---------- Sections ----------

    private fun home(c: LauncherConfig) {
        heading("Home screen", "What Home shows and how it starts.")
        group("Featured")
        toggle("Featured hero", "A large backdrop for one title at the top of Home", c.heroEnabled) { x, v -> x.copy(heroEnabled = v) }
        action("Trailers and video backgrounds", "Never played automatically", "Off") {}
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
            row.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP && run { rowMenu(r); true }
                if (movingRow == r.id && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    a.app.config.update { it.moveRow(r.id, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) }
                    body.post { focusRow(r.id) }
                    true
                } else false
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
            for (t in listOf(RowType.CONTINUE_WATCHING, RowType.FAVORITE_APPS, RowType.RECENT_MOVIES, RowType.RECENT_SHOWS, RowType.NEXT_EPISODES, RowType.COLLECTIONS))
                if (c.rows.none { it.type == t }) items += MenuPanel.Item(rowTitle(RowConfig("", t))) { addRow(RowConfig(t.name.lowercase(), t)) }
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
        heading("Appearance", "Themes restyle the launcher at once.")
        group("Theme")
        for (t in Palette.ALL) action(t.name, null, if (t.id == c.theme) "✓ In use" else null) { a.app.config.update { it.copy(theme = t.id) } }
        group("Accent")
        for (name in Palette.ACCENTS.keys) action(name.replaceFirstChar { it.uppercase() }, null, if (name == c.accent) "✓ In use" else null) { a.app.config.update { it.copy(accent = name) } }
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

    private fun playback() {
        val prefs = a.app.playbackPrefs
        heading("Playback", "Built-in player defaults. Theme stays under Appearance.")
        val builtin = SettingRow(a, p, "Play in Nyx TV", "Gradient player. Turn off to open the Plex app instead.").apply { on = prefs.useBuiltIn }
        builtin.setOnClickListener { prefs.useBuiltIn = !prefs.useBuiltIn; builtin.on = prefs.useBuiltIn }
        body.addView(builtin, llp(MATCH, WRAP))
        val directPlay = SettingRow(a, p, "Prefer direct play", "Fall back to converting on the server when needed.").apply { on = prefs.preferDirectPlay }
        directPlay.setOnClickListener { prefs.preferDirectPlay = !prefs.preferDirectPlay; directPlay.on = prefs.preferDirectPlay }
        body.addView(directPlay, llp(MATCH, WRAP))
        val autoplay = SettingRow(a, p, "Autoplay next episode", "After credits or the end of an episode.").apply { on = prefs.autoplayNext }
        autoplay.setOnClickListener { prefs.autoplayNext = !prefs.autoplayNext; autoplay.on = prefs.autoplayNext }
        body.addView(autoplay, llp(MATCH, WRAP))
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
        heading("Ambient mode", "Artwork from your library and a clock, or a black screen with a dim clock for OLED. It never keeps the TV awake: the TV's own sleep timer still applies.")
        toggle("Start when Home is idle", null, c.ambient.enabled) { x, v -> x.copy(ambient = x.ambient.copy(enabled = v)) }
        choice("Idle time", null, listOf(5, 10, 15, 30, 60).map { it to "$it min" }, c.ambient.idleMinutes) { v -> a.app.config.update { it.copy(ambient = it.ambient.copy(idleMinutes = v)) } }
        choice("Show", null, listOf(AmbientSource.ARTWORK to "Library artwork", AmbientSource.BLACK to "Black with a clock"), c.ambient.source) { v -> a.app.config.update { it.copy(ambient = it.ambient.copy(source = v)) } }
        toggle("Clock", null, c.ambient.showClock) { x, v -> x.copy(ambient = x.ambient.copy(showClock = v)) }
        action("Start now") { a.startAmbient() }
        val tv = TvSystem(a)
        if (tv.canOpen(TvSystem.Screen.SCREENSAVER)) action("Use as the TV's screensaver", "Choose “Nyx TV Ambient” in the TV's screensaver settings") { tv.open(TvSystem.Screen.SCREENSAVER) }
    }

    private fun tv() {
        val tv = TvSystem(a)
        heading("TV and inputs", "These open the TV's own controls; nothing here changes system settings by itself.")
        group("Inputs")
        val inputs = tv.inputs()
        if (inputs.isEmpty()) body.addView(a.text(12f, p.sub0, lines = 2, s = "This TV doesn't list its inputs to apps. Use the remote's input button or All TV settings."), llp(MATCH, WRAP) { setMargins(a.dp(14), a.dp(4), a.dp(14), 0) })
        for (i in inputs) action(i.label, if (i.passthrough) "HDMI input" else "Tuner") { if (!tv.switchTo(i)) a.toast("${i.label} couldn't be opened") }
        group("Settings")
        for ((label, screen) in listOf("Network" to TvSystem.Screen.WIFI, "Bluetooth" to TvSystem.Screen.BLUETOOTH, "Picture" to TvSystem.Screen.PICTURE, "Sound" to TvSystem.Screen.SOUND, "Date and time" to TvSystem.Screen.DATE, "All TV settings" to TvSystem.Screen.ALL))
            if (tv.canOpen(screen)) action(label) { tv.open(screen) }
    }

    private fun about() {
        heading("About")
        val prefs = a.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        action("Nyx TV ${BuildConfig.VERSION_NAME}", "Build ${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) " · debug" else ""}") {}
        action("Home app", if (AppRepository.isHomeDefault(a)) "Nyx TV is the TV's home app" else "Another app is the home app; Nyx TV Manager can change that") {}
        action("This TV", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})") {}
        group("Diagnostics")
        action("Memory", "Proportional set size of Nyx TV right now", "${mem.totalPss / 1024} MB") {}
        action("Last start", "Process start to first frame", "${prefs.getLong("last_start_ms", 0)} ms") {}
        action("Artwork cache", null, "${(a.app.images.diskCache?.size ?: 0) / (1024 * 1024)} MB on disk · ${(a.app.images.memoryCache?.size ?: 0) / (1024 * 1024)} MB in memory") {}
        action("Clear artwork cache") { a.app.images.memoryCache?.clear(); a.app.images.diskCache?.clear(); renderBody() }
        group("Privacy")
        body.addView(a.text(12f, p.sub0, lines = 4, s = "No telemetry, no ads, no accounts other than Plex. Settings backups made by Nyx TV Manager never include your Plex sign-in."), llp(MATCH, WRAP) { setMargins(a.dp(14), a.dp(4), a.dp(14), 0) })
    }
}
