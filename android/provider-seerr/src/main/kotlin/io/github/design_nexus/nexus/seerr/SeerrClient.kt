package io.github.design_nexus.nexus.seerr

import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.SearchResults
import io.github.design_nexus.nexus.media.ServerRef
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

class SeerrClient(
    private val http: SeerrHttp,
    val base: HttpUrl,
    private val auth: SeerrAuthHeaders? = null,
    val ref: ServerRef = ServerRef("seerr", "default"),
) {
    private fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val b = base.newBuilder().encodedPath("/api/v1$path")
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    fun status(probe: Boolean = true): SeerrStatusResult =
        SeerrParser.status(http.get(url("/status"), auth = null, probe = probe))

    fun authPlex(plexAuthToken: String): Pair<SeerrUser, String> {
        val body = JSONObject().put("authToken", plexAuthToken)
        val (json, cookie) = http.post(url("/auth/plex"), body, auth = null)
        val sessionCookie = cookie ?: throw SeerrUnavailableException("Seerr did not return a session cookie")
        return SeerrParser.user(json) to sessionCookie
    }

    fun authLocal(email: String, password: String): Pair<SeerrUser, String> {
        val body = JSONObject().put("email", email).put("password", password)
        val (json, cookie) = http.post(url("/auth/local"), body, auth = null)
        val sessionCookie = cookie ?: throw SeerrUnavailableException("Seerr did not return a session cookie")
        return SeerrParser.user(json) to sessionCookie
    }

    fun logout() {
        runCatching { http.post(url("/auth/logout"), JSONObject(), auth = auth) }
    }

    fun me(): SeerrUser = SeerrParser.user(http.get(url("/auth/me"), auth = auth))

    fun movieGenres(): List<SeerrGenre> = SeerrParser.genres(http.get(url("/genres/movie"), auth = auth))
    fun tvGenres(): List<SeerrGenre> = SeerrParser.genres(http.get(url("/genres/tv"), auth = auth))

    fun trending(page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/trending", listOf("page" to "$page")), auth = auth), ref, null, genreMap)

    fun popularMovies(page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/movies", listOf("page" to "$page")), auth = auth), ref, MediaKind.MOVIE, genreMap)

    fun popularTv(page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/tv", listOf("page" to "$page")), auth = auth), ref, MediaKind.SHOW, genreMap)

    fun upcomingMovies(page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/movies/upcoming", listOf("page" to "$page")), auth = auth), ref, MediaKind.MOVIE, genreMap)

    fun upcomingTv(page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/tv/upcoming", listOf("page" to "$page")), auth = auth), ref, MediaKind.SHOW, genreMap)

    fun moviesByGenre(genreId: Int, page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/movies/genre/$genreId", listOf("page" to "$page")), auth = auth), ref, MediaKind.MOVIE, genreMap)

    fun tvByGenre(genreId: Int, page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<List<MediaItem>, Map<String, SeerrMediaInfo>> =
        SeerrParser.discoverItems(http.get(url("/discover/tv/genre/$genreId", listOf("page" to "$page")), auth = auth), ref, MediaKind.SHOW, genreMap)

    fun search(query: String, page: Int = 1, genreMap: Map<Int, String> = emptyMap()): Pair<SearchResults, Map<String, SeerrMediaInfo>> =
        SeerrParser.searchResults(http.get(url("/search", listOf("query" to query, "page" to "$page")), auth = auth), ref, genreMap)

    fun movieDetails(tmdbId: Int): SeerrDetailBundle? =
        SeerrParser.movieDetails(http.get(url("/movie/$tmdbId"), auth = auth), ref)

    fun tvDetails(tmdbId: Int): SeerrDetailBundle? =
        SeerrParser.tvDetails(http.get(url("/tv/$tmdbId"), auth = auth), ref)

    fun requests(take: Int = 30, skip: Int = 0, filter: String = "all", requestedBy: Int? = null): List<SeerrRequest> {
        val q = ArrayList<Pair<String, String>>()
        q += "take" to "$take"
        q += "skip" to "$skip"
        q += "filter" to filter
        q += "sort" to "added"
        if (requestedBy != null) q += "requestedBy" to "$requestedBy"
        return SeerrParser.requests(http.get(url("/request", q), auth = auth))
    }

    fun requestMovie(tmdbId: Int, is4k: Boolean = false): SeerrRequest {
        val body = JSONObject()
            .put("mediaType", "movie")
            .put("mediaId", tmdbId)
            .put("is4k", is4k)
        val (json, _) = http.post(url("/request"), body, auth = auth)
        return SeerrParser.request(json as? JSONObject)
            ?: throw SeerrUnavailableException("Unexpected response creating request")
    }

    fun requestTv(tmdbId: Int, seasons: List<Int>, tvdbId: Int? = null, is4k: Boolean = false): SeerrRequest {
        require(seasons.isNotEmpty()) { "Select at least one season" }
        val body = JSONObject()
            .put("mediaType", "tv")
            .put("mediaId", tmdbId)
            .put("seasons", JSONArray(seasons))
            .put("is4k", is4k)
        if (tvdbId != null && tvdbId > 0) body.put("tvdbId", tvdbId)
        val (json, _) = http.post(url("/request"), body, auth = auth)
        return SeerrParser.request(json as? JSONObject)
            ?: throw SeerrUnavailableException("Unexpected response creating TV request")
    }

    fun cancelRequest(requestId: Int) {
        http.delete(url("/request/$requestId"), auth = auth)
    }
}
