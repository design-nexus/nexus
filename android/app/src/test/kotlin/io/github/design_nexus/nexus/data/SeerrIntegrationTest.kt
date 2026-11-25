package io.github.design_nexus.nexus.data

import io.github.design_nexus.nexus.media.ContentActionState
import io.github.design_nexus.nexus.media.Library
import io.github.design_nexus.nexus.media.MediaDetails
import io.github.design_nexus.nexus.media.MediaItem
import io.github.design_nexus.nexus.media.MediaKind
import io.github.design_nexus.nexus.media.MediaProvider
import io.github.design_nexus.nexus.media.Page
import io.github.design_nexus.nexus.media.ProviderStatus
import io.github.design_nexus.nexus.media.ProviderUnavailable
import io.github.design_nexus.nexus.media.RowQuery
import io.github.design_nexus.nexus.media.SearchResults
import io.github.design_nexus.nexus.media.ServerRef
import io.github.design_nexus.nexus.media.SortOrder
import io.github.design_nexus.nexus.seerr.SeerrMediaInfo
import io.github.design_nexus.nexus.seerr.SeerrMediaStatus
import io.github.design_nexus.nexus.seerr.SeerrRequest
import io.github.design_nexus.nexus.seerr.SeerrRequestStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(
    override val id: String,
    private val results: SearchResults = SearchResults(),
    private val fail: Boolean = false,
) : MediaProvider {
    override val status: StateFlow<ProviderStatus> = MutableStateFlow(ProviderStatus.Online(id, "http://localhost"))
    override suspend fun libraries(): List<Library> = emptyList()
    override suspend fun row(query: RowQuery): List<MediaItem> = emptyList()
    override suspend fun browse(libraryId: String, sort: SortOrder, genre: String?, offset: Int, limit: Int): Page = Page(emptyList(), 0, 0)
    override suspend fun genres(libraryId: String): List<String> = emptyList()
    override suspend fun details(item: MediaItem): MediaDetails = MediaDetails(item)
    override suspend fun children(item: MediaItem): List<MediaItem> = emptyList()
    override suspend fun search(query: String, limit: Int): SearchResults {
        if (fail) throw ProviderUnavailable("$id offline")
        return results
    }
    override suspend fun setWatched(item: MediaItem, watched: Boolean) {}
    override fun imageUrl(path: String?, width: Int, height: Int): String? = path
}

class SeerrIntegrationTest {
    private val plexRef = ServerRef("plex", "p1")
    private val seerrRef = ServerRef("seerr", "default")

    @Test
    fun plexAvailabilityResolutionMatchesByTmdbAndFallbackTitleYear() = runTest {
        val plexMovie = MediaItem(
            server = plexRef, id = "101", kind = MediaKind.MOVIE,
            title = "Dune: Part Two", year = 2024, tmdbId = 693134,
            durationMs = 10_000_000L, viewOffsetMs = 2_000_000L,
        )
        val plexShow = MediaItem(
            server = plexRef, id = "202", kind = MediaKind.SHOW,
            title = "Andor", year = 2022,
        )
        val plex = FakeProvider("plex", SearchResults(movies = listOf(plexMovie), shows = listOf(plexShow)))

        val seerrDune = MediaItem(server = seerrRef, id = "movie:693134", kind = MediaKind.MOVIE, title = "Dune Part Two", year = 2024, tmdbId = 693134)
        val foundDune = MediaAvailabilityResolver.findInPlex(seerrDune, plex)
        assertNotNull(foundDune)
        val state = MediaAvailabilityResolver.resolveActionState(foundDune, null)
        assertTrue(state is ContentActionState.AvailableInPlex)
        assertTrue((state as ContentActionState.AvailableInPlex).resume)

        val seerrAndor = MediaItem(server = seerrRef, id = "tv:83867", kind = MediaKind.SHOW, title = "Andor", year = 2022, tmdbId = 83867)
        assertNotNull(MediaAvailabilityResolver.findInPlex(seerrAndor, plex))

        val seerrMissing = MediaItem(server = seerrRef, id = "movie:999", kind = MediaKind.MOVIE, title = "Mickey 17", year = 2025, tmdbId = 999)
        assertNull(MediaAvailabilityResolver.findInPlex(seerrMissing, plex))
    }

    @Test
    fun actionStatesHandlePendingProcessingAndStaleMetadata() {
        val pendingInfo = SeerrMediaInfo(
            tmdbId = 1, status = SeerrMediaStatus.PENDING,
            requests = listOf(SeerrRequest(10, MediaKind.MOVIE, 1, title = "Test", status = SeerrRequestStatus.PENDING, mediaStatus = SeerrMediaStatus.PENDING)),
        )
        val pending = MediaAvailabilityResolver.resolveActionState(null, pendingInfo)
        assertTrue(pending is ContentActionState.PendingApproval)

        val procInfo = SeerrMediaInfo(
            tmdbId = 2, status = SeerrMediaStatus.PROCESSING,
            requests = listOf(SeerrRequest(11, MediaKind.MOVIE, 2, title = "Test2", status = SeerrRequestStatus.APPROVED, mediaStatus = SeerrMediaStatus.PROCESSING)),
        )
        val proc = MediaAvailabilityResolver.resolveActionState(null, procInfo)
        assertTrue(proc is ContentActionState.ApprovedProcessing)

        val staleInfo = SeerrMediaInfo(tmdbId = 3, status = SeerrMediaStatus.AVAILABLE)
        val stale = MediaAvailabilityResolver.resolveActionState(null, staleInfo)
        assertTrue(stale is ContentActionState.NotAvailable && stale.staleSeerrAvailable)
    }

    @Test
    fun unifiedSearchDeduplicatesAndIsolatesBackendFailure() = runTest {
        val plexDune = MediaItem(server = plexRef, id = "101", kind = MediaKind.MOVIE, title = "Dune: Part Two", year = 2024, tmdbId = 693134)
        val seerrDune = MediaItem(server = seerrRef, id = "movie:693134", kind = MediaKind.MOVIE, title = "Dune: Part Two", year = 2024, tmdbId = 693134)
        val seerrDune1984 = MediaItem(server = seerrRef, id = "movie:841", kind = MediaKind.MOVIE, title = "Dune", year = 1984, tmdbId = 841)

        val merged = MediaAvailabilityResolver.mergeSearchResults(
            plex = SearchResults(movies = listOf(plexDune)),
            seerr = SearchResults(movies = listOf(seerrDune, seerrDune1984)),
        )
        assertEquals(2, merged.movies.size)
        assertEquals("plex", merged.movies[0].server.provider)
        assertEquals("seerr", merged.movies[1].server.provider)

        // Seerr failure isolation: Plex search still succeeds.
        val plex = FakeProvider("plex", SearchResults(movies = listOf(plexDune)))
        val brokenSeerr = FakeProvider("seerr", fail = true)
        val pRes = async { runCatching { plex.search("dune", 20) }.getOrElse { SearchResults() } }
        val sRes = async { runCatching { brokenSeerr.search("dune", 20) }.getOrElse { SearchResults() } }
        val isolated = MediaAvailabilityResolver.mergeSearchResults(pRes.await(), sRes.await())
        assertEquals(1, isolated.movies.size)
        assertEquals("plex", isolated.movies[0].server.provider)
    }
}
