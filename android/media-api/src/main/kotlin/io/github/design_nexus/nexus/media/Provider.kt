package io.github.design_nexus.nexus.media

import kotlinx.coroutines.flow.StateFlow

sealed interface ProviderStatus {
    /** Not signed in or no server chosen. */
    data object Unconfigured : ProviderStatus
    data object Connecting : ProviderStatus
    data class Online(val serverName: String, val address: String) : ProviderStatus
    /** Unreachable; cached content is still shown. [retryAtMs] is wall-clock time of the next attempt. */
    data class Offline(val serverName: String, val reason: String, val retryAtMs: Long) : ProviderStatus
}

/**
 * A source of movies and shows. Plex is the only one today; the launcher only talks to
 * this interface so another provider can be added without changing the UI.
 *
 * Every call may throw [ProviderUnavailable] when the server can't be reached; callers
 * keep showing cached content.
 */
interface MediaProvider {
    val id: String
    val status: StateFlow<ProviderStatus>

    suspend fun libraries(): List<Library>
    suspend fun row(query: RowQuery): List<MediaItem>
    suspend fun browse(libraryId: String, sort: SortOrder, genre: String?, offset: Int, limit: Int): Page
    suspend fun genres(libraryId: String): List<String>
    suspend fun details(item: MediaItem): MediaDetails
    suspend fun children(item: MediaItem): List<MediaItem>
    suspend fun search(query: String, limit: Int): SearchResults
    suspend fun setWatched(item: MediaItem, watched: Boolean)

    /**
     * A URL for artwork at about [width] × [height] pixels, without credentials: the HTTP
     * client adds them for this provider's hosts, so tokens never reach caches or logs.
     */
    fun imageUrl(path: String?, width: Int, height: Int): String?

    /** A cache key for that artwork that doesn't change when the server's address does. */
    fun imageCacheKey(path: String?, width: Int, height: Int): String? = path?.let { "$id:$it:${width}x$height" }
}

class ProviderUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface PlaybackOutcome {
    data class Started(val method: String) : PlaybackOutcome
    /** The player opened, but not straight into playback (for example on the title's page). */
    data class Fallback(val method: String, val note: String) : PlaybackOutcome
    data class Failed(val reason: String) : PlaybackOutcome
}

/** Hands a title to a player (built-in Media3, or the official Plex app). */
interface PlaybackBridge {
    suspend fun play(item: MediaItem, fromStart: Boolean): PlaybackOutcome
}

enum class StreamMode { DIRECT, TRANSCODE }

enum class TimelineState { PLAYING, PAUSED, STOPPED }

/**
 * An ephemeral stream for one play session. May carry credentials in [headers] or the URL;
 * never log it or put it in a cache.
 */
data class StreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val sessionId: String,
    val mode: StreamMode,
    /** Absolute item position that corresponds to position 0 of this stream. */
    val streamOffsetMs: Long = 0,
    val durationMs: Long? = null,
)

data class TimelineUpdate(
    val itemId: String,
    val sessionId: String,
    val state: TimelineState,
    val positionMs: Long,
    val durationMs: Long? = null,
)

enum class MarkerKind { INTRO, CREDITS }

/** A span Plex marked for skipping (intro or credits). Times are absolute in the item. */
data class MediaMarker(val kind: MarkerKind, val startMs: Long, val endMs: Long)

enum class TrackKind { AUDIO, SUBTITLE }

data class MediaTrack(
    val id: Int,
    val kind: TrackKind,
    val title: String,
    val language: String? = null,
    val selected: Boolean = false,
)

data class Chapter(val title: String, val startMs: Long)

/**
 * Everything the player needs besides the stream URL: markers, tracks, chapters, title line.
 */
data class PlayInfo(
    val item: MediaItem,
    val markers: List<MediaMarker> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    /** Next episode when this item is an episode; null for movies or the last episode. */
    val next: MediaItem? = null,
    /** Previous episode when this item is an episode; null for movies or the first episode. */
    val previous: MediaItem? = null,
)
