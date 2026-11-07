package dev.jellystack.design.tv

import com.dongpiandi.videopro.data.DongpianException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class VideoProContentController(
    private val scope: CoroutineScope,
    private val catalogRepository: VideoProCatalogGateway,
    private val readState: () -> VideoProUiState,
    private val writeState: (VideoProUiState) -> Unit,
    private val searchDebounceMillis: Long = 350L,
) {
    private var homeJob: Job? = null
    private var libraryJob: Job? = null
    private var searchJob: Job? = null
    private var playlistJob: Job? = null
    private var detailJob: Job? = null

    private var homeGeneration = 0L
    private var libraryGeneration = 0L
    private var searchGeneration = 0L
    private var playlistGeneration = 0L
    private var detailGeneration = 0L

    fun loadHome() {
        val generation = ++homeGeneration
        homeJob?.cancel()
        writeState(readState().copy(home = VideoProHomeState(loading = true)))
        homeJob =
            scope.launch {
                try {
                    val result = catalogRepository.loadHome()
                    if (generation != homeGeneration) return@launch
                    writeState(readState().copy(home = VideoProHomeState(data = result)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    if (generation != homeGeneration) return@launch
                    writeState(readState().copy(home = VideoProHomeState(failure = failure.toVideoProFailure())))
                }
            }
    }

    fun loadLibrary(reset: Boolean) {
        val current = readState()
        if (current.session !is VideoProSessionUiState.SignedIn) return
        val canLoadMore =
            current.library.hasMore &&
                !current.library.loading &&
                !current.library.loadingMore
        if (!reset && !canLoadMore) return
        val page = if (reset) 1 else current.library.page + 1
        val generation = ++libraryGeneration
        libraryJob?.cancel()
        writeState(
            current.copy(
                library =
                    current.library.copy(
                        loading = reset,
                        loadingMore = !reset,
                        failure = null,
                    ),
            ),
        )
        libraryJob =
            scope.launch {
                try {
                    val result = catalogRepository.browse(page = page, limit = 20)
                    if (generation != libraryGeneration) return@launch
                    val previous = if (reset) emptyList() else readState().library.items
                    val merged =
                        (previous + result.items)
                            .distinctBy { it.variantId ?: it.id }
                    writeState(
                        readState().copy(
                            library =
                                VideoProCatalogState(
                                    items = merged,
                                    page = page,
                                    hasMore = result.hasMore,
                                ),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    if (generation != libraryGeneration) return@launch
                    writeState(
                        readState().copy(
                            library =
                                readState().library.copy(
                                    loading = false,
                                    loadingMore = false,
                                    failure = failure.toVideoProFailure(),
                                ),
                        ),
                    )
                }
            }
    }

    fun updateSearchQuery(query: String) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        val normalized = query.trimStart()
        if (normalized.isBlank()) {
            writeState(readState().copy(search = VideoProSearchState(query = normalized)))
            return
        }
        writeState(
            readState().copy(
                search =
                    readState().search.copy(
                        query = normalized,
                        loading = true,
                        failure = null,
                    ),
            ),
        )
        searchJob =
            scope.launch {
                delay(searchDebounceMillis)
                try {
                    val result = catalogRepository.search(normalized.trim(), page = 1, limit = 24)
                    if (generation != searchGeneration) return@launch
                    writeState(
                        readState().copy(
                            search =
                                VideoProSearchState(
                                    query = normalized,
                                    items = result.items,
                                ),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    if (generation != searchGeneration) return@launch
                    writeState(
                        readState().copy(
                            search =
                                readState().search.copy(
                                    loading = false,
                                    failure = failure.toVideoProFailure(),
                                ),
                        ),
                    )
                }
            }
    }

    fun loadPlaylists() {
        val generation = ++playlistGeneration
        playlistJob?.cancel()
        writeState(readState().copy(playlists = VideoProPlaylistState(loading = true)))
        playlistJob =
            scope.launch {
                try {
                    val result = catalogRepository.loadPlaylists()
                    if (generation != playlistGeneration) return@launch
                    writeState(readState().copy(playlists = VideoProPlaylistState(data = result)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    if (generation != playlistGeneration) return@launch
                    writeState(
                        readState().copy(
                            playlists = VideoProPlaylistState(failure = failure.toVideoProFailure()),
                        ),
                    )
                }
            }
    }

    fun loadDetail(variantId: String) {
        val generation = ++detailGeneration
        detailJob?.cancel()
        writeState(readState().copy(detail = VideoProDetailState(variantId = variantId, loading = true)))
        detailJob =
            scope.launch {
                try {
                    val detail = catalogRepository.loadDetail(variantId)
                    val episodePage =
                        catalogRepository.loadEpisodes(
                            variantId = detail.variantId,
                            offset = 0,
                            limit = 100,
                        )
                    if (generation != detailGeneration) return@launch
                    val episodes =
                        (detail.episodes + episodePage.items)
                            .distinctBy { it.token.ifBlank { it.id } }
                    writeState(
                        readState().copy(
                            detail =
                                VideoProDetailState(
                                    variantId = variantId,
                                    data = detail,
                                    episodes = episodes,
                                ),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DongpianException) {
                    if (generation != detailGeneration) return@launch
                    writeState(
                        readState().copy(
                            detail =
                                VideoProDetailState(
                                    variantId = variantId,
                                    failure = failure.toVideoProFailure(),
                                ),
                        ),
                    )
                }
            }
    }

    fun cancelAll() {
        homeJob?.cancel()
        libraryJob?.cancel()
        searchJob?.cancel()
        playlistJob?.cancel()
        detailJob?.cancel()
        homeJob = null
        libraryJob = null
        searchJob = null
        playlistJob = null
        detailJob = null
    }
}
