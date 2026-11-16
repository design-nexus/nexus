package io.github.design_nexus.nyx.tv.ui.pages

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.R
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.media.MediaDetails
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.Cell
import io.github.design_nexus.nyx.tv.ui.Chip
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MEDIUM
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.PillButton
import io.github.design_nexus.nyx.tv.ui.RowData
import io.github.design_nexus.nyx.tv.ui.RowHost
import io.github.design_nexus.nyx.tv.ui.RowsAdapter
import io.github.design_nexus.nyx.tv.ui.WRAP
import kotlinx.coroutines.Job
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.formatRuntime
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.vlinear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A title's own page: backdrop, facts, Play or Resume, and seasons, episodes or related titles. */
class DetailsPage(a: HomeActivity, private var item: MediaItem) : Page(a), RowHost {
    override val palette: Palette get() = a.palette
    override val provider: MediaProvider get() = a.app.plex
    override val images: ImageLoader get() = a.app.images
    override val appRepo: AppRepository get() = a.app.apps
    override val reduceMotion get() = a.config.reduceMotion
    override val focus = FocusMemory()
    override val scope: CoroutineScope get() = a.scope

    private val root = FrameLayout(a)
    override val view: View get() = root
    private val info = vlinear(a)
    private val eyebrow = a.text(11f, palette.accent, BOLD).apply { letterSpacing = 0.12f }
    private val title = a.text(40f, palette.text, BOLD, 2)
    private val meta = a.text(13f, palette.sub1, MEDIUM)
    private var ratings: LinearLayout = hlinear(a)
    private val summary = a.text(13.5f, palette.sub1, lines = 4).apply { setLineSpacing(0f, 1.15f) }
    private val credits = a.text(12f, palette.sub0, lines = 2)
    private val buttons = hlinear(a).apply { clipChildren = false }
    private val seasons = hlinear(a).apply { clipChildren = false }
    private val seasonScroll = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        visibility = View.GONE
        addView(seasons)
    }
    private val list = RecyclerView(a)
    private val adapter = RowsAdapter(this)
    private var details: MediaDetails? = null
    private var seasonItems: List<MediaItem> = emptyList()
    private var currentSeason: MediaItem? = null
    private var episodes: List<MediaItem> = emptyList()
    private var playTarget: MediaItem? = null
    private var episodesJob: Job? = null
    private val column = vlinear(a).apply { clipChildren = false; clipToPadding = false }

    init {
        info.clipChildren = false
        info.setPadding(a.dp(48), 0, a.dp(48), 0)
        info.addView(eyebrow)
        info.addView(title, llp(MATCH, WRAP) { topMargin = a.dp(8); bottomMargin = a.dp(10) })
        info.addView(meta)
        info.addView(ratings, llp(WRAP, WRAP) { topMargin = a.dp(10) })
        info.addView(summary, llp(a.dp(520), WRAP) { topMargin = a.dp(12) })
        info.addView(credits, llp(a.dp(520), WRAP) { topMargin = a.dp(8) })
        info.addView(buttons, llp(WRAP, WRAP) { topMargin = a.dp(18) })
        // Season chips sit under Play — never overlaid at a fixed Y that covers the buttons.
        column.addView(info, llp(a.dp(736), WRAP))
        seasonScroll.clipToPadding = false
        column.addView(seasonScroll, llp(MATCH, WRAP) { topMargin = a.dp(16); bottomMargin = a.dp(10) })
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = adapter
        list.itemAnimator = null
        list.clipChildren = false
        list.clipToPadding = false
        list.setPadding(0, a.dp(8), 0, a.dp(24))
        column.addView(list, llp(MATCH, 0, 1f))
        root.addView(column, flp(MATCH, MATCH).apply { setMargins(0, a.dp(80), 0, 0) })
        seasonScroll.setPadding(a.dp(48), 0, a.dp(48), 0)
        render()
        load()
    }

    override fun focusDefault() {
        buttons.getChildAt(0)?.requestFocus()
    }

    override fun onShow() {
        a.paintScrim(full = false)
        a.setBackdrop(item.backdrop)
        currentSeason?.let { loadEpisodes(it, preserveFocus = true) }
    }

    private fun render() {
        val i = item
        val isEp = i.kind == MediaKind.EPISODE
        eyebrow.text = when {
            isEp -> listOfNotNull(i.seriesTitle?.uppercase(), i.episodeCode).joinToString(" · ")
            i.tagline.isNotBlank() -> i.tagline.uppercase()
            i.kind == MediaKind.SHOW -> i.childCount?.let { "$it SEASONS" } ?: "TV SHOW"
            i.kind == MediaKind.COLLECTION -> "COLLECTION"
            i.kind == MediaKind.PLAYLIST -> i.childCount?.let { "$it TITLES" } ?: "PLAYLIST"
            else -> "MOVIE"
        }
        title.text = i.title
        val left = if (i.progress > 0) "${formatRuntime(i.remainingMs)} left" else null
        meta.text = listOfNotNull(metaLine(i.copy(seasonNumber = null)).ifEmpty { null }, left).joinToString("  ·  ")
        info.removeView(ratings)
        ratings = ratingChips(a, i)
        info.addView(ratings, 3)
        summary.text = i.summary
        val d = details
        credits.text = if (d == null) "" else listOfNotNull(
            d.cast.take(4).joinToString(", ") { it.name }.ifEmpty { null }?.let { "Starring $it" },
            d.directors.take(2).joinToString(", ").ifEmpty { null }?.let { "Directed by $it" },
            d.studio,
        ).joinToString("  ·  ")
        buildButtons()
    }

    private fun buildButtons() {
        val hadFocus = buttons.hasFocus()
        buttons.removeAllViews()
        fun add(b: PillButton, click: () -> Unit) {
            b.setOnClickListener { click() }
            b.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) focusBelow() else false }
            buttons.addView(b, llp(WRAP, a.dp(38)) { marginEnd = a.dp(10) })
        }
        val i = item
        when (i.kind) {
            MediaKind.MOVIE, MediaKind.EPISODE -> {
                add(PillButton(a, palette, if (i.progress > 0) "Resume" else "Play", R.drawable.ic_play, primary = true, reduceMotion = reduceMotion)) { a.play(i) }
                if (i.progress > 0) add(PillButton(a, palette, "From the beginning", reduceMotion = reduceMotion)) { a.play(i, fromStart = true) }
                add(PillButton(a, palette, if (i.watched) "Mark unwatched" else "Mark watched", R.drawable.ic_check, reduceMotion = reduceMotion)) { toggleWatched() }
                if (i.kind == MediaKind.EPISODE && i.seriesId != null) add(PillButton(a, palette, "Go to show", R.drawable.ic_tv, reduceMotion = reduceMotion)) {
                    a.openDetails(i.copy(id = i.seriesId!!, kind = MediaKind.SHOW, title = i.seriesTitle ?: i.title, seasonNumber = null, episodeNumber = null, thumb = null, summary = ""))
                }
            }
            MediaKind.SHOW, MediaKind.SEASON -> {
                val t = playTarget
                val label = when {
                    t == null -> "Play"
                    t.progress > 0 -> "Resume ${t.episodeCode ?: ""}".trim()
                    else -> "Play ${t.episodeCode ?: ""}".trim()
                }
                add(PillButton(a, palette, label, R.drawable.ic_play, primary = true, reduceMotion = reduceMotion)) { t?.let { a.play(it) } ?: a.toast("Loading episodes…") }
                add(PillButton(a, palette, if (i.watched) "Mark unwatched" else "Mark watched", R.drawable.ic_check, reduceMotion = reduceMotion)) { toggleWatched() }
            }
            MediaKind.COLLECTION, MediaKind.PLAYLIST -> add(
                PillButton(a, palette, "Play", R.drawable.ic_play, primary = true, reduceMotion = reduceMotion),
            ) {
                details?.children?.firstOrNull { it.kind == MediaKind.MOVIE || it.kind == MediaKind.EPISODE }
                    ?.let { a.play(it) }
                    ?: a.toast("Nothing playable in this list yet")
            }
        }
        if (hadFocus) buttons.getChildAt(0)?.requestFocus()
    }

    private fun focusBelow(): Boolean {
        if (seasons.childCount > 0) { seasons.getChildAt(seasonItems.indexOf(currentSeason).coerceAtLeast(0))?.requestFocus(); return true }
        val v = (list.layoutManager as LinearLayoutManager).findViewByPosition(0) as? io.github.design_nexus.nyx.tv.ui.RowView ?: return false
        v.focusRemembered(focus)
        return true
    }

    private fun toggleWatched() {
        val i = item
        a.scope.launch {
            runCatching { provider.setWatched(i, !i.watched) }
                .onSuccess { item = i.copy(watched = !i.watched, viewOffsetMs = 0); render() }
                .onFailure { a.toast("The server couldn't be reached") }
        }
    }

    private fun load() {
        a.scope.launch {
            val d = runCatching { provider.details(item) }.getOrNull() ?: return@launch
            details = d
            item = d.item.copy(backdrop = d.item.backdrop ?: item.backdrop, poster = d.item.poster ?: item.poster)
            a.setBackdrop(item.backdrop)
            when (item.kind) {
                MediaKind.SHOW -> { seasonItems = d.children.filter { it.kind == MediaKind.SEASON }; buildSeasons(); pickSeason() }
                MediaKind.SEASON -> { seasonItems = listOf(item); currentSeason = item; loadEpisodes(item) }
                MediaKind.COLLECTION -> adapter.submit(
                    listOf(RowData("children", "In this collection", CardStyle.POSTER, d.children.map { Cell.Media(it) })),
                )
                MediaKind.PLAYLIST -> adapter.submit(
                    listOf(RowData("children", "In this playlist", CardStyle.POSTER, d.children.map { Cell.Media(it) })),
                )
                else -> if (d.related.isNotEmpty()) {
                    adapter.submit(listOf(RowData("related", "More like this", CardStyle.POSTER, d.related.map { Cell.Media(it) })))
                }
            }
            render()
        }
    }

    private fun buildSeasons() {
        seasons.removeAllViews()
        for (s in seasonItems) {
            val c = Chip(a, palette, s.title, s == currentSeason)
            c.setOnClickListener { currentSeason = s; repaintSeasons(); loadEpisodes(s) }
            c.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN) {
                    buttons.getChildAt(0)?.requestFocus(); true
                } else false
            }
            seasons.addView(c, llp(WRAP, a.dp(28)) { marginStart = a.dp(4); marginEnd = a.dp(4) })
        }
        seasonScroll.visibility = if (seasonItems.size <= 1) View.GONE else View.VISIBLE
    }

    private fun repaintSeasons() {
        for (n in 0 until seasons.childCount) (seasons.getChildAt(n) as Chip).selectedChip = seasonItems.getOrNull(n) == currentSeason
    }

    private fun pickSeason() {
        val s = seasonItems.firstOrNull { (it.unwatchedCount ?: 0) > 0 && it.seasonNumber != 0 } ?: seasonItems.firstOrNull { it.seasonNumber != 0 } ?: seasonItems.firstOrNull() ?: return
        currentSeason = s
        repaintSeasons()
        loadEpisodes(s)
    }

    private fun loadEpisodes(s: MediaItem, preserveFocus: Boolean = false) {
        episodesJob?.cancel()
        episodesJob = a.scope.launch {
            episodes = runCatching { provider.children(s) }.getOrDefault(emptyList())
            val target = episodes.firstOrNull { it.progress > 0 } ?: episodes.firstOrNull { !it.watched } ?: episodes.firstOrNull()
            if (playTarget == null || item.kind == MediaKind.SEASON || s == currentSeason) playTarget = target
            if (!preserveFocus) {
                val start = episodes.indexOf(target).coerceAtLeast(0)
                focus.remember("episodes", start)
            }
            adapter.submit(listOf(RowData("episodes", s.title, CardStyle.LANDSCAPE, episodes.map { Cell.Media(it.copy(seriesTitle = null)) })))
            buildButtons()
        }
    }

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {}

    override fun onCellClicked(row: RowData, cell: Cell) {
        val i = (cell as? Cell.Media)?.item ?: return
        if (row.id == "episodes") a.play(i) else a.openDetails(i)
    }

    override fun onCellMenu(row: RowData, cell: Cell, view: View) {
        val i = (cell as? Cell.Media)?.item ?: return
        a.openDetails(i)
    }
}
