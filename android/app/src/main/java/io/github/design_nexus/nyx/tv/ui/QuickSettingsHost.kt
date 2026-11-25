package io.github.design_nexus.nyx.tv.ui

/**
 * Host for [QuickSettings]: Home shows it over the launcher; the player shows it over
 * video so playback can keep running.
 */
interface QuickSettingsHost {
    val palette: Palette
    val clock24h: Boolean
    fun closeOverlay()
    fun openQuickSettings()
    fun openInputs()
    fun showMenu(title: String?, subtitle: String?, items: List<MenuPanel.Item>, anchorStart: Int = 0, anchorTop: Int = 0)
    fun toast(msg: String)
    fun startAmbient()
    fun startSleepAmbient()
    fun openNyxSettings()
}
