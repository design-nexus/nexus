package io.github.design_nexus.nexus.ui

import android.content.Context
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
import io.github.design_nexus.nexus.R
import io.github.design_nexus.nexus.device.SleepTimer
import io.github.design_nexus.nexus.device.TvSystem
import java.util.Date

/** The compact panel on the right: network, Bluetooth, inputs, sleep timer, ambient, settings. */
class QuickSettings(
    context: Context,
    private val host: QuickSettingsHost,
) : FrameLayout(context) {
    private val p = host.palette
    private val tv = TvSystem(context)
    private val tiles = ArrayList<View>()
    private var first: View? = null
    private lateinit var panel: LinearLayout

    init {
        setBackgroundColor(alpha(p.base, 0.62f))
        isClickable = true
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.mantle)
            setPadding(dp(26), dp(30), dp(26), dp(24))
            elevation = dp(20).toFloat()
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        panel.addView(context.text(32f, p.text, LIGHT, s = DateFormat.format(if (host.clock24h) "H:mm" else "h:mm", Date())))
        panel.addView(context.text(12.5f, p.sub0, s = DateFormat.format("EEEE, MMMM d", Date())), llp(WRAP, WRAP) { bottomMargin = dp(18) })
        val grid = GridLayout(context).apply { columnCount = 2 }
        fun tile(icon: Int, title: String, sub: String, on: Boolean = false, action: () -> Unit) {
            val t = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                contentDescription = "$title. $sub"
            }
            val iv = ImageView(context).apply { setImageResource(icon) }
            val tt = context.text(12.5f, p.text, MEDIUM, s = title)
            val ts = context.text(10f, p.sub0, s = sub)
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
        tile(R.drawable.ic_wifi, "Network", "Wi-Fi and Ethernet", on = true) { host.closeOverlay(); tv.open(TvSystem.Screen.WIFI) }
        tile(R.drawable.ic_bt, "Bluetooth", "Remotes, speakers", on = true) { host.closeOverlay(); tv.open(TvSystem.Screen.BLUETOOTH) }
        tile(R.drawable.ic_input, "Inputs", "HDMI") { host.openInputs() }
        tile(R.drawable.ic_moon, "Sleep timer", SleepTimer.subtitle(), on = SleepTimer.isActive()) { showSleepTimer() }
        tile(R.drawable.ic_sound, "Sound", "Output and audio") { host.closeOverlay(); tv.open(TvSystem.Screen.SOUND) }
        tile(R.drawable.ic_image, "Ambient now", "Artwork and a clock") { host.closeOverlay(); host.startAmbient() }
        tile(R.drawable.ic_gear, "Nexus settings", "Home, rows, Plex") { host.openNexusSettings() }
        tile(R.drawable.ic_tv, "All TV settings", "The TV's own settings") { host.closeOverlay(); tv.open(TvSystem.Screen.ALL) }
        panel.addView(grid)
        val model = android.os.Build.MODEL.ifEmpty { "TV" }
        panel.addView(
            context.text(
                10.5f,
                p.sub0,
                s = "${android.os.Build.MANUFACTURER} $model · Nexus ${io.github.design_nexus.nexus.BuildConfig.VERSION_NAME}",
            ),
            llp(WRAP, WRAP) { topMargin = dp(10) },
        )
        addView(panel, flp(dp(360), MATCH, Gravity.END))
        tiles.forEachIndexed { i, v ->
            val col = i % 2
            val row = i / 2
            v.nextFocusLeftId = if (col == 0) v.id else tiles.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = if (col == 1) v.id else tiles.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusUpId = tiles.getOrNull(i - 2)?.id ?: v.id
            v.nextFocusDownId = tiles.getOrNull(i + 2)?.id ?: v.id
            v.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (col == 0) { host.closeOverlay(); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> col == 1
                    KeyEvent.KEYCODE_DPAD_UP -> row == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> i + 2 >= tiles.size
                    else -> false
                }
            }
        }
    }

    fun focusFirst() = first?.requestFocus()

    private fun showSleepTimer() {
        val items = ArrayList<MenuPanel.Item>()
        items += MenuPanel.Item("Off") {
            SleepTimer.cancel()
            host.toast("Sleep timer off")
        }
        for (m in SleepTimer.CHOICES_MIN) {
            val label = SleepTimer.formatDuration(m)
            items += MenuPanel.Item(label) {
                SleepTimer.schedule(m) { host.startSleepAmbient() }
                host.toast("Sleep timer · $label")
            }
        }
        if (tv.canOpen(TvSystem.Screen.POWER)) {
            items += MenuPanel.Item("TV power settings…") {
                host.closeOverlay()
                if (!tv.open(TvSystem.Screen.POWER)) host.toast("Couldn't open power settings")
            }
        }
        host.showMenu("Sleep timer", null, items)
    }

    override fun focusSearch(focused: View?, direction: Int): View {
        val next = super.focusSearch(focused, direction)
        if (next != null && isSelfOrDescendant(next)) return next
        if (direction == FOCUS_LEFT) {
            host.closeOverlay()
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
            host.closeOverlay(); return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION -> { host.openQuickSettings(); return true }
                KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_WINDOW -> { host.openInputs(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
