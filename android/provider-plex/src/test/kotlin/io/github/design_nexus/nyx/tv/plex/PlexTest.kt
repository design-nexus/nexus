package io.github.design_nexus.nyx.tv.plex

import io.github.design_nexus.nyx.tv.media.MediaKind
import io.github.design_nexus.nyx.tv.media.ProviderStatus
import io.github.design_nexus.nyx.tv.media.ProviderUnavailable
import io.github.design_nexus.nyx.tv.media.RowQuery
import io.github.design_nexus.nyx.tv.media.RowSource
import io.github.design_nexus.nyx.tv.media.ServerRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val S = ServerRef("plex", "abc")

class PlexParserTest {
    @Test fun episode() {
        val v = JSONObject(
            """{"type":"episode","ratingKey":"76809","title":"In a Lonely Place","grandparentTitle":"Altered Carbon","parentIndex":1,"index":3,
               "duration":3060000,"viewOffset":1530000,"thumb":"/library/metadata/76809/thumb/1","grandparentThumb":"/library/metadata/76805/thumb/2",
               "grandparentArt":"/library/metadata/76805/art/2","parentRatingKey":"76806","grandparentRatingKey":"76805","librarySectionID":5}"""
        )
        val i = PlexParser.item(v, S)!!
        assertEquals(MediaKind.EPISODE, i.kind)
        assertEquals("S1 · E3", i.episodeCode)
        assertEquals("Altered Carbon", i.seriesTitle)
        assertEquals("/library/metadata/76805/thumb/2", i.poster)
        assertEquals("/library/metadata/76809/thumb/1", i.thumb)
        assertEquals(0.5f, i.progress, 0.001f)
        assertEquals("76805", i.seriesId)
        assertEquals("5", i.libraryId)
        assertFalse(i.watched)
    }

    @Test fun showCountsRatingsAndUnsafeArt() {
        val v = JSONObject(
            """{"type":"show","ratingKey":76805,"title":"Altered Carbon","leafCount":18,"viewedLeafCount":4,"childCount":2,
               "thumb":"https://evil.example/x.jpg","art":"https://evil.example/art.jpg","Genre":[{"tag":"Drama"}],
               "Rating":[{"image":"rottentomatoes://image.rating.ripe","value":7.5,"type":"critic"},{"image":"rottentomatoes://image.rating.upright","value":6.4,"type":"audience"}]}"""
        )
        val i = PlexParser.item(v, S)!!
        assertEquals(14, i.unwatchedCount)
        assertEquals(2, i.childCount)
        assertNull(i.poster)
        assertNull(i.backdrop)
        assertEquals(listOf("Drama"), i.genres)
        assertEquals(listOf("rt_critic" to 75, "rt_audience" to 64), i.ratings.map { it.source to it.percent })
    }

    @Test fun collectionPosterFromThumbQueryAndCompositeFallback() {
        val withQuery = JSONObject(
            """{"type":"collection","ratingKey":"42","title":"Star Wars","childCount":9,
               "thumb":"/library/collections/42/composite/1700000000?x=1","updatedAt":1700000000}""",
        )
        val a = PlexParser.item(withQuery, S)!!
        assertEquals(MediaKind.COLLECTION, a.kind)
        assertEquals("/library/collections/42/composite/1700000000", a.poster)
        assertEquals(9, a.childCount)

        val fromImage = JSONObject(
            """{"type":"collection","ratingKey":"7","title":"Bond","updatedAt":99,
               "Image":[{"type":"coverPoster","url":"/library/collections/7/composite/99"}]}""",
        )
        assertEquals("/library/collections/7/composite/99", PlexParser.item(fromImage, S)!!.poster)

        val synthesized = JSONObject(
            """{"type":"collection","ratingKey":"3","title":"Empty","updatedAt":55}""",
        )
        assertEquals("/library/collections/3/composite/55", PlexParser.item(synthesized, S)!!.poster)

        val playlist = JSONObject(
            """{"type":"playlist","ratingKey":"8","title":"Friday","leafCount":4,
               "thumb":"/playlists/8/composite/11"}""",
        )
        val p = PlexParser.item(playlist, S)!!
        assertEquals(MediaKind.PLAYLIST, p.kind)
        assertEquals("/playlists/8/composite/11", p.poster)
        assertEquals(4, p.childCount)
    }

    @Test fun artPathStripsQueryAndAbsoluteLocal() {
        assertEquals(
            "/library/collections/1/composite/2",
            PlexParser.artPath("/library/collections/1/composite/2?X-Plex-Token=nope"),
        )
        assertEquals(
            "/library/metadata/1/thumb/2",
            PlexParser.artPath("http://192.168.1.4:32400/library/metadata/1/thumb/2"),
        )
        assertNull(PlexParser.artPath("https://evil.example/x.jpg"))
        assertNull(PlexParser.artPath("//evil/x"))
    }

    @Test fun externalGuidsParsed() {
        val v = JSONObject(
            """{"type":"movie","ratingKey":"101","title":"Dune: Part Two","year":2024,
               "Guid":[{"id":"imdb://tt15239678"},{"id":"tmdb://693134"},{"id":"tvdb://999"}]}"""
        )
        val i = PlexParser.item(v, S)!!
        assertEquals(693134, i.tmdbId)
        assertEquals(999, i.tvdbId)
        assertEquals("tt15239678", i.imdbId)
    }

    @Test fun rejectsUnknownAndBadKeys() {
        assertNull(PlexParser.item(JSONObject("""{"type":"artist","ratingKey":"1"}"""), S))
        assertNull(PlexParser.item(JSONObject("""{"type":"movie","ratingKey":"1/../2"}"""), S))
    }

    @Test fun resourcesOrderLocalFirstAndHonourHttps() {
        val a = JSONArray(
            """[{"provides":"server","clientIdentifier":"abc","accessToken":"t","name":"Home","owned":true,"connections":[
                {"uri":"https://remote.plex.direct:32400","local":false,"relay":false},
                {"uri":"https://relay.plex.direct","local":false,"relay":true},
                {"uri":"http://192.168.1.4:32400","local":true},
                {"uri":"https://local.plex.direct:32400","local":true},
                {"uri":"https://user:pw@bad.test","local":true}]},
               {"provides":"server","clientIdentifier":"def","accessToken":"u","name":"Away","httpsRequired":1,"connections":[
                {"uri":"http://10.0.0.5:32400","local":true},{"uri":"https://secure.plex.direct:32400","local":true}]},
               {"provides":"player","clientIdentifier":"tv","accessToken":"v"}]"""
        )
        val s = PlexAccount.parseResources(a)
        assertEquals(listOf("Away", "Home"), s.map { it.name })
        assertEquals(
            listOf("https://local.plex.direct:32400/", "http://192.168.1.4:32400/", "https://remote.plex.direct:32400/", "https://relay.plex.direct/"),
            s[1].connections.map { it.url.toString() },
        )
        assertEquals(listOf("https://secure.plex.direct:32400/"), s[0].connections.map { it.url.toString() })
        assertFalse(s.toString().contains("\"t\""))
    }

    @Test fun gdm() {
        val f = Gdm.parse("HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nResource-Identifier: abc\r\nName: Home\r\nPort: 32400\r\n", "192.168.1.20")
        assertEquals(Gdm.Found("abc", "Home", "192.168.1.20", 32400), f)
        assertNull(Gdm.parse("HTTP/1.0 200 OK\r\nContent-Type: plex/player\r\nResource-Identifier: x\r\n", "1.2.3.4"))
    }

    @Test fun serverUrlRules() {
        assertEquals("http://192.168.1.20:32400/", PlexHttp.serverUrl("192.168.1.20:32400").toString())
        assertNull(PlexHttp.serverUrl("http://u:p@host/"))
        assertNull(PlexHttp.serverUrl("http://host/path"))
        assertTrue(PlexHttp.relativeKey("/library/metadata/1/thumb/2"))
        assertFalse(PlexHttp.relativeKey("//evil/x"))
        assertFalse(PlexHttp.relativeKey("/x?y"))
    }
}

class PlexProviderTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var up = true
    private val seen = ArrayList<RecordedRequest>()

    private class MemStore(var s: PlexSession?) : PlexSessionStore {
        override fun load() = s
        override fun save(session: PlexSession?) { s = session }
    }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (!up) return MockResponse.Builder().code(503).build()
                if (request.headers["X-Plex-Token"] != "srv") return MockResponse.Builder().code(401).build()
                val path = request.url.encodedPath
                val body = when {
                    path == "/" -> """{"MediaContainer":{"machineIdentifier":"abc","friendlyName":"Home"}}"""
                    path == "/library/sections" -> """{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Movies"},{"key":"5","type":"show","title":"TV"},{"key":"9","type":"artist","title":"Music"}]}}"""
                    path == "/hubs/continueWatching/items" -> """{"MediaContainer":{"Metadata":[{"type":"movie","ratingKey":"7","title":"Dune","duration":100,"viewOffset":40}]}}"""
                    path == "/library/sections/1/all" -> """{"MediaContainer":{"totalSize":2,"Metadata":[{"type":"movie","ratingKey":"8","title":"A","addedAt":5,"thumb":"/library/metadata/8/thumb/1"},{"type":"movie","ratingKey":"9","title":"B","addedAt":9}]}}"""
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    @After fun stop() {
        server.close()
        scope.cancel()
    }

    private fun provider(store: PlexSessionStore) =
        PlexProvider(PlexHttp(PlexClientInfo("test")), store, scope, localDiscovery = { emptyList() })

    private fun session() = PlexSession("abc", "Home", "srv", listOf(server.url("/").toString()))

    @Test fun rowsAndTokenOnlyInHeaders() = runBlocking {
        val p = provider(MemStore(session()))
        val cw = p.row(RowQuery(RowSource.CONTINUE_WATCHING))
        assertEquals("Dune", cw.single().title)
        assertTrue(p.status.value is ProviderStatus.Online)
        val recent = p.row(RowQuery(RowSource.RECENT_MOVIES, limit = 5))
        assertEquals(listOf("B", "A"), recent.map { it.title })
        assertEquals(2, p.libraries().size)
        val img = p.imageUrl(recent[1].poster, 300, 450)!!
        assertFalse(img.contains("srv"))
        assertTrue(img.contains("/photo/:/transcode"))
        assertEquals("srv", p.tokenFor(server.url("/photo/:/transcode")))
        assertNull(p.tokenFor(okhttp3.HttpUrl.Builder().scheme("http").host("other.example").build()))
        assertTrue(seen.none { it.url.toString().contains("srv") })
        val composite = p.imageUrl("/library/collections/42/composite/9", 300, 450)!!
        assertFalse(composite.contains("/photo/:/transcode"))
        assertTrue(composite.contains("/library/collections/42/composite/9"))
    }

    @Test fun wrongServerIdentityIsRejected() = runBlocking {
        val p = provider(MemStore(session().copy(serverId = "someone-else")))
        try {
            p.row(RowQuery(RowSource.CONTINUE_WATCHING)); throw AssertionError("connected to the wrong server")
        } catch (_: ProviderUnavailable) {}
        assertTrue(p.status.value is ProviderStatus.Offline)
    }

    @Test fun goesOfflineAndSchedulesBoundedRetry() = runBlocking {
        val p = provider(MemStore(session()))
        up = false
        try { p.libraries(); throw AssertionError() } catch (_: ProviderUnavailable) {}
        val st = p.status.value as ProviderStatus.Offline
        assertTrue(st.retryAtMs > System.currentTimeMillis())
        assertTrue(st.retryAtMs - System.currentTimeMillis() <= 10 * 60_000)
        p.setActive(false)
    }

    @Test fun unconfiguredWithoutSession() = runBlocking {
        val p = provider(MemStore(null))
        assertEquals(ProviderStatus.Unconfigured, p.status.value)
        try { p.libraries(); throw AssertionError() } catch (_: ProviderUnavailable) {}
        assertNotNull(p)
    }
}
