@file:Suppress("FunctionName", "FunctionNaming")

package dev.jellystack.design.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * V1 playlists entry point. The Dongpiandi playlist data source is wired in a later migration phase;
 * keeping this screen local prevents the retired Seerr discovery surface from becoming reachable.
 */
@Composable
internal fun TvPlaylistsScreen(
    strings: TvStrings,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(TvBackground)
                .padding(TvScreenPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = strings.discover,
                modifier = Modifier.semantics { heading() },
                color = TvText,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.tvTagline,
                color = TvTextMuted,
                fontSize = 20.sp,
            )
        }
    }
}
