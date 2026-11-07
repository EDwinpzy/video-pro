package dev.jellystack.design.tv

import com.dongpiandi.videopro.data.DongpianException
import com.dongpiandi.videopro.data.DongpianFailureKind
import com.dongpiandi.videopro.data.DongpianSessionState
import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianCatalogPage
import com.dongpiandi.videopro.data.model.DongpianDetail
import com.dongpiandi.videopro.data.model.DongpianEpisode
import com.dongpiandi.videopro.data.model.DongpianEpisodePage
import com.dongpiandi.videopro.data.model.DongpianHome
import com.dongpiandi.videopro.data.model.DongpianHomeSection
import com.dongpiandi.videopro.data.model.DongpianPlaylistPage
import com.dongpiandi.videopro.data.model.DongpianPlaylistSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VideoProCoordinatorTest {
    @Test
    fun restoredSessionLoadsHomeAndAllFiveDestinationsAreReachable() =
        runTest {
            val session = DongpianSessionState("scope", "测试用户")
            val sessions = FakeSessionGateway(restored = session)
            val catalog = FakeCatalogGateway()
            val coordinator = coordinator(sessions, catalog)

            coordinator.start()
            advanceUntilIdle()

            assertEquals(session, assertIs<VideoProSessionUiState.SignedIn>(coordinator.state.value.session).session)
            assertEquals(
                "首页内容",
                coordinator.state.value.home.data
                    ?.sections
                    ?.single()
                    ?.title,
            )

            coordinator.selectTab(VideoProTab.LIBRARY)
            advanceUntilIdle()
            assertEquals(listOf(1), catalog.browsePages)
            assertEquals(
                listOf("片库卡片"),
                coordinator.state.value.library.items
                    .map { it.title },
            )

            coordinator.content.updateSearchQuery("测试")
            advanceTimeBy(400L)
            advanceUntilIdle()
            assertEquals(listOf("测试"), catalog.searchQueries)

            coordinator.selectTab(VideoProTab.PLAYLISTS)
            advanceUntilIdle()
            assertEquals(
                listOf("片单 A"),
                coordinator.state.value.playlists.data
                    ?.items
                    ?.map { it.title },
            )

            coordinator.selectTab(VideoProTab.ME)
            assertEquals(VideoProRoute.Tab(VideoProTab.ME), coordinator.state.value.route)
            assertEquals(VideoProTab.HOME, VideoProTab.entries.first())
            assertEquals(5, VideoProTab.entries.size)
        }

    @Test
    fun signedOutRestoreShowsLoginAndLoginLoadsHome() =
        runTest {
            val sessions = FakeSessionGateway(restored = null)
            val catalog = FakeCatalogGateway()
            val coordinator = coordinator(sessions, catalog)

            coordinator.start()
            advanceUntilIdle()
            assertIs<VideoProSessionUiState.SignedOut>(coordinator.state.value.session)

            coordinator.login("account", "password")
            advanceUntilIdle()

            assertEquals(listOf("account:password"), sessions.loginAttempts)
            assertIs<VideoProSessionUiState.SignedIn>(coordinator.state.value.session)
            assertEquals(
                "首页内容",
                coordinator.state.value.home.data
                    ?.sections
                    ?.single()
                    ?.title,
            )
        }

    @Test
    fun invalidLoginDoesNotCallTheGateway() =
        runTest {
            val sessions = FakeSessionGateway(restored = null)
            val coordinator = coordinator(sessions, FakeCatalogGateway())

            coordinator.login("  ", "password")

            assertEquals(emptyList(), sessions.loginAttempts)
            assertEquals(VideoProFailure.INVALID_INPUT, coordinator.state.value.login.failure)
        }

    @Test
    fun detailBackReturnsToItsSourceTabThenHomeAndFinallyRequestsExit() =
        runTest {
            val coordinator = coordinator(FakeSessionGateway(restored = session()), FakeCatalogGateway())
            coordinator.start()
            advanceUntilIdle()
            coordinator.selectTab(VideoProTab.LIBRARY)
            advanceUntilIdle()

            coordinator.openDetail("detail", "detail")
            advanceUntilIdle()
            assertEquals(VideoProRoute.Detail("detail", "detail"), coordinator.state.value.route)

            assertTrue(coordinator.back())
            assertEquals(VideoProRoute.Tab(VideoProTab.LIBRARY), coordinator.state.value.route)
            assertTrue(coordinator.back())
            assertEquals(VideoProRoute.Tab(VideoProTab.HOME), coordinator.state.value.route)
            assertFalse(coordinator.back())
        }

    @Test
    fun logoutClearsSessionAndCachedContent() =
        runTest {
            val sessions = FakeSessionGateway(restored = session())
            val coordinator = coordinator(sessions, FakeCatalogGateway())
            coordinator.start()
            advanceUntilIdle()

            coordinator.logout()
            advanceUntilIdle()

            assertEquals(1, sessions.logoutCalls)
            assertIs<VideoProSessionUiState.SignedOut>(coordinator.state.value.session)
            assertNull(coordinator.state.value.home.data)
            assertEquals(VideoProRoute.Tab(VideoProTab.HOME), coordinator.state.value.route)
        }

    @Test
    fun staleSearchCompletionCannotReplaceANewerQuery() =
        runTest {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val catalog =
                FakeCatalogGateway(
                    searchProvider = { query, _, _ ->
                        if (query == "旧") {
                            oldStarted.complete(Unit)
                            withContext(NonCancellable) { releaseOld.await() }
                            page(card("旧结果"))
                        } else {
                            page(card("新结果"))
                        }
                    },
                )
            val coordinator =
                coordinator(
                    FakeSessionGateway(restored = session()),
                    catalog,
                    searchDebounceMillis = 0L,
                )
            coordinator.start()
            advanceUntilIdle()

            coordinator.content.updateSearchQuery("旧")
            runCurrent()
            oldStarted.await()
            coordinator.content.updateSearchQuery("新")
            runCurrent()
            releaseOld.complete(Unit)
            advanceUntilIdle()

            assertEquals("新", coordinator.state.value.search.query)
            assertEquals(
                listOf("新结果"),
                coordinator.state.value.search.items
                    .map { it.title },
            )
        }

    @Test
    fun staleDetailCompletionCannotReplaceTheCurrentDetailKey() =
        runTest {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val catalog =
                FakeCatalogGateway(
                    detailProvider = { variantId ->
                        if (variantId == "old") {
                            oldStarted.complete(Unit)
                            withContext(NonCancellable) { releaseOld.await() }
                        }
                        detail(variantId)
                    },
                )
            val coordinator = coordinator(FakeSessionGateway(restored = session()), catalog)
            coordinator.start()
            advanceUntilIdle()

            coordinator.openDetail("old", "旧")
            runCurrent()
            oldStarted.await()
            coordinator.openDetail("new", "新")
            runCurrent()
            releaseOld.complete(Unit)
            advanceUntilIdle()

            assertEquals("new", coordinator.state.value.detail.variantId)
            assertEquals(
                "new",
                coordinator.state.value.detail.data
                    ?.variantId,
            )
        }

    @Test
    fun homeFailureIsMappedToStableChineseUiFailure() =
        runTest {
            val catalog =
                FakeCatalogGateway(
                    homeProvider = {
                        throw DongpianException(
                            kind = DongpianFailureKind.NETWORK,
                            message = "internal detail must not reach the UI",
                        )
                    },
                )
            val coordinator = coordinator(FakeSessionGateway(restored = session()), catalog)

            coordinator.start()
            advanceUntilIdle()

            assertEquals(VideoProFailure.NETWORK, coordinator.state.value.home.failure)
        }

    private suspend fun coordinator(
        sessions: FakeSessionGateway,
        catalog: FakeCatalogGateway,
        searchDebounceMillis: Long = 0L,
    ): VideoProCoordinator =
        VideoProCoordinator(
            scope = kotlinx.coroutines.CoroutineScope(coroutineContext),
            sessionRepository = sessions,
            catalogRepository = catalog,
            searchDebounceMillis = searchDebounceMillis,
        )

    private fun session() = DongpianSessionState("scope", "测试用户")

    private class FakeSessionGateway(
        var restored: DongpianSessionState?,
    ) : VideoProSessionGateway {
        val loginAttempts = mutableListOf<String>()
        var logoutCalls = 0
        var loginResult: DongpianSessionState = DongpianSessionState("scope", "测试用户")

        override suspend fun restore(): DongpianSessionState? = restored

        override suspend fun login(
            account: String,
            password: String,
        ): DongpianSessionState {
            loginAttempts += "$account:$password"
            return loginResult
        }

        override suspend fun logout() {
            logoutCalls += 1
            restored = null
        }
    }

    private class FakeCatalogGateway(
        private val homeProvider: () -> DongpianHome = { home() },
        private val browseProvider: (Int, Int) -> DongpianCatalogPage = { _, _ -> page(card("片库卡片")) },
        private val searchProvider: suspend (String, Int, Int) -> DongpianCatalogPage = { query, _, _ ->
            page(card(query))
        },
        private val detailProvider: suspend (String) -> DongpianDetail = { variantId -> detail(variantId) },
        private val episodesProvider: suspend (String, Int, Int) -> DongpianEpisodePage = { _, _, _ ->
            DongpianEpisodePage(emptyList(), null)
        },
    ) : VideoProCatalogGateway {
        val browsePages = mutableListOf<Int>()
        val searchQueries = mutableListOf<String>()

        override suspend fun loadHome(): DongpianHome = homeProvider()

        override suspend fun browse(
            page: Int,
            limit: Int,
        ): DongpianCatalogPage {
            browsePages += page
            return browseProvider(page, limit)
        }

        override suspend fun search(
            query: String,
            page: Int,
            limit: Int,
        ): DongpianCatalogPage {
            searchQueries += query
            return searchProvider(query, page, limit)
        }

        override suspend fun loadDetail(variantId: String): DongpianDetail = detailProvider(variantId)

        override suspend fun loadEpisodes(
            variantId: String,
            offset: Int,
            limit: Int,
        ): DongpianEpisodePage = episodesProvider(variantId, offset, limit)

        override suspend fun loadPlaylists(): DongpianPlaylistPage =
            DongpianPlaylistPage(
                items = listOf(DongpianPlaylistSummary("playlist", "片单 A", null, 2, null)),
                nextCursor = null,
                hasMore = false,
            )
    }

    private companion object {
        fun home(): DongpianHome =
            DongpianHome(
                sections =
                    listOf(
                        DongpianHomeSection(
                            id = "home",
                            type = null,
                            title = "首页内容",
                            subtitle = null,
                            cards = listOf(card("hero")),
                        ),
                    ),
                nextCursor = null,
            )

        fun page(card: DongpianCatalogCard): DongpianCatalogPage =
            DongpianCatalogPage(items = listOf(card), nextCursor = null, hasMore = false)

        fun card(id: String): DongpianCatalogCard =
            DongpianCatalogCard(
                id = id,
                variantId = id,
                title = id,
                contentKind = "movie",
                year = 2026,
                area = null,
                language = null,
                genres = emptyList(),
                actors = emptyList(),
                directors = emptyList(),
                posterUrl = null,
                carouselUrl = null,
                backdropUrl = null,
                remarks = null,
                detailPath = null,
            )

        fun detail(variantId: String): DongpianDetail =
            DongpianDetail(
                id = variantId,
                variantId = variantId,
                title = variantId,
                contentKind = "movie",
                year = 2026,
                area = null,
                language = null,
                genres = emptyList(),
                actors = emptyList(),
                directors = emptyList(),
                posterUrl = null,
                backdropUrl = null,
                description = null,
                remarks = null,
                episodes = listOf(DongpianEpisode("episode", 1, "第1集", "token", null)),
                variants = emptyList(),
                episodeNextCursor = null,
            )
    }
}
