package com.qiubo.optimaltv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.Coil
import coil.request.SuccessResult
import com.qiubo.optimaltv.auth.DongpianAuthClient
import com.qiubo.optimaltv.data.model.posterCandidatesFor
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.components.VideoProStartupLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun HomeStartupGate(content: @Composable () -> Unit) {
    val revision by DongpianAuthClient.sessionRevisionState.collectAsStateWithLifecycle()
    var ready by remember(revision) { mutableStateOf(false) }
    var entered by remember(revision) { mutableStateOf(false) }
    var failed by remember(revision) { mutableStateOf(false) }
    var attempt by remember(revision) { mutableIntStateOf(0) }
    var restoreFocusAfterRetry by remember(revision) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(ready) {
        if (ready && restoreFocusAfterRetry) {
            withFrameNanos { }
            focusManager.moveFocus(FocusDirection.Next)
            restoreFocusAfterRetry = false
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, revision) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopped = true
            if (event == Lifecycle.Event.ON_START && stopped) {
                stopped = false
                ready = false
                OtvLog.i("startup foreground_revalidate")
                attempt++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, attempt) {
        failed = false
        try {
            val loaded = withTimeoutOrNull(180_000) {
                prepareHomeEntry()
                true
            }
            if (loaded == true) {
                ready = true
                entered = true
                OtvLog.i("startup all_home_first_rows_ready")
            } else {
                failed = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            OtvLog.w("startup preload failed: ${failure.javaClass.simpleName}")
            failed = true
        }
    }
    // Keep navigation and playback composed under the foreground refresh curtain.
    // Returning from Home must not recreate a player or lose its episode/progress.
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        !ready && !failed && it.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)
    }) {
        if (entered) content()
        if (!ready) {
            StartupLoadingScreen(failed) {
                restoreFocusAfterRetry = entered
                attempt++
            }
        }
    }
}

@Composable
private fun StartupLoadingScreen(
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    InitialFocusEffect(retryFocus, "startup-retry", enabled = failed)
    if (!failed) {
        VideoProStartupLoading()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF050507)).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.video_pro_startup_icon),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(168.dp),
            )
            Spacer(Modifier.height(30.dp))
            Text(stringResource(R.string.startup_failed), color = Color(0xFFB8BAC5))
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                Text(stringResource(R.string.startup_retry))
            }
        }
    }
}

private suspend fun prepareHomeEntry() = coroutineScope {
    val categories = listOf("movie", "tv", "anime", "short", "variety")
    preloadHomeTabs(
        categories = categories,
        loadTab = { category ->
            retryStartupLoad(
                onFailure = { attempt, failure ->
                    OtvLog.w(
                        "startup home_tab_retry category=$category attempt=$attempt " +
                            "reason=${failure.javaClass.simpleName}",
                    )
                },
            ) {
                val firstRow = Graph.repo.loadHomeFirstSection(category, revalidate = true)
                OtvLog.i("startup first_section_loaded category=$category items=${firstRow.size}")
                firstRow
                    .take(STARTUP_FIRST_SECTION_ARTWORK_LIMIT)
                    .map { TvImagePreload(it.posterUrl, false) }
            }
        },
        loadArtwork = { cards ->
            val history = Graph.db.vodDao().historyFlow(Int.MAX_VALUE).first()
            val heroPreviews =
                history.asSequence()
                    .sortedByDescending { it.updatedAt }
                    .distinctBy { it.vodId }
                    .take(STARTUP_HERO_PREVIEW_LIMIT)
                    .map { TvImagePreload(it.posterUrl, true) }
                    .toList()
            val firstRowArtwork = cards.filter { it.url.isNotBlank() }.distinct()
            if (heroPreviews.isNotEmpty()) {
                Graph.appScope.launch {
                    preloadAllHomeArtwork(
                        images = heroPreviews.filter { it.url.isNotBlank() }.distinct(),
                        requireAll = false,
                    )
                }
            }
            preloadAllHomeArtwork(firstRowArtwork)
        },
        onTabReady = { _, _ -> },
    )
}

/** Enough cards to fill the first visible two-row section on a 1080p TV. */
private const val STARTUP_FIRST_SECTION_ARTWORK_LIMIT = 12
private const val STARTUP_HERO_PREVIEW_LIMIT = 5

/** Bounded decode waves, no card-count cap or timeout that silently admits partial content. */
private suspend fun preloadAllHomeArtwork(
    images: List<TvImagePreload>,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    requireAll: Boolean = true,
) = coroutineScope {
    val loader = Coil.imageLoader(Graph.appContext)
    val display = Graph.appContext.resources.displayMetrics
    val permits = Semaphore(4)
    var completed = 0
    onProgress(0, images.size)
    images.map { image ->
        async {
            permits.withPermit {
                val candidates = if (image.hero) listOf(image.url) else posterCandidatesFor(image.url)
                var success = false
                for (attempt in 0 until 2) {
                    for (url in candidates) {
                        val builder = if (image.hero) {
                            TvImageRequestSpec.heroRequest(Graph.appContext, url, TvImageRequestSpec.heroSize(display.widthPixels))
                        } else {
                            TvImageRequestSpec.posterRequest(Graph.appContext, url, TvImageRequestSpec.posterSize(display.widthPixels))
                        }
                        val result = withContext(Dispatchers.IO) {
                            loader.execute(builder.build())
                        }
                        if (result is SuccessResult && result.drawable.intrinsicWidth > 1 && result.drawable.intrinsicHeight > 1) {
                            success = true
                            break
                        }
                    }
                    if (success || attempt == 1) break
                    kotlinx.coroutines.delay(500L)
                }
                if (requireAll) check(success) { "Artwork unavailable" }
                onProgress(++completed, images.size)
            }
        }
    }.awaitAll()
}
