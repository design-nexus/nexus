package io.github.design_nexus.nexus.ui.pages

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nexus.R
import io.github.design_nexus.nexus.core.CardStyle
import io.github.design_nexus.nexus.core.FocusMemory
import io.github.design_nexus.nexus.data.AppRepository
import io.github.design_nexus.nexus.data.MediaAvailabilityResolver
import io.github.design_nexus.nexus.media.ContentActionState
import io.github.design_nexus.nexus.media.MediaDetails
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.MediaProvider
import io.github.design_nexus.nexus.seerr.SeerrDetailBundle
import io.github.design_nexus.nexus.seerr.SeerrMediaStatus
import io.github.design_nexus.nexus.seerr.SeerrRequestStatus
import io.github.design_nexus.nexus.seerr.SeerrSeasonInfo
import io.github.design_nexus.nexus.ui.BOLD
import io.github.design_nexus.nexus.ui.Cell
import io.github.design_nexus.nexus.ui.Chip
import io.github.design_nexus.nexus.ui.HomeActivity
import io.github.design_nexus.nexus.ui.MATCH
import io.github.design_nexus.nexus.ui.MEDIUM
import io.github.design_nexus.nexus.ui.MenuPanel
import io.github.design_nexus.nexus.ui.Page
import io.github.design_nexus.nexus.ui.Palette
import io.github.design_nexus.nexus.ui.PillButton
import io.github.design_nexus.nexus.ui.RowData
import io.github.design_nexus.nexus.ui.RowHost
import io.github.design_nexus.nexus.ui.RowsAdapter
import io.github.design_nexus.nexus.ui.SettingRow
import io.github.design_nexus.nexus.ui.WRAP
import io.github.design_nexus.nexus.ui.alpha
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import io.github.design_nexus.nexus.ui.dp
import io.github.design_nexus.nexus.ui.flp
import io.github.design_nexus.nexus.ui.formatRuntime
import io.github.design_nexus.nexus.ui.hlinear
import io.github.design_nexus.nexus.ui.llp
import io.github.design_nexus.nexus.ui.rounded
import io.github.design_nexus.nexus.ui.text
import io.github.design_nexus.nexus.ui.vlinear
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
    private var plexMatch: MediaItem? = null
    private var seerrBundle: SeerrDetailBundle? = null
    private var actionState: ContentActionState? = null
    private var requesting = false
    private val column = vlinear(a).apply { clipChildren = false; clipToPadding = false }

    override fun providerFor(item: MediaItem): MediaProvider =
        if (item.server.provider == "seerr") a.app.seerr else a.app.plex

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
        val playable = if (i.server.provider == "plex") i else plexMatch
        when (i.kind) {
            MediaKind.MOVIE, MediaKind.EPISODE -> {
                if (playable != null) {
                    add(PillButton(a, palette, if (playable.progress > 0) "Resume" else "Watch Now", R.drawable.ic_play, primary = true, reduceMotion = reduceMotion)) { a.play(playable) }
                    if (playable.progress > 0) add(PillButton(a, palette, "From the beginning", reduceMotion = reduceMotion)) { a.play(playable, fromStart = true) }
                    add(PillButton(a, palette, if (playable.watched) "Mark unwatched" else "Mark watched", R.drawable.ic_check, reduceMotion = reduceMotion)) { toggleWatched() }
                    if (playable.kind == MediaKind.EPISODE && playable.seriesId != null) add(PillButton(a, palette, "Go to show", R.drawable.ic_tv, reduceMotion = reduceMotion)) {
                        a.openDetails(playable.copy(id = playable.seriesId!!, kind = MediaKind.SHOW, title = playable.seriesTitle ?: playable.title, seasonNumber = null, episodeNumber = null, thumb = null, summary = ""))
                    }
                } else {
                    when (val st = actionState) {
                        is ContentActionState.PendingApproval -> add(PillButton(a, palette, "Pending Approval", R.drawable.ic_info, primary = true, reduceMotion = reduceMotion)) { showRequestStatusMenu(st.requestId, "Pending Approval") }
                        is ContentActionState.ApprovedProcessing -> add(PillButton(a, palette, "View Status", R.drawable.ic_info, primary = true, reduceMotion = reduceMotion)) { showRequestStatusMenu(st.requestId, st.statusLabel) }
                        else -> add(PillButton(a, palette, if (requesting) "Requesting…" else "Request", R.drawable.ic_plus, primary = true, reduceMotion = reduceMotion)) { submitMovieRequest() }
                    }
                }
            }
            MediaKind.SHOW, MediaKind.SEASON -> {
                if (playable != null) {
                    val t = playTarget
                    val label = when {
                        t == null -> "Watch Now"
                        t.progress > 0 -> "Resume ${t.episodeCode ?: ""}".trim()
                        else -> "Watch ${t.episodeCode ?: ""}".trim()
                    }
                    add(PillButton(a, palette, label, R.drawable.ic_play, primary = true, reduceMotion = reduceMotion)) { t?.let { a.play(it) } ?: a.toast("Loading episodes…") }
                    add(PillButton(a, palette, if (playable.watched) "Mark unwatched" else "Mark watched", R.drawable.ic_check, reduceMotion = reduceMotion)) { toggleWatched() }
                    if (seerrBundle?.seasons?.any { it.mediaStatus == SeerrMediaStatus.UNKNOWN && it.requestStatus == null } == true) {
                        add(PillButton(a, palette, "Request Seasons", R.drawable.ic_plus, reduceMotion = reduceMotion)) { openSeasonPicker() }
                    }
                } else {
                    when (val st = actionState) {
                        is ContentActionState.PendingApproval -> {
                            add(PillButton(a, palette, "Pending Approval", R.drawable.ic_info, primary = true, reduceMotion = reduceMotion)) { showRequestStatusMenu(st.requestId, "Pending Approval") }
                            if (seerrBundle?.seasons?.isNotEmpty() == true) add(PillButton(a, palette, "Request Seasons", R.drawable.ic_plus, reduceMotion = reduceMotion)) { openSeasonPicker() }
                        }
                        is ContentActionState.ApprovedProcessing -> {
                            add(PillButton(a, palette, "View Status", R.drawable.ic_info, primary = true, reduceMotion = reduceMotion)) { showRequestStatusMenu(st.requestId, st.statusLabel) }
                            if (seerrBundle?.seasons?.isNotEmpty() == true) add(PillButton(a, palette, "Request Seasons", R.drawable.ic_plus, reduceMotion = reduceMotion)) { openSeasonPicker() }
                        }
                        else -> add(PillButton(a, palette, if (requesting) "Requesting…" else "Request Seasons", R.drawable.ic_plus, primary = true, reduceMotion = reduceMotion)) { openSeasonPicker() }
                    }
                }
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
        if (seasonScroll.visibility == View.VISIBLE && seasons.childCount > 0) {
            seasons.getChildAt(seasonItems.indexOf(currentSeason).coerceAtLeast(0))?.requestFocus()
            return true
        }
        val v = (list.layoutManager as LinearLayoutManager).findViewByPosition(0) as? io.github.design_nexus.nexus.ui.RowView ?: return false
        v.focusRemembered(focus)
        return true
    }

    private fun toggleWatched() {
        val i = item
        val nextWatched = !i.watched
        a.scope.launch {
            runCatching { provider.setWatched(i, nextWatched) }
                .onSuccess {
                    item = i.copy(watched = nextWatched, viewOffsetMs = 0)
                    render()
                    if (i.kind == MediaKind.SHOW || i.kind == MediaKind.SEASON) {
                        val s = currentSeason
                        if (s != null) {
                            episodes = episodes.map { it.copy(watched = nextWatched, viewOffsetMs = 0) }
                            adapter.submit(listOf(RowData("episodes", s.title, CardStyle.LANDSCAPE, episodes.map { Cell.Media(it.copy(seriesTitle = null)) })))
                            loadEpisodes(s, preserveFocus = true)
                        }
                    }
                }
                .onFailure { a.toast("The server couldn't be reached") }
        }
    }

    private fun load() {
        a.scope.launch {
            if (item.server.provider == "seerr") {
                loadSeerr()
                return@launch
            }
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
            if (a.app.seerr.isConfigured && item.kind == MediaKind.SHOW && item.tmdbId != null) {
                seerrBundle = runCatching { a.app.seerr.detailsBundle(item) }.getOrNull()
                buildButtons()
            }
        }
    }

    private suspend fun loadSeerr() = coroutineScope {
        val sbJob = async { runCatching { a.app.seerr.detailsBundle(item) }.getOrNull() }
        val plexJob = async { MediaAvailabilityResolver.findInPlex(item, a.app.plex) }
        val sb = sbJob.await()
        val pm = plexJob.await()
        seerrBundle = sb
        plexMatch = pm
        actionState = MediaAvailabilityResolver.resolveActionState(pm, sb?.mediaInfo)

        if (pm != null) {
            val pd = runCatching { a.app.plex.details(pm) }.getOrNull()
            if (pd != null) {
                details = pd
                plexMatch = pd.item
                item = pd.item.copy(
                    backdrop = pd.item.backdrop ?: sb?.details?.item?.backdrop ?: item.backdrop,
                    poster = pd.item.poster ?: sb?.details?.item?.poster ?: item.poster,
                )
                a.setBackdrop(item.backdrop)
                if (item.kind == MediaKind.SHOW) {
                    seasonItems = pd.children.filter { it.kind == MediaKind.SEASON }
                    buildSeasons()
                    pickSeason()
                } else if (pd.related.isNotEmpty()) {
                    adapter.submit(listOf(RowData("related", "More like this", CardStyle.POSTER, pd.related.map { Cell.Media(it) })))
                }
                render()
                return@coroutineScope
            }
        }

        if (sb != null) {
            val d = sb.details
            details = d
            item = d.item.copy(backdrop = d.item.backdrop ?: item.backdrop, poster = d.item.poster ?: item.poster)
            a.setBackdrop(item.backdrop)
            if (item.kind == MediaKind.SHOW && sb.seasons.isNotEmpty()) {
                buildSeerrSeasons(sb.seasons)
            }
            if (d.related.isNotEmpty()) {
                adapter.submit(listOf(RowData("related", "Recommended", CardStyle.POSTER, d.related.map { Cell.Media(it) })))
            }
        }
        render()
    }

    private fun buildSeerrSeasons(seerrSeasons: List<SeerrSeasonInfo>) {
        seasons.removeAllViews()
        for (s in seerrSeasons) {
            val statusSuffix = when {
                s.mediaStatus == SeerrMediaStatus.AVAILABLE -> " · Available"
                s.requestStatus == SeerrRequestStatus.PENDING -> " · Pending"
                s.mediaStatus == SeerrMediaStatus.PROCESSING || s.requestStatus == SeerrRequestStatus.APPROVED -> " · Processing"
                else -> ""
            }
            val c = Chip(a, palette, "${s.name}$statusSuffix", false)
            c.setOnClickListener { openSeasonPicker() }
            c.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN) {
                    buttons.getChildAt(0)?.requestFocus(); true
                } else false
            }
            seasons.addView(c, llp(WRAP, a.dp(28)) { marginStart = a.dp(4); marginEnd = a.dp(4) })
        }
        seasonScroll.visibility = if (seerrSeasons.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun submitMovieRequest() {
        val tmdbId = item.tmdbId ?: item.id.substringAfter(':', item.id).toIntOrNull()
        if (tmdbId == null) {
            a.toast("Missing TMDB ID for this title")
            return
        }
        if (requesting) return
        requesting = true
        buildButtons()
        a.scope.launch {
            try {
                a.app.seerr.requestMovie(tmdbId)
                a.toast("Requested ${item.title}")
                loadSeerr()
            } catch (e: Exception) {
                a.toast(e.message ?: "Request failed")
            } finally {
                requesting = false
                buildButtons()
            }
        }
    }

    private fun showRequestStatusMenu(requestId: Int?, statusLabel: String) {
        val items = ArrayList<MenuPanel.Item>()
        items += MenuPanel.Item("Status: $statusLabel") {}
        if (requestId != null) {
            items += MenuPanel.Item("Cancel Request", danger = true) {
                a.scope.launch {
                    runCatching { a.app.seerr.cancelRequest(requestId) }
                        .onSuccess { a.toast("Request cancelled"); loadSeerr() }
                        .onFailure { a.toast(it.message ?: "Could not cancel request") }
                }
            }
        }
        a.showMenu(item.title, "Seerr Request", items)
    }

    private fun openSeasonPicker() {
        val tmdbId = item.tmdbId ?: item.id.substringAfter(':', item.id).toIntOrNull()
        val seasonsList = seerrBundle?.seasons.orEmpty()
        if (tmdbId == null || seasonsList.isEmpty()) {
            a.toast("No season information available")
            return
        }
        val selected = LinkedHashSet<Int>()
        for (s in seasonsList) {
            if (s.mediaStatus == SeerrMediaStatus.UNKNOWN && s.requestStatus == null) {
                selected += s.seasonNumber
            }
        }
        if (selected.isEmpty()) {
            a.toast("All seasons are already available or requested")
            return
        }

        val panel = vlinear(a).apply {
            background = rounded(palette.mantle, a.dp(16).toFloat(), a.dp(1), alpha(palette.o0, 0.35f))
            setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(18))
            elevation = a.dp(24).toFloat()
        }
        panel.addView(a.text(17f, palette.text, BOLD, s = "Request Seasons — ${item.title}"))
        panel.addView(
            a.text(12f, palette.sub0, s = "Select seasons with OK, then Submit Request"),
            llp(WRAP, WRAP) { topMargin = a.dp(3); bottomMargin = a.dp(12) },
        )
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val rows = vlinear(a)
        var firstFocus: View? = null
        val submitBtn = PillButton(a, palette, "Submit (${selected.size})", R.drawable.ic_plus, primary = true, reduceMotion = reduceMotion)

        fun updateSubmitLabel() {
            submitBtn.setText(if (selected.isEmpty()) "Select a season" else "Submit (${selected.size})")
        }

        for (s in seasonsList) {
            val availableOrReq = s.mediaStatus != SeerrMediaStatus.UNKNOWN || s.requestStatus != null
            val statusText = when {
                s.mediaStatus == SeerrMediaStatus.AVAILABLE -> "Available"
                s.requestStatus == SeerrRequestStatus.PENDING -> "Pending"
                s.mediaStatus == SeerrMediaStatus.PROCESSING || s.requestStatus == SeerrRequestStatus.APPROVED -> "Processing"
                s.mediaStatus == SeerrMediaStatus.PARTIALLY_AVAILABLE -> "Partial"
                else -> if (s.episodeCount > 0) "${s.episodeCount} episodes" else "Not requested"
            }
            val row = SettingRow(a, palette, s.name, statusText)
            if (!availableOrReq) {
                row.on = s.seasonNumber in selected
                row.setOnClickListener {
                    if (s.seasonNumber in selected) selected.remove(s.seasonNumber) else selected.add(s.seasonNumber)
                    row.on = s.seasonNumber in selected
                    updateSubmitLabel()
                }
            } else {
                row.value.text = statusText
            }
            rows.addView(row, llp(MATCH, WRAP))
            if (firstFocus == null) firstFocus = row
        }
        scroll.addView(rows, FrameLayout.LayoutParams(MATCH, WRAP))
        panel.addView(scroll, llp(MATCH, a.dp(260)))

        val footer = hlinear(a).apply { gravity = Gravity.END }
        val cancelBtn = PillButton(a, palette, "Cancel", reduceMotion = reduceMotion)
        cancelBtn.setOnClickListener { a.closeOverlay() }
        submitBtn.setOnClickListener {
            if (selected.isEmpty() || requesting) return@setOnClickListener
            requesting = true
            a.closeOverlay()
            a.scope.launch {
                try {
                    a.app.seerr.requestTv(tmdbId, selected.sorted(), item.tvdbId)
                    a.toast("Requested ${selected.size} season(s) of ${item.title}")
                    loadSeerr()
                } catch (e: Exception) {
                    a.toast(e.message ?: "TV request failed")
                } finally {
                    requesting = false
                    buildButtons()
                }
            }
        }
        footer.addView(cancelBtn, llp(WRAP, a.dp(38)) { marginEnd = a.dp(10) })
        footer.addView(submitBtn, llp(WRAP, a.dp(38)))
        panel.addView(footer, llp(MATCH, WRAP) { topMargin = a.dp(14) })

        val wrap = object : FrameLayout(a) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    a.closeOverlay(); return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(alpha(palette.base, 0.68f))
            isClickable = true
        }
        wrap.addView(panel, flp(a.dp(480), WRAP, Gravity.CENTER))
        a.openOverlay(wrap)
        panel.post { (firstFocus ?: submitBtn).requestFocus() }
    }

    private fun buildSeasons() {
        seasons.removeAllViews()
        if (seasonItems.size <= 1) {
            seasonScroll.visibility = View.GONE
            return
        }
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
        seasonScroll.visibility = View.VISIBLE
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
