package io.github.design_nexus.nexus.ambient

import android.service.dreams.DreamService
import io.github.design_nexus.nexus.NexusApp
import io.github.design_nexus.nexus.ui.AmbientView
import io.github.design_nexus.nexus.ui.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Nexus Ambient as the TV's screensaver. The TV decides when it starts and when it sleeps. */
class AmbientDreamService : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: AmbientView? = null
    private var dreaming = false
    private var attachJob: Job? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val app = application as NexusApp
        if (app.config.loaded) {
            mountView(app)
        } else {
            attachJob = scope.launch {
                app.config.load()
                mountView(app)
            }
        }
    }

    private fun mountView(app: NexusApp) {
        val c = app.config.config.value
        if (!c.ambient.enabled) {
            finish()
            return
        }
        val v = AmbientView(this, Palette.of(c.theme, c.accent), c.ambient.source, c.ambient.showClock, c.clock24h)
        view = v
        setContentView(v)
        if (dreaming) v.start(app.cache, app.plex, app.images)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        val app = application as NexusApp
        view?.start(app.cache, app.plex, app.images)
    }

    override fun onDreamingStopped() {
        dreaming = false
        attachJob?.cancel()
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
