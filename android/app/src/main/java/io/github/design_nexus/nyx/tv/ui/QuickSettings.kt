package io.github.design_nexus.nyx.tv.ui

import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.design_nexus.nyx.tv.R
import io.github.design_nexus.nyx.tv.device.TvSystem
import io.github.design_nexus.nyx.tv.ui.pages.SettingsPage
import java.util.Date

/** The compact panel on the right: network, Bluetooth, inputs, picture, sound, ambient, settings. */
class QuickSettings(private val a: HomeActivity) : FrameLayout(a) {
    private val p = a.palette
    private val tv = TvSystem(a)
    private val tiles = ArrayList<View>()
    private var first: View? = null
    private lateinit var panel: LinearLayout

    init {
        setBackgroundColor(alpha(p.base, 0.62f))
        isClickable = true
        isFocusable = false
        // Never let focus search leave this overlay into Home underneath.
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.mantle)
            setPadding(dp(26), dp(30), dp(26), dp(24))
            elevation = dp(20).toFloat()
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        panel.addView(a.text(32f, p.text, LIGHT, s = DateFormat.format(if (a.config.clock24h) "H:mm" else "h:mm", Date())))
        panel.addView(a.text(12.5f, p.sub0, s = DateFormat.format("EEEE, MMMM d", Date())), llp(WRAP, WRAP) { bottomMargin = dp(18) })
        val grid = GridLayout(a).apply { columnCount = 2 }
        fun tile(icon: Int, title: String, sub: String, on: Boolean = false, action: () -> Unit) {
            val t = LinearLayout(a).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                contentDescription = "$title. $sub"
            }
            val iv = ImageView(a).apply { setImageResource(icon) }
            val tt = a.text(12.5f, p.text, MEDIUM, s = title)
            val ts = a.text(10f, p.sub0, s = sub)
            t.addView(iv, llp(dp(18), dp(18)) { bottomMargin = dp(12) })
            t.addView(tt)
            t.addView(ts)
            val idle = if (on) mix(p.s0, p.accent, 0.22f) else p.s0
            fun paint(f: Boolean) {
                t.background = rounded(if (f) p.accent else idle, dp(12).toFloat())
                val fg = if (f) p.onAccent else p.text
                tt.setTextColor(fg); ts.setTextColor(if (f) alpha(p.onAccent, 0.75f) else p.sub0)
                iv.imageTintList = ColorStateList.valueOf(fg)
                val s = if (f) 1.04f else 1f
                t.scaleX = s; t.scaleY = s
            }
            paint(false)
            t.setOnFocusChangeListener { _, f -> paint(f) }
            t.setOnClickListener { action() }
            grid.addView(t, GridLayout.LayoutParams().apply { width = dp(150); height = dp(74); setMargins(0, 0, dp(9), dp(9)) })
            tiles += t
            if (first == null) first = t
        }
        tile(R.drawable.ic_wifi, "Network", "Wi-Fi and Ethernet", on = true) { a.closeOverlay(); tv.open(TvSystem.Screen.WIFI) }
        tile(R.drawable.ic_bt, "Bluetooth", "Remotes, speakers", on = true) { a.closeOverlay(); tv.open(TvSystem.Screen.BLUETOOTH) }
        tile(R.drawable.ic_input, "Inputs", "HDMI") { showInputs() }
        tile(R.drawable.ic_sound, "Sound", "Output and audio") { a.closeOverlay(); tv.open(TvSystem.Screen.SOUND) }
        tile(R.drawable.ic_image, "Ambient now", "Artwork and a clock") { a.closeOverlay(); a.startAmbient() }
        tile(R.drawable.ic_gear, "Nyx TV settings", "Home, rows, Plex") { a.closeOverlay(); a.push(SettingsPage(a, SettingsPage.Section.HOME)) }
        tile(R.drawable.ic_tv, "All TV settings", "The TV's own settings") { a.closeOverlay(); tv.open(TvSystem.Screen.ALL) }
        panel.addView(grid)
        val model = android.os.Build.MODEL.ifEmpty { "TV" }
        panel.addView(a.text(10.5f, p.sub0, s = "${android.os.Build.MANUFACTURER} $model · Nyx TV ${io.github.design_nexus.nyx.tv.BuildConfig.VERSION_NAME}"), llp(WRAP, WRAP) { topMargin = dp(10) })
        addView(panel, flp(dp(360), MATCH, Gravity.END))
        // Wire explicit neighbors so D-pad never searches outside the panel.
        tiles.forEachIndexed { i, v ->
            val col = i % 2
            val row = i / 2
            v.nextFocusLeftId = if (col == 0) v.id else tiles.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = if (col == 1) v.id else tiles.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusUpId = tiles.getOrNull(i - 2)?.id ?: v.id
            v.nextFocusDownId = tiles.getOrNull(i + 2)?.id ?: v.id
            // Left column: Left closes (panel is on the right edge of the screen).
            v.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (col == 0) { a.closeOverlay(); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> col == 1
                    KeyEvent.KEYCODE_DPAD_UP -> row == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> i + 2 >= tiles.size
                    else -> false
                }
            }
        }
    }

    fun focusFirst() = first?.requestFocus()

    private fun showInputs() {
        val list = tv.inputs().filter { it.passthrough }
        if (list.isEmpty()) {
            a.closeOverlay()
            if (!tv.open(TvSystem.Screen.ALL)) a.toast("This TV doesn't list its HDMI inputs to apps")
            return
        }
        a.showMenu(
            "Inputs",
            "HDMI inputs",
            list.map { i -> MenuPanel.Item(i.label, "HDMI") { if (!tv.switchTo(i)) a.toast("${i.label} couldn't be opened") } },
        )
    }

    override fun focusSearch(focused: View?, direction: Int): View {
        val next = super.focusSearch(focused, direction)
        if (next != null && isSelfOrDescendant(next)) return next
        if (direction == FOCUS_LEFT) {
            a.closeOverlay()
            return focused ?: this
        }
        return focused ?: first ?: this
    }

    private fun isSelfOrDescendant(v: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === this || cur === panel) return true
            cur = cur.parent as? View
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            a.closeOverlay(); return true
        }
        return super.dispatchKeyEvent(event)
    }
}
