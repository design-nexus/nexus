package io.github.design_nexus.nyx.tv.ui.pages

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.ServerRef
import io.github.design_nexus.nyx.tv.seerr.SeerrAuthExpiredException
import io.github.design_nexus.nyx.tv.seerr.SeerrGenre
import io.github.design_nexus.nyx.tv.seerr.SeerrMediaStatus
import io.github.design_nexus.nyx.tv.seerr.SeerrRequest
import io.github.design_nexus.nyx.tv.seerr.SeerrRequestStatus
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.Cell
import io.github.design_nexus.nyx.tv.ui.Chip
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MenuPanel
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.RowData
import io.github.design_nexus.nyx.tv.ui.RowHost
import io.github.design_nexus.nyx.tv.ui.RowView
import io.github.design_nexus.nyx.tv.ui.RowsAdapter
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Native Discover tab powered by Seerr: trending, popular, upcoming, genres, and My Requests. */
class DiscoverPage(a: HomeActivity) : Page(a), RowHost {
    enum class Mode { ALL, MOVIES, SHOWS, GENRE, MY_REQUESTS }

    override val tab = "discover"
    override val palette: Palette get() = a.palette
    override val provider: MediaProvider get() = a.app.seerr
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
                        if (list.hasFocus()) {
                            val first = (list.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                            if (first <= 0 && (list.getChildAt(0)?.top ?: 0) >= 0) {
                                focusSelectedChip()
                                return true
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (chips.hasFocus() && adapter.itemCount > 0) {
                            val v = (list.layoutManager as LinearLayoutManager).findViewByPosition(0) as? RowView
                            if (v != null) {
                                v.focusRemembered(focus)
                                return true
                            }
                            list.requestFocus()
                            return true
                        }
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }
    override val view: View get() = root

    private val header = vlinear(a).apply { clipChildren = false; clipToPadding = false }
    private val title = a.text(26f, palette.text, BOLD, s = "Discover")
    private val subtitle = a.text(13f, palette.sub0)
    private val chips = hlinear(a).apply { clipChildren = false; clipToPadding = false }
    private val chipViews = LinkedHashMap<Mode, Chip>()
    private val empty = a.text(14f, palette.sub0, lines = 3).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val list = RecyclerView(a)
    private val adapter = RowsAdapter(this)

    private var mode = Mode.ALL
    private var selectedGenre: SeerrGenre? = null
    private var genres: List<SeerrGenre> = emptyList()
    private val requestBadges = HashMap<String, Pair<String, Int>>()
    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        val top = hlinear(a).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(title, llp(WRAP, WRAP) { marginEnd = a.dp(14) })
        top.addView(subtitle)
        header.addView(top, llp(MATCH, WRAP) { setMargins(a.dp(48), 0, a.dp(48), a.dp(6)) })

        val chipScroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(a.dp(48), 0, a.dp(48), 0)
            addView(chips)
        }
        buildChips()
        header.addView(chipScroll, llp(MATCH, WRAP))
        root.addView(header, flp(MATCH, WRAP).apply { topMargin = a.dp(68) })

        list.layoutManager = object : LinearLayoutManager(a) {
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView,
                child: View,
                rect: android.graphics.Rect,
                immediate: Boolean,
                focusedChildVisible: Boolean,
            ): Boolean {
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
        list.outlineProvider = ViewOutlineProvider.BOUNDS
        list.clipToOutline = true
        list.setPadding(0, a.dp(6), 0, a.dp(16))
        root.addView(list, flp(MATCH, MATCH).apply { topMargin = a.dp(138) })
        root.addView(empty, flp(a.dp(520), WRAP, Gravity.CENTER))
    }

    override fun focusDefault() {
        focusSelectedChip()
    }

    override fun onShow() {
        a.paintScrim(full = true)
        a.setBackdrop(null)
        if (!loadedOnce || mode == Mode.MY_REQUESTS) {
            load()
        }
    }

    override fun onBack(): Boolean {
        if (mode != Mode.ALL) {
            setMode(Mode.ALL)
            focusSelectedChip()
            return true
        }
        return false
    }

    override fun providerFor(item: MediaItem): MediaProvider =
        if (item.server.provider == "seerr") a.app.seerr else a.app.plex

    override fun badgeFor(item: MediaItem): Pair<String, Int>? {
        requestBadges[item.key]?.let { return it }
        val info = a.app.seerr.cachedMediaInfo(item) ?: return null
        return when (info.status) {
            SeerrMediaStatus.AVAILABLE -> "In Plex" to palette.green
            SeerrMediaStatus.PENDING -> "Pending" to palette.yellow
            SeerrMediaStatus.PROCESSING -> "Processing" to palette.accent
            SeerrMediaStatus.PARTIALLY_AVAILABLE -> "Partial" to palette.yellow
            else -> null
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        chipViews.clear()
        val defs = listOf(
            Mode.ALL to "All",
            Mode.MOVIES to "Movies",
            Mode.SHOWS to "TV Shows",
            Mode.GENRE to (selectedGenre?.let { "Genre: ${it.name}" } ?: "Genre ▾"),
            Mode.MY_REQUESTS to "My Requests",
        )
        for ((m, label) in defs) {
            val c = Chip(a, palette, label, selected = mode == m)
            c.setOnClickListener {
                if (m == Mode.GENRE) openGenrePicker() else setMode(m)
            }
            chipViews[m] = c
            chips.addView(c, llp(WRAP, a.dp(28)) { marginEnd = a.dp(8) })
        }
    }

    private fun updateChipSelection() {
        for ((m, c) in chipViews) {
            c.selectedChip = (m == mode)
            if (m == Mode.GENRE) {
                c.text = selectedGenre?.let { "Genre: ${it.name}" } ?: "Genre ▾"
            }
        }
    }

    private fun focusSelectedChip() {
        (chipViews[mode] ?: chipViews[Mode.ALL])?.requestFocus()
    }

    private fun setMode(next: Mode) {
        mode = next
        if (next != Mode.GENRE) selectedGenre = null
        updateChipSelection()
        load()
    }

    private fun openGenrePicker() {
        a.scope.launch {
            val listGenres = if (genres.isNotEmpty()) genres else {
                runCatching { a.app.seerr.allGenres() }.getOrElse { emptyList() }.also { genres = it }
            }
            if (listGenres.isEmpty()) {
                a.toast("Could not load genres from Seerr")
                return@launch
            }
            val items = listGenres.map { g ->
                MenuPanel.Item(g.name, if (selectedGenre?.id == g.id) "✓" else null) {
                    selectedGenre = g
                    mode = Mode.GENRE
                    updateChipSelection()
                    load()
                }
            }
            a.showMenu("Filter by Genre", "Movies and TV Shows", items)
        }
    }

    private fun load() {
        loadJob?.cancel()
        empty.visibility = View.GONE
        subtitle.text = when (mode) {
            Mode.ALL -> "Trending, popular & upcoming"
            Mode.MOVIES -> "Popular & upcoming movies"
            Mode.SHOWS -> "Popular & upcoming TV shows"
            Mode.GENRE -> selectedGenre?.name ?: "By genre"
            Mode.MY_REQUESTS -> "Track your Seerr requests"
        }
        loadJob = a.scope.launch {
            try {
                val rows = when (mode) {
                    Mode.ALL -> loadAll()
                    Mode.MOVIES -> loadMovies()
                    Mode.SHOWS -> loadShows()
                    Mode.GENRE -> loadGenre(selectedGenre)
                    Mode.MY_REQUESTS -> loadMyRequests()
                }
                loadedOnce = true
                adapter.submit(rows)
                if (rows.isEmpty()) {
                    empty.text = if (mode == Mode.MY_REQUESTS) {
                        "You haven't requested any titles yet."
                    } else {
                        "No titles available right now."
                    }
                    empty.visibility = View.VISIBLE
                }
            } catch (e: SeerrAuthExpiredException) {
                adapter.submit(emptyList())
                empty.text = "Seerr session expired. Open Settings › Seerr to sign in again."
                empty.visibility = View.VISIBLE
            } catch (e: Exception) {
                adapter.submit(emptyList())
                empty.text = "Could not reach Seerr: ${e.message ?: "Network error"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun loadAll(): List<RowData> = kotlinx.coroutines.coroutineScope {
        val t = async { runCatching { a.app.seerr.trending() }.getOrElse { emptyList() } }
        val pm = async { runCatching { a.app.seerr.popularMovies() }.getOrElse { emptyList() } }
        val pt = async { runCatching { a.app.seerr.popularTv() }.getOrElse { emptyList() } }
        val um = async { runCatching { a.app.seerr.upcomingMovies() }.getOrElse { emptyList() } }
        val ut = async { runCatching { a.app.seerr.upcomingTv() }.getOrElse { emptyList() } }
        buildList {
            t.await().takeIf { it.isNotEmpty() }?.let { add(row("trending", "Trending", it)) }
            pm.await().takeIf { it.isNotEmpty() }?.let { add(row("pop-movies", "Popular Movies", it)) }
            pt.await().takeIf { it.isNotEmpty() }?.let { add(row("pop-tv", "Popular TV Shows", it)) }
            um.await().takeIf { it.isNotEmpty() }?.let { add(row("up-movies", "Upcoming Movies", it)) }
            ut.await().takeIf { it.isNotEmpty() }?.let { add(row("up-tv", "Upcoming TV Shows", it)) }
        }
    }

    private suspend fun loadMovies(): List<RowData> = kotlinx.coroutines.coroutineScope {
        val pm = async { a.app.seerr.popularMovies() }
        val um = async { a.app.seerr.upcomingMovies() }
        buildList {
            pm.await().takeIf { it.isNotEmpty() }?.let { add(row("pop-movies", "Popular Movies", it)) }
            um.await().takeIf { it.isNotEmpty() }?.let { add(row("up-movies", "Upcoming Movies", it)) }
        }
    }

    private suspend fun loadShows(): List<RowData> = kotlinx.coroutines.coroutineScope {
        val pt = async { a.app.seerr.popularTv() }
        val ut = async { a.app.seerr.upcomingTv() }
        buildList {
            pt.await().takeIf { it.isNotEmpty() }?.let { add(row("pop-tv", "Popular TV Shows", it)) }
            ut.await().takeIf { it.isNotEmpty() }?.let { add(row("up-tv", "Upcoming TV Shows", it)) }
        }
    }

    private suspend fun loadGenre(g: SeerrGenre?): List<RowData> {
        if (g == null) return emptyList()
        return kotlinx.coroutines.coroutineScope {
            val m = async { runCatching { a.app.seerr.moviesByGenre(g.id) }.getOrElse { emptyList() } }
            val t = async { runCatching { a.app.seerr.tvByGenre(g.id) }.getOrElse { emptyList() } }
            buildList {
                m.await().takeIf { it.isNotEmpty() }?.let { add(row("g-m-${g.id}", "${g.name} Movies", it)) }
                t.await().takeIf { it.isNotEmpty() }?.let { add(row("g-t-${g.id}", "${g.name} TV Shows", it)) }
            }
        }
    }

    private suspend fun loadMyRequests(): List<RowData> {
        val reqs = a.app.seerr.myRequests("all")
        requestBadges.clear()
        val itemsWithStatus = reqs.map { req ->
            val item = req.toMediaItem()
            requestBadges[item.key] = badgeForRequest(req)
            req to item
        }
        val pending = itemsWithStatus.filter { (r, _) ->
            r.status == SeerrRequestStatus.PENDING ||
                (r.status == SeerrRequestStatus.APPROVED && r.mediaStatus != SeerrMediaStatus.AVAILABLE)
        }.map { it.second }
        val available = itemsWithStatus.filter { (r, _) ->
            r.mediaStatus == SeerrMediaStatus.AVAILABLE || r.mediaStatus == SeerrMediaStatus.PARTIALLY_AVAILABLE
        }.map { it.second }
        val other = itemsWithStatus.filter { (r, _) ->
            r.status == SeerrRequestStatus.DECLINED || r.status == SeerrRequestStatus.FAILED
        }.map { it.second }

        return buildList {
            if (pending.isNotEmpty()) add(row("req-pending", "Pending & Processing", pending))
            if (available.isNotEmpty()) add(row("req-available", "Available", available))
            if (other.isNotEmpty()) add(row("req-other", "Declined / Failed", other))
        }
    }

    private fun badgeForRequest(r: SeerrRequest): Pair<String, Int> = when {
        r.mediaStatus == SeerrMediaStatus.AVAILABLE -> "In Plex" to palette.green
        r.mediaStatus == SeerrMediaStatus.PARTIALLY_AVAILABLE -> "Partial" to palette.yellow
        r.status == SeerrRequestStatus.PENDING -> "Pending" to palette.yellow
        r.status == SeerrRequestStatus.APPROVED -> "Processing" to palette.accent
        r.status == SeerrRequestStatus.DECLINED -> "Declined" to palette.red
        r.status == SeerrRequestStatus.FAILED -> "Failed" to palette.red
        else -> r.status.label to palette.accent
    }

    private fun SeerrRequest.toMediaItem(): MediaItem {
        val prefix = if (mediaKind == MediaKind.SHOW) "tv" else "movie"
        return MediaItem(
            server = ServerRef("seerr", "default"),
            id = "$prefix:$tmdbId",
            kind = mediaKind,
            title = title,
            year = year,
            poster = posterPath,
            backdrop = backdropPath,
            tmdbId = tmdbId,
            tvdbId = tvdbId,
        )
    }

    private fun row(id: String, title: String, items: List<MediaItem>): RowData =
        RowData(id, title, CardStyle.POSTER, items.map { Cell.Media(it) })

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {}

    override fun onCellClicked(row: RowData, cell: Cell) {
        if (cell is Cell.Media) a.openDetails(cell.item)
    }

    override fun onCellMenu(row: RowData, cell: Cell, view: View) {
        if (cell is Cell.Media) a.openDetails(cell.item)
    }
}
