package io.github.design_nexus.nyx.tv.playback

import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.PlaybackBridge
import io.github.design_nexus.nyx.tv.media.PlaybackOutcome

/**
 * Chooses the built-in Media3 player or the official Plex app from [PlaybackPrefs].
 */
class PlaybackRouter(
    val prefs: PlaybackPrefs,
    private val media3: Media3Bridge,
    val plexApp: PlexAppBridge,
) : PlaybackBridge {
    override suspend fun play(item: MediaItem, fromStart: Boolean): PlaybackOutcome {
        if (prefs.useBuiltIn) {
            val r = media3.play(item, fromStart)
            if (r !is PlaybackOutcome.Failed) return r
        }
        return plexApp.play(item, fromStart)
    }
}
