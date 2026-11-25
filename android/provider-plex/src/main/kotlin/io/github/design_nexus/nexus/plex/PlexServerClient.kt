package io.github.design_nexus.nexus.plex

import io.github.design_nexus.nexus.media.Chapter
import io.github.design_nexus.nexus.media.Library
import io.github.design_nexus.nexus.media.MarkerKind
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.MediaMarker
import io.github.design_nexus.nexus.media.MediaTrack
import io.github.design_nexus.nexus.media.Page
import io.github.design_nexus.nexus.media.PlayInfo
import io.github.design_nexus.nexus.media.SearchResults
import io.github.design_nexus.nexus.media.ServerRef
import io.github.design_nexus.nexus.media.SortOrder
import io.github.design_nexus.nexus.media.StreamMode
import io.github.design_nexus.nexus.media.StreamSource
import io.github.design_nexus.nexus.media.TimelineState
import io.github.design_nexus.nexus.media.TimelineUpdate
import io.github.design_nexus.nexus.media.TrackKind
import io.github.design_nexus.nexus.plex.PlexParser.objects
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID

/**
 * One Plex Media Server at one address. Every path here is a Plex Media Server endpoint;
 * see docs/PLEX.md for which are in Plex's published API and which are only observed.
 */
class PlexServerClient(
    private val http: PlexHttp,
    val base: HttpUrl,
    private val token: String,
    val ref: ServerRef,
    var name: String = "Plex",
) {
    private fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        require(PlexHttp.relativeKey(path)) { "bad path" }
        val b = base.newBuilder().encodedPath(path)
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    fun get(path: String, query: List<Pair<String, String>> = emptyList(), client: OkHttpClient = http.client): Any =
        http.json(http.request(url(path, query), token).get().build(), client)

    private fun page(offset: Int, limit: Int) = listOf("X-Plex-Container-Start" to "$offset", "X-Plex-Container-Size" to "$limit")

    /** The server's identity: (machineIdentifier, friendlyName). */
    fun identity(probe: Boolean = false): Pair<String, String> {
        val c = PlexParser.container(get("/", client = if (probe) http.probe else http.client))
        val id = c.optString("machineIdentifier").ifEmpty { throw PlexBadResponse("No server identity") }
        return id to c.optString("friendlyName", "Plex")
    }

    fun libraries(): List<Library> = PlexParser.container(get("/library/sections")).optJSONArray("Directory").objects().mapNotNull { d ->
        val kind = when (d.optString("type")) {
            "movie" -> MediaKind.MOVIE
            "show" -> MediaKind.SHOW
            else -> return@mapNotNull null
        }
        val key = d.optString("key").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
        Library(ref, key, d.optString("title", "Library"), kind)
    }

    /** Continue Watching, trying the newer hub first and falling back to On Deck. */
    fun continueWatching(limit: Int): List<MediaItem> {
        for (path in listOf("/hubs/continueWatching/items", "/hubs/home/continueWatching")) {
            val items = try {
                val o = get(path, page(0, limit))
                PlexParser.items(o, ref).ifEmpty { hubItems(o) }
            } catch (e: PlexBadResponse) {
                continue
            }
            if (items.isNotEmpty()) return items.take(limit)
        }
        return onDeck(limit).filter { it.viewOffsetMs > 0 || it.kind == MediaKind.EPISODE }.take(limit)
    }

    fun onDeck(limit: Int): List<MediaItem> = PlexParser.items(get("/library/onDeck", page(0, limit)), ref)

    private fun hubItems(o: Any): List<MediaItem> =
        PlexParser.container(o).optJSONArray("Hub").objects().flatMap { h -> h.optJSONArray("Metadata").objects().mapNotNull { PlexParser.item(it, ref) } }

    fun recentlyAdded(library: Library, limit: Int): List<MediaItem> =
        browse(library, SortOrder.RECENTLY_ADDED, null, 0, limit).items

    fun browse(library: Library, sort: SortOrder, genreKey: String?, offset: Int, limit: Int): Page {
        val q = ArrayList<Pair<String, String>>()
        q += "type" to if (library.kind == MediaKind.SHOW) "2" else "1"
        q += "sort" to when (sort) {
            SortOrder.RECENTLY_ADDED -> "addedAt:desc"
            SortOrder.TITLE -> "titleSort"
            SortOrder.RELEASE_DATE -> "originallyAvailableAt:desc"
            SortOrder.RATING -> "audienceRating:desc"
        }
        if (genreKey != null) q += "genre" to genreKey
        q += "includeGuids" to "1"
        q += page(offset, limit)
        val o = get("/library/sections/${library.id}/all", q)
        val c = PlexParser.container(o)
        val items = PlexParser.items(o, ref).map { it.copy(libraryId = it.libraryId ?: library.id) }
        return Page(items, c.optInt("totalSize", c.optInt("size", items.size)), offset)
    }

    /** Genres of a library: title to the key `browse` filters by. */
    fun genres(library: Library): Map<String, String> =
        PlexParser.container(get("/library/sections/${library.id}/genre")).optJSONArray("Directory").objects()
            .mapNotNull { d -> d.optString("title").ifEmpty { null }?.let { it to d.optString("key") } }
            .filter { it.second.isNotEmpty() && it.second.all(Char::isDigit) }
            .toMap()

    fun collections(library: Library, limit: Int): List<MediaItem> =
        PlexParser.items(get("/library/sections/${library.id}/collections", page(0, limit)), ref)

    /** Video playlists on this server (audio playlists are skipped). */
    fun playlists(limit: Int): List<MediaItem> =
        PlexParser.items(
            get("/playlists", page(0, limit) + listOf("playlistType" to "video")),
            ref,
        )

    fun playlist(id: String): JSONObject? {
        require(isDigits(id))
        return PlexParser.container(get("/playlists/$id")).optJSONArray("Metadata").objects().firstOrNull()
    }

    fun metadata(id: String, includeMarkers: Boolean = false): JSONObject? {
        require(isDigits(id))
        val q = ArrayList<Pair<String, String>>()
        q += "includeRelated" to "1"
        q += "includeExtras" to "0"
        q += "includeGuids" to "1"
        if (includeMarkers) q += "includeMarkers" to "1"
        return PlexParser.container(get("/library/metadata/$id", q)).optJSONArray("Metadata").objects().firstOrNull()
    }

    fun children(item: MediaItem): List<MediaItem> {
        require(isDigits(item.id))
        val path = when (item.kind) {
            MediaKind.COLLECTION -> "/library/collections/${item.id}/children"
            MediaKind.PLAYLIST -> "/playlists/${item.id}/items"
            else -> "/library/metadata/${item.id}/children"
        }
        return PlexParser.items(get(path), ref)
    }

    fun search(query: String, limit: Int): SearchResults {
        val o = get("/hubs/search", listOf("query" to query, "limit" to "$limit", "includeCollections" to "1", "includeGuids" to "1"))
        val all = PlexParser.container(o).optJSONArray("Hub").objects().flatMap { h -> h.optJSONArray("Metadata").objects().mapNotNull { PlexParser.item(it, ref) } }
        return SearchResults(
            movies = all.filter { it.kind == MediaKind.MOVIE },
            shows = all.filter { it.kind == MediaKind.SHOW },
            episodes = all.filter { it.kind == MediaKind.EPISODE },
            collections = all.filter { it.kind == MediaKind.COLLECTION },
        )
    }

    fun setWatched(id: String, watched: Boolean) {
        require(isDigits(id))
        get(if (watched) "/:/scrobble" else "/:/unscrobble", listOf("key" to id, "identifier" to "com.plexapp.plugins.library"))
    }

    /**
     * Artwork URL with no token in the query (the HTTP client adds the header).
     * Collection/playlist composites are served directly — `/photo/:/transcode` often
     * returns a blank image for those paths.
     */
    fun imageUrl(path: String, width: Int, height: Int): String? {
        if (!PlexHttp.relativeKey(path)) return null
        if (!path.startsWith("/library/") && !path.startsWith("/playlists/")) return null
        if ("/composite/" in path) {
            return base.newBuilder().encodedPath(path).build().toString()
        }
        return base.newBuilder().encodedPath("/photo/:/transcode")
            .addQueryParameter("url", path).addQueryParameter("width", "$width").addQueryParameter("height", "$height")
            .addQueryParameter("minSize", "1").addQueryParameter("upscale", "1").build().toString()
    }

    /** Markers, tracks, chapters and next episode for the player chrome. */
    fun playInfo(itemId: String): PlayInfo {
        require(isDigits(itemId))
        val meta = metadata(itemId, includeMarkers = true) ?: throw PlexBadResponse("Title not found")
        val item = PlexParser.item(meta, ref) ?: throw PlexBadResponse("Title not found")
        val part = firstPart(meta)
        val streams = part?.let { p ->
            meta.optJSONArray("Media").objects().getOrNull(p.mediaIndex)
                ?.optJSONArray("Part")?.objects()?.getOrNull(p.partIndex)
                ?.optJSONArray("Stream").objects().orEmpty()
        }.orEmpty()
        val audio = streams.mapNotNull { s ->
            if (s.optInt("streamType") != 2) return@mapNotNull null
            val id = s.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            MediaTrack(
                id = id,
                kind = TrackKind.AUDIO,
                title = s.optString("displayTitle").ifEmpty { s.optString("language", "Audio") },
                language = s.optString("languageCode").ifEmpty { s.optString("language").ifEmpty { null } },
                selected = s.optInt("selected", 0) == 1 || s.optBoolean("selected", false),
            )
        }
        val subs = streams.mapNotNull { s ->
            if (s.optInt("streamType") != 3) return@mapNotNull null
            val id = s.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            MediaTrack(
                id = id,
                kind = TrackKind.SUBTITLE,
                title = s.optString("displayTitle").ifEmpty { s.optString("language", "Subtitle") },
                language = s.optString("languageCode").ifEmpty { s.optString("language").ifEmpty { null } },
                selected = s.optInt("selected", 0) == 1 || s.optBoolean("selected", false),
            )
        }
        val markers = meta.optJSONArray("Marker").objects().mapNotNull { m ->
            val kind = when (m.optString("type")) {
                "intro" -> MarkerKind.INTRO
                "credits" -> MarkerKind.CREDITS
                else -> return@mapNotNull null
            }
            MediaMarker(kind, m.optLong("startTimeOffset"), m.optLong("endTimeOffset"))
        }.filter { it.endMs > it.startMs }
        val chapters = meta.optJSONArray("Chapter").objects().mapNotNull { c ->
            val start = c.optLong("startTimeOffset", -1)
            if (start < 0) return@mapNotNull null
            Chapter(c.optString("tag").ifEmpty { "Chapter" }, start)
        }
        val (prev, next) = adjacentEpisodes(item, meta)
        return PlayInfo(item = item, markers = markers, audioTracks = audio, subtitleTracks = subs, chapters = chapters, next = next, previous = prev)
    }

    /**
     * Resolve a playable stream for [itemId]. Direct play uses the first Media/Part; transcode
     * asks the server for a progressive Matroska (same approach as Theater).
     */
    fun resolve(
        itemId: String,
        positionMs: Long,
        mode: StreamMode,
        audioStreamId: Int? = null,
        subtitleStreamId: Int? = null,
    ): StreamSource {
        require(isDigits(itemId))
        val meta = metadata(itemId) ?: throw PlexBadResponse("Title not found")
        val part = firstPart(meta) ?: throw PlexBadResponse("No playable media")
        val duration = meta.optLong("duration", 0).takeIf { it > 0 }
        val session = UUID.randomUUID().toString()
        return when (mode) {
            StreamMode.DIRECT -> {
                val path = part.key
                require(PlexHttp.relativeKey(path) && path.startsWith("/library/parts/")) { "bad part" }
                val q = ArrayList<Pair<String, String>>()
                if (audioStreamId != null) q += "audioStreamID" to "$audioStreamId"
                if (subtitleStreamId != null) q += "subtitleStreamID" to "$subtitleStreamId"
                else q += "subtitle" to "none"
                StreamSource(
                    url = url(path, q).toString(),
                    headers = mapOf("X-Plex-Token" to token),
                    sessionId = session,
                    mode = StreamMode.DIRECT,
                    streamOffsetMs = 0,
                    durationMs = duration,
                )
            }
            StreamMode.TRANSCODE -> {
                val query = transcodeQuery(itemId, part.mediaIndex, part.partIndex, positionMs, session, audioStreamId, subtitleStreamId)
                val decision = PlexParser.container(
                    http.json(
                        http.request(url("/video/:/transcode/universal/decision", query), token)
                            .header("X-Plex-Session-Identifier", session).get().build()
                    )
                )
                val code = decision.optInt("generalDecisionCode", decision.optInt("transcodeDecisionCode", -1))
                if (code !in 1000 until 2000) throw PlexBadResponse("Plex can't convert this video")
                StreamSource(
                    url = url("/video/:/transcode/universal/start.mkv", query).toString(),
                    headers = mapOf("X-Plex-Token" to token, "X-Plex-Session-Identifier" to session),
                    sessionId = session,
                    mode = StreamMode.TRANSCODE,
                    streamOffsetMs = positionMs,
                    durationMs = duration,
                )
            }
        }
    }

    private fun adjacentEpisodes(item: MediaItem, meta: JSONObject): Pair<MediaItem?, MediaItem?> {
        if (item.kind != MediaKind.EPISODE) return null to null
        val seasonId = item.parentId ?: return null to null
        val siblings = try { children(item.copy(id = seasonId, kind = MediaKind.SEASON)) } catch (_: Exception) { emptyList() }
        val idx = siblings.indexOfFirst { it.id == item.id }
        var prev: MediaItem? = if (idx > 0) siblings[idx - 1] else null
        var next: MediaItem? = if (idx >= 0 && idx + 1 < siblings.size) siblings[idx + 1] else null
        if (prev != null && next != null) return prev to next

        val showId = item.seriesId ?: meta.opt("grandparentRatingKey")?.toString()?.takeIf { isDigits(it) } ?: return prev to next
        val seasons = try { children(MediaItem(ref, showId, MediaKind.SHOW, title = "")) } catch (_: Exception) { emptyList() }
        val si = seasons.indexOfFirst { it.id == seasonId }
        if (si < 0) return prev to next
        if (prev == null && si > 0) {
            prev = try { children(seasons[si - 1]).lastOrNull() } catch (_: Exception) { null }
        }
        if (next == null && si + 1 < seasons.size) {
            next = try { children(seasons[si + 1]).firstOrNull() } catch (_: Exception) { null }
        }
        return prev to next
    }

    fun timeline(update: TimelineUpdate) {
        require(isDigits(update.itemId))
        val state = when (update.state) {
            TimelineState.PLAYING -> "playing"
            TimelineState.PAUSED -> "paused"
            TimelineState.STOPPED -> "stopped"
        }
        http.json(
            http.request(
                url(
                    "/:/timeline",
                    listOf(
                        "key" to "/library/metadata/${update.itemId}",
                        "ratingKey" to update.itemId,
                        "state" to state,
                        "time" to "${update.positionMs}",
                        "duration" to "${update.durationMs ?: 0}",
                    )
                ),
                token
            ).header("X-Plex-Session-Identifier", update.sessionId).post(okhttp3.RequestBody.EMPTY).build()
        )
    }

    fun stopTranscode(sessionId: String) {
        if (sessionId.isBlank()) return
        try {
            get("/video/:/transcode/universal/stop", listOf("session" to sessionId))
        } catch (_: PlexBadResponse) {
            // Already finished.
        }
    }

    private fun isDigits(s: String): Boolean = s.isNotEmpty() && s.all(Char::isDigit)

    private fun transcodeQuery(
        itemId: String,
        mediaIndex: Int,
        partIndex: Int,
        positionMs: Long,
        session: String,
        audioStreamId: Int? = null,
        subtitleStreamId: Int? = null,
    ): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        q += "path" to "/library/metadata/$itemId"
        q += "mediaIndex" to "$mediaIndex"
        q += "partIndex" to "$partIndex"
        q += "session" to session
        q += "transcodeSessionId" to session
        q += "protocol" to "http"
        q += "directPlay" to "0"
        q += "directStream" to "0"
        q += "directStreamAudio" to "0"
        q += "offset" to java.lang.String.format(java.util.Locale.US, "%.3f", positionMs / 1000.0)
        q += "videoResolution" to "1920x1080"
        q += "videoBitrate" to "8000"
        q += "audioChannelCount" to "2"
        q += "subtitles" to if (subtitleStreamId != null) "burn" else "none"
        q += "advancedSubtitles" to "text"
        if (audioStreamId != null) q += "audioStreamID" to "$audioStreamId"
        if (subtitleStreamId != null) q += "subtitleStreamID" to "$subtitleStreamId"
        q += "X-Plex-Client-Profile-Name" to "Generic"
        q += "X-Plex-Client-Profile-Extra" to
            "add-transcode-target(type=videoProfile&context=streaming&protocol=http&container=mkv&videoCodec=h264&audioCodec=aac&subtitleCodec=srt&replace=true)"
        q += "X-Plex-Client-Identifier" to http.info.clientId
        q += "X-Plex-Session-Identifier" to session
        q += "X-Plex-Product" to http.info.product
        return q
    }

    private data class PartRef(val key: String, val mediaIndex: Int, val partIndex: Int)

    private fun firstPart(meta: JSONObject): PartRef? {
        val media = meta.optJSONArray("Media").objects()
        media.forEachIndexed { mi, m ->
            m.optJSONArray("Part").objects().forEachIndexed { pi, p ->
                val key = p.optString("key")
                if (PlexHttp.relativeKey(key) && key.startsWith("/library/parts/")) return PartRef(key, mi, pi)
            }
        }
        return null
    }
}
