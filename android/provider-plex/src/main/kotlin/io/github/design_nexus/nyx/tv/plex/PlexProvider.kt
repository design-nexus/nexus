package io.github.design_nexus.nyx.tv.plex

import io.github.design_nexus.nyx.tv.core.Backoff
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
import io.github.design_nexus.nyx.tv.media.ServerRef
import io.github.design_nexus.nyx.tv.media.SortOrder
import io.github.design_nexus.nyx.tv.media.PlayInfo
import io.github.design_nexus.nyx.tv.media.StreamMode
import io.github.design_nexus.nyx.tv.media.StreamSource
import io.github.design_nexus.nyx.tv.media.TimelineUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** What's remembered about the chosen server. Holds credentials: store it encrypted, never back it up. */
data class PlexSession(
    val serverId: String,
    val serverName: String,
    val serverToken: String,
    val addresses: List<String>,
    val lastGood: String? = null,
    val accountToken: String? = null,
    val userName: String? = null,
    /** HTTPS avatar from plex.tv (`/api/v2/user` thumb). */
    val userThumb: String? = null,
) {
    override fun toString(): String = "PlexSession($serverName, $serverId, ${addresses.size} addresses)"
}

interface PlexSessionStore {
    fun load(): PlexSession?
    fun save(session: PlexSession?)
}

class PlexProvider(
    private val http: PlexHttp,
    private val store: PlexSessionStore,
    private val scope: CoroutineScope,
    private val account: PlexAccount = PlexAccount(http),
    private val localDiscovery: () -> List<Gdm.Found> = { Gdm.discover() },
    private val now: () -> Long = System::currentTimeMillis,
) : MediaProvider {
    override val id = "plex"
    private val _status = MutableStateFlow<ProviderStatus>(if (store.load() == null) ProviderStatus.Unconfigured else ProviderStatus.Connecting)
    override val status: StateFlow<ProviderStatus> = _status

    private val mutex = Mutex()
    @Volatile private var client: PlexServerClient? = null
    @Volatile private var libs: List<Library>? = null
    private val genreKeys = HashMap<String, Map<String, String>>()
    private val backoff = Backoff()
    private var retryJob: Job? = null
    @Volatile private var active = true

    val session: PlexSession? get() = store.load()

    // ---------- Sign-in ----------

    suspend fun createPin(): PlexPin = io { account.createPin() }
    suspend fun checkPin(pin: PlexPin): String? = io { account.checkPin(pin) }
    suspend fun servers(accountToken: String): List<PlexServer> = io { account.servers(accountToken) }

    /** Connect to [server] through its best connection and remember it. */
    suspend fun choose(server: PlexServer, accountToken: String?): String = mutex.withLock {
        val user = io { accountToken?.let { runCatching { account.user(it) }.getOrNull() } }
        val c = probe(server.connections.groupBy { it.priority }.toSortedMap().values.map { g -> g.map { it.url } }, server.id, server.token)
            ?: throw ProviderUnavailable("${server.name} can't be reached")
        val s = PlexSession(
            server.id, server.name, server.token, server.connections.map { it.url.toString() }, c.base.toString(),
            accountToken, user?.name, user?.thumb,
        )
        store.save(s)
        online(c)
        c.name
    }

    /** Refresh name/avatar from plex.tv when we have an account token but no thumb yet. */
    suspend fun refreshUserProfile() {
        val s = store.load() ?: return
        val token = s.accountToken ?: return
        if (s.userThumb != null && s.userName != null) return
        val user = io { runCatching { account.user(token) }.getOrNull() } ?: return
        val next = s.copy(
            userName = user.name ?: s.userName,
            userThumb = user.thumb ?: s.userThumb,
        )
        if (next != s) store.save(next)
    }

    /** A server by address and token, for servers the account can't list. */
    suspend fun connectManual(address: String, token: String): String = mutex.withLock {
        val url = PlexHttp.serverUrl(address) ?: throw IllegalArgumentException("Enter an address like 192.168.1.20:32400")
        val tmp = PlexServerClient(http, url, token, ServerRef(id, ""))
        val (sid, name) = io { tmp.identity() }
        val c = PlexServerClient(http, url, token, ServerRef(id, sid), name)
        io { c.libraries() }
        store.save(PlexSession(sid, name, token, listOf(url.toString()), url.toString()))
        online(c)
        name
    }

    fun signOut() {
        store.save(null)
        client = null
        libs = null
        genreKeys.clear()
        retryJob?.cancel()
        _status.value = ProviderStatus.Unconfigured
    }

    /** The launcher is visible (true) or not; retries only run while it is. */
    fun setActive(visible: Boolean) {
        active = visible
        if (!visible) {
            retryJob?.cancel()
        } else if (_status.value is ProviderStatus.Offline && retryJob?.isActive != true) {
            scheduleRetry(1_000)
        }
    }

    /** The token for requests to [url], when it's one of this server's addresses. */
    fun tokenFor(url: HttpUrl): String? {
        val s = store.load() ?: return null
        val match = (s.addresses + listOfNotNull(s.lastGood, client?.base?.toString())).any { a ->
            PlexHttp.serverUrl(a)?.let { it.host == url.host && it.port == url.port } == true
        }
        return if (match) s.serverToken else null
    }

    // ---------- Connection ----------

    private suspend fun connected(): PlexServerClient {
        client?.let { return it }
        return mutex.withLock { client ?: connect() }
    }

    private suspend fun connect(): PlexServerClient {
        var s = store.load() ?: run {
            _status.value = ProviderStatus.Unconfigured
            throw ProviderUnavailable("Not signed in to Plex")
        }
        if (_status.value !is ProviderStatus.Offline) _status.value = ProviderStatus.Connecting
        val known = (listOfNotNull(s.lastGood) + s.addresses).distinct().mapNotNull { PlexHttp.serverUrl(it) }
        var c = probe(listOf(known.take(1), known.drop(1)), s.serverId, s.serverToken)
        if (c == null && s.accountToken != null) {
            val fresh = runCatching { io { account.servers(s.accountToken!!) } }.getOrNull()?.firstOrNull { it.id == s.serverId }
            if (fresh != null) {
                s = s.copy(serverToken = fresh.token, addresses = fresh.connections.map { it.url.toString() })
                store.save(s)
                c = probe(fresh.connections.groupBy { it.priority }.toSortedMap().values.map { g -> g.map { it.url } }, s.serverId, s.serverToken)
            }
        }
        if (c == null) {
            val local = runCatching { io { localDiscovery() } }.getOrDefault(emptyList()).firstOrNull { it.id == s.serverId }
            if (local != null) PlexHttp.serverUrl("http://${local.host}:${local.port}")?.let { c = probe(listOf(listOf(it)), s.serverId, s.serverToken) }
        }
        val found = c ?: run {
            offline(s.serverName, "unreachable")
            throw ProviderUnavailable("${s.serverName} is unreachable")
        }
        if (found.base.toString() != s.lastGood) store.save(s.copy(lastGood = found.base.toString()))
        online(found)
        return found
    }

    /** Try each group of addresses in order, the addresses in a group at once; the first that is this server wins. */
    private suspend fun probe(groups: List<List<HttpUrl>>, serverId: String, token: String): PlexServerClient? {
        for (group in groups.filter { it.isNotEmpty() }) {
            val winner = coroutineScope {
                val results = Channel<PlexServerClient?>(group.size)
                val jobs = group.take(4).map { url ->
                    launch(Dispatchers.IO) {
                        val c = PlexServerClient(http, url, token, ServerRef(id, serverId))
                        val ok = runCatching { c.identity(probe = true) }.getOrNull()
                        results.send(if (ok?.first == serverId) c.also { it.name = ok.second } else null)
                    }
                }
                var hit: PlexServerClient? = null
                repeat(jobs.size) { if (hit == null) hit = results.receive() }
                jobs.forEach { it.cancel() }
                hit
            }
            if (winner != null) return winner
        }
        return null
    }

    private fun online(c: PlexServerClient) {
        client = c
        backoff.reset()
        retryJob?.cancel()
        _status.value = ProviderStatus.Online(c.name, c.base.host)
    }

    private fun offline(name: String, reason: String) {
        client = null
        val wait = backoff.next()
        _status.value = ProviderStatus.Offline(name, reason, now() + wait)
        scheduleRetry(wait)
    }

    private fun scheduleRetry(waitMs: Long) {
        retryJob?.cancel()
        if (!active) return
        retryJob = scope.launch {
            delay(waitMs)
            runCatching { connected() }
        }
    }

    private suspend fun <T> call(block: (PlexServerClient) -> T): T {
        val c = connected()
        return try {
            io { block(c) }
        } catch (e: ProviderUnavailable) {
            offline(c.name, "unreachable")
            throw e
        } catch (e: PlexAuthRequired) {
            client = null
            _status.value = ProviderStatus.Offline(c.name, "Sign in to Plex again", Long.MAX_VALUE)
            throw ProviderUnavailable("Plex sign-in expired", e)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------- MediaProvider ----------

    override suspend fun libraries(): List<Library> = libs ?: call { it.libraries() }.also { libs = it }

    private suspend fun librariesOf(kind: MediaKind, only: String?): List<Library> =
        libraries().filter { it.kind == kind && (only == null || it.id == only) }

    override suspend fun row(query: RowQuery): List<MediaItem> = when (query.source) {
        RowSource.CONTINUE_WATCHING -> call { it.continueWatching(query.limit) }
        RowSource.NEXT_EPISODES -> call { c -> c.onDeck(query.limit * 2).filter { it.kind == MediaKind.EPISODE && it.viewOffsetMs == 0L }.take(query.limit) }
        RowSource.RECENT_MOVIES -> librariesOf(MediaKind.MOVIE, query.libraryId).flatMap { l -> call { it.recentlyAdded(l, query.limit) } }.sortedByDescending { it.addedAt }.take(query.limit)
        RowSource.RECENT_SHOWS -> librariesOf(MediaKind.SHOW, query.libraryId).flatMap { l -> call { it.recentlyAdded(l, query.limit) } }.sortedByDescending { it.addedAt }.take(query.limit)
        RowSource.COLLECTIONS -> librariesOf(MediaKind.MOVIE, query.libraryId).flatMap { l -> call { it.collections(l, query.limit) } }.take(query.limit)
        RowSource.PLAYLISTS -> call { it.playlists(query.limit) }
        RowSource.LIBRARY -> query.libraryId?.let { browse(it, SortOrder.RECENTLY_ADDED, null, 0, query.limit).items } ?: emptyList()
        RowSource.GENRE -> {
            val genre = query.genre ?: return emptyList()
            librariesOf(MediaKind.MOVIE, query.libraryId).flatMap { l -> browse(l.id, SortOrder.RECENTLY_ADDED, genre, 0, query.limit).items }.take(query.limit)
        }
    }

    override suspend fun browse(libraryId: String, sort: SortOrder, genre: String?, offset: Int, limit: Int): Page {
        val lib = libraries().firstOrNull { it.id == libraryId } ?: return Page(emptyList(), 0, offset)
        val key = genre?.let { g -> genreMap(lib)[g] ?: return Page(emptyList(), 0, offset) }
        return call { it.browse(lib, sort, key, offset, limit) }
    }

    private suspend fun genreMap(lib: Library): Map<String, String> =
        genreKeys[lib.id] ?: call { it.genres(lib) }.also { genreKeys[lib.id] = it }

    override suspend fun genres(libraryId: String): List<String> {
        val lib = libraries().firstOrNull { it.id == libraryId } ?: return emptyList()
        return genreMap(lib).keys.sorted()
    }

    override suspend fun details(item: MediaItem): MediaDetails {
        val meta = when (item.kind) {
            MediaKind.PLAYLIST -> call { it.playlist(item.id) }
            else -> call { it.metadata(item.id) }
        } ?: return MediaDetails(item, children = if (item.kind == MediaKind.PLAYLIST) children(item) else emptyList())
        val kids = if (
            item.kind == MediaKind.SHOW ||
            item.kind == MediaKind.SEASON ||
            item.kind == MediaKind.COLLECTION ||
            item.kind == MediaKind.PLAYLIST
        ) {
            children(item)
        } else {
            emptyList()
        }
        return PlexParser.details(meta, item.server, kids) ?: MediaDetails(item, children = kids)
    }

    override suspend fun children(item: MediaItem): List<MediaItem> = call { it.children(item) }

    override suspend fun search(query: String, limit: Int): SearchResults =
        if (query.isBlank()) SearchResults() else call { it.search(query.trim(), limit) }

    override suspend fun setWatched(item: MediaItem, watched: Boolean) = call { it.setWatched(item.id, watched) }

    suspend fun playInfo(itemId: String): PlayInfo = call { it.playInfo(itemId) }

    suspend fun resolvePlayback(
        item: MediaItem,
        positionMs: Long,
        mode: StreamMode,
        audioStreamId: Int? = null,
        subtitleStreamId: Int? = null,
    ): StreamSource {
        if (item.kind != MediaKind.MOVIE && item.kind != MediaKind.EPISODE) {
            throw ProviderUnavailable("Only movies and episodes can play")
        }
        return call { it.resolve(item.id, positionMs.coerceAtLeast(0), mode, audioStreamId, subtitleStreamId) }
    }

    suspend fun reportTimeline(update: TimelineUpdate) = call { it.timeline(update) }

    suspend fun stopPlaybackSession(sessionId: String) = call { it.stopTranscode(sessionId) }

    override fun imageCacheKey(path: String?, width: Int, height: Int): String? =
        path?.let { p -> store.load()?.let { "plex:${it.serverId}:$p:${width}x$height" } }

    override fun imageUrl(path: String?, width: Int, height: Int): String? {
        if (path == null) return null
        val c = client ?: store.load()?.let { s -> (s.lastGood ?: s.addresses.firstOrNull())?.let(PlexHttp::serverUrl)?.let { PlexServerClient(http, it, "", ServerRef(id, s.serverId)) } }
        return c?.imageUrl(path, width, height)
    }
}
