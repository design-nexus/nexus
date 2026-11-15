package io.github.design_nexus.nyx.tv.device

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings

/**
 * The TV's own settings and inputs, reached only through public intents and the TV input
 * framework: nothing here needs a system permission. Where a direct control isn't
 * available, the matching settings screen opens instead.
 */
class TvSystem(private val context: Context) {
    data class Input(val id: String, val label: String, val type: Int, val passthrough: Boolean)

    enum class Screen(val actions: List<String>) {
        WIFI(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
        BLUETOOTH(listOf(Settings.ACTION_BLUETOOTH_SETTINGS)),
        PICTURE(listOf("android.settings.DISPLAY_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS)),
        SOUND(listOf(Settings.ACTION_SOUND_SETTINGS)),
        DATE(listOf(Settings.ACTION_DATE_SETTINGS)),
        SCREENSAVER(listOf(Settings.ACTION_DREAM_SETTINGS)),
        ABOUT(listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        ALL(listOf(Settings.ACTION_SETTINGS)),
    }

    fun canOpen(s: Screen): Boolean = s.actions.any { Intent(it).resolveActivity(context.packageManager) != null }

    fun open(s: Screen): Boolean {
        for (action in s.actions) {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    /** HDMI and other inputs the TV input framework lists. Empty when the TV doesn't expose them. */
    fun inputs(): List<Input> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return runCatching {
            tim.tvInputList.filter { it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER }.map { info ->
                val label = (runCatching { info.loadCustomLabel(context) }.getOrNull() ?: info.loadLabel(context)).toString()
                Input(info.id, label, info.type, info.isPassthroughInput)
            }
        }.getOrDefault(emptyList())
    }

    /** Switches to [input] through the TV app that handles passthrough channels. */
    fun switchTo(input: Input): Boolean {
        val uri = if (input.passthrough) TvContract.buildChannelUriForPassthroughInput(input.id) else TvContract.buildChannelsUriForInput(input.id)
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(i) }.isSuccess
    }
}
