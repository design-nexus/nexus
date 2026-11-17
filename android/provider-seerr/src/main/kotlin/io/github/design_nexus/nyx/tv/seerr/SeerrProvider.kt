package io.github.design_nexus.nyx.tv.seerr

import io.github.design_nexus.nyx.tv.media.Library
import io.github.design_nexus.nyx.tv.media.MediaDetails
import io.github.design_nexus.nyx.tv.media.MediaItem
import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.MediaProvider
import io.github.design_nexus.nyx.tv.media.Page
import io.github.design_nexus.nyx.tv.media.ProviderStatus
import io.github.design_nexus.nyx.tv.media.ProviderUnavailable
import io.github.design_nexus.nyx.tv.media.RowQuery
import io.github.design_nexus.nyx.tv.media.RowSource
import io.github.design_nexus.nyx.tv.media.SearchResults
import io.github.design_nexus.nyx.tv.media.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

class SeerrProvider(
    private val http: SeerrHttp,
    private val store: SeerrSessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) : MediaProvider {
    override val id = "seerr"

    private val _status = MutableStateFlow<ProviderStatus>(
        store.loadSeerr()?.let { ProviderStatus.Connecting } ?: ProviderStatus.Unconfigured
    )
    override val status: StateFlow<ProviderStatus> = _status

    private val authMutex = Mutex()
    private val inFlightRequests = ConcurrentHashMap.newKeySet<String>()
    private val mediaInfoCache = ConcurrentHashMap<String, SeerrMediaInfo>()
    private val summaryCache = ConcurrentHashMap<String, MediaItem>()

    @Volatile private var movieGenreMap: Map<Int, String> = emptyMap()
    @Volatile private var tvGenreMap: Map<Int, String> = emptyMap()
    @Volatile private var genresLoadedAt: Long = 0L

    private data class CacheEntry<T>(val value: T, val fetchedAt: Long)
    private val rowCache = ConcurrentHashMap<String, CacheEntry<List<MediaItem>>>()

    val session: SeerrSession? get() = store.loadSeerr()
    val isConfigured: Boolean get() = session != null

    fun authHeadersFor(url: HttpUrl): SeerrAuthHeaders? {
        val s = session ?: return null
        val base = s.serverUrl.toHttpUrlOrNull() ?: return null
        if (base.scheme != url.scheme || base.host != url.host || base.port != url.port) return null
        return SeerrAuthHeaders(cookie = s.sessionCookie, apiKey = s.apiKey)
    }

    suspend fun testConnection(rawUrl: String): SeerrStatusResult = withContext(Dispatchers.IO) {
        val base = SeerrHttp.normalizeServerUrl(rawUrl)
            ?: throw SeerrBadRequestException("Enter a valid Seerr URL (e.g. http://192.168.1.20:5055 or https://seerr.ts.net)")
        SeerrClient(http, base).status(probe = true)
    }

    suspend fun signInWithPlex(rawUrl: String, plexAccountToken: String): SeerrSession = authMutex.withLock {
        withContext(Dispatchers.IO) {
            val base = SeerrHttp.normalizeServerUrl(rawUrl)
                ?: throw SeerrBadRequestException("Enter a valid Seerr URL")
            val unauth = SeerrClient(http, base)
            val st = unauth.status(probe = true)
            val (user, cookie) = unauth.authPlex(plexAccountToken)
            val s = SeerrSession(
                serverUrl = base.toString().trimEnd('/'),
                authMode = SeerrAuthMode.PLEX,
                sessionCookie = cookie,
                userId = user.id,
                displayName = user.displayName,
                permissions = user.permissions,
                serverVersion = st.version,
            )
            saveAndMarkOnline(s)
            s
        }
    }

    suspend fun signInLocal(rawUrl: String, email: String, password: String): SeerrSession = authMutex.withLock {
        withContext(Dispatchers.IO) {
            val base = SeerrHttp.normalizeServerUrl(rawUrl)
                ?: throw SeerrBadRequestException("Enter a valid Seerr URL")
            val unauth = SeerrClient(http, base)
            val st = unauth.status(probe = true)
            val (user, cookie) = unauth.authLocal(email.trim(), password)
            val s = SeerrSession(
                serverUrl = base.toString().trimEnd('/'),
                authMode = SeerrAuthMode.LOCAL,
                sessionCookie = cookie,
                userId = user.id,
                displayName = user.displayName,
                permissions = user.permissions,
                serverVersion = st.version,
            )
            saveAndMarkOnline(s)
            s
        }
    }

    suspend fun signInWithApiKey(rawUrl: String, apiKey: String): SeerrSession = authMutex.withLock {
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isEmpty()) throw SeerrBadRequestException("API key cannot be empty")
            val base = SeerrHttp.normalizeServerUrl(rawUrl)
                ?: throw SeerrBadRequestException("Enter a valid Seerr URL")
            val client = SeerrClient(http, base, SeerrAuthHeaders(apiKey = key))
            val st = client.status(probe = true)
            val user = client.me()
            val s = SeerrSession(
                serverUrl = base.toString().trimEnd('/'),
                authMode = SeerrAuthMode.API_KEY,
                apiKey = key,
                userId = user.id,
                displayName = user.displayName,
                permissions = user.permissions,
                serverVersion = st.version,
            )
            saveAndMarkOnline(s)
            s
        }
    }

    suspend fun verifySession(): Boolean = withContext(Dispatchers.IO) {
        val s = session ?: run {
            _status.value = ProviderStatus.Unconfigured
            return@withContext false
        }
        try {
            val client = requireClient(s)
            val st = client.status(probe = true)
            val user = client.me()
            val updated = s.copy(
                userId = user.id,
                displayName = user.displayName,
                permissions = user.permissions,
                serverVersion = st.version,
            )
            if (updated != s) store.saveSeerr(updated)
            _status.value = ProviderStatus.Online("Seerr ${st.version}", s.serverUrl)
            true
        } catch (e: SeerrAuthExpiredException) {
            _status.value = ProviderStatus.Offline("Seerr", "Session expired — sign in again", now() + 60_000)
            false
        } catch (e: Exception) {
            _status.value = ProviderStatus.Offline("Seerr", e.message ?: "Unreachable", now() + 15_000)
            false
        }
    }

    fun disconnect() {
        val s = session
        if (s?.sessionCookie != null) {
            runCatching { requireClient(s).logout() }
        }
        store.saveSeerr(null)
        rowCache.clear()
        mediaInfoCache.clear()
        summaryCache.clear()
        movieGenreMap = emptyMap()
        tvGenreMap = emptyMap()
        genresLoadedAt = 0L
        _status.value = ProviderStatus.Unconfigured
    }

    fun cachedMediaInfo(item: MediaItem): SeerrMediaInfo? = mediaInfoCache[item.key]

    suspend fun trending(forceRefresh: Boolean = false): List<MediaItem> = cachedFeed("trending", forceRefresh) { c, m ->
        c.trending(1, m)
    }

    suspend fun popularMovies(forceRefresh: Boolean = false): List<MediaItem> = cachedFeed("popular_movies", forceRefresh) { c, m ->
        c.popularMovies(1, m)
    }

    suspend fun popularTv(forceRefresh: Boolean = false): List<MediaItem> = cachedFeed("popular_tv", forceRefresh) { c, m ->
        c.popularTv(1, m)
    }

    suspend fun upcomingMovies(forceRefresh: Boolean = false): List<MediaItem> = cachedFeed("upcoming_movies", forceRefresh) { c, m ->
        c.upcomingMovies(1, m)
    }

    suspend fun upcomingTv(forceRefresh: Boolean = false): List<MediaItem> = cachedFeed("upcoming_tv", forceRefresh) { c, m ->
        c.upcomingTv(1, m)
    }

    suspend fun moviesByGenre(genreId: Int, forceRefresh: Boolean = false): List<MediaItem> =
        cachedFeed("genre_movie_$genreId", forceRefresh) { c, m -> c.moviesByGenre(genreId, 1, m) }

    suspend fun tvByGenre(genreId: Int, forceRefresh: Boolean = false): List<MediaItem> =
        cachedFeed("genre_tv_$genreId", forceRefresh) { c, m -> c.tvByGenre(genreId, 1, m) }

    suspend fun allGenres(): List<SeerrGenre> = withContext(Dispatchers.IO) {
        ensureGenres()
        val combined = LinkedHashMap<Int, String>()
        combined.putAll(movieGenreMap)
        combined.putAll(tvGenreMap)
        combined.entries.map { SeerrGenre(it.key, it.value) }.sortedBy { it.name }
    }

    suspend fun myRequests(filter: String = "all"): List<SeerrRequest> = withContext(Dispatchers.IO) {
        val s = session ?: throw ProviderUnavailable("Seerr is not configured")
        val client = requireClient(s)
        val raw = wrapCall(s) {
            client.requests(take = 50, skip = 0, filter = filter, requestedBy = s.userId)
        }
        enrichRequests(client, raw)
    }

    private suspend fun enrichRequests(client: SeerrClient, reqs: List<SeerrRequest>): List<SeerrRequest> = coroutineScope {
        reqs.chunked(8).flatMap { batch ->
            batch.map { req ->
                async {
                    if (req.tmdbId <= 0) return@async req
                    val key = "${if (req.mediaKind == MediaKind.SHOW) "tv" else "movie"}:${req.tmdbId}"
                    val cached = summaryCache[key]
                    if (cached != null) {
                        return@async req.copy(
                            title = cached.title,
                            year = cached.year ?: req.year,
                            posterPath = cached.poster ?: req.posterPath,
                            backdropPath = cached.backdrop ?: req.backdropPath,
                        )
                    }
                    val needsMeta = req.posterPath == null ||
                        req.title.startsWith("Movie #") ||
                        req.title.startsWith("TV Show #")
                    if (!needsMeta) return@async req
                    val bundle = runCatching {
                        if (req.mediaKind == MediaKind.SHOW) client.tvDetails(req.tmdbId)
                        else client.movieDetails(req.tmdbId)
                    }.getOrNull() ?: return@async req
                    val item = bundle.details.item
                    summaryCache[key] = item
                    bundle.mediaInfo?.let { mediaInfoCache[item.key] = it }
                    req.copy(
                        title = item.title,
                        year = item.year ?: req.year,
                        posterPath = item.poster ?: req.posterPath,
                        backdropPath = item.backdrop ?: req.backdropPath,
                    )
                }
            }.awaitAll()
        }
    }

    suspend fun detailsBundle(item: MediaItem): SeerrDetailBundle = withContext(Dispatchers.IO) {
        val s = session ?: throw ProviderUnavailable("Seerr is not configured")
        val tmdbId = item.tmdbId ?: parseTmdbId(item.id)
            ?: throw SeerrBadRequestException("Missing TMDB ID for ${item.title}")
        val client = requireClient(s)
        val bundle = wrapCall(s) {
            if (item.kind == MediaKind.SHOW) client.tvDetails(tmdbId) else client.movieDetails(tmdbId)
        } ?: throw SeerrBadRequestException("Title not found on Seerr")
        bundle.mediaInfo?.let { mediaInfoCache[bundle.details.item.key] = it }
        bundle
    }

    suspend fun requestMovie(tmdbId: Int): SeerrRequest = withContext(Dispatchers.IO) {
        val lockKey = "movie:$tmdbId"
        if (!inFlightRequests.add(lockKey)) throw SeerrBadRequestException("Request already in progress")
        try {
            val s = session ?: throw ProviderUnavailable("Seerr is not configured")
            val req = wrapCall(s) { requireClient(s).requestMovie(tmdbId) }
            rowCache.clear()
            req
        } finally {
            inFlightRequests.remove(lockKey)
        }
    }

    suspend fun requestTv(tmdbId: Int, seasons: List<Int>, tvdbId: Int? = null): SeerrRequest = withContext(Dispatchers.IO) {
        val lockKey = "tv:$tmdbId"
        if (!inFlightRequests.add(lockKey)) throw SeerrBadRequestException("Request already in progress")
        try {
            val s = session ?: throw ProviderUnavailable("Seerr is not configured")
            val req = wrapCall(s) { requireClient(s).requestTv(tmdbId, seasons, tvdbId) }
            rowCache.clear()
            req
        } finally {
            inFlightRequests.remove(lockKey)
        }
    }

    suspend fun cancelRequest(requestId: Int) = withContext(Dispatchers.IO) {
        val s = session ?: throw ProviderUnavailable("Seerr is not configured")
        wrapCall(s) { requireClient(s).cancelRequest(requestId) }
        rowCache.clear()
    }

    // ---------- MediaProvider ----------

    override suspend fun libraries(): List<Library> = emptyList()

    override suspend fun row(query: RowQuery): List<MediaItem> = when (query.source) {
        RowSource.RECENT_MOVIES -> popularMovies()
        RowSource.RECENT_SHOWS -> popularTv()
        else -> trending()
    }.take(query.limit)

    override suspend fun browse(libraryId: String, sort: SortOrder, genre: String?, offset: Int, limit: Int): Page =
        Page(emptyList(), 0, offset)

    override suspend fun genres(libraryId: String): List<String> = allGenres().map { it.name }

    override suspend fun details(item: MediaItem): MediaDetails = detailsBundle(item).details

    override suspend fun children(item: MediaItem): List<MediaItem> = emptyList()

    override suspend fun search(query: String, limit: Int): SearchResults = withContext(Dispatchers.IO) {
        val s = session ?: throw ProviderUnavailable("Seerr is not configured")
        ensureGenres()
        val combinedGenres = movieGenreMap + tvGenreMap
        val (results, infoMap) = wrapCall(s) { requireClient(s).search(query, 1, combinedGenres) }
        mediaInfoCache.putAll(infoMap)
        SearchResults(
            movies = results.movies.take(limit),
            shows = results.shows.take(limit),
        )
    }

    override suspend fun setWatched(item: MediaItem, watched: Boolean) {
        // Seerr does not manage playback watch state.
    }

    override fun imageUrl(path: String?, width: Int, height: Int): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        if (!path.startsWith("/")) return null
        val size = when {
            width >= 900 -> "w1280"
            width >= 400 -> "w780"
            else -> "w500"
        }
        return "https://image.tmdb.org/t/p/$size$path"
    }

    override fun imageCacheKey(path: String?, width: Int, height: Int): String? =
        path?.let { "tmdb:$it:${if (width >= 900) "w1280" else "w500"}" }

    // ---------- Internals ----------

    private suspend fun cachedFeed(
        key: String,
        forceRefresh: Boolean,
        fetch: (SeerrClient, Map<Int, String>) -> Pair<List<MediaItem>, Map<String, SeerrMediaInfo>>,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val cached = rowCache[key]
        if (!forceRefresh && cached != null && now() - cached.fetchedAt < FEED_TTL_MS) {
            return@withContext cached.value
        }
        val s = session ?: throw ProviderUnavailable("Seerr is not configured")
        ensureGenres()
        val combinedGenres = movieGenreMap + tvGenreMap
        val (items, infoMap) = wrapCall(s) { fetch(requireClient(s), combinedGenres) }
        mediaInfoCache.putAll(infoMap)
        rowCache[key] = CacheEntry(items, now())
        items
    }

    private fun ensureGenres() {
        val s = session ?: return
        if (movieGenreMap.isNotEmpty() && now() - genresLoadedAt < GENRE_TTL_MS) return
        val client = requireClient(s)
        runCatching {
            val mg = client.movieGenres().associate { it.id to it.name }
            val tg = client.tvGenres().associate { it.id to it.name }
            if (mg.isNotEmpty()) movieGenreMap = mg
            if (tg.isNotEmpty()) tvGenreMap = tg
            genresLoadedAt = now()
        }
    }

    private fun saveAndMarkOnline(s: SeerrSession) {
        store.saveSeerr(s)
        rowCache.clear()
        mediaInfoCache.clear()
        summaryCache.clear()
        _status.value = ProviderStatus.Online("Seerr ${s.serverVersion ?: ""}".trim(), s.serverUrl)
    }

    private fun requireClient(s: SeerrSession): SeerrClient {
        val base = s.serverUrl.toHttpUrlOrNull() ?: throw ProviderUnavailable("Invalid Seerr URL")
        return SeerrClient(http, base, SeerrAuthHeaders(cookie = s.sessionCookie, apiKey = s.apiKey))
    }

    private inline fun <T> wrapCall(s: SeerrSession, block: () -> T): T = try {
        val res = block()
        if (_status.value !is ProviderStatus.Online) {
            _status.value = ProviderStatus.Online("Seerr ${s.serverVersion ?: ""}".trim(), s.serverUrl)
        }
        res
    } catch (e: SeerrAuthExpiredException) {
        _status.value = ProviderStatus.Offline("Seerr", "Session expired — sign in again", now() + 60_000)
        throw e
    } catch (e: SeerrUnavailableException) {
        _status.value = ProviderStatus.Offline("Seerr", e.message ?: "Unreachable", now() + 15_000)
        throw ProviderUnavailable(e.message ?: "Seerr unreachable", e)
    }

    private fun parseTmdbId(id: String): Int? = id.substringAfter(':', id).toIntOrNull()

    private companion object {
        const val FEED_TTL_MS = 2 * 60_000L
        const val GENRE_TTL_MS = 30 * 60_000L
    }
}
