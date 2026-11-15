package io.github.design_nexus.nyx.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.design_nexus.nyx.tv.core.ConfigCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Settings backups for TV Manager, over adb:
 *
 *   am broadcast -n <pkg>/.BackupReceiver -a io.github.design_nexus.nyx.tv.EXPORT
 *   adb pull /sdcard/Android/data/<pkg>/files/nyx-tv-settings.json
 *   adb push file /sdcard/Android/data/<pkg>/files/nyx-tv-settings.json
 *   am broadcast -n <pkg>/.BackupReceiver -a io.github.design_nexus.nyx.tv.IMPORT
 *
 * The receiver requires android.permission.DUMP, which the adb shell holds and ordinary
 * apps can't get. The file holds layout and preferences only: never the Plex session.
 */
class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NyxTvApp
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, FILE)
        val pending = goAsync()
        app.scope.launch(Dispatchers.IO) {
            try {
                app.config.load()
                when (intent.action) {
                    EXPORT -> {
                        file.writeText(app.config.export())
                        pending.resultCode = 1
                        pending.resultData = file.absolutePath
                    }
                    IMPORT -> {
                        val text = runCatching { file.readText() }.getOrNull()
                        if (text == null) {
                            pending.resultCode = 0
                            pending.resultData = "no file"
                        } else if (runCatching { JSONObject(text) }.isFailure) {
                            pending.resultCode = 0
                            pending.resultData = "invalid json"
                        } else {
                            val decoded = ConfigCodec.decode(text)
                            app.config.updateAndAwait { decoded }
                            file.delete()
                            pending.resultCode = 1
                            pending.resultData = "imported"
                        }
                    }
                }
                Log.i("NyxTV", "backup: ${intent.action} -> ${pending.resultData}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXPORT = "io.github.design_nexus.nyx.tv.EXPORT"
        const val IMPORT = "io.github.design_nexus.nyx.tv.IMPORT"
        const val FILE = "nyx-tv-settings.json"
    }
}
