package io.github.design_nexus.nyx.tv.ui.pages

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.data.MediaJson
import io.github.design_nexus.nyx.tv.media.Library
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.RowQuery
import io.github.design_nexus.nyx.tv.media.RowSource
import io.github.design_nexus.nyx.tv.media.SortOrder
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.Cell
import io.github.design_nexus.nyx.tv.ui.CellAdapter
import io.github.design_nexus.nyx.tv.ui.Chip
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MenuPanel
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.RowData
import io.github.design_nexus.nyx.tv.ui.RowHost
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Movies or TV Shows: genre chips, sort, collections, and a poster grid that pages in. */
class LibraryPage(a: HomeActivity, private val kind: Kind) : Page(a), RowHost {
    enum class Kind { MOVIES, SHOWS }

    override val tab = if (kind == Kind.MOVIES) "movies" else "shows"
    override val palette: Palette get() = a.palette
    override val provider: MediaProvider get() = a.app.plex
    override val images: ImageLoader get() = a.app.images
    override val appRepo: AppRepository get() = a.app.apps
    override val reduceMotion get() = a.config.reduceMotion
    override val focus = FocusMemory()
    override val scope: CoroutineScope get() = a.scope

    private val root = FrameLayout(a)
    override val view: View get() = root
    private val title = a.text(30f, palette.text, BOLD, s = if (kind == Kind.MOVIES) "Movies" else "TV Shows")
    private val count = a.text(13f, palette.sub0)
    private val chips = hlinear(a)
    private val grid = RecyclerView(a)
    private val adapter = CellAdapter(this, CardStyle.POSTER, captions = true)
    private val handler = Handler(Looper.getMainLooper())

    private var libraries: List<Library> = emptyList()
    private var library: Library? = null
    private var genre: String? = null
    private var collectionsMode = false
    private var sort = SortOrder.RECENTLY_ADDED
    private val items = ArrayList<MediaItem>()
    private var total = 0
    private var loading: Job? = null
    private var loaded = false

    init {
        val head = vlinear(a)
        val t = hlinear(a)
        t.addView(title)
        t.addView(count, llp(WRAP, WRAP) { marginStart = a.dp(14) })
        head.addView(t)
        val sc = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(chips) }
        chips.clipChildren = false
        chips.setPadding(0, a.dp(6), a.dp(20), a.dp(6))
        head.addView(sc, llp(MATCH, WRAP) { topMargin = a.dp(10) })
        root.addView(head, flp(MATCH, WRAP).apply { setMargins(a.dp(48), a.dp(76), a.dp(48), 0) })
        grid.layoutManager = GridLayoutManager(a, 7)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.setPadding(a.dp(48), a.dp(10), a.dp(30), a.dp(40))
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!collectionsMode && lm.findLastVisibleItemPosition() > items.size - 21 && items.size < total) loadMore()
            }
        })
        root.addView(grid, flp(MATCH, MATCH).apply { topMargin = a.dp(160) })
    }

    override fun focusDefault() {
        val lm = grid.layoutManager as GridLayoutManager
        val i = focus.positionIn("grid", adapter.itemCount)
        lm.findViewByPosition(i)?.requestFocus() ?: (chips.getChildAt(0)?.requestFocus() ?: grid.requestFocus())
    }

    override fun onShow() {
        a.paintScrim(full = true)
        if (!loaded) start()
    }

    override fun onHide() = handler.removeCallbacksAndMessages(null)

    private fun start() {
        loaded = true
        a.scope.launch {
            libraries = runCatching { provider.libraries() }.getOrDefault(emptyList())
                .filter { it.kind == if (kind == Kind.MOVIES) MediaKind.MOVIE else MediaKind.SHOW }
            library = libraries.firstOrNull()
            buildChips(runCatching { library?.let { provider.genres(it.id) } }.getOrNull().orEmpty())
            reload()
        }
    }

    private fun buildChips(genres: List<String>) {
        chips.removeAllViews()
        fun add(label: String, selected: Boolean, click: () -> Unit) {
            val c = Chip(a, palette, label, selected)
            c.setOnClickListener { click() }
            chips.addView(c, llp(WRAP, a.dp(28)) { marginEnd = a.dp(7) })
        }
        if (libraries.size > 1) for (l in libraries) add(l.title, l == library) { library = l; genre = null; collectionsMode = false; start2() }
        add("All", genre == null && !collectionsMode) { genre = null; collectionsMode = false; reload(); repaint() }
        if (kind == Kind.MOVIES) add("Collections", collectionsMode) { collectionsMode = true; genre = null; reload(); repaint() }
        for (g in genres.take(14)) add(g, g == genre) { genre = g; collectionsMode = false; reload(); repaint() }
        add("Sort: ${sortLabel()}", false) {
            sort = SortOrder.entries[(sort.ordinal + 1) % SortOrder.entries.size]
            reload(); repaint()
        }
    }

    private fun start2() = a.scope.launch {
        buildChips(runCatching { library?.let { provider.genres(it.id) } }.getOrNull().orEmpty())
        reload()
    }

    private fun repaint() {
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as Chip
            val t = c.text.toString()
            c.selectedChip = when {
                t.startsWith("Sort:") -> { c.text = "Sort: ${sortLabel()}"; false }
                t == "All" -> genre == null && !collectionsMode
                t == "Collections" -> collectionsMode
                libraries.any { it.title == t } -> library?.title == t
                else -> t == genre
            }
        }
    }

    private fun sortLabel() = when (sort) {
        SortOrder.RECENTLY_ADDED -> "Recently added"
        SortOrder.TITLE -> "Title"
        SortOrder.RELEASE_DATE -> "Release date"
        SortOrder.RATING -> "Rating"
    }

    private fun cacheKey() = "lib:${library?.id}:$sort:$genre:$collectionsMode"

    private fun reload() {
        loading?.cancel()
        items.clear()
        total = 0
        focus.forget("grid")
        val lib = library ?: run { count.text = "No ${title.text.toString().lowercase()} library on this server"; publish(); return }
        loading = a.scope.launch {
            withContext(Dispatchers.IO) { a.app.cache.get(cacheKey()) }?.let { cached -> items.addAll(cached); total = cached.size; publish() }
            try {
                if (collectionsMode) {
                    val list = provider.row(RowQuery(RowSource.COLLECTIONS, lib.id, limit = 200))
                    items.clear(); items.addAll(list); total = list.size
                } else {
                    val page = provider.browse(lib.id, sort, genre, 0, PAGE)
                    items.clear(); items.addAll(page.items); total = page.total
                }
                withContext(Dispatchers.IO) { a.app.cache.put(cacheKey(), items.take(PAGE)) }
                publish()
            } catch (_: Exception) {
                publish()
            }
        }
    }

    private fun loadMore() {
        if (loading?.isActive == true) return
        val lib = library ?: return
        loading = a.scope.launch {
            runCatching { provider.browse(lib.id, sort, genre, items.size, PAGE) }.onSuccess { p ->
                items.addAll(p.items); total = p.total; publish()
            }
        }
    }

    private fun publish() {
        val noun = when { collectionsMode -> "collections"; kind == Kind.MOVIES -> "movies"; else -> "shows" }
        count.text = if (total > 0) "${"%,d".format(total)} $noun" else if (library == null) count.text else "Nothing here yet"
        adapter.submit(RowData("grid", "", CardStyle.POSTER, items.map { Cell.Media(it) }))
    }

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {
        val item = (cell as? Cell.Media)?.item ?: return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ a.setBackdrop(item.backdrop) }, 300)
    }

    override fun onCellClicked(row: RowData, cell: Cell) {
        (cell as? Cell.Media)?.let { a.openDetails(it.item) }
    }

    override fun onCellMenu(row: RowData, cell: Cell, view: View) {
        val i = (cell as? Cell.Media)?.item ?: return
        val items = ArrayList<MenuPanel.Item>()
        if (i.kind == MediaKind.MOVIE) items += MenuPanel.Item(if (i.progress > 0) "Resume" else "Play") { a.play(i) }
        items += MenuPanel.Item("Details") { a.openDetails(i) }
        if (i.kind != MediaKind.COLLECTION) items += MenuPanel.Item(if (i.watched) "Mark as unwatched" else "Mark as watched") {
            a.scope.launch { runCatching { provider.setWatched(i, !i.watched) }; reload() }
        }
        a.showMenu(i.title, i.year?.toString(), items)
    }

    @Suppress("unused")
    private fun encodeForDebug() = MediaJson.encode(items.take(1))

    private companion object {
        const val PAGE = 70
    }
}
