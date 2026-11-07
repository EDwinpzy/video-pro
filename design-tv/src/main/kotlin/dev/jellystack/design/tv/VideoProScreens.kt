@file:Suppress("FunctionName", "FunctionNaming")

package dev.jellystack.design.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianHomeSection

@Composable
internal fun VideoProHomeScreen(
    state: VideoProHomeState,
    strings: VideoProStrings,
    actions: VideoProActions,
    focusRevision: Long,
    modifier: Modifier = Modifier,
) {
    val heroFocus = remember { FocusRequester() }
    val hero =
        state.data
            ?.sections
            ?.firstOrNull { it.cards.isNotEmpty() }
            ?.cards
            ?.firstOrNull()
    val hasHomeContent = state.data?.sections?.any { it.cards.isNotEmpty() } == true
    LaunchedEffect(hero?.variantId, hero?.id, focusRevision) {
        if (hero != null) heroFocus.requestFocus()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = TvScreenPadding,
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item("header") { VideoProPageHeader(strings.home, strings.homeSubtitle) }
        when {
            state.loading && state.data == null -> item("loading") { VideoProLoadingPanel(strings) }
            state.failure != null && state.data == null ->
                item("failure") {
                    VideoProFailurePanel(
                        message = state.failure.message(strings),
                        retryLabel = strings.retry,
                        onRetry = actions.browse.retryHome,
                    )
                }
            !hasHomeContent ->
                item("empty") { VideoProEmptyPanel(strings.emptyHome) }
            else -> {
                hero?.let {
                    item("hero") {
                        VideoProHero(
                            card = it,
                            strings = strings,
                            onOpenDetail = { actions.navigation.openDetail(it) },
                            focusRequester = heroFocus,
                        )
                    }
                }
                state.data.sections.forEach { section ->
                    if (section.cards.isNotEmpty()) {
                        item("section:${section.id}") {
                            VideoProSection(section, strings, actions.navigation.openDetail)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoProLibraryScreen(
    state: VideoProCatalogState,
    strings: VideoProStrings,
    actions: VideoProActions,
    focusRevision: Long,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state.items.firstOrNull()?.variantId, state.items.firstOrNull()?.id, focusRevision) {
        if (state.items.isNotEmpty()) firstFocus.requestFocus()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = TvScreenPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item("header") { VideoProPageHeader(strings.library, strings.librarySubtitle) }
        when {
            state.loading && state.items.isEmpty() -> item("loading") { VideoProLoadingPanel(strings) }
            state.failure != null && state.items.isEmpty() ->
                item("failure") {
                    VideoProFailurePanel(
                        message = state.failure.message(strings),
                        retryLabel = strings.retry,
                        onRetry = actions.browse.retryLibrary,
                    )
                }
            state.items.isEmpty() -> item("empty") { VideoProEmptyPanel(strings.emptyLibrary) }
            else -> {
                state.items.chunked(6).forEachIndexed { rowIndex, row ->
                    item("row:$rowIndex") {
                        VideoProCatalogRow(
                            items = row,
                            onOpenDetail = actions.navigation.openDetail,
                            firstFocusRequester = firstFocus.takeIf { rowIndex == 0 },
                        )
                    }
                }
                if (state.failure != null) {
                    item("more-failure") {
                        VideoProFailurePanel(
                            message = state.failure.message(strings),
                            retryLabel = strings.retry,
                            onRetry = actions.browse.loadMoreLibrary,
                        )
                    }
                }
                item("load-more") {
                    when {
                        state.loadingMore -> TvStatusAnchor(strings.loadingMore)
                        state.hasMore ->
                            TvActionButton(
                                label = strings.loadMore,
                                onClick = actions.browse.loadMoreLibrary,
                                primary = true,
                            )
                        else -> TvStatusAnchor(strings.allLoaded, announce = false)
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoProSearchScreen(
    state: VideoProSearchState,
    strings: VideoProStrings,
    actions: VideoProActions,
    focusRevision: Long,
    modifier: Modifier = Modifier,
) {
    val queryFocus = remember { FocusRequester() }
    LaunchedEffect(focusRevision) { queryFocus.requestFocus() }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(TvScreenPadding),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        VideoProPageHeader(strings.search, strings.searchSubtitle)
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.searchDetail.updateSearch,
            placeholder = { Text(strings.searchHint) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            colors = tvOutlinedTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.searchDetail.retrySearch() }),
            modifier =
                Modifier
                    .width(760.dp)
                    .focusRequester(queryFocus)
                    .testTag("video-pro-search-query")
                    .tvReturnToNavigationRailOnLeft(),
        )
        when {
            state.query.isBlank() ->
                TvStatusAnchor(
                    label = strings.searchHint,
                    modifier = Modifier.tvReturnToNavigationRailOnLeft(),
                )
            state.loading -> TvLoading(strings.searching, Modifier.weight(1f))
            state.failure != null ->
                VideoProFailurePanel(
                    message = state.failure.message(strings),
                    retryLabel = strings.retry,
                    onRetry = actions.searchDetail.retrySearch,
                )
            state.items.isEmpty() -> VideoProEmptyPanel(strings.searchEmpty)
            else ->
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    state.items.chunked(6).forEachIndexed { rowIndex, row ->
                        item("result-row:$rowIndex") {
                            VideoProCatalogRow(
                                items = row,
                                onOpenDetail = actions.navigation.openDetail,
                                firstFocusRequester = null,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
internal fun VideoProPlaylistsScreen(
    state: VideoProPlaylistState,
    strings: VideoProStrings,
    actions: VideoProActions,
    focusRevision: Long,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    val playlists = state.data?.items.orEmpty()
    LaunchedEffect(playlists.firstOrNull()?.id, focusRevision) {
        if (playlists.isNotEmpty()) firstFocus.requestFocus()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = TvScreenPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item("header") { VideoProPageHeader(strings.playlists, strings.playlistsSubtitle) }
        when {
            state.loading -> item("loading") { VideoProLoadingPanel(strings) }
            state.failure != null ->
                item("failure") {
                    VideoProFailurePanel(
                        message = state.failure.message(strings),
                        retryLabel = strings.retry,
                        onRetry = actions.browse.retryPlaylists,
                    )
                }
            playlists.isEmpty() -> item("empty") { VideoProEmptyPanel(strings.emptyPlaylists) }
            else ->
                playlists.chunked(5).forEachIndexed { rowIndex, row ->
                    item("playlist-row:$rowIndex") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(TvLayoutTokens.CardSpacing),
                        ) {
                            itemsIndexed(row, key = { _, item -> item.id }) { index, item ->
                                TvMediaCard(
                                    title = item.title,
                                    subtitle = item.itemCount?.let(strings::playlistCount),
                                    imageUrl = item.coverUrl,
                                    format = TvMediaCardFormat.POSTER,
                                    onClick = { actions.browse.openPlaylist(item) },
                                    focusToNavigationRailOnLeft = rowIndex == 0 && index == 0,
                                    providedFocusRequester = firstFocus.takeIf { rowIndex == 0 && index == 0 },
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
internal fun VideoProMeScreen(
    sessionName: String,
    strings: VideoProStrings,
    actions: VideoProActions,
    focusRevision: Long,
    modifier: Modifier = Modifier,
) {
    val firstActionFocus = remember { FocusRequester() }
    LaunchedEffect(focusRevision) { firstActionFocus.requestFocus() }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(TvScreenPadding),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        VideoProPageHeader(strings.me, strings.meSubtitle)
        Column(
            modifier =
                Modifier
                    .width(620.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvSurfaceRaised)
                    .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.currentAccount, color = TvTextMuted, fontSize = 18.sp)
            Text(sessionName, color = TvText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier =
                Modifier
                    .width(620.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvSurfaceRaised)
                    .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                strings.systemDictationTitle,
                color = TvText,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(strings.systemDictationDescription, color = TvTextMuted, fontSize = 17.sp)
            Text(
                if (actions.dictation.enabled) {
                    strings.systemDictationEnabled
                } else {
                    strings.systemDictationDisabled
                },
                color = if (actions.dictation.enabled) Color(0xFF68D391) else TvTextMuted,
                fontSize = 17.sp,
            )
        }
        TvActionButton(
            label = strings.systemDictationAction,
            onClick = actions.dictation.openSettings,
            primary = true,
            focusRequester = firstActionFocus,
            focusToNavigationRailOnLeft = true,
        )
        TvActionButton(
            label = strings.logout,
            onClick = actions.account.logout,
            destructive = true,
            focusToNavigationRailOnLeft = true,
        )
    }
}

@Composable
private fun VideoProHero(
    card: DongpianCatalogCard,
    strings: VideoProStrings,
    onOpenDetail: () -> Unit,
    focusRequester: FocusRequester,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(TvSurface),
    ) {
        AsyncImage(
            model = card.backdropUrl ?: card.carouselUrl ?: card.posterUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF2080910), Color(0xC0080910), Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                card.title,
                color = TvText,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(card.metadata(), color = TvTextMuted, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    label = strings.details,
                    onClick = onOpenDetail,
                    primary = true,
                    focusRequester = focusRequester,
                    focusToNavigationRailOnLeft = true,
                )
                TvActionButton(
                    label = strings.playPending,
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun VideoProSection(
    section: DongpianHomeSection,
    strings: VideoProStrings,
    onOpenDetail: (DongpianCatalogCard) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvSectionTitle(section.title?.takeIf(String::isNotBlank) ?: strings.home)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(TvLayoutTokens.CardSpacing)) {
            itemsIndexed(section.cards, key = { _, item -> "${item.id}:${item.variantId}" }) { index, card ->
                TvMediaCard(
                    title = card.title,
                    subtitle = card.metadata(),
                    imageUrl = card.posterUrl ?: card.backdropUrl,
                    format = TvMediaCardFormat.POSTER,
                    onClick = { onOpenDetail(card) },
                    focusToNavigationRailOnLeft = index == 0,
                )
            }
        }
    }
}

@Composable
private fun VideoProCatalogRow(
    items: List<DongpianCatalogCard>,
    onOpenDetail: (DongpianCatalogCard) -> Unit,
    firstFocusRequester: FocusRequester?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(TvLayoutTokens.CardSpacing)) {
        items.forEachIndexed { index, card ->
            TvMediaCard(
                title = card.title,
                subtitle = card.metadata(),
                imageUrl = card.posterUrl ?: card.backdropUrl,
                format = TvMediaCardFormat.POSTER,
                onClick = { onOpenDetail(card) },
                focusToNavigationRailOnLeft = index == 0,
                providedFocusRequester = firstFocusRequester.takeIf { index == 0 },
            )
        }
    }
}
