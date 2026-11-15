package io.github.design_nexus.nyx.tv.ui.pages

import android.content.Intent
import android.content.res.ColorStateList
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import io.github.design_nexus.nyx.tv.R
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.core.FocusMemory
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.SearchResults
import io.github.design_nexus.nyx.tv.ui.Cell
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MEDIUM
import io.github.design_nexus.nyx.tv.ui.Page
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.RowData
import io.github.design_nexus.nyx.tv.ui.RowHost
import io.github.design_nexus.nyx.tv.ui.RowsAdapter
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.alpha
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.hlinear
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.rounded
import io.github.design_nexus.nyx.tv.ui.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One search across Plex and the installed apps, typed with the TV keyboard or spoken. */
class SearchPage(a: HomeActivity) : Page(a), RowHost {
    override val tab = "search"
    override val palette: Palette get() = a.palette
    override val provider: MediaProvider get() = a.app.plex
    override val images: ImageLoader get() = a.app.images
    override val appRepo: AppRepository get() = a.app.apps
    override val reduceMotion get() = a.config.reduceMotion
    override val focus = FocusMemory()
    override val scope: CoroutineScope get() = a.scope

    private val root = FrameLayout(a)
    override val view: View get() = root
    private val field = EditText(a)
    private val mic = ImageView(a)
    private val hint = a.text(13f, palette.sub0)
    private val list = RecyclerView(a)
    private val adapter = RowsAdapter(this)
    private var job: Job? = null

    init {
        val bar = hlinear(a)
        field.apply {
            textSize = 17f
            setTextColor(palette.text)
            setHintTextColor(palette.sub0)
            hint = "Movies, shows, episodes, apps"
            typeface = MEDIUM
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(alpha(palette.o0, 0.2f), dp(14).toFloat())
            setPadding(dp(18), 0, dp(18), 0)
            setOnFocusChangeListener { _, f -> background = rounded(alpha(palette.o0, 0.2f), dp(14).toFloat(), if (f) dp(2) else 0, palette.accent) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, _, _ -> schedule(text.toString(), now = true); list.requestFocus(); true }
        }
        bar.addView(field, llp(a.dp(520), a.dp(46)))
        mic.apply {
            setImageResource(R.drawable.ic_mic)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            imageTintList = ColorStateList.valueOf(palette.sub1)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, f ->
                background = if (f) rounded(palette.accent, dp(23).toFloat()) else rounded(alpha(palette.o0, 0.2f), dp(23).toFloat())
                imageTintList = ColorStateList.valueOf(if (f) palette.onAccent else palette.sub1)
            }
            background = rounded(alpha(palette.o0, 0.2f), dp(23).toFloat())
            setOnClickListener { voice() }
        }
        bar.addView(mic, llp(a.dp(46), a.dp(46)) { marginStart = a.dp(10) })
        root.addView(bar, flp(WRAP, WRAP).apply { setMargins(a.dp(48), a.dp(80), 0, 0) })
        hint.text = "Search your Plex library and installed apps. Press the remote mic on this screen, or choose the mic here."
        root.addView(hint, flp(a.dp(560), WRAP).apply { setMargins(a.dp(48), a.dp(140), 0, 0) })
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = adapter
        list.itemAnimator = null
        list.clipChildren = false
        list.clipToPadding = false
        list.setPadding(0, 0, 0, a.dp(120))
        root.addView(list, flp(MATCH, MATCH).apply { topMargin = a.dp(144) })
        field.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN && adapter.itemCount > 0) { list.requestFocus(); true } else false }
    }

    override fun focusDefault() { field.requestFocus() }

    override fun onShow() {
        a.paintScrim(full = true)
    }

    fun startVoiceSearch() = voice()

    private fun voice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Nyx TV")
        if (!a.startVoice(intent) { text -> field.setText(text); field.setSelection(text.length); schedule(text, now = true) }) {
            a.toast("Voice search isn't available on this TV")
        }
    }

    private fun schedule(q: String, now: Boolean = false) {
        job?.cancel()
        job = a.scope.launch {
            if (!now) delay(350)
            run(q.trim())
        }
    }

    private suspend fun run(q: String) {
        if (q.isEmpty()) { adapter.submit(emptyList()); hint.visibility = View.VISIBLE; return }
        hint.visibility = View.GONE
        val apps = a.apps.filter { it.label.contains(q, ignoreCase = true) && it.pkg !in a.config.hiddenApps }
        val rows = ArrayList<RowData>()
        if (apps.isNotEmpty()) rows += RowData("apps", "Apps", CardStyle.APP_TILE, apps.take(12).map { Cell.App(it) })
        adapter.submit(rows.toList())
        val r = runCatching { provider.search(q, 20) }.getOrElse { SearchResults() }
        val media = ArrayList<RowData>()
        if (r.movies.isNotEmpty()) media += RowData("movies", "Movies", CardStyle.POSTER, r.movies.map { Cell.Media(it) })
        if (r.shows.isNotEmpty()) media += RowData("shows", "TV Shows", CardStyle.POSTER, r.shows.map { Cell.Media(it) })
        if (r.episodes.isNotEmpty()) media += RowData("episodes", "Episodes", CardStyle.LANDSCAPE, r.episodes.map { Cell.Media(it) })
        if (r.collections.isNotEmpty()) media += RowData("collections", "Collections", CardStyle.POSTER, r.collections.map { Cell.Media(it) })
        adapter.submit(media + rows)
        if (media.isEmpty() && rows.isEmpty()) { hint.text = "Nothing found for “$q”"; hint.visibility = View.VISIBLE }
    }

    override fun onCellFocused(row: RowData, index: Int, cell: Cell) {}

    override fun onCellClicked(row: RowData, cell: Cell) {
        when (cell) {
            is Cell.App -> a.launchApp(cell.entry)
            is Cell.Media -> if (row.id == "episodes") a.play(cell.item) else a.openDetails(cell.item)
        }
    }

    override fun onCellMenu(row: RowData, cell: Cell, view: View) {
        when (cell) {
            is Cell.App -> appMenu(a, cell.entry)
            is Cell.Media -> a.openDetails(cell.item)
        }
    }
}
