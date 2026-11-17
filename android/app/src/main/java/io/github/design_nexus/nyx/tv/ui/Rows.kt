package io.github.design_nexus.nyx.tv.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.data.AppEntry
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Cell {
    val key: String

    data class Media(val item: MediaItem) : Cell { override val key get() = item.key }
    data class App(val entry: AppEntry) : Cell { override val key get() = "app:${entry.pkg}" }
}

data class RowData(val id: String, val title: String, val style: CardStyle, val cells: List<Cell>)

/** What a page hosting rows provides. */
interface RowHost {
    val palette: Palette
    val provider: MediaProvider
    val images: ImageLoader
    val appRepo: AppRepository
    val reduceMotion: Boolean
    val focus: FocusMemory
    val scope: CoroutineScope
    fun providerFor(item: MediaItem): MediaProvider = provider
    fun badgeFor(item: MediaItem): Pair<String, Int>? = null
    fun onCellFocused(row: RowData, index: Int, cell: Cell)
    fun onCellClicked(row: RowData, cell: Cell)
    fun onCellMenu(row: RowData, cell: Cell, view: View)
}

/** Binds artwork for an app tile off the main thread. */
fun bindApp(host: RowHost, tile: AppTile, entry: AppEntry, caption: Boolean) {
    tile.pkg = entry.pkg
    tile.setPlaceholder(entry.label)
    host.scope.launch {
        val (art, banner) = withContext(Dispatchers.IO) { host.appRepo.artwork(entry) to host.appRepo.hasBanner(entry) }
        if (tile.pkg == entry.pkg) tile.bind(entry.label, art, banner, caption)
    }
}

class CellAdapter(private val host: RowHost, private val style: CardStyle, private val captions: Boolean) : RecyclerView.Adapter<CellAdapter.VH>() {
    var row: RowData = RowData("", "", style, emptyList())
        private set
    /** Set by [RowView] so cells can move to the adjacent row. */
    var rowView: RowView? = null

    class VH(val view: View) : RecyclerView.ViewHolder(view)

    fun submit(r: RowData) {
        val old = row.cells
        row = r
        val oldKeys = old.map { it.key }
        val newKeys = r.cells.map { it.key }
        when {
            oldKeys == newKeys -> notifyItemRangeChanged(0, r.cells.size)
            old.isNotEmpty() && r.cells.size > old.size && newKeys.subList(0, old.size) == oldKeys -> {
                notifyItemRangeInserted(old.size, r.cells.size - old.size)
            }
            else -> notifyDataSetChanged()
        }
    }

    override fun getItemCount() = row.cells.size

    /** Style-aware types so landscape/poster/app holders never share a pool slot. */
    override fun getItemViewType(position: Int): Int {
        val app = if (row.cells[position] is Cell.App) 1 else 0
        return style.ordinal * 2 + app
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val isApp = viewType % 2 == 1
        val v: View = if (isApp) AppTile(parent.context, host.palette, host.reduceMotion)
        else MediaCard(parent.context, host.palette, style, host.reduceMotion)
        v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = v.dp(14) }
        val vh = VH(v)
        v.setOnFocusChangeListener { _, f ->
            if (!f) return@setOnFocusChangeListener
            val i = vh.bindingAdapterPosition
            if (i >= 0 && i < row.cells.size) {
                host.focus.remember(row.id, i)
                host.onCellFocused(row, i, row.cells[i])
            }
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val cell = row.cells[position]
        when (cell) {
            is Cell.Media -> (h.view as MediaCard).bind(cell.item, host.providerFor(cell.item), host.images, captions, host.badgeFor(cell.item))
            is Cell.App -> bindApp(host, h.view as AppTile, cell.entry, captions)
        }
        h.view.setOnClickListener { host.onCellClicked(row, cell) }
        h.view.setOnLongClickListener { host.onCellMenu(row, cell, it); true }
        h.view.setOnKeyListener { v, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP &&
                    run { host.onCellMenu(row, cell, v); true }
            }
            val pos = h.bindingAdapterPosition
            if (pos < 0) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_MENU -> true // handled on UP
                KeyEvent.KEYCODE_DPAD_LEFT -> pos == 0 // stop at start — no jump to another row
                KeyEvent.KEYCODE_DPAD_RIGHT -> pos >= row.cells.size - 1 // stop at end
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val rv = rowView ?: return@setOnKeyListener false
                    val rowsRv = rv.parent as? RecyclerView ?: return@setOnKeyListener false
                    val rowPos = rowsRv.getChildAdapterPosition(rv)
                    if (rowPos < 0) return@setOnKeyListener false
                    val targetPos = if (code == KeyEvent.KEYCODE_DPAD_UP) rowPos - 1 else rowPos + 1
                    if (targetPos !in 0 until (rowsRv.adapter?.itemCount ?: 0)) {
                        // First row ↑ may leave to the hero; last row ↓ stops here.
                        return@setOnKeyListener code == KeyEvent.KEYCODE_DPAD_DOWN
                    }
                    rowsRv.scrollToPosition(targetPos)
                    fun focusTarget(): Boolean {
                        val target = rowsRv.findViewHolderForAdapterPosition(targetPos)?.itemView as? RowView
                            ?: return false
                        // Always land on the first title when switching rows.
                        target.focusFirst()
                        return true
                    }
                    rowsRv.post {
                        if (!focusTarget()) rowsRv.post { focusTarget() }
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onViewRecycled(h: VH) {
        (h.view as? MediaCard)?.recycle()
        (h.view as? AppTile)?.clearFocusChrome()
    }
}

/** A titled horizontal row. */
class RowView(ctx: Context, host: RowHost, style: CardStyle, pool: RecyclerView.RecycledViewPool) : LinearLayout(ctx) {
    val title: TextView = ctx.text(15.5f, host.palette.text, MEDIUM)
    val adapter = CellAdapter(host, style, captions = style == CardStyle.POSTER || style == CardStyle.SQUARE)
    val list: RecyclerView = object : RecyclerView(ctx) {
        override fun focusSearch(focused: View, direction: Int): View? {
            val pos = getChildAdapterPosition(focused)
            val count = this@RowView.adapter.itemCount
            if (pos >= 0 && count > 0) {
                if (direction == View.FOCUS_LEFT && pos == 0) return focused
                if (direction == View.FOCUS_RIGHT && pos >= count - 1) return focused
            }
            return super.focusSearch(focused, direction)
        }
    }

    init {
        adapter.rowView = this
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(title, llp(WRAP, WRAP) { marginStart = dp(48); bottomMargin = dp(4) })
        list.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false).apply { initialPrefetchItemCount = 6 }
        list.setRecycledViewPool(pool)
        list.adapter = adapter
        list.clipToPadding = false
        list.clipChildren = false
        list.setPadding(dp(48), dp(10), dp(48), dp(14))
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.overScrollMode = OVER_SCROLL_NEVER
        list.isFocusable = false
        list.descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(list, llp(MATCH, WRAP))
    }

    fun focusFirst() {
        if (adapter.itemCount <= 0) return
        val lm = list.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(0, 0)
        list.post { lm.findViewByPosition(0)?.requestFocus() }
    }

    fun focusRemembered(memory: FocusMemory) {
        val i = memory.positionIn(adapter.row.id, adapter.itemCount)
        val lm = list.layoutManager as LinearLayoutManager
        val v = lm.findViewByPosition(i)
        if (v != null) v.requestFocus() else {
            lm.scrollToPositionWithOffset(i, dp(48))
            list.post { lm.findViewByPosition(i)?.requestFocus() }
        }
    }
}

/** The vertical list of rows. */
class RowsAdapter(private val host: RowHost) : RecyclerView.Adapter<RowsAdapter.VH>() {
    private val pool = RecyclerView.RecycledViewPool().apply {
        for (style in CardStyle.entries) {
            setMaxRecycledViews(style.ordinal * 2, 16)
            setMaxRecycledViews(style.ordinal * 2 + 1, 12)
        }
    }
    var rows: List<RowData> = emptyList()
        private set

    class VH(val row: RowView) : RecyclerView.ViewHolder(row)

    init { setHasStableIds(true) }

    fun submit(list: List<RowData>) {
        val oldIds = rows.map { it.id to it.style }
        rows = list
        if (oldIds == list.map { it.id to it.style }) notifyItemRangeChanged(0, list.size) else notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = rows[position].id.hashCode().toLong()
    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = rows[position].style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val r = RowView(parent.context, host, CardStyle.entries[viewType], pool)
        r.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return VH(r)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.row.title.text = r.title
        h.row.adapter.submit(r)
    }
}
