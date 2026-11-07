@file:Suppress("FunctionName", "FunctionNaming")

package dev.jellystack.design.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianDetail
import com.dongpiandi.videopro.data.model.DongpianEpisode

@Composable
internal fun VideoProPageHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, modifier = Modifier.tvHeading(), color = TvText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TvTextMuted, fontSize = 18.sp)
    }
}

@Composable
internal fun VideoProFailurePanel(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(TvSurfaceRaised)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvStatusAnchor(message)
        TvActionButton(retryLabel, onRetry, primary = true)
    }
}

@Composable
internal fun VideoProLoadingPanel(strings: VideoProStrings) {
    TvLoading(strings.loading, Modifier.height(280.dp).fillMaxWidth())
}

@Composable
internal fun VideoProEmptyPanel(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TvSurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = TvTextMuted, fontSize = 20.sp)
    }
}

@Composable
internal fun VideoProBrandMark() {
    Image(
        painter = painterResource(R.drawable.video_pro_mark),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(118.dp),
    )
}

internal fun DongpianCatalogCard.metadata(): String =
    buildList {
        year?.let { add(it.toString()) }
        genres.take(2).forEach(::add)
    }.joinToString("  ·  ")

internal fun DongpianDetail.metadata(): String =
    buildList {
        year?.let { add(it.toString()) }
        area?.takeIf(String::isNotBlank)?.let(::add)
        genres.take(2).forEach(::add)
    }.joinToString("  ·  ")

internal fun DongpianEpisode.label(strings: VideoProStrings): String =
    title?.takeIf(String::isNotBlank)
        ?: number?.let(strings::episodeNumber)
        ?: strings.episodeFallback
