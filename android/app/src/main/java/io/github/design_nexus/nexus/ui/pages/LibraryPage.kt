package io.github.design_nexus.nexus.ui.pages

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nexus.core.CardStyle
import io.github.design_nexus.nexus.core.FocusMemory
import io.github.design_nexus.nexus.data.AppRepository
import io.github.design_nexus.nexus.data.MediaJson
import io.github.design_nexus.nexus.media.Library
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.MediaProvider
import io.github.design_nexus.nexus.media.RowQuery
import io.github.design_nexus.nexus.media.RowSource
import io.github.design_nexus.nexus.media.SortOrder
import io.github.design_nexus.nexus.ui.BOLD
import io.github.design_nexus.nexus.ui.Cell
import io.github.design_nexus.nexus.ui.CellAdapter
import io.github.design_nexus.nexus.ui.Chip
import io.github.design_nexus.nexus.ui.HomeActivity
import io.github.design_nexus.nexus.ui.MATCH
import io.github.design_nexus.nexus.ui.MEDIUM
import io.github.design_nexus.nexus.ui.MenuPanel
import io.github.design_nexus.nexus.ui.Page
import io.github.design_nexus.nexus.ui.Palette
import io.github.design_nexus.nexus.ui.RowData
import io.github.design_nexus.nexus.ui.RowHost
import io.github.design_nexus.nexus.ui.WRAP
import io.github.design_nexus.nexus.ui.dp
import io.github.design_nexus.nexus.ui.flp
import io.github.design_nexus.nexus.ui.hlinear
import io.github.design_nexus.nexus.ui.llp
import io.github.design_nexus.nexus.ui.rounded
import io.github.design_nexus.nexus.ui.text
import io.github.design_nexus.nexus.ui.vlinear
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

    private val root = object : FrameLayout(a) {
        init {
            clipChildren = false
            clipToPadding = false
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (chips.hasFocus()) {
                            a.focusNav()
                            return true
                        }
                        if (grid.hasFocus()) {
                            val focused = grid.focusedChild
                            val pos = if (focused != null) grid.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
                            if (pos in 0 until 7) {
                                focusSelectedChip()
                                return true
                            }
                        }
                        if (alphaRail.hasFocus() && alphaRail.getChildAt(0)?.hasFocus() == true) {
                            focusSelectedChip()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (chips.hasFocus()) {
                            val last = chips.getChildAt(chips.childCount - 1)
                            if (last?.hasFocus() == true) {
                                if (alphaScroll.visibility == View.VISIBLE) {
                                    val target = alphaViews[activeAlpha] ?: alphaViews["#"]
                                    if (target != null && !target.requestFocus()) {
                                        alphaRail.post { target.requestFocus() }
                                    }
                                }
                                return true
                            }
                        }
                        if (alphaRail.hasFocus()) return true
                        if (grid.hasFocus()) {
                            val focused = grid.focusedChild
                            val pos = if (focused != null) grid.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
                            if (pos >= 0 && (pos % 7 == 6 || pos == adapter.itemCount - 1)) {
                                if (alphaScroll.visibility == View.VISIBLE) focusAlphaForPosition(pos)
                                return true
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (alphaRail.hasFocus()) {
                            focusGridFromAlpha()
                            return true
                        }
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }
    override val view: View get() = root
    private val title = a.text(30f, palette.text, BOLD, s = if (kind == Kind.MOVIES) "Movies" else "TV Shows")
    private val count = a.text(13f, palette.sub0)
    private val chips = hlinear(a)
    private val grid = RecyclerView(a)
    private val alphaScroll = object : ScrollView(a) {
        override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean) = false
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = false
        visibility = View.GONE
    }
    private val alphaRail = vlinear(a)
    private val alphaViews = LinkedHashMap<String, TextView>()
    private var activeAlpha: String? = null
    private val adapter = CellAdapter(this, CardStyle.POSTER, captions = true)
    private val handler = Handler(Looper.getMainLooper())

    private var libraries: List<Library> = emptyList()
    private var library: Library? = null
    private var genre: String? = null
    private var collectionsMode = false
    private var sort = SortOrder.RECENTLY_ADDED
    private val items = ArrayList<MediaItem>()
    private var total = 0
    private var totalKnown = false
    private var loading: Job? = null
    private var loaded = false
    private var suppressLoadMore = false

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
        grid.layoutManager = object : GridLayoutManager(a, 7) {
            override fun supportsPredictiveItemAnimations() = false
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView,
                child: View,
                rect: Rect,
                immediate: Boolean,
                focusedChildVisible: Boolean,
            ): Boolean {
                if (alphaRail.hasFocus()) return false
                val dy = child.top - parent.paddingTop
                if (dy == 0) return false
                if (immediate || reduceMotion) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
                return true
            }
        }
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.outlineProvider = ViewOutlineProvider.BOUNDS
        grid.clipToOutline = true
        grid.setPadding(a.dp(48), a.dp(14), a.dp(48), a.dp(40))
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        grid.setHasFixedSize(true)
        grid.isNestedScrollingEnabled = false
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (suppressLoadMore) return
                val lm = rv.layoutManager as GridLayoutManager
                if (!collectionsMode && lm.findLastVisibleItemPosition() > items.size - 21 && items.size < total) loadMore()
            }
        })
        root.addView(grid, flp(MATCH, MATCH).apply { topMargin = a.dp(166) })
        buildAlphaRail()
        alphaScroll.addView(alphaRail, FrameLayout.LayoutParams(WRAP, WRAP))
        root.addView(
            alphaScroll,
            flp(WRAP, MATCH, Gravity.END).apply {
                topMargin = a.dp(168)
                bottomMargin = a.dp(24)
                rightMargin = a.dp(12)
            },
        )
    }

    private fun buildAlphaRail() {
        alphaRail.gravity = Gravity.CENTER_HORIZONTAL
        alphaRail.clipChildren = false
        for (letter in ALPHA_LETTERS) {
            val tv = TextView(a).apply {
                text = letter
                textSize = 10.5f
                typeface = MEDIUM
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = "Jump to $letter"
                setOnClickListener { jumpToLetter(letter) }
                setOnFocusChangeListener { _, f ->
                    paintAlpha(this, letter, f)
                    if (f) {
                        val maxScroll = (alphaRail.height - alphaScroll.height).coerceAtLeast(0)
                        val targetY = (top + height / 2 - alphaScroll.height / 2).coerceIn(0, maxScroll)
                        if (alphaScroll.scrollY != targetY) alphaScroll.scrollTo(0, targetY)
                    }
                }
            }
            paintAlpha(tv, letter, false)
            alphaViews[letter] = tv
            alphaRail.addView(tv, llp(a.dp(26), a.dp(24)) { topMargin = a.dp(1); bottomMargin = a.dp(1) })
        }
    }

    private fun paintAlpha(tv: TextView, letter: String, focused: Boolean) {
        val active = activeAlpha == letter
        tv.background = if (focused) rounded(palette.text, a.dp(6).toFloat()) else null
        tv.setTextColor(
            when {
                focused -> palette.base
                active -> palette.accent
                else -> palette.sub0
            },
        )
    }

    private fun repaintAlpha() {
        for ((letter, tv) in alphaViews) paintAlpha(tv, letter, tv.hasFocus())
    }

    override fun focusDefault() {
        if (!focusFirstGridItem()) focusSelectedChip()
    }

    private fun focusFirstGridItem(): Boolean {
        if (adapter.itemCount == 0) return false
        val lm = grid.layoutManager as GridLayoutManager
        val first = lm.findViewByPosition(0)
        if (first != null) return first.requestFocus()
        lm.scrollToPositionWithOffset(0, 0)
        grid.post { (grid.layoutManager as GridLayoutManager).findViewByPosition(0)?.requestFocus() }
        return true
    }

    private fun focusSelectedChip() {
        var allChip: View? = null
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as? Chip ?: continue
            if (c.selectedChip) {
                c.requestFocus()
                return
            }
            if (allChip == null && c.text.toString() == "All") allChip = c
        }
        (allChip ?: chips.getChildAt(0))?.requestFocus()
    }

    private fun focusAlphaForPosition(pos: Int) {
        val letter = items.getOrNull(pos)?.let { alphaForTitle(it.title) } ?: "A"
        (alphaViews[letter] ?: alphaViews["A"])?.requestFocus()
    }

    private fun focusGridFromAlpha() {
        val focused = alphaRail.findFocus()
        if (focused != null && grid.childCount > 0) {
            val fLoc = IntArray(2)
            focused.getLocationInWindow(fLoc)
            val targetY = fLoc[1] + focused.height / 2
            val cLoc = IntArray(2)
            var best: View? = null
            var bestDy = Int.MAX_VALUE
            var bestX = Int.MIN_VALUE
            val rowSnap = a.dp(48)
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i) ?: continue
                if (!child.isFocusable) continue
                child.getLocationInWindow(cLoc)
                val cy = cLoc[1] + child.height / 2
                val dy = kotlin.math.abs(cy - targetY)
                val sameBand = kotlin.math.abs(dy - bestDy) <= rowSnap
                if (dy < bestDy - rowSnap || (sameBand && (dy < bestDy || cLoc[0] > bestX))) {
                    if (!sameBand || dy < bestDy) bestDy = dy
                    bestX = cLoc[0]
                    best = child
                }
            }
            if (best != null && best.requestFocus()) return
        }
        val lm = grid.layoutManager as GridLayoutManager
        val pos = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        lm.findViewByPosition(pos)?.requestFocus() ?: grid.getChildAt(0)?.requestFocus()
    }

    override fun onShow() {
        a.paintScrim(full = true)
        if (!loaded) start()
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        suppressLoadMore = true
        grid.stopScroll()
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(0, 0)
        alphaScroll.scrollTo(0, 0)
        activeAlpha = items.firstOrNull()?.let { alphaForTitle(it.title) }
        repaintAlpha()
        suppressLoadMore = false
    }

    private fun start() {
        loaded = true
        a.scope.launch {
            libraries = runCatching { provider.libraries() }.getOrDefault(emptyList())
                .filter { it.kind == if (kind == Kind.MOVIES) MediaKind.MOVIE else MediaKind.SHOW }
            library = libraries.firstOrNull()
            buildChips(runCatching { library?.let { provider.genres(it.id) } }.getOrNull().orEmpty())
            reload()
            if (root.isAttachedToWindow && root.findFocus() == null && a.currentRootTab() == tab) {
                focusDefault()
            }
        }
    }

    private fun buildChips(genres: List<String>) {
        chips.removeAllViews()
        fun add(label: String, selected: Boolean, click: () -> Unit) {
            val c = Chip(a, palette, label, selected)
            c.setOnClickListener { click() }
            chips.addView(c, llp(WRAP, a.dp(28)) { marginEnd = a.dp(7) })
        }
        add("Sort: ${sortLabel()}", false) {
            a.showMenu(
                "Sort",
                null,
                SortOrder.entries.map { order ->
                    MenuPanel.Item(
                        label = sortLabel(order),
                        right = if (sort == order) "✓" else null,
                    ) {
                        if (sort != order) {
                            sort = order
                            reload()
                            repaint()
                        }
                    }
                },
            )
        }
        if (libraries.size > 1) for (l in libraries) add(l.title, l == library) { library = l; genre = null; collectionsMode = false; start2() }
        add("All", genre == null && !collectionsMode) { genre = null; collectionsMode = false; reload(); repaint() }
        if (kind == Kind.MOVIES) add("Collections", collectionsMode) { collectionsMode = true; genre = null; reload(); repaint() }
        for (g in orderGenres(genres)) add(g, g == genre) { genre = g; collectionsMode = false; reload(); repaint() }
    }

    private fun orderGenres(raw: List<String>): List<String> {
        val filtered = raw.filterNot { it.equals("Award Show", ignoreCase = true) }
            .ifEmpty { raw }
        val rank = POPULAR_GENRES.withIndex().associate { it.value.lowercase() to it.index }
        return filtered.sortedWith(
            compareBy<String>({ rank[it.lowercase()] ?: Int.MAX_VALUE }, { it.lowercase() }),
        )
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

    private fun sortLabel(order: SortOrder = sort) = when (order) {
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
        totalKnown = false
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
                totalKnown = true
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
        if (items.size >= total) return
        loading = a.scope.launch {
            runCatching { provider.browse(lib.id, sort, genre, items.size, PAGE) }.onSuccess { p ->
                items.addAll(p.items); total = p.total; publish(allowAutoFocus = false)
            }
        }
    }

    private fun scrollGridTo(idx: Int) {
        val rowStart = (idx / 7) * 7
        suppressLoadMore = true
        grid.stopScroll()
        val lm = grid.layoutManager as GridLayoutManager
        lm.scrollToPositionWithOffset(rowStart, 0)
        grid.post {
            if (lm.findFirstVisibleItemPosition() != rowStart) {
                lm.scrollToPositionWithOffset(rowStart, 0)
            }
        }
        grid.postDelayed({ suppressLoadMore = false }, 300)
    }

    private fun jumpToLetter(letter: String) {
        if (sort != SortOrder.TITLE || collectionsMode) return
        val lib = library ?: return
        loading?.cancel()
        val existingIdx = findLetterIndexIn(items, letter)
        if (totalKnown && existingIdx != null && (items.size - existingIdx >= PAGE || items.size >= total)) {
            activeAlpha = items.getOrNull(existingIdx)?.let { alphaForTitle(it.title) } ?: letter
            repaintAlpha()
            scrollGridTo(existingIdx)
            return
        }
        loading = a.scope.launch {
            try {
                if (items.isEmpty() || !totalKnown) {
                    val firstPage = provider.browse(lib.id, SortOrder.TITLE, genre, 0, JUMP_PAGE)
                    items.clear()
                    items.addAll(firstPage.items)
                    total = firstPage.total
                    totalKnown = true
                }
                while (items.size < total) {
                    val found = findLetterIndexIn(items, letter)
                    if (found != null && items.size - found >= PAGE) break
                    val page = provider.browse(lib.id, SortOrder.TITLE, genre, items.size, JUMP_PAGE)
                    if (page.items.isEmpty()) break
                    items.addAll(page.items)
                    total = page.total
                }
                val idx = findLetterIndexIn(items, letter) ?: (items.size - 1).coerceAtLeast(0)
                activeAlpha = items.getOrNull(idx)?.let { alphaForTitle(it.title) } ?: letter
                repaintAlpha()
                publish(allowAutoFocus = false)
                scrollGridTo(idx)
            } catch (_: Exception) {
                suppressLoadMore = false
                publish(allowAutoFocus = false)
            }
        }
    }

    private fun findLetterIndexIn(list: List<MediaItem>, letter: String): Int? {
        if (letter == "#") {
            val idx = list.indexOfFirst { alphaForTitle(it.title) == "#" }
            return if (idx >= 0) idx else if (list.isNotEmpty()) 0 else null
        }
        val target = letter[0]
        val exact = list.indexOfFirst { alphaForTitle(it.title) == letter }
        if (exact >= 0) return exact
        val after = list.indexOfFirst {
            val ch = alphaForTitle(it.title)[0]
            ch in 'A'..'Z' && ch >= target
        }
        return if (after >= 0) after else null
    }

    private fun sortKey(rawTitle: String): String {
        val clean = rawTitle.trim().dropWhile { !it.isLetterOrDigit() }
        val stripped = when {
            clean.startsWith("The ", ignoreCase = true) -> clean.substring(4)
            clean.startsWith("An ", ignoreCase = true) -> clean.substring(3)
            clean.startsWith("A ", ignoreCase = true) -> clean.substring(2)
            else -> clean
        }
        return stripped.trimStart().dropWhile { !it.isLetterOrDigit() }
    }

    private fun alphaForTitle(rawTitle: String): String {
        val ch = sortKey(rawTitle).firstOrNull()?.uppercaseChar() ?: return "#"
        return if (ch in 'A'..'Z') ch.toString() else "#"
    }

    private fun publish(allowAutoFocus: Boolean = true) {
        val noun = when { collectionsMode -> "collections"; kind == Kind.MOVIES -> "movies"; else -> "shows" }
        count.text = if (total > 0) "${"%,d".format(total)} $noun" else if (library == null) count.text else "Nothing here yet"
        val showAlpha = sort == SortOrder.TITLE && items.isNotEmpty()
        alphaScroll.visibility = if (showAlpha) View.VISIBLE else View.GONE
        grid.setPadding(a.dp(48), a.dp(14), a.dp(if (showAlpha) 56 else 48), a.dp(40))
        adapter.submit(RowData("grid", "", CardStyle.POSTER, items.map { Cell.Media(it) }))
        if (allowAutoFocus && root.isAttachedToWindow && root.findFocus() == null && a.currentRootTab() == tab) {
            focusFirstGridItem()
        }
    }

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {
        val item = (cell as? Cell.Media)?.item ?: return
        val nextAlpha = alphaForTitle(item.title)
        if (activeAlpha != nextAlpha) {
            activeAlpha = nextAlpha
            repaintAlpha()
        }
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
            a.scope.launch {
                runCatching { provider.setWatched(i, !i.watched) }.onFailure { a.toast("Couldn't update watched state") }
                reload()
            }
        }
        a.showMenu(i.title, i.year?.toString(), items)
    }

    @Suppress("unused")
    private fun encodeForDebug() = MediaJson.encode(items.take(1))

    private companion object {
        const val PAGE = 70
        const val JUMP_PAGE = 350
        val ALPHA_LETTERS = listOf("#") + ('A'..'Z').map { it.toString() }
        val POPULAR_GENRES = listOf(
            "Action",
            "Adventure",
            "Animation",
            "Comedy",
            "Crime",
            "Documentary",
            "Drama",
            "Family",
            "Fantasy",
            "Horror",
            "Mystery",
            "Romance",
            "Sci-Fi",
            "Science Fiction",
            "Thriller",
            "Western",
        )
    }
}
