package io.github.design_nexus.nyx.tv.data

import io.github.design_nexus.nyx.tv.core.RowConfig
import io.github.design_nexus.nyx.tv.core.RowType
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.ProviderUnavailable
import io.github.design_nexus.nyx.tv.media.RowQuery
import io.github.design_nexus.nyx.tv.media.RowSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Media rows: cached rows first, then fresh ones from the provider when it answers. */
class HomeRepository(private val provider: MediaProvider, private val cache: MetadataCache, private val scope: CoroutineScope) {

    fun source(row: RowConfig): RowSource? = when (row.type) {
        RowType.CONTINUE_WATCHING -> RowSource.CONTINUE_WATCHING
        RowType.NEXT_EPISODES -> RowSource.NEXT_EPISODES
        RowType.RECENT_MOVIES -> RowSource.RECENT_MOVIES
        RowType.RECENT_SHOWS -> RowSource.RECENT_SHOWS
        RowType.COLLECTIONS -> RowSource.COLLECTIONS
        RowType.PLAYLISTS -> RowSource.PLAYLISTS
        RowType.LIBRARY -> RowSource.LIBRARY
        RowType.GENRE -> RowSource.GENRE
        RowType.FAVORITE_APPS -> null
    }

    private fun key(row: RowConfig) = "${provider.id}:${row.type}:${row.libraryId}:${row.genre}:${row.limit}"

    fun cached(row: RowConfig): List<MediaItem>? = cache.get(key(row))

    /** Emits the cached row (if any), then the fresh one; on failure, just the cached one. */
    fun row(row: RowConfig, refresh: Boolean = true): Flow<List<MediaItem>> = flow {
        val src = source(row) ?: return@flow
        val cached = cache.get(key(row))
        if (cached != null) emit(cached)
        if (!refresh) return@flow
        try {
            val fresh = provider.row(RowQuery(src, row.libraryId, row.genre, row.limit))
            val pinned = if (row.pinned.isEmpty()) fresh else fresh.sortedBy { i -> row.pinned.indexOf(i.id).let { if (it < 0) Int.MAX_VALUE else it } }
            cache.put(key(row), pinned)
            if (pinned != cached) emit(pinned)
        } catch (_: ProviderUnavailable) {
        } catch (_: Exception) {
        }
    }.flowOn(Dispatchers.IO)

    /** The featured title: the newest movie or show with a backdrop, from cache when offline. */
    suspend fun featured(rows: List<RowConfig>): MediaItem? = withContext(Dispatchers.IO) {
        val candidates = rows.filter { it.type == RowType.RECENT_MOVIES || it.type == RowType.RECENT_SHOWS }
            .flatMap { cache.get(key(it)).orEmpty() }
        candidates.filter { it.backdrop != null && it.summary.isNotBlank() }.maxByOrNull { it.addedAt }
    }

    fun clear() = cache.clear()
}
