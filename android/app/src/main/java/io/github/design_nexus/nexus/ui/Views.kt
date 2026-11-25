package io.github.design_nexus.nexus.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Small helpers for building the 10-foot UI in code. Sizes are dp (the TV is 960 × 540 dp). */
fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun alpha(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (a * 255).toInt().coerceIn(0, 255))
fun mix(a: Int, b: Int, t: Float): Int = ColorUtils.blendARGB(a, b, t)

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

fun Context.text(size: Float, color: Int, face: Typeface = Typeface.DEFAULT, lines: Int = 1, s: CharSequence = ""): TextView = TextView(this).apply {
    textSize = size
    setTextColor(color)
    typeface = face
    text = s
    includeFontPadding = false
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun vlinear(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
fun hlinear(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun lp(w: Int, h: Int, block: ViewGroup.MarginLayoutParams.() -> Unit = {}) = ViewGroup.MarginLayoutParams(w, h).apply(block)
fun llp(w: Int, h: Int, weight: Float = 0f, block: LinearLayout.LayoutParams.() -> Unit = {}) = LinearLayout.LayoutParams(w, h, weight).apply(block)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY, block: FrameLayout.LayoutParams.() -> Unit = {}) = FrameLayout.LayoutParams(w, h, gravity).apply(block)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun formatRuntime(ms: Long): String {
    val m = (ms / 60000).toInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
