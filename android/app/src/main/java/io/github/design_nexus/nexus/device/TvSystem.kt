package io.github.design_nexus.nexus.device

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
        SCREENSAVER(listOf("com.google.android.tv.settings.ambient", Settings.ACTION_DREAM_SETTINGS)),
        ABOUT(listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        /** Power / energy / sleep screens — first match that resolves wins. */
        POWER(
            listOf(
                "android.settings.POWER_USAGE_SUMMARY",
                "com.android.tv.settings.action.POWER",
                "android.settings.DISPLAY_SETTINGS",
                Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SETTINGS,
            ),
        ),
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

    /**
     * Sync Android TV's system screensaver with Nexus's Ambient setting when
     * `WRITE_SECURE_SETTINGS` has been granted via ADB.
     */
    fun syncScreensaver(enabled: Boolean): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            val cr = context.contentResolver
            Settings.Secure.putInt(cr, "screensaver_enabled", if (enabled) 1 else 0)
            if (enabled) {
                Settings.Secure.putString(
                    cr,
                    "screensaver_components",
                    "${context.packageName}/.ambient.AmbientDreamService",
                )
            }
            true
        }.getOrDefault(false)
    }

    /**
     * HDMI and other inputs the TV input framework lists.
     * Dedupes identical labels (e.g. Apple TV listed twice) preferring a connected input.
     */
    fun inputs(): List<Input> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return runCatching {
            val raw = tim.tvInputList
                .filter { it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER }
                .map { info ->
                    val label = (runCatching { info.loadCustomLabel(context) }.getOrNull()
                        ?: info.loadLabel(context)).toString()
                    Input(info.id, label, info.type, info.isPassthroughInput)
                }
                .distinctBy { it.id }
            collapseDuplicateLabels(raw) { id ->
                runCatching { tim.getInputState(id) }.getOrNull() == TvInputManager.INPUT_STATE_CONNECTED
            }
        }.getOrDefault(emptyList())
    }

    /** Switches to [input] through the TV app that handles passthrough channels. */
    fun switchTo(input: Input): Boolean {
        val uri = if (input.passthrough) {
            TvContract.buildChannelUriForPassthroughInput(input.id)
        } else {
            TvContract.buildChannelsUriForInput(input.id)
        }
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(i) }.isSuccess
    }

    companion object {
        /** Keep first of each label; if duplicates, prefer [isConnected]. */
        internal fun collapseDuplicateLabels(
            inputs: List<Input>,
            isConnected: (String) -> Boolean,
        ): List<Input> {
            val byLabel = LinkedHashMap<String, Input>()
            for (input in inputs) {
                val key = input.label.trim().lowercase()
                val prev = byLabel[key]
                when {
                    prev == null -> byLabel[key] = input
                    isConnected(input.id) && !isConnected(prev.id) -> byLabel[key] = input
                    // else keep prev (first seen, or already connected)
                }
            }
            return byLabel.values.toList()
        }
    }
}
