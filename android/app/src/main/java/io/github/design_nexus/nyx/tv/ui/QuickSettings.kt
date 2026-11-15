package io.github.design_nexus.nyx.tv.ui

import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    private var first: View? = null

    init {
        setBackgroundColor(alpha(p.base, 0.62f))
        isClickable = true
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.mantle)
            setPadding(dp(26), dp(30), dp(26), dp(24))
            elevation = dp(20).toFloat()
        }
        panel.addView(a.text(32f, p.text, LIGHT, s = DateFormat.format(if (a.config.clock24h) "H:mm" else "h:mm", Date())))
        panel.addView(a.text(12.5f, p.sub0, s = DateFormat.format("EEEE, MMMM d", Date())), llp(WRAP, WRAP) { bottomMargin = dp(18) })
        val grid = GridLayout(a).apply { columnCount = 2 }
        fun tile(icon: Int, title: String, sub: String, on: Boolean = false, action: () -> Unit) {
            val t = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
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
            if (first == null) first = t
        }
        tile(R.drawable.ic_wifi, "Network", "Wi-Fi and Ethernet", on = true) { a.closeOverlay(); tv.open(TvSystem.Screen.WIFI) }
        tile(R.drawable.ic_bt, "Bluetooth", "Remotes, speakers", on = true) { a.closeOverlay(); tv.open(TvSystem.Screen.BLUETOOTH) }
        tile(R.drawable.ic_input, "Inputs", "HDMI and Live TV") { showInputs() }
        tile(R.drawable.ic_picture, "Picture", "The TV's picture settings") { a.closeOverlay(); tv.open(TvSystem.Screen.PICTURE) }
        tile(R.drawable.ic_sound, "Sound", "Output and audio") { a.closeOverlay(); tv.open(TvSystem.Screen.SOUND) }
        tile(R.drawable.ic_image, "Ambient now", "Artwork and a clock") { a.closeOverlay(); a.startAmbient() }
        tile(R.drawable.ic_gear, "Nyx TV settings", "Home, rows, Plex") { a.closeOverlay(); a.push(SettingsPage(a, SettingsPage.Section.HOME)) }
        tile(R.drawable.ic_tv, "All TV settings", "The TV's own settings") { a.closeOverlay(); tv.open(TvSystem.Screen.ALL) }
        panel.addView(grid)
        val model = android.os.Build.MODEL.ifEmpty { "TV" }
        panel.addView(a.text(10.5f, p.sub0, s = "${android.os.Build.MANUFACTURER} $model · Nyx TV ${io.github.design_nexus.nyx.tv.BuildConfig.VERSION_NAME}"), llp(WRAP, WRAP) { topMargin = dp(10) })
        addView(panel, flp(dp(360), MATCH, Gravity.END))
    }

    fun focusFirst() = first?.requestFocus()

    private fun showInputs() {
        val list = tv.inputs()
        if (list.isEmpty()) {
            a.closeOverlay()
            if (!tv.open(TvSystem.Screen.ALL)) a.toast("This TV doesn't list its inputs to apps")
            return
        }
        a.showMenu("Inputs", "Names come from the TV, or HDMI-CEC when the device reports one",
            list.map { i -> MenuPanel.Item(i.label, if (i.passthrough) "HDMI" else "Tuner") { if (!tv.switchTo(i)) a.toast("${i.label} couldn't be opened") } })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { a.closeOverlay(); return true }
        return super.dispatchKeyEvent(event)
    }
}
