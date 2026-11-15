package io.github.design_nexus.nyx.tv.ui.pages

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.core.AppFolder
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.core.LauncherConfig
import io.github.design_nexus.nyx.tv.data.AppEntry
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.ui.AppTile
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.Cell
import io.github.design_nexus.nyx.tv.ui.Chip
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MEDIUM
import io.github.design_nexus.nyx.tv.ui.MenuPanel
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.RowData
import io.github.design_nexus.nyx.tv.ui.RowHost
import io.github.design_nexus.nyx.tv.ui.RowView
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.bindApp
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.CoroutineScope

/** Every app: favorites along the top, then all apps (with folders) in a grid. */
class AppsPage(a: HomeActivity) : Page(a), RowHost {
    override val tab = "apps"
    override val palette: Palette get() = a.palette
    override val provider: MediaProvider get() = a.app.plex
    override val images: ImageLoader get() = a.app.images
    override val appRepo: AppRepository get() = a.app.apps
    override val reduceMotion get() = a.config.reduceMotion
    override val focus = FocusMemory()
    override val scope: CoroutineScope get() = a.scope

    private val root = FrameLayout(a)
    override val view: View get() = root
    private val favRow = RowView(a, this, CardStyle.APP_TILE, RecyclerView.RecycledViewPool())
    private val count = a.text(12f, palette.sub0)
    private val hiddenChip = Chip(a, palette, "Show hidden apps")
    private val grid = RecyclerView(a)
    private val gridAdapter = GridAdapter()
    private var showHidden = false

    private sealed interface Entry {
        data class App(val e: AppEntry, val hidden: Boolean) : Entry
        data class Folder(val f: AppFolder, val apps: List<AppEntry>) : Entry
    }

    private var entries: List<Entry> = emptyList()

    init {
        val col = vlinear(a).apply { clipChildren = false }
        favRow.title.text = "Favorites"
        col.addView(favRow, llp(MATCH, WRAP))
        val head = hlinear(a)
        head.addView(a.text(15.5f, palette.text, MEDIUM, s = "All Apps"))
        head.addView(count, llp(WRAP, WRAP) { marginStart = a.dp(10) })
        head.addView(View(a), llp(0, 1, 1f))
        head.addView(hiddenChip, llp(WRAP, a.dp(28)))
        col.addView(head, llp(MATCH, WRAP) { setMargins(a.dp(48), a.dp(4), a.dp(48), a.dp(2)) })
        grid.layoutManager = GridLayoutManager(a, 6)
        grid.adapter = gridAdapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.setPadding(a.dp(48), a.dp(10), a.dp(30), a.dp(40))
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        col.addView(grid, llp(MATCH, 0, 1f))
        root.addView(col, flp(MATCH, MATCH).apply { topMargin = a.dp(70) })
        hiddenChip.setOnClickListener { showHidden = !showHidden; hiddenChip.selectedChip = showHidden; rebuild() }
        rebuild()
    }

    override fun focusDefault() {
        if (favRow.adapter.itemCount > 0) favRow.focusRemembered(focus) else grid.getChildAt(0)?.requestFocus()
    }

    override fun onShow() {
        a.paintScrim(full = true)
    }

    override fun onConfigChanged(c: LauncherConfig) = rebuild()
    override fun onAppsChanged(apps: List<AppEntry>) = rebuild()

    private fun favorites(): List<AppEntry> {
        val byPkg = a.apps.associateBy { it.pkg }
        val favs = a.config.favorites.mapNotNull { byPkg[it] }
        return favs.ifEmpty { LauncherConfig.DEFAULT_FAVORITES.mapNotNull { byPkg[it] } }.filter { it.pkg !in a.config.hiddenApps }
    }

    private fun rebuild() {
        val c = a.config
        favRow.adapter.submit(RowData("favorites", "Favorites", CardStyle.APP_TILE, favorites().map { Cell.App(it) }))
        val inFolders = c.folders.flatMap { it.packages }.toSet()
        val byPkg = a.apps.associateBy { it.pkg }
        val folders = c.folders.map { f -> Entry.Folder(f, f.packages.mapNotNull { byPkg[it] }) }.filter { it.apps.isNotEmpty() }
        val visible = a.apps.filter { it.pkg !in c.hiddenApps && it.pkg !in inFolders }.map { Entry.App(it, false) }
        val hidden = if (showHidden) a.apps.filter { it.pkg in c.hiddenApps }.map { Entry.App(it, true) } else emptyList()
        entries = folders + visible + hidden
        count.text = "${a.apps.size} installed" + if (c.hiddenApps.isNotEmpty()) " · ${c.hiddenApps.size} hidden" else ""
        gridAdapter.notifyDataSetChanged()
    }

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        inner class VH(val tile: AppTile) : RecyclerView.ViewHolder(tile)

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val t = AppTile(parent.context, palette, reduceMotion, 122)
            t.layoutParams = RecyclerView.LayoutParams(WRAP, WRAP).apply { bottomMargin = t.dp(14) }
            return VH(t)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            when (val e = entries[position]) {
                is Entry.App -> {
                    bindApp(this@AppsPage, h.tile, e.e, caption = true)
                    h.tile.alpha = if (e.hidden) 0.5f else 1f
                    h.tile.setOnClickListener { a.launchApp(e.e) }
                    h.tile.setOnLongClickListener { menu(e.e, e.hidden); true }
                    h.tile.setOnKeyListener { _, code, ev ->
                        if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) {
                            menu(e.e, e.hidden); return@setOnKeyListener true
                        }
                        if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val i = h.bindingAdapterPosition
                        if (i < 0) return@setOnKeyListener false
                        val cols = (grid.layoutManager as GridLayoutManager).spanCount
                        val last = entries.size - 1
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> i % cols == 0
                            KeyEvent.KEYCODE_DPAD_RIGHT -> i == last || i % cols == cols - 1
                            KeyEvent.KEYCODE_DPAD_UP -> i < cols
                            KeyEvent.KEYCODE_DPAD_DOWN -> i + cols > last
                            else -> false
                        }
                    }
                }
                is Entry.Folder -> {
                    h.tile.pkg = null
                    h.tile.alpha = 1f
                    h.tile.setPlaceholder("${e.f.name}\n${e.apps.size} apps")
                    h.tile.caption.text = e.f.name
                    h.tile.setOnClickListener { openFolder(e) }
                    h.tile.setOnLongClickListener { folderMenu(e.f); true }
                    h.tile.setOnKeyListener(null)
                }
            }
        }
    }

    private fun menu(e: AppEntry, hidden: Boolean) {
        if (hidden) {
            a.showMenu(e.label, "Hidden", listOf(MenuPanel.Item("Show again") { a.app.config.update { it.setHidden(e.pkg, false) } }))
            return
        }
        val items = ArrayList<MenuPanel.Item>()
        items += MenuPanel.Item("Add to a folder…") { chooseFolder(e) }
        val inFolder = a.config.folders.firstOrNull { e.pkg in it.packages }
        if (inFolder != null) items += MenuPanel.Item("Take out of ${inFolder.name}") {
            a.app.config.update { c -> c.copy(folders = c.folders.map { f -> if (f.id == inFolder.id) f.copy(packages = f.packages - e.pkg) else f }) }
        }
        val shared = ArrayList<MenuPanel.Item>()
        shared += MenuPanel.Item("Open") { a.launchApp(e) }
        val fav = e.pkg in a.config.favorites
        shared += MenuPanel.Item(if (fav) "Remove from favorites" else "Add to favorites") { a.app.config.update { it.toggleFavorite(e.pkg) } }
        shared += items
        shared += MenuPanel.Item("Hide") { a.app.config.update { it.setHidden(e.pkg, true) } }
        shared += MenuPanel.Item("App info") { a.app.apps.openInfo(e.pkg) }
        if (!e.system) shared += MenuPanel.Item("Uninstall", danger = true) { a.app.apps.uninstall(e.pkg) }
        a.showMenu(e.label, a.app.apps.versionName(e.pkg)?.let { "Version $it" }, shared)
    }

    private fun chooseFolder(e: AppEntry) {
        val items = a.config.folders.map { f ->
            MenuPanel.Item(f.name) { a.app.config.update { c -> c.copy(folders = c.folders.map { if (it.id == f.id) it.copy(packages = (it.packages + e.pkg).distinct()) else it.copy(packages = it.packages - e.pkg) }) } }
        } + MenuPanel.Item("New folder") {
            a.app.config.update { c ->
                val n = c.folders.size + 1
                c.copy(folders = c.folders.map { it.copy(packages = it.packages - e.pkg) } + AppFolder("folder-${System.currentTimeMillis()}", "Folder $n", listOf(e.pkg)))
            }
        }
        a.showMenu("Add ${e.label} to", null, items)
    }

    private fun openFolder(e: Entry.Folder) {
        a.showMenu(e.f.name, "${e.apps.size} apps", e.apps.map { app -> MenuPanel.Item(app.label) { a.launchApp(app) } })
    }

    private fun folderMenu(f: AppFolder) {
        val names = listOf("Streaming", "Music", "Games", "Live TV", "Tools", "Kids")
        a.showMenu(f.name, null, names.map { n ->
            MenuPanel.Item("Rename to $n") { a.app.config.update { c -> c.copy(folders = c.folders.map { if (it.id == f.id) it.copy(name = n) else it }) } }
        } + MenuPanel.Item("Remove folder", danger = true) { a.app.config.update { c -> c.copy(folders = c.folders.filter { it.id != f.id }) } })
    }

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {}
    override fun onCellClicked(row: RowData, cell: Cell) { (cell as? Cell.App)?.let { a.launchApp(it.entry) } }
    override fun onCellMenu(row: RowData, cell: Cell, view: View) { (cell as? Cell.App)?.let { appMenu(a, it.entry) } }
}
