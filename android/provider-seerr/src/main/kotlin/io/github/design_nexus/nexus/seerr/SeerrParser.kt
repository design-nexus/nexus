package io.github.design_nexus.nexus.seerr

import io.github.design_nexus.nexus.media.MediaDetails
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.Person
import io.github.design_nexus.nexus.media.Rating
import io.github.design_nexus.nexus.media.SearchResults
import io.github.design_nexus.nexus.media.ServerRef
import org.json.JSONArray
import org.json.JSONObject

object SeerrParser {
    fun status(o: Any?): SeerrStatusResult {
        val obj = o as? JSONObject ?: throw SeerrUnavailableException("Invalid status payload")
        return SeerrStatusResult(
            version = obj.optString("version", "unknown").ifEmpty { "unknown" },
            commitTag = obj.optStr("commitTag"),
            updateAvailable = obj.optBoolean("updateAvailable", false),
        )
    }

    fun user(o: Any?): SeerrUser {
        val obj = o as? JSONObject ?: throw SeerrUnavailableException("Invalid user payload")
        val id = obj.optInt("id", -1)
        if (id < 0) throw SeerrAuthExpiredException("No authenticated Seerr user")
        val name = obj.optStr("displayName")
            ?: obj.optStr("plexUsername")
            ?: obj.optStr("username")
            ?: obj.optStr("email")
            ?: "Seerr User"
        return SeerrUser(
            id = id,
            displayName = name,
            email = obj.optStr("email"),
            plexUsername = obj.optStr("plexUsername"),
            permissions = obj.optLong("permissions", 0L),
            avatar = obj.optStr("avatar"),
        )
    }

    fun genres(o: Any?): List<SeerrGenre> {
        val arr = o as? JSONArray ?: return emptyList()
        return arr.objects().mapNotNull { g ->
            val id = g.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val name = g.optStr("name") ?: return@mapNotNull null
            SeerrGenre(id, name)
        }
    }

    fun discoverItems(
        o: Any?,
        server: ServerRef,
        forcedKind: MediaKind? = null,
        genreMap: Map<Int, String> = emptyMap(),
    ): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> {
        val obj = o as? JSONObject ?: return emptyList<MediaItem>() to emptyMap()
        val infoMap = HashMap<String, SeerrMediaInfo>()
        val items = obj.optJSONArray("results").objects().mapNotNull { r ->
            val item = summaryItem(r, server, forcedKind, genreMap) ?: return@mapNotNull null
            mediaInfo(r.optJSONObject("mediaInfo"), item.tmdbId ?: 0)?.let { infoMap[item.key] = it }
            item
        }
        return items to infoMap
    }

    fun searchResults(
        o: Any?,
        server: ServerRef,
        genreMap: Map<Int, String> = emptyMap(),
    ): Pair<SearchResults, Map<String, SeerrMediaInfo>> {
        val obj = o as? JSONObject ?: return SearchResults() to emptyMap()
        val infoMap = HashMap<String, SeerrMediaInfo>()
        val movies = ArrayList<MediaItem>()
        val shows = ArrayList<MediaItem>()
        for (r in obj.optJSONArray("results").objects()) {
            val type = r.optStr("mediaType")
            if (type != "movie" && type != "tv") continue
            val item = summaryItem(r, server, null, genreMap) ?: continue
            mediaInfo(r.optJSONObject("mediaInfo"), item.tmdbId ?: 0)?.let { infoMap[item.key] = it }
            if (item.kind == MediaKind.MOVIE) movies += item else if (item.kind == MediaKind.SHOW) shows += item
        }
        return SearchResults(movies = movies, shows = shows) to infoMap
    }

    fun summaryItem(
        r: JSONObject,
        server: ServerRef,
        forcedKind: MediaKind? = null,
        genreMap: Map<Int, String> = emptyMap(),
    ): MediaItem? {
        val tmdbId = r.optInt("id", -1).takeIf { it > 0 } ?: return null
        val kind = forcedKind ?: when (r.optStr("mediaType")) {
            "movie" -> MediaKind.MOVIE
            "tv" -> MediaKind.SHOW
            else -> if (r.has("firstAirDate") || r.has("name")) MediaKind.SHOW else MediaKind.MOVIE
        }
        val title = if (kind == MediaKind.SHOW) {
            r.optStr("name") ?: r.optStr("originalName") ?: r.optStr("title") ?: "Untitled"
        } else {
            r.optStr("title") ?: r.optStr("originalTitle") ?: r.optStr("name") ?: "Untitled"
        }
        val dateStr = if (kind == MediaKind.SHOW) r.optStr("firstAirDate") else r.optStr("releaseDate")
        val year = dateStr?.take(4)?.toIntOrNull()
        val genres = r.optJSONArray("genreIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { genreMap[arr.optInt(it, -1)] }
        } ?: r.optJSONArray("genres").objects().mapNotNull { it.optStr("name") }
        val vote = r.optDouble("voteAverage", Double.NaN)
        val ratings = if (!vote.isNaN() && vote > 0.0) listOf(Rating("tmdb", vote, 10.0)) else emptyList()
        val kindPrefix = if (kind == MediaKind.SHOW) "tv" else "movie"
        return MediaItem(
            server = server,
            id = "$kindPrefix:$tmdbId",
            kind = kind,
            title = title,
            year = year,
            summary = r.optStr("overview") ?: "",
            genres = genres,
            poster = r.optStr("posterPath"),
            backdrop = r.optStr("backdropPath"),
            ratings = ratings,
            tmdbId = tmdbId,
        )
    }

    fun movieDetails(o: Any?, server: ServerRef): SeerrDetailBundle? {
        val obj = o as? JSONObject ?: return null
        val tmdbId = obj.optInt("id", -1).takeIf { it > 0 } ?: return null
        val base = summaryItem(obj, server, MediaKind.MOVIE) ?: return null
        val runtimeMin = obj.optLong("runtime", 0L)
        val imdbId = obj.optStr("imdbId") ?: obj.optJSONObject("externalIds")?.optStr("imdbId")
        val item = base.copy(
            tagline = obj.optStr("tagline") ?: "",
            durationMs = runtimeMin * 60_000L,
            imdbId = imdbId,
        )
        val credits = obj.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast").objects().take(20).mapNotNull { c ->
            val name = c.optStr("name") ?: return@mapNotNull null
            Person(name, c.optStr("character"), c.optStr("profilePath"))
        }
        val directors = credits?.optJSONArray("crew").objects()
            .filter { it.optStr("job") == "Director" }
            .mapNotNull { it.optStr("name") }
        val studio = obj.optJSONArray("productionCompanies").objects().firstOrNull()?.optStr("name")
        val related = obj.optJSONObject("recommendations")?.optJSONArray("results").objects()
            .mapNotNull { summaryItem(it, server, MediaKind.MOVIE) }
            .take(20)
        val info = mediaInfo(obj.optJSONObject("mediaInfo"), tmdbId)
        return SeerrDetailBundle(
            details = MediaDetails(
                item = item,
                cast = cast,
                directors = directors,
                studio = studio,
                related = related,
            ),
            mediaInfo = info,
        )
    }

    fun tvDetails(o: Any?, server: ServerRef): SeerrDetailBundle? {
        val obj = o as? JSONObject ?: return null
        val tmdbId = obj.optInt("id", -1).takeIf { it > 0 } ?: return null
        val base = summaryItem(obj, server, MediaKind.SHOW) ?: return null
        val ext = obj.optJSONObject("externalIds")
        val tvdbId = ext?.optInt("tvdbId", -1)?.takeIf { it > 0 }
        val imdbId = ext?.optStr("imdbId")
        val rawSeasons = obj.optJSONArray("seasons").objects().filter { it.optInt("seasonNumber", 0) > 0 }
        val info = mediaInfo(obj.optJSONObject("mediaInfo"), tmdbId, tvdbId)

        val requestBySeason = HashMap<Int, SeerrRequestStatus>()
        info?.requests?.forEach { req ->
            for (s in req.seasons) {
                requestBySeason[s] = req.status
            }
        }

        val seasons = rawSeasons.map { s ->
            val num = s.optInt("seasonNumber")
            val mStatus = info?.seasonStatuses?.get(num) ?: SeerrMediaStatus.UNKNOWN
            SeerrSeasonInfo(
                seasonNumber = num,
                name = s.optStr("name") ?: "Season $num",
                episodeCount = s.optInt("episodeCount", 0),
                airDate = s.optStr("airDate"),
                posterPath = s.optStr("posterPath"),
                mediaStatus = mStatus,
                requestStatus = requestBySeason[num],
            )
        }

        val item = base.copy(
            tagline = obj.optStr("tagline") ?: "",
            childCount = seasons.size.takeIf { it > 0 } ?: obj.optInt("numberOfSeasons", 0).takeIf { it > 0 },
            tvdbId = tvdbId,
            imdbId = imdbId,
        )
        val credits = obj.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast").objects().take(20).mapNotNull { c ->
            val name = c.optStr("name") ?: return@mapNotNull null
            Person(name, c.optStr("character"), c.optStr("profilePath"))
        }
        val studio = obj.optJSONArray("networks").objects().firstOrNull()?.optStr("name")
        val related = obj.optJSONObject("recommendations")?.optJSONArray("results").objects()
            .mapNotNull { summaryItem(it, server, MediaKind.SHOW) }
            .take(20)
        return SeerrDetailBundle(
            details = MediaDetails(
                item = item,
                cast = cast,
                studio = studio,
                related = related,
            ),
            mediaInfo = info,
            seasons = seasons,
        )
    }

    fun mediaInfo(obj: JSONObject?, fallbackTmdbId: Int = 0, fallbackTvdbId: Int? = null): SeerrMediaInfo? {
        if (obj == null) return null
        val tmdbId = obj.optInt("tmdbId", fallbackTmdbId).takeIf { it > 0 } ?: fallbackTmdbId
        val tvdbId = obj.optInt("tvdbId", -1).takeIf { it > 0 } ?: fallbackTvdbId
        val status = SeerrMediaStatus.fromCode(obj.optInt("status", 1))
        val seasonMap = HashMap<Int, SeerrMediaStatus>()
        for (s in obj.optJSONArray("seasons").objects()) {
            val num = s.optInt("seasonNumber", -1)
            if (num > 0) {
                seasonMap[num] = SeerrMediaStatus.fromCode(s.optInt("status", 1))
            }
        }
        val reqs = obj.optJSONArray("requests").objects().mapNotNull { request(it) }
        return SeerrMediaInfo(
            id = obj.optInt("id", -1).takeIf { it > 0 },
            tmdbId = tmdbId,
            tvdbId = tvdbId,
            status = status,
            requests = reqs,
            seasonStatuses = seasonMap,
        )
    }

    fun requests(o: Any?): List<SeerrRequest> {
        val obj = o as? JSONObject ?: return emptyList()
        return obj.optJSONArray("results").objects().mapNotNull { request(it) }
    }

    fun request(obj: JSONObject?): SeerrRequest? {
        if (obj == null) return null
        val id = obj.optInt("id", -1).takeIf { it > 0 } ?: return null
        val type = obj.optStr("type") ?: obj.optJSONObject("media")?.optStr("mediaType")
        val kind = if (type == "tv") MediaKind.SHOW else MediaKind.MOVIE
        val media = obj.optJSONObject("media")
        val tmdbId = media?.optInt("tmdbId", -1)?.takeIf { it > 0 }
            ?: obj.optInt("tmdbId", -1).takeIf { it > 0 }
            ?: 0
        val tvdbId = media?.optInt("tvdbId", -1)?.takeIf { it > 0 }
        val status = SeerrRequestStatus.fromCode(obj.optInt("status", 1))
        val mediaStatus = SeerrMediaStatus.fromCode(media?.optInt("status", 1) ?: 1)
        val seasons = obj.optJSONArray("seasons").objects().mapNotNull {
            it.optInt("seasonNumber", -1).takeIf { n -> n > 0 }
        }
        val title = media?.optStr("title")
            ?: media?.optStr("name")
            ?: if (kind == MediaKind.SHOW) "TV Show #$tmdbId" else "Movie #$tmdbId"
        val year = (media?.optStr("releaseDate") ?: media?.optStr("firstAirDate"))?.take(4)?.toIntOrNull()
        val requestedBy = obj.optJSONObject("requestedBy")?.let {
            it.optStr("displayName") ?: it.optStr("plexUsername") ?: it.optStr("username")
        }
        return SeerrRequest(
            id = id,
            mediaKind = kind,
            tmdbId = tmdbId,
            tvdbId = tvdbId,
            title = title,
            year = year,
            posterPath = media?.optStr("posterPath"),
            backdropPath = media?.optStr("backdropPath"),
            status = status,
            mediaStatus = mediaStatus,
            seasons = seasons,
            requestedBy = requestedBy,
            createdAt = obj.optStr("createdAt"),
            is4k = obj.optBoolean("is4k", false),
        )
    }

    private fun JSONObject.optStr(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
