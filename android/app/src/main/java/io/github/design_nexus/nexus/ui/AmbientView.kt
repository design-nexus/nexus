package io.github.design_nexus.nexus.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.target
import io.github.design_nexus.nexus.core.AmbientSource
import io.github.design_nexus.nexus.data.MetadataCache
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaProvider
import java.util.Date
import kotlin.random.Random

/**
 * Ambient: library backdrops that change every 40 s with a clock, or black with a dim clock
 * that moves a little each minute. Uses only cached artwork, so it works offline and wakes
 * nothing on the network beyond the image cache.
 */
class AmbientView(ctx: Context, private val p: Palette, private val source: AmbientSource, private val showClock: Boolean, private val h24: Boolean) : FrameLayout(ctx) {
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val shade = View(ctx).apply {
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xA6000000.toInt(), 0, 0))
    }
    private val clockBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val clock = ctx.text(72f, Color.WHITE, LIGHT)
    private val date = ctx.text(15f, 0xD9FFFFFF.toInt())
    private val caption = ctx.text(13f, 0xCCFFFFFF.toInt(), MEDIUM, lines = 2).apply { gravity = Gravity.END }
    private val handler = Handler(Looper.getMainLooper())
    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var loader: ImageLoader? = null
    private var provider: MediaProvider? = null
    private var imageDisposable: coil3.request.Disposable? = null
    @Volatile private var active = false

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(image, flp(MATCH, MATCH))
        addView(shade, flp(MATCH, MATCH))
        clockBox.addView(clock)
        clockBox.addView(date)
        addView(clockBox, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(48), 0, 0, dp(44)) })
        addView(caption, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(50)) })
        clockBox.visibility = if (showClock) VISIBLE else GONE
        if (source == AmbientSource.BLACK) {
            shade.visibility = GONE
            caption.visibility = GONE
            clock.textSize = 54f
            clock.setTextColor(0xFF5A5A66.toInt())
            date.setTextColor(0xFF3E3E48.toInt())
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.format(if (h24) "H:mm" else "h:mm", Date())
            date.text = DateFormat.format("EEEE, MMMM d", Date())
            if (source == AmbientSource.BLACK) {
                (clockBox.layoutParams as LayoutParams).apply { leftMargin = dp(48 + Random.nextInt(0, 120)); bottomMargin = dp(44 + Random.nextInt(0, 80)) }
                clockBox.requestLayout()
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val next = object : Runnable {
        override fun run() {
            show()
            handler.postDelayed(this, 40_000)
        }
    }

    fun start(cache: MetadataCache, provider: MediaProvider, loader: ImageLoader) {
        active = true
        this.loader = loader
        this.provider = provider
        handler.post(tick)
        if (source == AmbientSource.ARTWORK) {
            Thread {
                val list = listOf("RECENT_MOVIES", "RECENT_SHOWS", "CONTINUE_WATCHING", "COLLECTIONS").flatMap { t ->
                    runCatching { cache.readableDatabase.rawQuery("SELECT key FROM rows WHERE key LIKE ?", arrayOf("%:$t:%")).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() } }.getOrDefault(emptyList())
                }.flatMap { cache.get(it).orEmpty() }.filter { it.backdrop != null }.distinctBy { it.key }.shuffled()
                handler.post {
                    if (!active) return@post
                    items = list
                    handler.post(next)
                }
            }.start()
        }
    }

    private fun show() {
        if (!active) return
        val l = loader ?: return
        val pr = provider ?: return
        if (items.isEmpty()) return
        val item = items[index++ % items.size]
        caption.text = listOfNotNull(item.seriesTitle ?: item.title, item.year?.toString()).joinToString(" · ") + "\nIn your Plex library"
        val url = pr.imageUrl(item.backdrop, 1280, 720) ?: return
        image.animate().cancel()
        image.animate().alpha(0f).setDuration(600).withEndAction {
            if (!active) return@withEndAction
            imageDisposable?.dispose()
            imageDisposable = l.enqueue(ImageRequest.Builder(context).data(url).size(1280, 720).target(
                onSuccess = { img ->
                    if (!active) return@target
                    image.setImageDrawable(img.asDrawable(resources))
                    image.animate().alpha(1f).setDuration(900).start()
                },
            ).apply { pr.imageCacheKey(item.backdrop, 1280, 720)?.let { diskCacheKey(it); memoryCacheKey(it) } }.build())
        }.start()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        image.animate().cancel()
        imageDisposable?.dispose()
        imageDisposable = null
        image.setImageDrawable(null)
    }

}

private fun coil3.Image.asDrawable(res: android.content.res.Resources): android.graphics.drawable.Drawable = when (this) {
    is coil3.BitmapImage -> android.graphics.drawable.BitmapDrawable(res, bitmap)
    else -> android.graphics.drawable.ColorDrawable(0)
}
