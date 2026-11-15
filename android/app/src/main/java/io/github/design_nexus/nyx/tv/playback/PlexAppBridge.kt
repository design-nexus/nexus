package io.github.design_nexus.nyx.tv.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.PlaybackBridge
import io.github.design_nexus.nyx.tv.media.PlaybackOutcome
import io.github.design_nexus.nyx.tv.plex.PlexLinks

/**
 * Plays through the official Plex app. Direct links into a title are experimental (off by
 * default) until they're verified on the TV; the reliable path opens Plex itself. See
 * docs/PLEX.md for what's been tested.
 */
class PlexAppBridge(
    private val context: Context,
    private val prefs: PlaybackPrefs,
) : PlaybackBridge {
    var directLinks: Boolean
        get() = prefs.directLinks
        set(v) { prefs.directLinks = v }

    val installed: Boolean
        get() = context.packageManager.getLeanbackLaunchIntentForPackage(PLEX) != null ||
            context.packageManager.getLaunchIntentForPackage(PLEX) != null

    override suspend fun play(item: MediaItem, fromStart: Boolean): PlaybackOutcome {
        if (!installed) return PlaybackOutcome.Failed("The Plex app isn't installed")
        if (directLinks) {
            for (c in PlexLinks.candidates(item, fromStart)) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(c.uri)).setPackage(PLEX).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (start(intent)) return PlaybackOutcome.Started(c.kind.name)
            }
        }
        val open = context.packageManager.getLeanbackLaunchIntentForPackage(PLEX)
            ?: context.packageManager.getLaunchIntentForPackage(PLEX)
        return if (open != null && start(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            PlaybackOutcome.Fallback("open-app", "Plex opened; find the title there")
        else PlaybackOutcome.Failed("Plex couldn't be opened")
    }

    private fun start(intent: Intent): Boolean = runCatching { context.startActivity(intent); true }.getOrDefault(false)

    companion object {
        const val PLEX = "com.plexapp.android"
    }
}
