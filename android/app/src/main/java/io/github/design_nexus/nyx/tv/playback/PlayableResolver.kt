package io.github.design_nexus.nyx.tv.playback

import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider

/**
 * Turns a show/season into the episode Details would Watch (resume, else next
 * unwatched, else first). Movies and episodes pass through.
 */
object PlayableResolver {
    sealed interface Result {
        data class Play(val item: MediaItem) : Result
        /** Not a leaf title — open details instead of playing. */
        data class OpenDetails(val item: MediaItem) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun resolve(provider: MediaProvider, item: MediaItem): Result = when (item.kind) {
        MediaKind.MOVIE, MediaKind.EPISODE -> Result.Play(item)
        MediaKind.SHOW, MediaKind.SEASON -> {
            val episode = runCatching { episodeFor(provider, item) }.getOrNull()
            when {
                episode != null -> Result.Play(episode)
                else -> Result.Failed("Couldn't find an episode to play")
            }
        }
        MediaKind.COLLECTION, MediaKind.PLAYLIST -> Result.OpenDetails(item)
    }

    private suspend fun episodeFor(provider: MediaProvider, item: MediaItem): MediaItem? {
        val season = when (item.kind) {
            MediaKind.SEASON -> item
            else -> pickSeason(provider.children(item))
        } ?: return null
        val episodes = provider.children(season)
        return episodes.firstOrNull { it.progress > 0 }
            ?: episodes.firstOrNull { !it.watched }
            ?: episodes.firstOrNull()
    }

    /** Same pick as DetailsPage: first season with unwatched (skip specials), else first real season. */
    private fun pickSeason(seasons: List<MediaItem>): MediaItem? =
        seasons.firstOrNull { (it.unwatchedCount ?: 0) > 0 && it.seasonNumber != 0 }
            ?: seasons.firstOrNull { it.seasonNumber != 0 }
            ?: seasons.firstOrNull()
}
