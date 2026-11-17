package io.github.design_nexus.nyx.tv.seerr

import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.ProviderStatus
import io.github.design_nexus.nyx.tv.media.ProviderUnavailable
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemorySeerrStore(var current: SeerrSession? = null) : SeerrSessionStore {
    override fun loadSeerr(): SeerrSession? = current
    override fun saveSeerr(session: SeerrSession?) {
        current = session
    }
}

class SeerrTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySeerrStore
    private lateinit var provider: SeerrProvider

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        store = InMemorySeerrStore()
        provider = SeerrProvider(SeerrHttp(OkHttpClient()), store)
    }

    @After
    fun teardown() {
        server.close()
    }

    @Test
    fun urlNormalizationSupportsLanHttpsAndTailscale() {
        assertEquals("http://192.168.1.20:5055/", SeerrHttp.normalizeServerUrl("192.168.1.20:5055").toString())
        assertEquals("http://100.64.0.10:5055/", SeerrHttp.normalizeServerUrl("100.64.0.10:5055").toString())
        assertEquals("https://seerr.home.ts.net/", SeerrHttp.normalizeServerUrl("seerr.home.ts.net").toString())
        assertEquals("https://seerr.example.com/", SeerrHttp.normalizeServerUrl("https://seerr.example.com/").toString())
        assertNull(SeerrHttp.normalizeServerUrl("   "))
    }

    @Test
    fun plexAuthStoresCookieAndScopingIsolatesHosts() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.target) {
                    "/api/v1/status" -> MockResponse(body = """{"version":"2.1.0","updateAvailable":false}""")
                    "/api/v1/auth/plex" -> {
                        val body = JSONObject(request.body?.utf8() ?: "{}")
                        assertEquals("plex-tok-123", body.getString("authToken"))
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Set-Cookie", "connect.sid=s%3Aabc123; Path=/; HttpOnly")
                            .body("""{"id":7,"displayName":"Ken","plexUsername":"ken","permissions":32}""")
                            .build()
                    }
                    else -> MockResponse(code = 404)
                }
            }
        }

        val url = server.url("/").toString()
        val session = provider.signInWithPlex(url, "plex-tok-123")
        assertEquals("Ken", session.displayName)
        assertEquals("connect.sid=s%3Aabc123", session.sessionCookie)
        assertTrue(provider.isConfigured)
        assertTrue(provider.status.value is ProviderStatus.Online)

        // Credentials must only attach to the Seerr host, never to TMDB or other hosts.
        assertNotNull(provider.authHeadersFor(server.url("/api/v1/discover/trending")))
        assertNull(provider.authHeadersFor("https://image.tmdb.org/t/p/w500/abc.jpg".toHttpUrl()))

        provider.disconnect()
        assertFalse(provider.isConfigured)
        assertTrue(provider.status.value is ProviderStatus.Unconfigured)
    }

    @Test
    fun discoverSearchAndRequestsWorkEndToEnd() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.target == "/api/v1/status" -> MockResponse(body = """{"version":"2.1.0"}""")
                    request.target == "/api/v1/auth/local" -> MockResponse.Builder()
                        .code(200)
                        .addHeader("Set-Cookie", "connect.sid=local-sid")
                        .body("""{"id":2,"displayName":"Viewer","permissions":32}""")
                        .build()
                    request.target == "/api/v1/genres/movie" -> MockResponse(body = """[{"id":878,"name":"Science Fiction"}]""")
                    request.target == "/api/v1/genres/tv" -> MockResponse(body = """[{"id":18,"name":"Drama"}]""")
                    request.target.startsWith("/api/v1/discover/trending") -> MockResponse(
                        body = """{"results":[{"id":693134,"mediaType":"movie","title":"Dune: Part Two","releaseDate":"2024-02-27","genreIds":[878],"posterPath":"/dune2.jpg","voteAverage":8.3,"mediaInfo":{"tmdbId":693134,"status":5}}]}"""
                    )
                    request.target.startsWith("/api/v1/search") -> MockResponse(
                        body = """{"results":[{"id":95396,"mediaType":"tv","name":"Severance","firstAirDate":"2022-02-18","genreIds":[18],"posterPath":"/sev.jpg"}]}"""
                    )
                    request.target == "/api/v1/tv/95396" -> MockResponse(
                        body = """{"id":95396,"name":"Severance","firstAirDate":"2022-02-18","externalIds":{"tvdbId":371980},"seasons":[{"seasonNumber":1,"name":"Season 1","episodeCount":9},{"seasonNumber":2,"name":"Season 2","episodeCount":10}],"mediaInfo":{"tmdbId":95396,"status":4,"seasons":[{"seasonNumber":1,"status":5}]}}"""
                    )
                    request.target == "/api/v1/request" && request.method == "POST" -> {
                        val b = JSONObject(request.body?.utf8() ?: "{}")
                        assertEquals("tv", b.getString("mediaType"))
                        assertEquals(95396, b.getInt("mediaId"))
                        assertEquals(2, b.getJSONArray("seasons").getInt(0))
                        MockResponse(
                            code = 201,
                            body = """{"id":55,"type":"tv","status":1,"seasons":[{"seasonNumber":2}],"media":{"tmdbId":95396,"status":2,"title":"Severance"}}""",
                        )
                    }
                    request.target.startsWith("/api/v1/request?") && request.method == "GET" -> MockResponse(
                        body = """{"results":[{"id":55,"type":"tv","status":1,"seasons":[{"seasonNumber":2}],"media":{"tmdbId":95396,"status":2}}]}"""
                    )
                    else -> MockResponse(code = 404)
                }
            }
        }

        provider.signInLocal(server.url("/").toString(), "viewer@example.com", "secret")

        val trending = provider.trending()
        assertEquals(1, trending.size)
        assertEquals("Dune: Part Two", trending[0].title)
        assertEquals(listOf("Science Fiction"), trending[0].genres)
        assertEquals(SeerrMediaStatus.AVAILABLE, provider.cachedMediaInfo(trending[0])?.status)
        assertEquals("https://image.tmdb.org/t/p/w500/dune2.jpg", provider.imageUrl(trending[0].poster, 200, 300))

        val search = provider.search("severance", 20)
        assertEquals(1, search.shows.size)
        assertEquals(MediaKind.SHOW, search.shows[0].kind)

        val tvDetails = provider.detailsBundle(search.shows[0])
        assertEquals(2, tvDetails.seasons.size)
        assertEquals(SeerrMediaStatus.AVAILABLE, tvDetails.seasons[0].mediaStatus)
        assertEquals(SeerrMediaStatus.UNKNOWN, tvDetails.seasons[1].mediaStatus)

        val created = provider.requestTv(95396, listOf(2), tvdbId = 371980)
        assertEquals(55, created.id)
        assertEquals(SeerrRequestStatus.PENDING, created.status)

        val myReqs = provider.myRequests()
        assertEquals(1, myReqs.size)
        assertEquals("Severance", myReqs[0].title)
        assertEquals(2022, myReqs[0].year)
        assertEquals(listOf(2), myReqs[0].seasons)
    }

    @Test(expected = SeerrAuthExpiredException::class)
    fun expiredSessionRaisesTypedError() = runTest {
        store.saveSeerr(SeerrSession(server.url("/").toString(), SeerrAuthMode.LOCAL, sessionCookie = "connect.sid=old"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse(code = 401, body = """{"message":"Unauthorized"}""")
        }
        provider.trending()
    }

    @Test(expected = SeerrDuplicateRequestException::class)
    fun duplicateRequestRaisesTypedError() = runTest {
        store.saveSeerr(SeerrSession(server.url("/").toString(), SeerrAuthMode.LOCAL, sessionCookie = "connect.sid=ok"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse(code = 409, body = """{"message":"Already requested"}""")
        }
        provider.requestMovie(693134)
    }

    @Test(expected = ProviderUnavailable::class)
    fun unconfiguredProviderFailsCleanly() = runTest {
        provider.trending()
    }
}
