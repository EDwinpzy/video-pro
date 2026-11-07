@file:Suppress("FunctionName", "FunctionNaming")

package dev.jellystack.design.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.dongpiandi.videopro.data.model.DongpianDetail

@Composable
internal fun VideoProDetailScreen(
    state: VideoProDetailState,
    strings: VideoProStrings,
    actions: VideoProActions,
    focusRevision: Long,
    modifier: Modifier = Modifier,
) {
    val backFocus = remember { FocusRequester() }
    val detail = state.data
    LaunchedEffect(detail?.variantId, focusRevision) {
        backFocus.requestFocus()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = TvScreenPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item("back") {
            TvActionButton(
                label = strings.back,
                onClick = actions.navigation.back,
                leading = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                focusRequester = backFocus,
                focusToNavigationRailOnLeft = true,
            )
        }
        when {
            state.loading -> item("loading") { VideoProLoadingPanel(strings) }
            state.failure != null ->
                item("failure") {
                    VideoProFailurePanel(
                        message = state.failure.message(strings),
                        retryLabel = strings.retry,
                        onRetry = actions.searchDetail.retryDetail,
                    )
                }
            detail != null -> {
                item("hero") { VideoProDetailHero(detail, strings) }
                item("overview") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.overview, color = TvText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            detail.description?.takeIf(String::isNotBlank) ?: strings.noOverview,
                            color = TvTextMuted,
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                        )
                    }
                }
                item("episodes") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(strings.episodes, color = TvText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (state.episodes.isEmpty()) {
                            TvStatusAnchor(strings.noEpisodes)
                        } else {
                            state.episodes.chunked(4).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { episode ->
                                        TvActionButton(
                                            label = episode.label(strings),
                                            onClick = { actions.searchDetail.openEpisode(episode) },
                                            modifier = Modifier.width(260.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoProDetailHero(
    detail: DongpianDetail,
    strings: VideoProStrings,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(330.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(TvSurface),
    ) {
        AsyncImage(
            model = detail.backdropUrl ?: detail.posterUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2080910)))),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(detail.title, color = TvText, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(detail.metadata(), color = TvTextMuted, fontSize = 18.sp)
            TvActionButton(
                label = strings.playPending,
                onClick = {},
                enabled = false,
            )
        }
    }
}
