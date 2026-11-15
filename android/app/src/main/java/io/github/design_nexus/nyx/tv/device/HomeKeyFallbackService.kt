package io.github.design_nexus.nyx.tv.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import io.github.design_nexus.nyx.tv.data.AppRepository
import io.github.design_nexus.nyx.tv.ui.HomeActivity

/**
 * Optional helper for TVs that still open the stock home app after Nyx TV is set as HOME.
 * When enabled in system Accessibility settings, bringing launcherx / setupwraith to the
 * foreground reopens Nyx TV. Off by default; never auto-enables itself.
 */
class HomeKeyFallbackService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!prefs().getBoolean(PREF_ENABLED, false)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in STOCK_HOME) return
        // If Nyx is already the role holder, still bounce away from stock chrome that stole Home.
        val i = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .addCategory(Intent.CATEGORY_HOME)
        startActivity(i)
    }

    override fun onInterrupt() {}

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "a11y_fallback"
        const val PREF_ENABLED = "enabled"

        private val STOCK_HOME = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith",
        )

        fun isServiceEnabled(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val id = "${context.packageName}/${HomeKeyFallbackService::class.java.name}"
            return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.equals(id, ignoreCase = true) || it.resolveInfo?.serviceInfo?.name == HomeKeyFallbackService::class.java.name }
        }

        fun userWantsFallback(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

        fun setUserWantsFallback(context: android.content.Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, on).apply()
        }

        fun openAccessibilitySettings(context: android.content.Context) {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        fun needed(context: android.content.Context): Boolean = !AppRepository.isHomeDefault(context)
    }
}
