package dev.jellystack.design.tv

import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianEpisode
import com.dongpiandi.videopro.data.model.DongpianPlaylistSummary

internal class VideoProActions(
    val navigation: VideoProNavigationActions,
    val browse: VideoProBrowseActions,
    val searchDetail: VideoProSearchDetailActions,
    val account: VideoProAccountActions,
    val dictation: VideoProDictationActions,
)

internal class VideoProNavigationActions(
    val openDetail: (DongpianCatalogCard) -> Unit,
    val back: () -> Unit,
)

internal class VideoProBrowseActions(
    val retryHome: () -> Unit,
    val retryLibrary: () -> Unit,
    val loadMoreLibrary: () -> Unit,
    val retryPlaylists: () -> Unit,
    val openPlaylist: (DongpianPlaylistSummary) -> Unit,
)

internal class VideoProSearchDetailActions(
    val updateSearch: (String) -> Unit,
    val retrySearch: () -> Unit,
    val retryDetail: () -> Unit,
    val openEpisode: (DongpianEpisode) -> Unit,
)

internal class VideoProAccountActions(
    val logout: () -> Unit,
)

internal class VideoProDictationActions(
    val enabled: Boolean,
    val openSettings: () -> Unit,
)
