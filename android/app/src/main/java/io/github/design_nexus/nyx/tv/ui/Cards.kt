package io.github.design_nexus.nyx.tv.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.ImageLoader
import coil3.asImage
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.target
import coil3.size.Precision
import io.github.design_nexus.nyx.tv.core.CardStyle
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider

/** Card sizes in dp for each style (the TV is 960 × 540 dp). */
object CardSize {
    fun width(style: CardStyle): Int = when (style) {
        CardStyle.LANDSCAPE -> 200
        CardStyle.POSTER -> 108
        CardStyle.SQUARE -> 130
        CardStyle.APP_TILE -> 150
    }

    fun height(style: CardStyle): Int = when (style) {
        CardStyle.LANDSCAPE, CardStyle.APP_TILE -> width(style) * 9 / 16
        CardStyle.POSTER -> width(style) * 3 / 2
        CardStyle.SQUARE -> width(style)
    }
}

/** Focus: a slight scale and a ring, as in direction A (Cinema). */
object Focus {
    fun apply(v: View, focused: Boolean, ring: GradientDrawable, reduceMotion: Boolean, scale: Float = 1.08f) {
        val s = if (focused) scale else 1f
        if (reduceMotion) {
            v.scaleX = s; v.scaleY = s
        } else {
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
        v.foreground = if (focused) ring else null
        v.elevation = if (focused) v.dp(8).toFloat() else 0f
        v.translationZ = 0f
    }

    fun ring(ctx: Context, p: Palette, radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setStroke(ctx.dp(2.5), p.text)
        setColor(0)
    }
}

fun roundClip(v: View, radius: Float) {
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
    v.clipToOutline = true
}

object Images {
    /** Artwork into [iv] at the card's pixel size; the request is cancelled if the view is reused. */
    fun load(loader: ImageLoader, provider: MediaProvider, iv: ImageView, path: String?, wPx: Int, hPx: Int): Disposable? {
        val url = provider.imageUrl(path, wPx, hPx)
        if (url == null) {
            iv.setImageDrawable(null)
            return null
        }
        val req = ImageRequest.Builder(iv.context)
            .data(url)
            .size(wPx, hPx)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .target(iv)
            .apply { provider.imageCacheKey(path, wPx, hPx)?.let { k -> diskCacheKey(k); memoryCacheKey(k) } }
            .build()
        return loader.enqueue(req)
    }

    fun placeholder(d: Drawable?, iv: ImageView) = iv.setImageDrawable(d)
}

/** A media card: artwork, an optional label inside (landscape), progress, watched or unwatched badge. */
class MediaCard(ctx: Context, private val p: Palette, val style: CardStyle, private val reduceMotion: Boolean) : LinearLayout(ctx) {
    private val radius = dp(10).toFloat()
    /** Unclipped shell that holds the focus ring; artwork is clipped inside [art]. */
    val frame = FrameLayout(ctx)
    private val art = FrameLayout(ctx)
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val shade = View(ctx).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(alpha(0xFF000000.toInt(), 0.8f), 0))
    }
    private val label = vlinear(ctx)
    private val labelTitle = ctx.text(12.5f, 0xFFFFFFFF.toInt(), BOLD)
    private val labelSub = ctx.text(10.5f, 0xDDFFFFFF.toInt())
    private val placeholderTitle = ctx.text(12f, p.sub1, MEDIUM, 3).apply { gravity = Gravity.CENTER }
    private val progressTrack = View(ctx).apply { background = rounded(0x47FFFFFF, dp(2).toFloat()) }
    private val progressFill = View(ctx).apply { background = rounded(p.accent, dp(2).toFloat()) }
    private val badge = ctx.text(10.5f, p.onAccent, BOLD).apply {
        gravity = Gravity.CENTER
        background = rounded(p.accent, dp(10).toFloat())
        setPadding(dp(5), 0, dp(5), 0)
    }
    private val check = ctx.text(12f, 0xFFFFFFFF.toInt(), BOLD, s = "✓").apply {
        gravity = Gravity.CENTER
        background = rounded(0xB811111B.toInt(), dp(10).toFloat())
    }
    val caption = vlinear(ctx)
    private val capTitle = ctx.text(12f, p.text, MEDIUM)
    private val capSub = ctx.text(10.5f, p.sub0)
    private val ring = Focus.ring(ctx, p, radius)
    private var request: Disposable? = null
    var item: MediaItem? = null
        private set

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val w = dp(CardSize.width(style)); val h = dp(CardSize.height(style))
        frame.background = rounded(p.s0, radius)
        frame.clipChildren = false
        roundClip(art, radius)
        art.addView(placeholderTitle, flp(MATCH, MATCH).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        art.addView(image, flp(MATCH, MATCH))
        art.addView(shade, flp(MATCH, h / 2, Gravity.BOTTOM))
        label.addView(labelTitle); label.addView(labelSub)
        art.addView(label, flp(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(14)) })
        art.addView(progressTrack, flp(MATCH, dp(3.5), Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(7)) })
        art.addView(progressFill, flp(0, dp(3.5), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(7)) })
        art.addView(badge, flp(WRAP, dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0); minimumWidth = dp(20) })
        art.addView(check, flp(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        frame.addView(art, flp(MATCH, MATCH))
        addView(frame, llp(w, h))
        caption.addView(capTitle); caption.addView(capSub)
        addView(caption, llp(w, WRAP) { topMargin = dp(7) })
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        Focus.apply(frame, gainFocus, ring, reduceMotion)
    }

    fun clearFocusChrome() = Focus.apply(frame, false, ring, reduceMotion = true)

    fun bind(i: MediaItem, provider: MediaProvider, loader: ImageLoader, showCaption: Boolean) {
        item = i
        val landscape = style == CardStyle.LANDSCAPE
        val isEp = i.kind == MediaKind.EPISODE
        val title = if (isEp) i.seriesTitle ?: i.title else i.title
        val sub = when {
            isEp -> listOfNotNull(i.episodeCode, i.title).joinToString(" · ")
            i.progress > 0 -> "${formatRuntime(i.remainingMs)} left"
            i.kind == MediaKind.COLLECTION || i.kind == MediaKind.PLAYLIST ->
                i.childCount?.let { "$it titles" } ?: ""
            else -> i.year?.toString() ?: ""
        }
        placeholderTitle.text = title
        labelTitle.text = title
        labelSub.text = sub
        val inside = landscape
        label.visibility = if (inside) VISIBLE else GONE
        shade.visibility = if (inside) VISIBLE else GONE
        caption.visibility = if (!inside && showCaption) VISIBLE else GONE
        capTitle.text = title
        capSub.text = sub
        val prog = i.progress
        progressTrack.visibility = if (prog > 0) VISIBLE else GONE
        progressFill.visibility = if (prog > 0) VISIBLE else GONE
        if (prog > 0) {
            frame.post {
                val full = frame.width - dp(16)
                progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply { width = (full * prog).toInt().coerceAtLeast(dp(3)) }
            }
        }
        val unwatched = i.unwatchedCount?.takeIf { it > 0 && (i.kind == MediaKind.SHOW || i.kind == MediaKind.SEASON) }
        badge.visibility = if (unwatched != null) VISIBLE else GONE
        badge.text = unwatched?.toString() ?: ""
        check.visibility = if (unwatched == null && i.watched) VISIBLE else GONE
        request?.dispose()
        val path = if (landscape) (i.thumb ?: i.backdrop ?: i.poster) else (i.poster ?: i.thumb)
        val wPx = frame.layoutParams.width; val hPx = frame.layoutParams.height
        request = Images.load(loader, provider, image, path, wPx, hPx)
        if (hasFocus()) Focus.apply(frame, true, ring, reduceMotion)
    }

    fun recycle() {
        request?.dispose()
        request = null
        image.setImageDrawable(null)
        clearFocusChrome()
    }
}

/** An installed app as a 16:9 tile: its TV banner, or its icon on a neutral fill with the name. */
class AppTile(ctx: Context, private val p: Palette, private val reduceMotion: Boolean, widthDp: Int = CardSize.width(CardStyle.APP_TILE)) : LinearLayout(ctx) {
    private val radius = dp(10).toFloat()
    val frame = FrameLayout(ctx)
    private val art = FrameLayout(ctx)
    val image = ImageView(ctx)
    val name: TextView = ctx.text(11f, p.text, MEDIUM).apply { gravity = Gravity.CENTER }
    val caption: TextView = ctx.text(11.5f, p.sub1, MEDIUM)
    private val ring = Focus.ring(ctx, p, radius)
    var pkg: String? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        frame.background = rounded(p.s0, radius)
        frame.clipChildren = false
        roundClip(art, radius)
        art.addView(image, flp(MATCH, MATCH))
        art.addView(name, flp(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        frame.addView(art, flp(MATCH, MATCH))
        val w = dp(widthDp)
        addView(frame, llp(w, w * 9 / 16))
        addView(caption, llp(w, WRAP) { topMargin = dp(6) })
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        Focus.apply(frame, gainFocus, ring, reduceMotion)
    }

    fun clearFocusChrome() = Focus.apply(frame, false, ring, reduceMotion = true)

    fun bind(label: String, artDrawable: Drawable?, banner: Boolean, showCaption: Boolean) {
        image.setImageDrawable(artDrawable)
        if (banner) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            name.visibility = GONE
        } else {
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(18)
            image.setPadding(pad, pad - dp(6), pad, pad + dp(6))
            name.visibility = VISIBLE
            name.text = label
        }
        caption.text = label
        caption.visibility = if (showCaption) VISIBLE else GONE
        if (hasFocus()) Focus.apply(frame, true, ring, reduceMotion)
    }

    fun setPlaceholder(label: String) {
        image.setImageDrawable(null)
        name.visibility = VISIBLE
        name.text = label
        caption.text = label
    }
}

@Suppress("unused")
private fun Drawable.asCoil() = asImage()
