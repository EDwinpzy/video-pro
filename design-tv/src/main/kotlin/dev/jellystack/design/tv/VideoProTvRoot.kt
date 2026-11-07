@file:Suppress("FunctionName", "FunctionNaming")

package dev.jellystack.design.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.dongpiandi.videopro.data.DongpianCatalogRepository
import com.dongpiandi.videopro.data.DongpianSessionRepository
import dev.jellystack.core.di.JellystackDI
import kotlinx.coroutines.delay

@Composable
fun VideoProTvRoot(
    modifier: Modifier = Modifier,
    onExitConfirmed: () -> Unit = {},
    systemDictationEnabled: Boolean = false,
    onOpenSystemDictationSettings: () -> Unit = {},
) {
    val koin = remember { JellystackDI.koin }
    val sessionRepository = remember(koin) { koin.get<DongpianSessionRepository>() }
    val catalogRepository = remember(koin) { koin.get<DongpianCatalogRepository>() }
    val sessionGateway = remember(sessionRepository) { DataVideoProSessionGateway(sessionRepository) }
    val catalogGateway = remember(catalogRepository) { DataVideoProCatalogGateway(catalogRepository) }
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(sessionGateway, catalogGateway, scope) {
            VideoProCoordinator(
                scope = scope,
                sessionRepository = sessionGateway,
                catalogRepository = catalogGateway,
            )
        }
    DisposableEffect(coordinator) {
        coordinator.start()
        onDispose(coordinator::close)
    }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val strings = ChineseVideoProStrings
    JellystackTvTheme {
        when (val session = state.session) {
            VideoProSessionUiState.Loading ->
                VideoProSessionStatus(
                    message = strings.sessionRestoring,
                    action = null,
                    onAction = {},
                    modifier = modifier,
                )
            is VideoProSessionUiState.Failed ->
                VideoProSessionStatus(
                    message = session.failure.message(strings),
                    action = strings.sessionRetry,
                    onAction = coordinator::start,
                    modifier = modifier,
                )
            is VideoProSessionUiState.SignedOut -> {
                BackHandler(onBack = onExitConfirmed)
                VideoProLoginScreen(
                    strings = strings,
                    state = state.login,
                    onLogin = coordinator::login,
                    modifier = modifier,
                )
            }
            is VideoProSessionUiState.SignedIn ->
                VideoProSignedInRoot(
                    state = state,
                    strings = strings,
                    coordinator = coordinator,
                    onExitConfirmed = onExitConfirmed,
                    systemDictationEnabled = systemDictationEnabled,
                    onOpenSystemDictationSettings = onOpenSystemDictationSettings,
                    modifier = modifier,
                )
        }
    }
}

@Composable
private fun VideoProSignedInRoot(
    state: VideoProUiState,
    strings: VideoProStrings,
    coordinator: VideoProCoordinator,
    onExitConfirmed: () -> Unit,
    systemDictationEnabled: Boolean,
    onOpenSystemDictationSettings: () -> Unit,
    modifier: Modifier,
) {
    val actions =
        remember(coordinator, strings) {
            VideoProActions(
                navigation =
                    VideoProNavigationActions(
                        openDetail = { card ->
                            val variantId = card.variantId?.takeIf(String::isNotBlank) ?: card.id
                            if (variantId.isNotBlank()) coordinator.openDetail(variantId, card.title)
                        },
                        back = { coordinator.back() },
                    ),
                browse =
                    VideoProBrowseActions(
                        retryHome = coordinator.content::loadHome,
                        retryLibrary = { coordinator.content.loadLibrary(reset = true) },
                        loadMoreLibrary = { coordinator.content.loadLibrary(reset = false) },
                        retryPlaylists = coordinator.content::loadPlaylists,
                        openPlaylist = { coordinator.setNotice(strings.playlistPendingNotice) },
                    ),
                searchDetail =
                    VideoProSearchDetailActions(
                        updateSearch = coordinator.content::updateSearchQuery,
                        retrySearch = {
                            val query = coordinator.state.value.search.query
                            if (query.isNotBlank()) coordinator.content.updateSearchQuery(query)
                        },
                        retryDetail = {
                            val detail = coordinator.state.value.route as? VideoProRoute.Detail
                            if (detail != null) coordinator.content.loadDetail(detail.variantId)
                        },
                        openEpisode = { coordinator.setNotice(strings.playerPendingNotice) },
                    ),
                account = VideoProAccountActions(logout = coordinator::logout),
                dictation =
                    VideoProDictationActions(
                        enabled = systemDictationEnabled,
                        openSettings = onOpenSystemDictationSettings,
                    ),
            )
        }
    BackHandler {
        if (!coordinator.back()) onExitConfirmed()
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(4_000L)
            coordinator.setNotice(null)
        }
    }
    CompositionLocalProvider(LocalTvNavigationRailOpener provides coordinator::openRail) {
        Box(modifier.fillMaxSize().background(TvBackground)) {
            VideoProRouteContent(
                state = state,
                strings = strings,
                actions = actions,
            )
            if (state.railExpanded) {
                VideoProNavigationRail(
                    selectedTab = (state.route as? VideoProRoute.Tab)?.tab ?: VideoProTab.HOME,
                    strings = strings,
                    onSelect = coordinator::selectTab,
                    onDismiss = coordinator::closeRail,
                )
            }
            state.notice?.let { notice ->
                VideoProNotice(notice)
            }
        }
    }
}

@Composable
private fun VideoProRouteContent(
    state: VideoProUiState,
    strings: VideoProStrings,
    actions: VideoProActions,
) {
    val sessionName = (state.session as? VideoProSessionUiState.SignedIn)?.session?.displayName.orEmpty()
    when (val route = state.route) {
        is VideoProRoute.Tab ->
            when (route.tab) {
                VideoProTab.HOME ->
                    VideoProHomeScreen(
                        state = state.home,
                        strings = strings,
                        actions = actions,
                        focusRevision = state.contentFocusRevision,
                    )
                VideoProTab.LIBRARY ->
                    VideoProLibraryScreen(
                        state = state.library,
                        strings = strings,
                        actions = actions,
                        focusRevision = state.contentFocusRevision,
                    )
                VideoProTab.SEARCH ->
                    VideoProSearchScreen(
                        state = state.search,
                        strings = strings,
                        actions = actions,
                        focusRevision = state.contentFocusRevision,
                    )
                VideoProTab.PLAYLISTS ->
                    VideoProPlaylistsScreen(
                        state = state.playlists,
                        strings = strings,
                        actions = actions,
                        focusRevision = state.contentFocusRevision,
                    )
                VideoProTab.ME ->
                    VideoProMeScreen(
                        sessionName = sessionName,
                        strings = strings,
                        actions = actions,
                        focusRevision = state.contentFocusRevision,
                    )
            }
        is VideoProRoute.Detail ->
            VideoProDetailScreen(
                state = state.detail,
                strings = strings,
                actions = actions,
                focusRevision = state.contentFocusRevision,
            )
    }
}

@Composable
private fun VideoProNavigationRail(
    selectedTab: VideoProTab,
    strings: VideoProStrings,
    onSelect: (VideoProTab) -> Unit,
    onDismiss: () -> Unit,
) {
    val requesters = remember { VideoProTab.entries.associateWith { FocusRequester() } }
    LaunchedEffect(selectedTab) {
        requesters.getValue(selectedTab).requestFocus()
    }
    val entries =
        listOf(
            VideoProRailEntry(VideoProTab.HOME, strings.home, Icons.Default.Home),
            VideoProRailEntry(VideoProTab.LIBRARY, strings.library, Icons.Default.VideoLibrary),
            VideoProRailEntry(VideoProTab.SEARCH, strings.search, Icons.Default.Search),
            VideoProRailEntry(VideoProTab.PLAYLISTS, strings.playlists, Icons.AutoMirrored.Filled.PlaylistPlay),
            VideoProRailEntry(VideoProTab.ME, strings.me, Icons.Default.Person),
        )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.54f))) {
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(TvLayoutTokens.ExpandedRailWidth + 44.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF210306), Color(0xF2210306), Color.Transparent),
                            ),
                        ).padding(horizontal = 22.dp, vertical = TvLayoutTokens.SafeInsets.vertical),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    strings.brand,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color(0xFFFF5A68),
                    fontSize = 25.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                entries.forEach { entry ->
                    VideoProRailItem(
                        entry = entry,
                        selected = entry.tab == selectedTab,
                        focusRequester = requesters.getValue(entry.tab),
                        onSelect = { onSelect(entry.tab) },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoProRailItem(
    entry: VideoProRailEntry,
    selected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .onPreviewKeyEvent { event ->
                    selected &&
                        event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        run {
                            onDismiss()
                            true
                        }
                }.semantics(mergeDescendants = true) {
                    contentDescription = entry.label
                    this.selected = selected
                }.tvFocusable(
                    onClick = onSelect,
                    shape = RoundedCornerShape(16.dp),
                    focusTargetId = "video-pro:rail:${entry.tab.name.lowercase()}",
                    providedFocusRequester = focusRequester,
                    fill = if (selected) TvPurpleStrong.copy(alpha = 0.52f) else Color.Transparent,
                ).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            entry.icon,
            contentDescription = null,
            tint = if (selected) TvPurple else TvTextMuted,
        )
        Text(
            entry.label,
            color = if (selected) TvText else TvTextMuted,
            fontSize = 19.sp,
        )
    }
}

@Composable
private fun VideoProSessionStatus(
    message: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(action) {
        if (action != null) actionFocus.requestFocus()
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF650012), Color(0xFF1B0308), Color(0xFF060102)),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            VideoProBrandMark()
            TvStatusAnchor(message)
            if (action != null) {
                TvActionButton(
                    label = action,
                    onClick = onAction,
                    primary = true,
                    focusRequester = actionFocus,
                )
            }
        }
    }
}

@Composable
private fun VideoProNotice(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = message,
            modifier =
                Modifier
                    .background(TvSurfaceRaised, RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            color = TvText,
            fontSize = 18.sp,
        )
    }
}

private data class VideoProRailEntry(
    val tab: VideoProTab,
    val label: String,
    val icon: ImageVector,
)
