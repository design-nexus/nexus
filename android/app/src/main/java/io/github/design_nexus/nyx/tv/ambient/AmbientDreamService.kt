package io.github.design_nexus.nyx.tv.ambient

import android.service.dreams.DreamService
import io.github.design_nexus.nyx.tv.NyxTvApp
import io.github.design_nexus.nyx.tv.ui.AmbientView
import io.github.design_nexus.nyx.tv.ui.Palette
import kotlinx.coroutines.runBlocking

/** Nyx TV Ambient as the TV's screensaver. The TV decides when it starts and when it sleeps. */
class AmbientDreamService : DreamService() {
    private var view: AmbientView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val app = application as NyxTvApp
        val c = if (app.config.loaded) app.config.config.value else runBlocking { app.config.load() }
        val v = AmbientView(this, Palette.of(c.theme, c.accent), c.ambient.source, c.ambient.showClock, c.clock24h)
        view = v
        setContentView(v)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val app = application as NyxTvApp
        view?.start(app.cache, app.plex, app.images)
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }
}
