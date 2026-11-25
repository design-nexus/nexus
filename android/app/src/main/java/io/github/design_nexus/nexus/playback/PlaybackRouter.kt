package io.github.design_nexus.nexus.playback

import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.PlaybackBridge
import io.github.design_nexus.nexus.media.PlaybackOutcome

/**
 * Chooses the built-in Media3 player or the official Plex app from [PlaybackPrefs].
 * When built-in is on, Media3 failures stay failures — never hand off to Plex.
 */
class PlaybackRouter(
    val prefs: PlaybackPrefs,
    private val media3: Media3Bridge,
    val plexApp: PlexAppBridge,
) : PlaybackBridge {
    override suspend fun play(item: MediaItem, fromStart: Boolean): PlaybackOutcome {
        if (prefs.useBuiltIn) return media3.play(item, fromStart)
        return plexApp.play(item, fromStart)
    }
}
