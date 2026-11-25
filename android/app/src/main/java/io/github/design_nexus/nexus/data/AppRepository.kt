package io.github.design_nexus.nexus.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    /** Opened through LEANBACK_LAUNCHER (a TV app) rather than the phone launcher category. */
    val tv: Boolean,
    val system: Boolean,
)

/** Installed apps the launcher can open, with banners loaded lazily. */
class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val art = LruCache<String, Drawable>(48)

    suspend fun load(): List<AppEntry> = withContext(Dispatchers.IO) {
        val self = context.packageName
        val found = LinkedHashMap<String, AppEntry>()
        fun add(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == self || ai.packageName in found) continue
                found[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    component = ComponentName(ai.packageName, ai.name),
                    tv = tv,
                    system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)
        found.values.sortedBy { it.label.lowercase() }
    }

    /** The app's TV banner, else its icon. Call off the main thread. */
    fun artwork(app: AppEntry): Drawable? {
        art.get(app.pkg)?.let { return it }
        val d = runCatching {
            pm.getActivityBanner(app.component) ?: pm.getApplicationBanner(app.pkg) ?: pm.getActivityIcon(app.component)
        }.getOrNull()
        if (d != null) art.put(app.pkg, d)
        return d
    }

    fun hasBanner(app: AppEntry): Boolean =
        runCatching { pm.getActivityBanner(app.component) != null || pm.getApplicationBanner(app.pkg) != null }.getOrDefault(false)

    fun forget(pkg: String) = art.remove(pkg)

    fun launch(app: AppEntry): Boolean {
        val intent = (if (app.tv) pm.getLeanbackLaunchIntentForPackage(app.pkg) else pm.getLaunchIntentForPackage(app.pkg))
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.component)
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    fun openInfo(pkg: String) = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun uninstall(pkg: String) = start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun isInstalled(pkg: String): Boolean = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun versionName(pkg: String): String? = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()

    fun start(intent: Intent): Boolean = try {
        context.startActivity(intent); true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        fun isHomeDefault(context: Context): Boolean {
            val ri = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            return ri?.activityInfo?.packageName == context.packageName
        }
    }
}
