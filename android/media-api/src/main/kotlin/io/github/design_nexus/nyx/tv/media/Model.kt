package io.github.design_nexus.nyx.tv.media

enum class MediaKind { MOVIE, SHOW, SEASON, EPISODE, COLLECTION, PLAYLIST }

/** A server of one provider: Plex's machine identifier, for example. */
data class ServerRef(val provider: String, val id: String)

data class Rating(val source: String, val value: Double, val scale: Double) {
    /** The rating as a percentage, 0–100. */
    val percent: Int get() = if (scale <= 0) 0 else (value / scale * 100).toInt()
}

/**
 * One title as the launcher shows it. Artwork fields are provider paths, not URLs:
 * [MediaProvider.imageUrl] turns them into URLs at the size a card needs.
 */
data class MediaItem(
    val server: ServerRef,
    val id: String,
    val kind: MediaKind,
    val title: String,
    val year: Int? = null,
    val summary: String = "",
    val tagline: String = "",
    val genres: List<String> = emptyList(),
    val contentRating: String? = null,
    val durationMs: Long = 0,
    val viewOffsetMs: Long = 0,
    val watched: Boolean = false,
    val unwatchedCount: Int? = null,
    val childCount: Int? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val parentId: String? = null,
    val seriesId: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val thumb: String? = null,
    val ratings: List<Rating> = emptyList(),
    val addedAt: Long = 0,
    val libraryId: String? = null,
) {
    val progress: Float
        get() = if (durationMs <= 0 || viewOffsetMs <= 0 || watched) 0f else (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val remainingMs: Long get() = (durationMs - viewOffsetMs).coerceAtLeast(0)

    /** "S1 · E5" for episodes, else null. */
    val episodeCode: String?
        get() = if (kind == MediaKind.EPISODE && seasonNumber != null && episodeNumber != null) "S$seasonNumber · E$episodeNumber" else null

    val key: String get() = "${server.provider}:${server.id}:$id"
}

data class Person(val name: String, val role: String? = null, val photo: String? = null)

data class MediaDetails(
    val item: MediaItem,
    val cast: List<Person> = emptyList(),
    val directors: List<String> = emptyList(),
    val studio: String? = null,
    /** Seasons of a show, episodes of a season, titles of a collection. */
    val children: List<MediaItem> = emptyList(),
    val related: List<MediaItem> = emptyList(),
)

data class Library(val server: ServerRef, val id: String, val title: String, val kind: MediaKind)

enum class RowSource { CONTINUE_WATCHING, NEXT_EPISODES, RECENT_MOVIES, RECENT_SHOWS, COLLECTIONS, PLAYLISTS, LIBRARY, GENRE }

data class RowQuery(
    val source: RowSource,
    val libraryId: String? = null,
    val genre: String? = null,
    val limit: Int = 20,
)

enum class SortOrder { RECENTLY_ADDED, TITLE, RELEASE_DATE, RATING }

data class Page(val items: List<MediaItem>, val total: Int, val offset: Int)

data class SearchResults(
    val movies: List<MediaItem> = emptyList(),
    val shows: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val collections: List<MediaItem> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty() && collections.isEmpty()
}
