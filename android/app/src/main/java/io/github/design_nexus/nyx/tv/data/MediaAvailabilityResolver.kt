package io.github.design_nexus.nyx.tv.data

import io.github.design_nexus.nyx.tv.media.ContentActionState
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.SearchResults
import io.github.design_nexus.nyx.tv.seerr.SeerrMediaInfo
import io.github.design_nexus.nyx.tv.seerr.SeerrMediaStatus
import io.github.design_nexus.nyx.tv.seerr.SeerrRequestStatus
import kotlin.math.abs

object MediaAvailabilityResolver {

    /**
     * Searches [plex] for a matching playable item for [item].
     * Returns null if Plex is unconfigured, unreachable, or has no match.
     */
    suspend fun findInPlex(item: MediaItem, plex: MediaProvider): MediaItem? {
        if (item.server.provider == "plex") return item
        val results = runCatching { plex.search(item.title, 20) }.getOrNull() ?: return null
        val candidates = when (item.kind) {
            MediaKind.MOVIE -> results.movies
            MediaKind.SHOW -> results.shows
            else -> emptyList()
        }
        return findMatch(item, candidates)
    }

    fun findMatch(target: MediaItem, candidates: List<MediaItem>): MediaItem? {
        if (candidates.isEmpty()) return null
        target.tmdbId?.let { tid ->
            candidates.firstOrNull { it.tmdbId == tid }?.let { return it }
        }
        target.tvdbId?.let { tvdb ->
            candidates.firstOrNull { it.tvdbId == tvdb }?.let { return it }
        }
        target.imdbId?.let { imdb ->
            candidates.firstOrNull { it.imdbId.equals(imdb, ignoreCase = true) }?.let { return it }
        }
        val normTarget = normalizeTitle(target.title)
        return candidates.firstOrNull { c ->
            normalizeTitle(c.title) == normTarget && yearsCompatible(target.year, c.year)
        }
    }

    fun resolveActionState(
        plexItem: MediaItem?,
        seerrInfo: SeerrMediaInfo?,
    ): ContentActionState {
        if (plexItem != null) {
            return ContentActionState.AvailableInPlex(
                plexItem = plexItem,
                resume = plexItem.progress > 0f,
            )
        }
        if (seerrInfo == null) return ContentActionState.NotAvailable()

        val activePending = seerrInfo.requests.firstOrNull { it.status == SeerrRequestStatus.PENDING }
        if (activePending != null || seerrInfo.status == SeerrMediaStatus.PENDING) {
            return ContentActionState.PendingApproval(activePending?.id)
        }

        val activeApproved = seerrInfo.requests.firstOrNull { it.status == SeerrRequestStatus.APPROVED }
        if (activeApproved != null || seerrInfo.status == SeerrMediaStatus.PROCESSING) {
            return ContentActionState.ApprovedProcessing(activeApproved?.id, "Processing")
        }

        if (seerrInfo.status == SeerrMediaStatus.AVAILABLE) {
            // Seerr thinks it's available, but Plex didn't have it (stale metadata or unindexed).
            return ContentActionState.NotAvailable(staleSeerrAvailable = true)
        }

        return ContentActionState.NotAvailable(staleSeerrAvailable = false)
    }

    /**
     * Merges Plex and Seerr search results, preferring Plex items when both match so
     * playback is immediate and duplicates are removed.
     */
    fun mergeSearchResults(plex: SearchResults, seerr: SearchResults): SearchResults {
        val mergedMovies = plex.movies + seerr.movies.filter { findMatch(it, plex.movies) == null }
        val mergedShows = plex.shows + seerr.shows.filter { findMatch(it, plex.shows) == null }
        return SearchResults(
            movies = mergedMovies,
            shows = mergedShows,
            episodes = plex.episodes,
            collections = plex.collections,
        )
    }

    internal fun normalizeTitle(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun yearsCompatible(a: Int?, b: Int?): Boolean =
        a == null || b == null || abs(a - b) <= 1
}
