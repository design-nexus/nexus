package io.github.design_nexus.nexus.playback

import android.content.Context
import android.content.Intent
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.PlaybackBridge
import io.github.design_nexus.nexus.media.PlaybackOutcome

/** Starts the built-in Media3 player for movies and episodes. */
class Media3Bridge(private val context: Context) : PlaybackBridge {
    override suspend fun play(item: MediaItem, fromStart: Boolean): PlaybackOutcome {
        if (item.kind != MediaKind.MOVIE && item.kind != MediaKind.EPISODE) {
            return PlaybackOutcome.Failed("Open the title and pick an episode to play")
        }
        val title = when {
            item.kind == MediaKind.EPISODE && item.seriesTitle != null ->
                "${item.seriesTitle} · ${item.episodeCode ?: item.title}"
            else -> item.title
        }
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
            .putExtra(PlayerActivity.EXTRA_RESUME_MS, if (fromStart) 0L else item.viewOffsetMs)
            .putExtra(PlayerActivity.EXTRA_DURATION_MS, item.durationMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            PlaybackOutcome.Started("media3")
        } catch (_: Exception) {
            PlaybackOutcome.Failed("The player couldn't open")
        }
    }
}
