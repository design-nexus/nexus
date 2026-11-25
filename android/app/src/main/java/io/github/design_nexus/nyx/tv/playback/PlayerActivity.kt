package io.github.design_nexus.nyx.tv.playback

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Job
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.design_nexus.nyx.tv.NyxTvApp
import io.github.design_nexus.nyx.tv.R
import io.github.design_nexus.nyx.tv.media.MarkerKind
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.PlayInfo
import io.github.design_nexus.nyx.tv.media.StreamMode
import io.github.design_nexus.nyx.tv.media.StreamSource
import io.github.design_nexus.nyx.tv.media.TimelineState
import io.github.design_nexus.nyx.tv.media.TimelineUpdate
import io.github.design_nexus.nyx.tv.device.TvSystem
import io.github.design_nexus.nyx.tv.ui.BOLD
import io.github.design_nexus.nyx.tv.ui.Images
import io.github.design_nexus.nyx.tv.ui.MATCH
import io.github.design_nexus.nyx.tv.ui.MEDIUM
import io.github.design_nexus.nyx.tv.ui.MenuPanel
import io.github.design_nexus.nyx.tv.ui.Palette
import io.github.design_nexus.nyx.tv.ui.QuickSettings
import io.github.design_nexus.nyx.tv.ui.QuickSettingsHost
import io.github.design_nexus.nyx.tv.ui.WRAP
import io.github.design_nexus.nyx.tv.ui.alpha
import io.github.design_nexus.nyx.tv.ui.dp
import io.github.design_nexus.nyx.tv.ui.flp
import io.github.design_nexus.nyx.tv.ui.llp
import io.github.design_nexus.nyx.tv.ui.rounded
import io.github.design_nexus.nyx.tv.ui.text
import io.github.design_nexus.nyx.tv.ui.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Plex-copy player chrome (Bedroom TV contract): top title + Ends at, thin scrubber,
 * Subtitles + Settings. No −10 / Play disc / +10 row. No chapters.
 * Settings is a right panel; Audio sync is Nyx-only.
 */
@UnstableApi
class PlayerActivity : Activity(), QuickSettingsHost {
    private val app get() = application as NyxTvApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: PlaybackPrefs
    override lateinit var palette: Palette
        private set
    override val clock24h: Boolean get() = app.config.config.value.clock24h
    private lateinit var delayProcessor: AudioDelayProcessor
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var chrome: FrameLayout
    private lateinit var topTitle: TextView
    private lateinit var topMeta: TextView
    private lateinit var clockView: TextView
    private lateinit var endsView: TextView
    private lateinit var skipBand: LinearLayout
    private lateinit var skipBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var timeNow: TextView
    private lateinit var timeLeft: TextView
    private lateinit var playIcon: ImageView
    private lateinit var scrub: ProgressBar
    private lateinit var scrubShell: FrameLayout
    private lateinit var scrubKnob: View
    private lateinit var introBand: View
    private lateinit var creditsBand: View
    private lateinit var prevEpBtn: ImageView
    private lateinit var nextEpBtn: ImageView
    private lateinit var subsBtn: ImageView
    private lateinit var settingsBtn: ImageView
    private lateinit var bottomChrome: LinearLayout
    private lateinit var railHost: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private lateinit var spinner: ProgressBar
    private var overlay: View? = null
    private var overlayReturn: View? = null
    private var upNextHost: LinearLayout? = null
    private var upNextLabel: TextView? = null
    private var upNextPlayBtn: TextView? = null
    private var upNextDontBtn: TextView? = null
    private var upNextThumbDisposable: coil3.request.Disposable? = null
    private var activeSkipKey: String? = null
    private var dismissedSkipKey: String? = null
    private var dismissedUpNext = false
    private var cancelledSkipKey: String? = null
    private var autoSkipKey: String? = null
    private var autoSkipLabelBase = "Skip Intro"
    private var autoSkipSeconds = -1
    private val autoSkipTick = object : Runnable {
        override fun run() {
            if (autoSkipSeconds < 0) return
            if (player?.isPlaying != true) {
                main.postDelayed(this, 500)
                return
            }
            autoSkipSeconds--
            if (autoSkipSeconds <= 0) {
                val key = autoSkipKey
                autoSkipSeconds = -1
                autoSkipKey = null
                cancelledSkipKey = key
                skipBtn.text = autoSkipLabelBase
                skipActiveMarker()
            } else {
                skipBtn.text = "$autoSkipLabelBase ($autoSkipSeconds)"
                main.postDelayed(this, 1000)
            }
        }
    }
    private var player: ExoPlayer? = null
    private var source: StreamSource? = null
    private var info: PlayInfo? = null
    private var itemId: String = ""
    private var resumeMs: Long = 0
    private var durationHint: Long = 0
    private var overlayUntil = 0L
    private var chromeVisible = true
    private var triedTranscode = false
    private var ending = false
    private var needsResumeOnStart = false
    private var startJob: Job? = null
    private var loadJob: Job? = null
    private var lastTimelineAtMs = 0L
    private var audioId: Int? = null
    private var subtitleId: Int? = null
    private var speed = 1f
    private var forceTranscode = false
    private var menuOpen = false
    private var menuKind = Menu.NONE
    /** Submenu was opened from Settings hub — Back/Left returns to Settings instead of closing. */
    private var menuFromSettings = false
    private var settingsFocusIndex = 4
    private var backLongPressHandled = false
    private var offsetValueView: TextView? = null
    private var pendingTranscodeMs = -1L
    private val transcodeSeekRunnable = Runnable {
        if (pendingTranscodeMs >= 0) {
            resumeMs = pendingTranscodeMs
            pendingTranscodeMs = -1L
            startStream(StreamMode.TRANSCODE)
        }
    }
    private var upNextSeconds = -1
    private val upNextTick = object : Runnable {
        override fun run() {
            if (upNextSeconds < 0) return
            if (player?.isPlaying != true) {
                main.postDelayed(this, 500)
                return
            }
            upNextSeconds--
            if (upNextSeconds <= 0) {
                cancelUpNext(startNext = true)
            } else {
                upNextLabel?.text = "UP NEXT · plays in $upNextSeconds s"
                main.postDelayed(this, 1000)
            }
        }
    }

    private enum class Menu { NONE, SETTINGS, SUBS, AUDIO, QUALITY, OPTIONS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = app.playbackPrefs
        val cfg = app.config.config.value
        palette = Palette.of(cfg.theme, cfg.accent)
        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        if (itemId.isEmpty() || !itemId.all(Char::isDigit)) {
            toast("Nothing to play"); finish(); return
        }
        delayProcessor = AudioDelayProcessor().apply { delayMs = prefs.audioOffsetMs }

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        playerView = PlayerView(this).apply {
            useController = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        chrome = FrameLayout(this)
        buildChrome()
        root.addView(chrome, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        skipBand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
            setPadding(dp(36), 0, dp(36), 0)
        }
        skipBtn = pill("Skip Intro", primary = true).also { b ->
            b.id = View.generateViewId()
            b.setOnClickListener { skipActiveMarker() }
            skipBand.addView(b, llp(WRAP, WRAP) { marginEnd = dp(8) })
        }
        nextBtn = pill("Next episode", primary = false).also { b ->
            b.id = View.generateViewId()
            b.visibility = View.GONE
            b.setOnClickListener { playNext() }
            skipBand.addView(b)
        }
        skipBtn.nextFocusRightId = nextBtn.id
        nextBtn.nextFocusLeftId = skipBtn.id
        root.addView(skipBand, flp(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(168) })
        railHost = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        root.addView(railHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlayHost = FrameLayout(this)
        root.addView(overlayHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::playerView.isInitialized) return
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent) {
        val nextId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        if (nextId.isEmpty() || !nextId.all(Char::isDigit)) {
            toast("Nothing to play"); finish(); return
        }
        loadJob?.cancel()
        startJob?.cancel()
        main.removeCallbacks(bufferingWatchdog)
        main.removeCallbacks(transcodeSeekRunnable)
        main.removeCallbacks(upNextTick)
        source?.takeIf { it.mode == StreamMode.TRANSCODE }?.let { s ->
            app.scope.launch { runCatching { app.plex.stopPlaybackSession(s.sessionId) } }
        }
        releasePlayer(report = source != null)
        itemId = nextId
        resumeMs = intent.getLongExtra(EXTRA_RESUME_MS, 0L).coerceAtLeast(0)
        durationHint = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        source = null
        info = null
        triedTranscode = false
        forceTranscode = false
        ending = false
        needsResumeOnStart = false
        audioId = null
        subtitleId = null
        pendingTranscodeMs = -1L
        activeSkipKey = null
        dismissedSkipKey = null
        dismissedUpNext = false
        cancelledSkipKey = null
        cancelAutoSkip()
        cancelUpNext(startNext = false)
        skipBand.visibility = View.GONE
        prevEpBtn.visibility = View.GONE
        nextEpBtn.visibility = View.GONE
        topTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        topMeta.text = ""
        showChrome(focusScrubber = true)
        loadAndPlay()
    }

    private fun buildChrome() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(36), dp(24), dp(36), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB8000000.toInt(), Color.TRANSPARENT),
            )
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topTitle = text(20f, Color.WHITE, BOLD)
        topMeta = text(12f, alpha(Color.WHITE, 0.88f))
        titleCol.addView(topTitle)
        titleCol.addView(topMeta, llp(WRAP, WRAP) { topMargin = dp(4) })
        top.addView(titleCol, llp(0, WRAP, 1f))
        val clockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        clockView = text(15f, Color.WHITE, MEDIUM, s = "").apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        endsView = text(11.5f, alpha(Color.WHITE, 0.78f), s = "")
        clockCol.addView(clockView)
        clockCol.addView(endsView, llp(WRAP, WRAP) { topMargin = dp(2) })
        top.addView(clockCol, llp(WRAP, WRAP) { marginStart = dp(24) })
        chrome.addView(top, flp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        bottomChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(20), dp(36), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), Color.TRANSPARENT),
            )
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        prevEpBtn = squareIcon(R.drawable.ic_prev_ep, "Previous episode") { playPrevious() }.apply {
            visibility = View.GONE
        }
        nextEpBtn = squareIcon(R.drawable.ic_next_ep, "Next episode") { playNext() }.apply {
            visibility = View.GONE
        }
        subsBtn = squareIcon(R.drawable.ic_cc, "Subtitles") { menuFromSettings = false; openMenu(Menu.SUBS) }
        settingsBtn = squareIcon(R.drawable.ic_gear, "Settings") {
            menuFromSettings = false
            settingsFocusIndex = 4
            openMenu(Menu.SETTINGS)
        }
        actions.addView(prevEpBtn, llp(dp(40), dp(40)) { marginEnd = dp(10) })
        actions.addView(nextEpBtn, llp(dp(40), dp(40)) { marginEnd = dp(10) })
        actions.addView(subsBtn, llp(dp(40), dp(40)) { marginEnd = dp(10) })
        actions.addView(settingsBtn, llp(dp(40), dp(40)))
        bottomChrome.addView(actions, llp(MATCH_PARENT, WRAP) { bottomMargin = dp(12) })

        val seek = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        timeNow = text(12f, alpha(Color.WHITE, 0.95f), MEDIUM, s = "0:00").apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        timeLeft = text(12f, alpha(Color.WHITE, 0.95f), MEDIUM, s = "0:00").apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            gravity = Gravity.END
        }
        scrub = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(alpha(Color.WHITE, 0.28f))
        }
        scrubShell = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = "Seek bar"
            setPadding(0, dp(12), 0, dp(12))
            clipChildren = false
            clipToPadding = false
            setOnFocusChangeListener { _, f -> paintScrub(f) }
            setOnClickListener { togglePlay() }
        }
        val teal = Palette.ACCENTS["teal"]?.let { if (palette.light) it.second else it.first } ?: palette.green
        introBand = View(this).apply {
            background = rounded(alpha(palette.yellow, 0.65f), dp(2).toFloat())
            visibility = View.GONE
        }
        creditsBand = View(this).apply {
            background = rounded(alpha(teal, 0.65f), dp(2).toFloat())
            visibility = View.GONE
        }
        // Bands under the thin track; knob on top.
        scrubShell.addView(introBand, FrameLayout.LayoutParams(0, dp(6), Gravity.CENTER_VERTICAL))
        scrubShell.addView(creditsBand, FrameLayout.LayoutParams(0, dp(6), Gravity.CENTER_VERTICAL))
        scrubShell.addView(scrub, FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.CENTER_VERTICAL))
        scrubKnob = View(this).apply {
            background = circle(Color.WHITE)
            visibility = View.GONE
            elevation = dp(3).toFloat()
        }
        scrubShell.addView(scrubKnob, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.START or Gravity.CENTER_VERTICAL))
        seek.addView(playIcon, llp(dp(28), dp(28)) { marginEnd = dp(12) })
        seek.addView(timeNow, llp(dp(56), WRAP))
        seek.addView(scrubShell, llp(0, WRAP, 1f) { marginStart = dp(10); marginEnd = dp(10) })
        seek.addView(timeLeft, llp(dp(72), WRAP))
        bottomChrome.addView(seek, llp(MATCH_PARENT, WRAP))
        chrome.addView(bottomChrome, flp(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun paintScrub(focused: Boolean) {
        scrub.progressTintList = ColorStateList.valueOf(Color.WHITE)
        scrub.progressBackgroundTintList = ColorStateList.valueOf(alpha(Color.WHITE, if (focused) 0.45f else 0.28f))
        scrub.scaleY = if (focused) 2.6f else 1f
        scrubShell.foreground = null
        scrubKnob.visibility = if (focused) View.VISIBLE else View.GONE
        if (focused) layoutScrubKnob()
    }

    private fun layoutScrubKnob() {
        if (scrubKnob.visibility != View.VISIBLE) return
        val w = scrubShell.width
        if (w <= 0) {
            scrubShell.post { layoutScrubKnob() }
            return
        }
        val lp = scrubKnob.layoutParams as FrameLayout.LayoutParams
        val kw = lp.width
        val frac = scrub.progress / 1000f
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        lp.leftMargin = ((w - kw) * frac).toInt().coerceIn(0, (w - kw).coerceAtLeast(0))
        scrubKnob.layoutParams = lp
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun squareIcon(res: Int, label: String, action: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = rounded(alpha(Color.WHITE, 0.14f), dp(12).toFloat())
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isFocusable = true
        isClickable = true
        contentDescription = label
        setOnFocusChangeListener { _, f ->
            background = rounded(if (f) Color.WHITE else alpha(Color.WHITE, 0.14f), dp(12).toFloat())
            imageTintList = ColorStateList.valueOf(if (f) Color.BLACK else Color.WHITE)
        }
        setOnClickListener { action() }
    }

    private fun pill(label: String, primary: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = BOLD
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(12), dp(22), dp(12))
        isFocusable = true
        isClickable = true
        fun paint(f: Boolean) {
            background = rounded(
                if (f || primary) Color.WHITE else alpha(Color.BLACK, 0.55f),
                dp(10).toFloat(),
                if (!primary && !f) dp(1) else 0,
                alpha(Color.WHITE, 0.3f),
            )
            setTextColor(if (f || primary) Color.BLACK else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
    }

    private val bufferingWatchdog = Runnable {
        val exo = player ?: return@Runnable
        val st = source ?: return@Runnable
        if (exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady) {
            android.util.Log.w("NyxPlayer", "buffering timeout mode=${st.mode}")
            if (st.mode == StreamMode.DIRECT && !triedTranscode) {
                triedTranscode = true
                resumeMs = absolutePosition()
                startStream(StreamMode.TRANSCODE)
            } else {
                toast("Stream stalled — retrying")
                resumeMs = absolutePosition()
                startStream(st.mode)
            }
        }
    }

    private fun loadAndPlay() {
        loadJob?.cancel()
        spinner.visibility = View.VISIBLE
        loadJob = scope.launch {
            try {
                info = withContext(Dispatchers.IO) { app.plex.playInfo(itemId) }
                val i = info!!
                topTitle.text = when {
                    i.item.kind == MediaKind.EPISODE && i.item.seriesTitle != null -> i.item.seriesTitle!!
                    else -> i.item.title
                }
                topMeta.text = buildMeta(i)
                if (durationHint <= 0) durationHint = i.item.durationMs
                audioId = i.audioTracks.firstOrNull { it.selected }?.id ?: i.audioTracks.firstOrNull()?.id
                subtitleId = i.subtitleTracks.firstOrNull { it.selected }?.id
                prevEpBtn.visibility = if (i.previous != null) View.VISIBLE else View.GONE
                nextEpBtn.visibility = if (i.next != null) View.VISIBLE else View.GONE
                scrubShell.post { layoutMarkerBands() }
                startStream(if (forceTranscode || !prefs.preferDirectPlay) StreamMode.TRANSCODE else StreamMode.DIRECT)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't load this title")
                finish()
            }
        }
    }

    private fun buildMeta(i: PlayInfo): String {
        val parts = mutableListOf<String>()
        i.item.year?.let { parts += it.toString() }
        val mins = (i.item.durationMs / 60_000L).toInt().coerceAtLeast(1)
        parts += if (mins >= 60) {
            val h = mins / 60; val m = mins % 60
            if (m == 0) "$h hour${if (h == 1) "" else "s"}" else "$h hour${if (h == 1) "" else "s"} $m minutes"
        } else "$mins minutes"
        i.item.episodeCode?.let { parts += it }
        if (i.item.kind == MediaKind.EPISODE) parts += i.item.title
        return parts.joinToString(" • ")
    }

    private fun startStream(mode: StreamMode) {
        startJob?.cancel()
        spinner.visibility = View.VISIBLE
        startJob = scope.launch {
            val item = info?.item ?: MediaItem(
                server = io.github.design_nexus.nyx.tv.media.ServerRef("plex", ""),
                id = itemId, kind = MediaKind.MOVIE, title = topTitle.text.toString(),
                durationMs = durationHint, viewOffsetMs = resumeMs,
            )
            val stream = try {
                withContext(Dispatchers.IO) {
                    app.plex.resolvePlayback(item, resumeMs, mode, audioId, subtitleId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mode == StreamMode.DIRECT && !triedTranscode) {
                    triedTranscode = true
                    startStream(StreamMode.TRANSCODE)
                    return@launch
                }
                toast(e.message ?: "Playback failed")
                finish(); return@launch
            }
            source?.takeIf { it.mode == StreamMode.TRANSCODE && it.sessionId != stream.sessionId }?.let { prev ->
                app.scope.launch { runCatching { app.plex.stopPlaybackSession(prev.sessionId) } }
            }
            source = stream
            attachPlayer(stream)
        }
    }

    private fun attachPlayer(stream: StreamSource) {
        releasePlayer(report = false)
        val http = app.http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val data = OkHttpDataSource.Factory(http).setDefaultRequestProperties(stream.headers)
        val mediaFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(data)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(delayProcessor))
                .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val exo = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaFactory).build()
        applySubtitleSelection(exo)
        player = exo
        playerView.player = exo
        exo.playbackParameters = PlaybackParameters(speed)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.d("NyxPlayer", "state=$state mode=${stream.mode} playing=${exo.isPlaying}")
                updateKeepScreenOn()
                if (state == Player.STATE_READY) {
                    spinner.visibility = View.GONE
                    main.removeCallbacks(bufferingWatchdog)
                } else if (state == Player.STATE_BUFFERING && exo.playWhenReady) {
                    main.removeCallbacks(bufferingWatchdog)
                    main.postDelayed(bufferingWatchdog, BUFFERING_TIMEOUT_MS)
                }
                if (state == Player.STATE_ENDED) {
                    main.removeCallbacks(bufferingWatchdog)
                    onEnded()
                }
                report(if (exo.isPlaying) TimelineState.PLAYING else TimelineState.PAUSED)
            }
            override fun onRenderedFirstFrame() {
                android.util.Log.d("NyxPlayer", "firstFrame mode=${stream.mode}")
                main.removeCallbacks(bufferingWatchdog)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn()
                report(if (isPlaying) TimelineState.PLAYING else TimelineState.PAUSED)
                updatePlayIcon()
                // Seeking/buffering flickers isPlaying — never pop chrome if the user hid it.
                if (!isPlaying) {
                    if (chromeVisible) bumpChromeTimer() // keep visible chrome up while paused
                } else {
                    bumpChromeTimer()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("NyxPlayer", "error mode=${stream.mode}: ${error.errorCodeName}", error)
                if (stream.mode == StreamMode.DIRECT && !triedTranscode) {
                    triedTranscode = true
                    resumeMs = absolutePosition()
                    startStream(StreamMode.TRANSCODE)
                } else {
                    toast("Playback failed")
                    finishPlayback()
                }
            }
        })
        exo.setMediaItem(ExoMediaItem.fromUri(stream.url))
        exo.prepare()
        if (stream.mode == StreamMode.DIRECT && resumeMs > 0) exo.seekTo(resumeMs)
        exo.playWhenReady = true
        updateKeepScreenOn()
        main.removeCallbacks(bufferingWatchdog)
        main.postDelayed(bufferingWatchdog, BUFFERING_TIMEOUT_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            val pos = absolutePosition()
            val dur = (source?.durationMs ?: durationHint).coerceAtLeast(1)
            scrub.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
            layoutScrubKnob()
            timeNow.text = fmt(pos)
            timeLeft.text = "−${fmt((dur - pos).coerceAtLeast(0))}"
            updateClock(dur - pos)
            updateSkip(pos)
            updatePlayIcon()
            layoutMarkerBands()
            if (chromeVisible && player?.isPlaying == true && !menuOpen && upNextSeconds < 0 &&
                System.currentTimeMillis() > overlayUntil
            ) {
                hideChrome()
            }
            if (player?.isPlaying == true) {
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (nowElapsed - lastTimelineAtMs >= 10_000L) {
                    lastTimelineAtMs = nowElapsed
                    report(TimelineState.PLAYING)
                }
            }
            main.postDelayed(this, 400)
        }
    }

    private fun updateClock(remainingMs: Long) {
        val now = Calendar.getInstance()
        clockView.text = DateFormat.getTimeFormat(this).format(now.time)
        if (remainingMs > 0) {
            val end = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + remainingMs }
            endsView.text = "Ends at ${DateFormat.getTimeFormat(this).format(end.time)}"
        } else endsView.text = ""
    }

    private fun updateSkip(pos: Long) {
        val markers = info?.markers.orEmpty()
        val intro = markers.firstOrNull { it.kind == MarkerKind.INTRO && pos in it.startMs until it.endMs }
        val credits = markers.firstOrNull { it.kind == MarkerKind.CREDITS && pos in it.startMs until it.endMs }
        val hasCreditsMarker = markers.any { it.kind == MarkerKind.CREDITS && it.endMs > it.startMs }
        val dur = (source?.durationMs ?: durationHint).coerceAtLeast(1)
        val nearEnd = !hasCreditsMarker && dur > 30_000L && pos >= dur - 15_000L
        val showUpNext = prefs.autoplayNext && info?.next != null && (credits != null || nearEnd) && !dismissedUpNext
        if (showUpNext) {
            cancelAutoSkip()
            skipBand.visibility = View.GONE
            activeSkipKey = null
            ensureUpNext()
            return
        }
        cancelUpNext(startNext = false)

        val key: String?
        val showSkip: Boolean
        val baseLabel: String
        val showNext: Boolean
        val autoEnabled: Boolean
        when {
            intro != null -> {
                key = "INTRO:${intro.startMs}"
                showSkip = true
                baseLabel = "Skip Intro"
                showNext = false
                autoEnabled = prefs.autoSkipIntro
            }
            credits != null || (nearEnd && info?.next != null) -> {
                key = if (credits != null) "CREDITS:${credits.startMs}" else "NEAR_END"
                showSkip = credits != null
                baseLabel = "Skip Credits"
                showNext = info?.next != null
                autoEnabled = credits != null && prefs.autoSkipCredits
            }
            else -> {
                key = null
                showSkip = false
                baseLabel = "Skip Intro"
                showNext = false
                autoEnabled = false
            }
        }

        if (key == null || key == dismissedSkipKey) {
            cancelAutoSkip()
            skipBand.visibility = View.GONE
            activeSkipKey = null
            return
        }

        val isNewKey = key != activeSkipKey
        activeSkipKey = key
        skipBand.visibility = View.VISIBLE
        skipBtn.visibility = if (showSkip) View.VISIBLE else View.GONE
        nextBtn.visibility = if (showNext) View.VISIBLE else View.GONE
        updateOverlayPositions()

        if (showSkip && autoEnabled && key != cancelledSkipKey) {
            if (autoSkipKey != key) {
                val delay = prefs.autoSkipDelaySec
                if (delay <= 0) {
                    cancelledSkipKey = key
                    cancelAutoSkip()
                    skipBtn.text = baseLabel
                    skipActiveMarker()
                    return
                }
                startAutoSkip(key, baseLabel, delay)
            }
        } else {
            cancelAutoSkip()
            skipBtn.text = baseLabel
        }

        if (isNewKey && !menuOpen) {
            focusSkipOrUpNext()
        }
    }

    private fun startAutoSkip(key: String, label: String, seconds: Int) {
        main.removeCallbacks(autoSkipTick)
        autoSkipKey = key
        autoSkipLabelBase = label
        autoSkipSeconds = seconds
        skipBtn.text = "$label ($seconds)"
        main.postDelayed(autoSkipTick, 1000)
    }

    private fun cancelAutoSkip() {
        main.removeCallbacks(autoSkipTick)
        autoSkipKey = null
        autoSkipSeconds = -1
    }

    private fun updateOverlayPositions() {
        val margin = dp(if (chromeVisible) 168 else 48)
        (skipBand.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                skipBand.layoutParams = lp
            }
        }
        (upNextHost?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin
                upNextHost?.layoutParams = lp
            }
        }
    }

    private fun focusSkipOrUpNext(): Boolean {
        if (menuOpen) return false
        upNextPlayBtn?.let { btn ->
            if (upNextHost?.visibility == View.VISIBLE) {
                btn.post { btn.requestFocus() }
                return true
            }
        }
        if (skipBand.visibility == View.VISIBLE) {
            val target = when {
                skipBtn.visibility == View.VISIBLE -> skipBtn
                nextBtn.visibility == View.VISIBLE -> nextBtn
                else -> null
            }
            if (target != null) {
                target.post { target.requestFocus() }
                return true
            }
        }
        return false
    }

    private fun layoutMarkerBands() {
        val dur = (source?.durationMs ?: durationHint).coerceAtLeast(1)
        val w = scrubShell.width
        if (w <= 0) {
            scrubShell.post { layoutMarkerBands() }
            return
        }
        fun place(band: View, kind: MarkerKind) {
            val m = info?.markers?.firstOrNull { it.kind == kind }
            if (m == null || m.endMs <= m.startMs) {
                band.visibility = View.GONE
                return
            }
            band.visibility = View.VISIBLE
            val lp = band.layoutParams as FrameLayout.LayoutParams
            lp.width = (((m.endMs - m.startMs).toFloat() / dur) * w).toInt().coerceAtLeast(dp(4))
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            lp.leftMargin = ((m.startMs.toFloat() / dur) * w).toInt().coerceIn(0, w)
            band.layoutParams = lp
        }
        place(introBand, MarkerKind.INTRO)
        place(creditsBand, MarkerKind.CREDITS)
    }

    private fun updatePlayIcon() {
        if (!::playIcon.isInitialized) return
        // Status indicator: play glyph while playing, pause glyph while paused.
        val playing = player?.isPlaying == true
        playIcon.setImageResource(if (playing) R.drawable.ic_play else R.drawable.ic_pause)
    }

    private fun ensureUpNext() {
        if (upNextSeconds >= 0) return
        val next = info?.next ?: return
        upNextSeconds = prefs.upNextDelaySec
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
            background = rounded(alpha(palette.mantle, 0.95f), dp(16).toFloat())
            elevation = dp(20).toFloat()
            isClickable = true
        }
        val artPath = next.thumb ?: next.poster ?: next.backdrop
        if (!artPath.isNullOrBlank()) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(alpha(Color.WHITE, 0.08f))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                clipToOutline = true
            }
            card.addView(thumb, llp(dp(144), dp(81)) { marginEnd = dp(16) })
            upNextThumbDisposable?.dispose()
            upNextThumbDisposable = Images.load(app.images, app.plex, thumb, artPath, dp(144), dp(81))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val label = text(12f, palette.accent, BOLD, s = "UP NEXT · plays in $upNextSeconds s").also {
            it.letterSpacing = 0.06f
        }
        upNextLabel = label
        col.addView(label)
        val title = listOfNotNull(next.seriesTitle, next.episodeCode ?: next.title).joinToString(" · ")
        col.addView(
            text(16f, palette.text, BOLD, s = title).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            llp(MATCH_PARENT, WRAP) { topMargin = dp(4) },
        )
        if (next.summary.isNotBlank()) {
            col.addView(
                text(12.5f, palette.sub0, lines = 2, s = next.summary.trim()).apply {
                    ellipsize = TextUtils.TruncateAt.END
                },
                llp(MATCH_PARENT, WRAP) { topMargin = dp(4) },
            )
        }
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val playNow = pill("Play now", primary = true).also {
            it.id = View.generateViewId()
            it.setOnClickListener { cancelUpNext(startNext = true) }
        }
        val dont = pill("Don't play", primary = false).also {
            it.id = View.generateViewId()
            it.setOnClickListener {
                dismissedUpNext = true
                cancelUpNext(startNext = false)
            }
        }
        playNow.nextFocusRightId = dont.id
        dont.nextFocusLeftId = playNow.id
        upNextPlayBtn = playNow
        upNextDontBtn = dont
        acts.addView(playNow, llp(WRAP, WRAP) { marginEnd = dp(10) })
        acts.addView(dont)
        col.addView(acts)
        card.addView(col, llp(0, WRAP, 1f))

        val railIndex = root.indexOfChild(railHost).takeIf { it >= 0 } ?: root.childCount
        val margin = dp(if (chromeVisible) 168 else 48)
        root.addView(
            card,
            railIndex,
            flp(dp(560), WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(36); bottomMargin = margin
            },
        )
        upNextHost = card
        if (!menuOpen) playNow.post { playNow.requestFocus() }
        main.removeCallbacks(upNextTick)
        main.postDelayed(upNextTick, 1000)
    }

    private fun cancelUpNext(startNext: Boolean) {
        main.removeCallbacks(upNextTick)
        upNextSeconds = -1
        upNextLabel = null
        upNextPlayBtn = null
        upNextDontBtn = null
        upNextThumbDisposable?.dispose()
        upNextThumbDisposable = null
        upNextHost?.let { root.removeView(it) }
        upNextHost = null
        if (startNext) playNext()
    }

    private fun skipActiveMarker() {
        val pos = absolutePosition()
        val m = info?.markers.orEmpty().firstOrNull {
            (it.kind == MarkerKind.INTRO || it.kind == MarkerKind.CREDITS) && pos in it.startMs until it.endMs
        } ?: return
        seekTo(m.endMs)
    }

    private fun absolutePosition(): Long {
        val p = player ?: return resumeMs
        return (source?.streamOffsetMs ?: 0) + p.currentPosition.coerceAtLeast(0)
    }

    private fun seekBy(delta: Long) = seekTo(absolutePosition() + delta)

    private fun seekTo(absMs: Long) {
        val p = player ?: return
        val target = absMs.coerceAtLeast(0)
        if (source?.mode == StreamMode.TRANSCODE) {
            // Debounce stream restarts while holding L/R.
            pendingTranscodeMs = target
            main.removeCallbacks(transcodeSeekRunnable)
            main.postDelayed(transcodeSeekRunnable, 350)
        } else {
            val local = target - (source?.streamOffsetMs ?: 0)
            p.seekTo(local.coerceAtLeast(0))
            p.playWhenReady = true
        }
        // Show chrome so the user sees how far they seeked; always focus scrubber (not Settings).
        showChrome(focusScrubber = true)
        // Optimistic scrub position while seeking
        val dur = (source?.durationMs ?: durationHint).coerceAtLeast(1)
        scrub.progress = ((target * 1000L) / dur).toInt().coerceIn(0, 1000)
        timeNow.text = fmt(target)
        timeLeft.text = "−${fmt((dur - target).coerceAtLeast(0))}"
        layoutScrubKnob()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            showChrome(focusScrubber = true)
        } else {
            p.play()
            showChrome(focusScrubber = true)
            bumpChromeTimer()
        }
        updatePlayIcon()
    }

    private fun trimSpeed(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"

    private fun playPrevious() {
        val prev = info?.previous ?: return
        playEpisode(prev)
    }

    private fun playNext() {
        val next = info?.next ?: return
        playEpisode(next)
    }

    private fun playEpisode(ep: MediaItem) {
        ending = true
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(EXTRA_ITEM_ID, ep.id)
            .putExtra(EXTRA_TITLE, listOfNotNull(ep.seriesTitle, ep.episodeCode ?: ep.title).joinToString(" · "))
            .putExtra(EXTRA_RESUME_MS, 0L)
            .putExtra(EXTRA_DURATION_MS, ep.durationMs)
        startActivity(intent)
    }

    private fun onEnded() {
        if (prefs.autoplayNext && info?.next != null) playNext()
        else finishPlayback()
    }

    private fun openMenu(kind: Menu) {
        menuOpen = true
        menuKind = kind
        railHost.removeAllViews()
        railHost.visibility = View.VISIBLE
        railHost.setBackgroundColor(alpha(Color.BLACK, 0.35f))
        railHost.setOnClickListener { closeMenu() }
        when (kind) {
            Menu.SETTINGS -> showSettingsPanel()
            Menu.SUBS -> showSubsPanel()
            Menu.AUDIO -> showAudioPanel()
            Menu.QUALITY -> showQualityPanel()
            Menu.OPTIONS -> showOptionsPanel()
            Menu.NONE -> {}
        }
        showChrome()
    }

    private fun panelShell(title: String, body: LinearLayout, focusIndex: Int = 1): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.mantle, dp(16).toFloat())
            setPadding(dp(8), dp(10), dp(8), dp(14))
            elevation = dp(24).toFloat()
            isClickable = true
            minimumWidth = dp(280)
        }
        panel.addView(text(17f, palette.text, BOLD, s = title).also {
            it.setPadding(dp(20), dp(10), dp(20), dp(12))
        })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(body)
        panel.addView(scroll, llp(MATCH_PARENT, WRAP_CONTENT))
        railHost.addView(panel, flp(dp(300), WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(48); rightMargin = dp(36)
        })
        body.getChildAt(focusIndex.coerceIn(0, (body.childCount - 1).coerceAtLeast(0)))?.requestFocus()
        return panel
    }

    private fun showSettingsPanel() {
        offsetValueView = null
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val subTitle = info?.subtitleTracks?.firstOrNull { it.id == subtitleId }?.title
            ?: if (subtitleId == null) "None" else "On"
        val audioTitle = info?.audioTracks?.firstOrNull { it.id == audioId }?.title
            ?: info?.audioTracks?.firstOrNull()?.title ?: "Default"
        val quality = if (source?.mode == StreamMode.DIRECT && !forceTranscode) "Original" else "Convert"
        body.addView(menuRow("Quality", quality, rowTag = TAG_QUALITY) {
            menuFromSettings = true; openMenu(Menu.QUALITY)
        })
        body.addView(menuRow("Subtitles", subTitle, rowTag = TAG_SUBS) {
            menuFromSettings = true; openMenu(Menu.SUBS)
        })
        body.addView(menuRow("Audio", audioTitle, rowTag = TAG_AUDIO) {
            menuFromSettings = true; openMenu(Menu.AUDIO)
        })
        body.addView(menuRow("Playback Options", "", rowTag = TAG_OPTIONS) {
            menuFromSettings = true; openMenu(Menu.OPTIONS)
        })
        val off = prefs.audioOffsetMs
        body.addView(menuRow("Audio sync", "$off ms", rowTag = TAG_OFFSET) {
            // L/R nudge on the hub row; OK stays here
        })
        panelShell("Settings", body, focusIndex = settingsFocusIndex.coerceIn(0, 4))
    }

    private fun nudgeOffset(delta: Int) {
        prefs.audioOffsetMs = prefs.audioOffsetMs + delta
        delayProcessor.delayMs = prefs.audioOffsetMs
        offsetValueView?.text = "${prefs.audioOffsetMs} ms"
    }

    private fun applySubtitleSelection(exo: ExoPlayer) {
        val disable = subtitleId == null
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disable)
            .build()
        playerView.subtitleView?.visibility = if (disable) View.GONE else View.VISIBLE
    }

    private fun showSubsPanel() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(menuRow("None", null, selected = subtitleId == null) {
            subtitleId = null; closeMenu()
            if (source?.mode == StreamMode.DIRECT) {
                player?.let { applySubtitleSelection(it) }
            } else {
                resumeMs = absolutePosition()
                startStream(source?.mode ?: StreamMode.DIRECT)
            }
        })
        for (t in info?.subtitleTracks.orEmpty()) {
            body.addView(menuRow(t.title, t.language, selected = t.id == subtitleId) {
                subtitleId = t.id; closeMenu(); resumeMs = absolutePosition()
                startStream(source?.mode ?: StreamMode.DIRECT)
            })
        }
        panelShell("Subtitles", body, 0)
    }

    private fun showAudioPanel() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tracks = info?.audioTracks.orEmpty()
        if (tracks.isEmpty()) body.addView(menuRow("Default audio", null, selected = true) {})
        for (t in tracks) {
            body.addView(menuRow(t.title, t.language, selected = t.id == audioId) {
                audioId = t.id; closeMenu(); resumeMs = absolutePosition()
                startStream(source?.mode ?: StreamMode.DIRECT)
            })
        }
        panelShell("Audio", body, 0)
    }

    private fun showQualityPanel() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(menuRow("Original", "Direct play", selected = source?.mode == StreamMode.DIRECT && !forceTranscode) {
            forceTranscode = false; closeMenu(); resumeMs = absolutePosition(); triedTranscode = false
            startStream(StreamMode.DIRECT)
        })
        body.addView(menuRow("Convert · 1080p", "Server transcode", selected = source?.mode == StreamMode.TRANSCODE || forceTranscode) {
            forceTranscode = true; closeMenu(); resumeMs = absolutePosition()
            startStream(StreamMode.TRANSCODE)
        })
        panelShell("Quality", body, 0)
    }

    private fun showOptionsPanel() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(11f, palette.sub0, BOLD, s = "SPEED").also {
            it.setPadding(dp(18), dp(8), dp(18), dp(4)); it.letterSpacing = 0.08f
        })
        for (s in listOf(0.75f, 1f, 1.25f, 1.5f)) {
            body.addView(menuRow(trimSpeed(s), null, selected = speed == s) {
                speed = s; player?.playbackParameters = PlaybackParameters(s); closeMenu()
            })
        }
        body.addView(text(11f, palette.sub0, BOLD, s = "NEXT EPISODE & SKIP").also {
            it.setPadding(dp(18), dp(14), dp(18), dp(4)); it.letterSpacing = 0.08f
        })
        body.addView(menuRow("Autoplay next", if (prefs.autoplayNext) "On" else "Off", selected = prefs.autoplayNext) {
            prefs.autoplayNext = !prefs.autoplayNext; openMenu(Menu.OPTIONS)
        })
        body.addView(menuRow("Auto-skip intros", if (prefs.autoSkipIntro) "On" else "Off", selected = prefs.autoSkipIntro) {
            prefs.autoSkipIntro = !prefs.autoSkipIntro; openMenu(Menu.OPTIONS)
        })
        body.addView(menuRow("Auto-skip credits", if (prefs.autoSkipCredits) "On" else "Off", selected = prefs.autoSkipCredits) {
            prefs.autoSkipCredits = !prefs.autoSkipCredits; openMenu(Menu.OPTIONS)
        })
        val skipChoices = listOf(0, 3, 5, 10, 15)
        val skipLabel = if (prefs.autoSkipDelaySec == 0) "Instant" else "${prefs.autoSkipDelaySec} s"
        body.addView(menuRow("Auto-skip countdown", skipLabel) {
            val nextIdx = (skipChoices.indexOf(prefs.autoSkipDelaySec) + 1) % skipChoices.size
            prefs.autoSkipDelaySec = skipChoices[nextIdx]
            openMenu(Menu.OPTIONS)
        })
        val upNextChoices = listOf(5, 10, 15, 20, 30)
        body.addView(menuRow("Up Next countdown", "${prefs.upNextDelaySec} s") {
            val nextIdx = (upNextChoices.indexOf(prefs.upNextDelaySec) + 1) % upNextChoices.size
            prefs.upNextDelaySec = upNextChoices[nextIdx]
            openMenu(Menu.OPTIONS)
        })
        panelShell("Playback Options", body, 1)
    }

    private fun menuRow(
        title: String,
        value: String?,
        selected: Boolean = false,
        nyx: Boolean = false,
        rowTag: String? = null,
        action: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isFocusable = true
            isClickable = true
            minimumHeight = dp(52)
            tag = rowTag
        }
        val lab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lab.addView(text(14f, palette.text, MEDIUM, s = title))
        if (nyx) {
            lab.addView(TextView(this).apply {
                text = "NYX"
                textSize = 9f
                typeface = BOLD
                setTextColor(palette.onAccent)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = rounded(palette.accent, dp(4).toFloat())
            }, llp(WRAP, WRAP) { marginStart = dp(8) })
        }
        row.addView(lab, llp(0, WRAP, 1f))
        if (!value.isNullOrEmpty()) {
            val valueTv = text(13f, palette.sub0, s = value).also { it.gravity = Gravity.END }
            if (rowTag == TAG_OFFSET) offsetValueView = valueTv
            row.addView(valueTv)
        }
        if (selected) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(palette.sub0)
            }, llp(dp(18), dp(18)) { marginStart = dp(8) })
        } else if (value != null && rowTag != TAG_OFFSET) {
            row.addView(text(14f, palette.sub0, s = "›").also { it.setPadding(dp(6), 0, 0, 0) })
        }
        fun paint(f: Boolean) {
            row.background = rounded(if (f) palette.text else Color.TRANSPARENT, dp(10).toFloat())
            val titleC = if (f) palette.base else palette.text
            val subC = if (f) alpha(palette.base, 0.7f) else palette.sub0
            ((lab.getChildAt(0) as TextView)).setTextColor(titleC)
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c is TextView && c !== lab.getChildAt(0)) c.setTextColor(subC)
                if (c is ImageView) c.imageTintList = ColorStateList.valueOf(subC)
            }
        }
        paint(false)
        row.setOnFocusChangeListener { _, f -> paint(f) }
        row.setOnClickListener { action() }
        return row
    }

    private fun closeMenu() {
        menuOpen = false
        menuKind = Menu.NONE
        menuFromSettings = false
        railHost.visibility = View.GONE
        railHost.removeAllViews()
        showChrome(focusScrubber = true)
        scrubShell.requestFocus()
    }

    private fun showChrome(focusScrubber: Boolean = false) {
        chromeVisible = true
        chrome.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        chrome.visibility = View.VISIBLE
        updateOverlayPositions()
        bumpChromeTimer()
        if (!menuOpen) {
            if (focusScrubber || !focusSkipOrUpNext()) {
                scrubShell.post { scrubShell.requestFocus() }
            }
        }
    }

    private fun bumpChromeTimer() {
        overlayUntil = if (player?.isPlaying == true) {
            System.currentTimeMillis() + 4_000L
        } else {
            Long.MAX_VALUE / 4
        }
    }

    private fun hideChrome() {
        if (menuOpen) return
        chromeVisible = false
        updateOverlayPositions()
        // Drop focus out of chrome so Android won't restore it onto Settings when shown again.
        scrubShell.clearFocus()
        prevEpBtn.clearFocus()
        nextEpBtn.clearFocus()
        subsBtn.clearFocus()
        settingsBtn.clearFocus()
        chrome.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        chrome.visibility = View.GONE
        if (!onSkipBand() && !onUpNext()) {
            root.requestFocus()
        }
    }

    /** Plex-like seek: ~30s base, hold accelerates. */
    private fun seekStep(forward: Boolean, event: KeyEvent?): Long {
        val held = event?.let { (it.eventTime - it.downTime).coerceAtLeast(0) } ?: 0L
        val step = when {
            held > 2500 -> 300_000L
            held > 1200 -> 60_000L
            held > 400 -> 30_000L
            else -> 30_000L
        }
        return if (forward) step else -step
    }

    private fun onScrubber(): Boolean = scrubShell.hasFocus()

    private fun onActionRow(): Boolean {
        val f = currentFocus ?: return false
        return f === prevEpBtn || f === nextEpBtn || f === subsBtn || f === settingsBtn
    }

    private fun onSkipBand(): Boolean {
        val f = currentFocus ?: return false
        return f === skipBtn || f === nextBtn || f.parent === skipBand
    }

    private fun onUpNext(): Boolean {
        val f = currentFocus ?: return false
        return upNextHost != null && (f === upNextPlayBtn || f === upNextDontBtn)
    }

    private fun report(state: TimelineState) {
        val s = source ?: return
        val pos = absolutePosition()
        val targetScope = if (state == TimelineState.STOPPED) app.scope else scope
        targetScope.launch {
            runCatching {
                app.plex.reportTimeline(TimelineUpdate(itemId, s.sessionId, state, pos, source?.durationMs ?: durationHint))
            }
        }
    }

    private fun finishPlayback() {
        if (ending) return
        ending = true
        report(TimelineState.STOPPED)
        source?.takeIf { it.mode == StreamMode.TRANSCODE }?.let { s ->
            app.scope.launch { runCatching { app.plex.stopPlaybackSession(s.sessionId) } }
        }
        releasePlayer(false)
        finish()
    }

    private fun updateKeepScreenOn() {
        val exo = player
        val keepOn = exo != null && exo.playWhenReady &&
            exo.playbackState != Player.STATE_IDLE &&
            exo.playbackState != Player.STATE_ENDED
        if (::playerView.isInitialized) playerView.keepScreenOn = keepOn
        if (keepOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun releasePlayer(report: Boolean) {
        main.removeCallbacks(bufferingWatchdog)
        if (report) report(TimelineState.STOPPED)
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        updateKeepScreenOn()
    }

    /**
     * Remote map (Plex Bedroom TV + Nyx):
     * Hidden: OK play/pause · ←→ seek · ↑↓ show chrome (scrubber) · Back exit
     * Visible: OK activate · ←→ seek on scrubber · ↑ Subs/Settings · ↓ Chapters · Back hide
     * Settings: L/R drill or nudge offset · Down on Audio sync / Back closes panel
     * Long-press Back / gear: Quick Settings over video (playback keeps running).
     */
    private fun leaveToHome(extra: String) {
        closeOverlay()
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(extra, true),
        )
    }

    private fun openOverlay(v: View) {
        closeOverlay()
        overlayReturn = currentFocus
        overlay = v
        chrome.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        railHost.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlayHost.addView(v, flp(MATCH, MATCH))
    }

    override fun closeOverlay() {
        val v = overlay ?: return
        overlayHost.removeView(v)
        overlay = null
        chrome.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        railHost.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val ret = overlayReturn?.takeIf { it.isAttachedToWindow }
        overlayReturn = null
        if (ret != null) ret.requestFocus() else root.requestFocus()
    }

    override fun openQuickSettings() {
        if (overlay is QuickSettings) {
            closeOverlay()
            return
        }
        if (menuOpen) closeMenu()
        openOverlay(QuickSettings(this, this).also { it.post { it.focusFirst() } })
    }

    override fun openInputs() {
        val tv = TvSystem(this)
        val list = tv.inputs().filter { it.passthrough }
        if (list.isEmpty()) {
            closeOverlay()
            if (!tv.open(TvSystem.Screen.ALL)) toast("This TV doesn't list its HDMI inputs to apps")
            return
        }
        showMenu(
            "Inputs",
            "HDMI inputs",
            list.map { i ->
                MenuPanel.Item(i.label, "HDMI") {
                    if (!tv.switchTo(i)) toast("${i.label} couldn't be opened")
                }
            },
        )
    }

    override fun showMenu(title: String?, subtitle: String?, items: List<MenuPanel.Item>, anchorStart: Int, anchorTop: Int) {
        val panel = MenuPanel(this, palette, title, subtitle, items) { closeOverlay() }
        val wrap = FrameLayout(this).apply {
            setBackgroundColor(alpha(palette.base, 0.62f))
            isClickable = true
        }
        val lpx = flp(dp(280), WRAP).apply {
            if (anchorStart == 0 && anchorTop == 0) gravity = Gravity.CENTER
            else {
                leftMargin = anchorStart
                topMargin = anchorTop
            }
        }
        wrap.addView(panel, lpx)
        openOverlay(wrap)
        panel.post { panel.focusFirst() }
    }

    override fun startAmbient() = leaveToHome(HomeActivity.EXTRA_START_AMBIENT)

    override fun startSleepAmbient() = leaveToHome(HomeActivity.EXTRA_START_SLEEP_AMBIENT)

    override fun openNyxSettings() = leaveToHome(HomeActivity.EXTRA_OPEN_SETTINGS)

    private fun handleBackPress(): Boolean {
        if (overlay != null) {
            closeOverlay()
            return true
        }
        if (menuOpen) {
            when (menuKind) {
                Menu.SETTINGS -> if (handleSettingsKeys(KeyEvent.KEYCODE_BACK)) return true
                Menu.QUALITY, Menu.OPTIONS, Menu.AUDIO, Menu.SUBS -> if (handleSubmenuKeys(KeyEvent.KEYCODE_BACK)) return true
                Menu.NONE -> {}
            }
            return false
        }
        if (autoSkipSeconds >= 0) {
            cancelledSkipKey = activeSkipKey
            cancelAutoSkip()
            updateSkip(absolutePosition())
            return true
        }
        if (chromeVisible) { hideChrome(); return true }
        if (upNextHost != null) {
            dismissedUpNext = true
            cancelUpNext(startNext = false)
            root.requestFocus()
            return true
        }
        if (skipBand.visibility == View.VISIBLE && onSkipBand()) {
            dismissedSkipKey = activeSkipKey
            cancelAutoSkip()
            skipBand.visibility = View.GONE
            root.requestFocus()
            return true
        }
        finishPlayback()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        backLongPressHandled = false
                    } else if (!backLongPressHandled) {
                        backLongPressHandled = true
                        openQuickSettings()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (backLongPressHandled) {
                        backLongPressHandled = false
                        return true
                    }
                    if (handleBackPress()) return true
                    return super.dispatchKeyEvent(event)
                }
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // Quick Settings / Inputs stay on the player so video keeps playing.
        when (key) {
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION -> {
                openQuickSettings()
                return true
            }
            KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_WINDOW -> {
                openInputs()
                return true
            }
        }

        // Overlay owns D-pad / OK; don't let player chrome steal them.
        if (overlay != null) return super.dispatchKeyEvent(event)

        if (menuOpen) {
            when (menuKind) {
                Menu.SETTINGS -> if (handleSettingsKeys(key)) return true
                Menu.QUALITY, Menu.OPTIONS, Menu.AUDIO, Menu.SUBS -> if (handleSubmenuKeys(key)) return true
                Menu.NONE -> {}
            }
            return super.dispatchKeyEvent(event)
        }

        if (key == KeyEvent.KEYCODE_ESCAPE) {
            return handleBackPress()
        }

        if (!chromeVisible) {
            if (onSkipBand() || onUpNext()) {
                when (key) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        currentFocus?.performClick(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (currentFocus === skipBtn && nextBtn.visibility == View.VISIBLE) {
                            nextBtn.requestFocus()
                        } else if (currentFocus === upNextPlayBtn) {
                            upNextDontBtn?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (currentFocus === nextBtn && skipBtn.visibility == View.VISIBLE) {
                            skipBtn.requestFocus()
                        } else if (currentFocus === upNextDontBtn) {
                            upNextPlayBtn?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        showChrome(focusScrubber = false); return true
                    }
                }
            }
            when (key) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlay(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); showChrome(focusScrubber = true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(seekStep(false, event)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(seekStep(true, event)); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showChrome(focusScrubber = false); return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPrevious(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    showChrome(focusScrubber = true); openMenu(Menu.SETTINGS); return true
                }
            }
            // Never let unhandled keys focus-search into invisible chrome.
            return true
        }

        bumpChromeTimer()
        when (key) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); showChrome(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(seekStep(false, event)); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(seekStep(true, event)); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPrevious(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (onScrubber()) { seekBy(seekStep(false, event)); return true }
                if (currentFocus === nextBtn && skipBtn.visibility == View.VISIBLE) {
                    skipBtn.requestFocus(); return true
                }
                if (currentFocus === upNextDontBtn) {
                    upNextPlayBtn?.requestFocus(); return true
                }
                return super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (onScrubber()) { seekBy(seekStep(true, event)); return true }
                if (currentFocus === skipBtn && nextBtn.visibility == View.VISIBLE) {
                    nextBtn.requestFocus(); return true
                }
                if (currentFocus === upNextPlayBtn) {
                    upNextDontBtn?.requestFocus(); return true
                }
                return super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                when {
                    onScrubber() -> { settingsBtn.requestFocus(); return true }
                    onActionRow() -> { focusSkipOrUpNext(); return true }
                    onSkipBand() || onUpNext() -> return true
                    else -> {
                        if (!focusSkipOrUpNext()) scrubShell.requestFocus()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    onSkipBand() || onUpNext() -> { settingsBtn.requestFocus(); return true }
                    onActionRow() -> { scrubShell.requestFocus(); return true }
                    onScrubber() -> { hideChrome(); return true }
                    else -> { hideChrome(); return true }
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val f = currentFocus
                if (f != null && f.isClickable) { f.performClick(); return true }
                togglePlay(); return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { openMenu(Menu.SETTINGS); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** @return true if the key was handled */
    private fun handleSettingsKeys(key: Int): Boolean {
        val focus = currentFocus
        val tag = focus?.tag as? String
        when (key) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                closeMenu(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Left never opens a menu. On Audio sync it nudges; otherwise no-op.
                if (tag == TAG_OFFSET) nudgeOffset(-PlaybackPrefs.NUDGE_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (tag) {
                    TAG_OFFSET -> { nudgeOffset(PlaybackPrefs.NUDGE_MS); return true }
                    TAG_QUALITY, TAG_SUBS, TAG_AUDIO, TAG_OPTIONS -> {
                        focus?.performClick(); return true // drill in only
                    }
                    else -> return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (tag == TAG_OFFSET) { closeMenu(); return true }
                return false // focus next row
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // OK selects / opens; on Audio sync L/R already nudge — stay
                if (tag == TAG_OFFSET) return true
                return false // let click open submenu
            }
        }
        return false
    }

    private fun handleSubmenuKeys(key: Int): Boolean {
        when (key) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Left / Back = go back out (never select)
                if (menuFromSettings) {
                    settingsFocusIndex = when (menuKind) {
                        Menu.QUALITY -> 0
                        Menu.SUBS -> 1
                        Menu.AUDIO -> 2
                        Menu.OPTIONS -> 3
                        else -> 4
                    }
                    openMenu(Menu.SETTINGS)
                } else closeMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Right does not select — only OK does
                return true
            }
        }
        return false
    }

    @Deprecated("TV remotes still hit this on API 26+")
    override fun onBackPressed() {
        when {
            upNextSeconds >= 0 -> cancelUpNext(startNext = false)
            menuOpen -> {
                when (menuKind) {
                    Menu.QUALITY, Menu.OPTIONS, Menu.AUDIO, Menu.SUBS -> {
                        if (menuFromSettings) openMenu(Menu.SETTINGS) else closeMenu()
                    }
                    else -> closeMenu()
                }
            }
            chromeVisible -> hideChrome()
            else -> finishPlayback()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::playerView.isInitialized) {
            main.removeCallbacks(tick)
            main.post(tick)
            if (needsResumeOnStart && !isFinishing && !ending) {
                needsResumeOnStart = false
                startStream(if (forceTranscode || !prefs.preferDirectPlay) StreamMode.TRANSCODE else StreamMode.DIRECT)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(tick)
        main.removeCallbacks(bufferingWatchdog)
        if (!isFinishing && !ending) {
            if (player != null) {
                resumeMs = absolutePosition()
            }
            if (info != null || itemId.isNotEmpty()) {
                needsResumeOnStart = true
            }
            loadJob?.cancel()
            startJob?.cancel()
            source?.takeIf { it.mode == StreamMode.TRANSCODE }?.let { s ->
                app.scope.launch { runCatching { app.plex.stopPlaybackSession(s.sessionId) } }
            }
            releasePlayer(report = player != null)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        main.removeCallbacks(bufferingWatchdog)
        main.removeCallbacks(transcodeSeekRunnable)
        main.removeCallbacks(upNextTick)
        if (!ending) {
            report(TimelineState.STOPPED)
            source?.takeIf { it.mode == StreamMode.TRANSCODE }?.let { s ->
                app.scope.launch { runCatching { app.plex.stopPlaybackSession(s.sessionId) } }
            }
        }
        releasePlayer(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_RESUME_MS = "resume_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        private const val TAG_QUALITY = "quality"
        private const val TAG_SUBS = "subs"
        private const val TAG_AUDIO = "audio"
        private const val TAG_OPTIONS = "options"
        private const val TAG_OFFSET = "offset"
        private const val UP_NEXT_COUNTDOWN_SEC = 15
        private const val BUFFERING_TIMEOUT_MS = 15_000L
    }
}
