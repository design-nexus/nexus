package io.github.design_nexus.nexus.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A rounded button: icon and label; accent fill when focused. */
class PillButton(ctx: Context, private val p: Palette, label: String, icon: Int? = null, private val primary: Boolean = false, private val reduceMotion: Boolean = false) : LinearLayout(ctx) {
    val label: TextView = ctx.text(14f, if (primary) p.base else p.text, MEDIUM, s = label)
    private val icon = icon?.let { ImageView(ctx).apply { setImageResource(it) } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(20), 0)
        minimumHeight = dp(38)
        this.icon?.let { addView(it, llp(dp(16), dp(16)) { marginEnd = if (label.isEmpty()) 0 else dp(8) }) }
        if (label.isEmpty()) setPadding(dp(11), 0, dp(11), 0)
        addView(this.label)
        isFocusable = true
        isClickable = true
        paint(false)
        setOnFocusChangeListener { _, f ->
            paint(f)
            val s = if (f) 1.06f else 1f
            if (reduceMotion) { scaleX = s; scaleY = s } else animate().scaleX(s).scaleY(s).setDuration(110).start()
        }
    }

    private fun paint(focused: Boolean) {
        val bg = when {
            focused -> p.accent
            primary -> p.text
            else -> alpha(p.o0, 0.32f)
        }
        val fg = when {
            focused -> p.onAccent
            primary -> p.base
            else -> p.text
        }
        background = rounded(bg, dp(19).toFloat())
        label.setTextColor(fg)
        icon?.imageTintList = ColorStateList.valueOf(fg)
    }

    fun setText(s: String) { label.text = s }
}

/** A filter chip. */
class Chip(ctx: Context, private val p: Palette, label: String, selected: Boolean = false) : TextView(ctx) {
    var selectedChip = selected
        set(v) { field = v; paint(hasFocus()) }

    init {
        text = label
        textSize = 12f
        typeface = MEDIUM
        gravity = Gravity.CENTER
        setPadding(dp(13), 0, dp(13), 0)
        minHeight = dp(28)
        isFocusable = true
        isClickable = true
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
    }

    private fun paint(focused: Boolean) {
        val (bg, fg, stroke) = when {
            focused -> Triple(p.accent, p.onAccent, 0)
            selectedChip -> Triple(p.text, p.base, 0)
            else -> Triple(0, p.sub1, dp(1.2))
        }
        background = rounded(bg, dp(14).toFloat(), stroke, p.o0)
        setTextColor(fg)
    }
}

/** A settings line: title and description on the left, a value or switch on the right. */
class SettingRow(ctx: Context, private val p: Palette, title: String, desc: String? = null) : LinearLayout(ctx) {
    val title: TextView = ctx.text(14f, p.text, MEDIUM, s = title)
    val desc: TextView = ctx.text(11f, p.sub0, s = desc ?: "")
    val value: TextView = ctx.text(12.5f, p.sub0)
    private val toggle = View(ctx)
    private val knob = View(ctx)
    private val toggleBox = FrameLayout(ctx)
    var on: Boolean? = null
        set(v) { field = v; paint(hasFocus()) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(9), dp(14), dp(9))
        minimumHeight = dp(46)
        val col = vlinear(ctx)
        col.addView(this.title)
        if (desc != null) col.addView(this.desc, llp(WRAP, WRAP) { topMargin = dp(3) })
        addView(col, llp(0, WRAP, 1f))
        addView(value, llp(WRAP, WRAP) { marginStart = dp(12) })
        toggleBox.addView(toggle, flp(MATCH, MATCH))
        toggleBox.addView(knob, FrameLayout.LayoutParams(dp(16), dp(16)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        addView(toggleBox, llp(dp(40), dp(22)) { marginStart = dp(12) })
        isFocusable = true
        isClickable = true
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
    }

    private fun paint(focused: Boolean) {
        background = if (focused) rounded(p.text, dp(10).toFloat()) else null
        title.setTextColor(if (focused) p.base else p.text)
        desc.setTextColor(if (focused) alpha(p.base, 0.75f) else p.sub0)
        value.setTextColor(if (focused) alpha(p.base, 0.8f) else p.sub0)
        val o = on
        toggleBox.visibility = if (o == null) GONE else VISIBLE
        if (o != null) {
            toggle.background = rounded(if (o) (if (focused) p.base else p.accent) else if (focused) alpha(p.base, 0.25f) else alpha(p.o0, 0.5f), dp(11).toFloat())
            knob.background = rounded(if (o) (if (focused) p.text else p.onAccent) else if (focused) p.base else p.sub1, dp(8).toFloat())
            // New LayoutParams so the knob actually moves (mutating margin alone often no-ops).
            knob.layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                gravity = Gravity.CENTER_VERTICAL or (if (o) Gravity.END else Gravity.START)
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
    }
}

/** A floating menu: a title, then actions. Back closes it. */
class MenuPanel(ctx: Context, p: Palette, title: String?, subtitle: String?, items: List<Item>, private val onClose: () -> Unit) : LinearLayout(ctx) {
    data class Item(val label: String, val right: String? = null, val danger: Boolean = false, val icon: Int? = null, val action: () -> Unit)

    private val first: View?

    init {
        orientation = VERTICAL
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = rounded(p.mantle, dp(14).toFloat(), dp(1), alpha(p.o0, 0.3f))
        elevation = dp(24).toFloat()
        if (title != null) addView(ctx.text(14f, p.text, MEDIUM, s = title), llp(WRAP, WRAP) { setMargins(dp(10), dp(6), dp(10), dp(2)) })
        if (subtitle != null) addView(ctx.text(11f, p.sub0, s = subtitle), llp(WRAP, WRAP) { setMargins(dp(10), 0, dp(10), dp(7)) })
        val scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(ctx).apply { orientation = VERTICAL }
        var f: View? = null
        for (it in items) {
            val row = hlinear(ctx).apply {
                setPadding(dp(12), 0, dp(12), 0)
                minimumHeight = dp(36)
                isFocusable = true
                isClickable = true
            }
            val icon = it.icon?.let { r -> ImageView(ctx).apply { setImageResource(r) } }
            icon?.let { iv -> row.addView(iv, llp(dp(16), dp(16)) { marginEnd = dp(10) }) }
            val label = ctx.text(13.5f, if (it.danger) p.red else p.sub1, s = it.label)
            row.addView(label, llp(0, WRAP, 1f))
            val right = it.right?.let { r -> ctx.text(11.5f, p.sub0, s = r) }
            right?.let { r -> row.addView(r) }
            fun paint(focused: Boolean) {
                row.background = if (focused) rounded(p.accent, dp(9).toFloat()) else null
                val fg = if (focused) p.onAccent else if (it.danger) p.red else p.sub1
                label.setTextColor(fg)
                right?.setTextColor(if (focused) p.onAccent else p.sub0)
                icon?.imageTintList = ColorStateList.valueOf(fg)
            }
            paint(false)
            row.setOnFocusChangeListener { _, focused -> paint(focused) }
            row.setOnClickListener { _ -> onClose(); it.action() }
            list.addView(row, llp(MATCH, WRAP))
            if (f == null) f = row
        }
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, llp(MATCH, WRAP))
        first = f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxH = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        val capped = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }

    fun focusFirst() = first?.requestFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onClose(); return true
        }
        return super.dispatchKeyEvent(event)
    }
}
