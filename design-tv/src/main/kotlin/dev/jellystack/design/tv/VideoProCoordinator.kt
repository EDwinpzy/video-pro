package dev.jellystack.design.tv

import com.dongpiandi.videopro.data.DongpianCatalogRepository
import com.dongpiandi.videopro.data.DongpianException
import com.dongpiandi.videopro.data.DongpianFailureKind
import com.dongpiandi.videopro.data.DongpianSessionRepository
import com.dongpiandi.videopro.data.DongpianSessionState
import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianCatalogPage
import com.dongpiandi.videopro.data.model.DongpianDetail
import com.dongpiandi.videopro.data.model.DongpianEpisode
import com.dongpiandi.videopro.data.model.DongpianHome
import com.dongpiandi.videopro.data.model.DongpianPlaylistPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class VideoProTab {
    HOME,
    LIBRARY,
    SEARCH,
    PLAYLISTS,
    ME,
}

internal sealed interface VideoProRoute {
    data class Tab(
        val tab: VideoProTab,
    ) : VideoProRoute

    data class Detail(
        val variantId: String,
        val title: String?,
    ) : VideoProRoute
}

internal enum class VideoProFailure {
    INVALID_INPUT,
    UNAUTHORIZED,
    NETWORK,
    SERVICE,
    RESPONSE,
    STORAGE,
    UNKNOWN,
}

internal sealed interface VideoProSessionUiState {
    data object Loading : VideoProSessionUiState

    data class SignedOut(
        val failure: VideoProFailure? = null,
    ) : VideoProSessionUiState

    data class SignedIn(
        val session: DongpianSessionState,
    ) : VideoProSessionUiState

    data class Failed(
        val failure: VideoProFailure,
    ) : VideoProSessionUiState
}

internal data class VideoProLoginState(
    val submitting: Boolean = false,
    val failure: VideoProFailure? = null,
)

internal data class VideoProHomeState(
    val data: DongpianHome? = null,
    val loading: Boolean = false,
    val failure: VideoProFailure? = null,
)

internal data class VideoProCatalogState(
    val items: List<DongpianCatalogCard> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val failure: VideoProFailure? = null,
)

internal data class VideoProSearchState(
    val query: String = "",
    val items: List<DongpianCatalogCard> = emptyList(),
    val loading: Boolean = false,
    val failure: VideoProFailure? = null,
)

internal data class VideoProPlaylistState(
    val data: DongpianPlaylistPage? = null,
    val loading: Boolean = false,
    val failure: VideoProFailure? = null,
)

internal data class VideoProDetailState(
    val variantId: String = "",
    val data: DongpianDetail? = null,
    val episodes: List<DongpianEpisode> = emptyList(),
    val loading: Boolean = false,
    val failure: VideoProFailure? = null,
)

internal data class VideoProUiState(
    val session: VideoProSessionUiState = VideoProSessionUiState.Loading,
    val login: VideoProLoginState = VideoProLoginState(),
    val route: VideoProRoute = VideoProRoute.Tab(VideoProTab.HOME),
    val backStack: List<VideoProRoute> = listOf(VideoProRoute.Tab(VideoProTab.HOME)),
    val railExpanded: Boolean = false,
    val home: VideoProHomeState = VideoProHomeState(),
    val library: VideoProCatalogState = VideoProCatalogState(),
    val search: VideoProSearchState = VideoProSearchState(),
    val playlists: VideoProPlaylistState = VideoProPlaylistState(),
    val detail: VideoProDetailState = VideoProDetailState(),
    val notice: String? = null,
    val contentFocusRevision: Long = 0L,
)

internal interface VideoProSessionGateway {
    suspend fun restore(): DongpianSessionState?

    suspend fun login(
        account: String,
        password: String,
    ): DongpianSessionState

    suspend fun logout()
}

internal interface VideoProCatalogGateway {
    suspend fun loadHome(): DongpianHome

    suspend fun browse(
        page: Int,
        limit: Int,
    ): DongpianCatalogPage

    suspend fun search(
        query: String,
        page: Int,
        limit: Int,
    ): DongpianCatalogPage

    suspend fun loadDetail(variantId: String): DongpianDetail

    suspend fun loadEpisodes(
        variantId: String,
        offset: Int,
        limit: Int,
    ): com.dongpiandi.videopro.data.model.DongpianEpisodePage

    suspend fun loadPlaylists(): DongpianPlaylistPage
}

internal class DataVideoProSessionGateway(
    private val repository: DongpianSessionRepository,
) : VideoProSessionGateway {
    override suspend fun restore(): DongpianSessionState? = repository.restore()

    override suspend fun login(
        account: String,
        password: String,
    ): DongpianSessionState = repository.login(account, password)

    override suspend fun logout() = repository.logout()
}

internal class DataVideoProCatalogGateway(
    private val repository: DongpianCatalogRepository,
) : VideoProCatalogGateway {
    override suspend fun loadHome(): DongpianHome = repository.loadHome()

    override suspend fun browse(
        page: Int,
        limit: Int,
    ): DongpianCatalogPage = repository.browse(page = page, limit = limit)

    override suspend fun search(
        query: String,
        page: Int,
        limit: Int,
    ): DongpianCatalogPage = repository.search(query = query, page = page, limit = limit)

    override suspend fun loadDetail(variantId: String): DongpianDetail = repository.loadDetail(variantId)

    override suspend fun loadEpisodes(
        variantId: String,
        offset: Int,
        limit: Int,
    ) = repository.loadEpisodes(variantId = variantId, offset = offset, limit = limit)

    override suspend fun loadPlaylists(): DongpianPlaylistPage = repository.loadPlaylists()
}

internal class VideoProCoordinator(
    private val scope: CoroutineScope,
    private val sessionRepository: VideoProSessionGateway,
    private val catalogRepository: VideoProCatalogGateway,
    private val searchDebounceMillis: Long = 350L,
) {
    private val mutableState = MutableStateFlow(VideoProUiState())
    val state: StateFlow<VideoProUiState> = mutableState.asStateFlow()

    internal val content =
        VideoProContentController(
            scope = scope,
            catalogRepository = catalogRepository,
            readState = { mutableState.value },
            writeState = { mutableState.value = it },
            searchDebounceMillis = searchDebounceMillis,
        )

    private var restoreJob: Job? = null
    private var loginJob: Job? = null

    fun start() {
        restoreJob?.cancel()
        restoreJob =
            scope.launch {
                mutableState.value =
                    mutableState.value.copy(
                        session = VideoProSessionUiState.Loading,
                        login = VideoProLoginState(),
                    )
                try {
                    val session = sessionRepository.restore()
                    if (session == null) {
                        mutableState.value =
                            mutableState.value.copy(
                                session = VideoProSessionUiState.SignedOut(),
                                route = VideoProRoute.Tab(VideoProTab.HOME),
                                backStack = listOf(VideoProRoute.Tab(VideoProTab.HOME)),
                            )
                    } else {
                        mutableState.value =
                            mutableState.value.copy(
                                session = VideoProSessionUiState.SignedIn(session),
                                route = VideoProRoute.Tab(VideoProTab.HOME),
                                backStack = listOf(VideoProRoute.Tab(VideoProTab.HOME)),
                            )
                        content.loadHome()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    mutableState.value =
                        mutableState.value.copy(
                            session = VideoProSessionUiState.Failed(failure.toVideoProFailure()),
                        )
                }
            }
    }

    fun login(
        account: String,
        password: String,
    ) {
        if (loginJob?.isActive == true) return
        val normalizedAccount = account.trim()
        if (normalizedAccount.isEmpty() || password.isEmpty()) {
            mutableState.value =
                mutableState.value.copy(
                    login = VideoProLoginState(failure = VideoProFailure.INVALID_INPUT),
                )
            return
        }
        mutableState.value =
            mutableState.value.copy(
                login = VideoProLoginState(submitting = true),
                session = VideoProSessionUiState.SignedOut(),
            )
        loginJob =
            scope.launch {
                try {
                    val session = sessionRepository.login(normalizedAccount, password)
                    mutableState.value =
                        mutableState.value.copy(
                            session = VideoProSessionUiState.SignedIn(session),
                            login = VideoProLoginState(),
                            route = VideoProRoute.Tab(VideoProTab.HOME),
                            backStack = listOf(VideoProRoute.Tab(VideoProTab.HOME)),
                        )
                    content.loadHome()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    mutableState.value =
                        mutableState.value.copy(
                            session = VideoProSessionUiState.SignedOut(),
                            login =
                                VideoProLoginState(
                                    failure = failure.toVideoProFailure(),
                                ),
                        )
                }
            }
    }

    fun logout() {
        scope.launch {
            try {
                sessionRepository.logout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DongpianException) {
                // The local session is already cleared by the repository before a storage failure is reported.
            }
            content.cancelAll()
            mutableState.value =
                VideoProUiState(
                    session = VideoProSessionUiState.SignedOut(),
                )
        }
    }

    fun selectTab(tab: VideoProTab) {
        val route = VideoProRoute.Tab(tab)
        mutableState.value =
            mutableState.value.copy(
                route = route,
                backStack = listOf(route),
                railExpanded = false,
                notice = null,
                contentFocusRevision = mutableState.value.contentFocusRevision + 1L,
            )
        when (tab) {
            VideoProTab.HOME -> if (mutableState.value.home.data == null) content.loadHome()
            VideoProTab.LIBRARY -> if (mutableState.value.library.page == 0) content.loadLibrary(reset = true)
            VideoProTab.SEARCH ->
                if (mutableState.value.search.query
                        .isNotBlank()
                ) {
                    content.updateSearchQuery(mutableState.value.search.query)
                }
            VideoProTab.PLAYLISTS -> if (mutableState.value.playlists.data == null) content.loadPlaylists()
            VideoProTab.ME -> Unit
        }
    }

    fun openRail() {
        if (mutableState.value.session is VideoProSessionUiState.SignedIn) {
            mutableState.value = mutableState.value.copy(railExpanded = true)
        }
    }

    fun closeRail() {
        val current = mutableState.value
        if (!current.railExpanded) return
        mutableState.value =
            current.copy(
                railExpanded = false,
                contentFocusRevision = current.contentFocusRevision + 1L,
            )
    }

    fun openDetail(
        variantId: String,
        title: String?,
    ) {
        val route = VideoProRoute.Detail(variantId, title)
        mutableState.value =
            mutableState.value.copy(
                route = route,
                backStack = mutableState.value.backStack + route,
                railExpanded = false,
                notice = null,
                contentFocusRevision = mutableState.value.contentFocusRevision + 1L,
            )
        if (mutableState.value.detail.variantId != variantId || mutableState.value.detail.data == null) {
            content.loadDetail(variantId)
        }
    }

    fun back(): Boolean {
        val current = mutableState.value
        return when {
            current.railExpanded -> {
                closeRail()
                true
            }
            current.backStack.size > 1 -> {
                val stack = current.backStack.dropLast(1)
                mutableState.value =
                    current.copy(
                        route = stack.last(),
                        backStack = stack,
                        notice = null,
                        contentFocusRevision = current.contentFocusRevision + 1L,
                    )
                true
            }
            current.route != VideoProRoute.Tab(VideoProTab.HOME) -> {
                selectTab(VideoProTab.HOME)
                true
            }
            else -> false
        }
    }

    fun setNotice(message: String?) {
        mutableState.value = mutableState.value.copy(notice = message)
    }

    fun close() {
        restoreJob?.cancel()
        loginJob?.cancel()
        content.cancelAll()
    }
}

internal fun Throwable.toVideoProFailure(): VideoProFailure =
    when (this) {
        is DongpianException ->
            when (kind) {
                DongpianFailureKind.INVALID_INPUT -> VideoProFailure.INVALID_INPUT
                DongpianFailureKind.UNAUTHORIZED -> VideoProFailure.UNAUTHORIZED
                DongpianFailureKind.NETWORK,
                DongpianFailureKind.TRANSPORT,
                -> VideoProFailure.NETWORK
                DongpianFailureKind.RESPONSE_TOO_LARGE,
                DongpianFailureKind.INVALID_RESPONSE,
                -> VideoProFailure.RESPONSE
                DongpianFailureKind.SESSION_STORAGE -> VideoProFailure.STORAGE
                DongpianFailureKind.HTTP,
                DongpianFailureKind.SIGNING_KEY_UNAVAILABLE,
                DongpianFailureKind.SYNC_NOT_ACCEPTED,
                -> VideoProFailure.SERVICE
            }
        is IllegalArgumentException -> VideoProFailure.INVALID_INPUT
        else -> VideoProFailure.UNKNOWN
    }

internal fun VideoProFailure.message(strings: VideoProStrings): String =
    when (this) {
        VideoProFailure.INVALID_INPUT -> strings.invalidInputError
        VideoProFailure.UNAUTHORIZED -> strings.unauthorizedError
        VideoProFailure.NETWORK -> strings.networkError
        VideoProFailure.SERVICE -> strings.serviceError
        VideoProFailure.RESPONSE -> strings.responseError
        VideoProFailure.STORAGE -> strings.storageError
        VideoProFailure.UNKNOWN -> strings.unknownError
    }
