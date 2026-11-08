package com.qiubo.optimaltv.ui.home

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.focus.FocusHarnessActivity
import com.qiubo.optimaltv.focus.Harness
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

@OptIn(ExperimentalTestApi::class)
class HomeRecommendationNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @After
    fun tearDown() {
        Harness.scene = null
        Harness.nativeFocus = false
    }

    @Test
    fun downFromSingleRowReachesSectionCallback() {
        val firstCardFocus = FocusRequester()
        val downCalls = AtomicInteger()
        Harness.nativeFocus = true
        Harness.scene = {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val nav = rememberNavController()
                val rowState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column {
                            HomeRecommendationHeader(title = "大家都在看", scale = 1f)
                            HomeRecommendationRow(
                                nav = nav,
                                entries =
                                    listOf(
                                        VodItem(
                                            id = "test-vod",
                                            sourceId = "test",
                                            title = "底部署名测试",
                                            categoryId = "anime",
                                        ),
                                    ),
                                scale = 1f,
                                rowState = rowState,
                                firstCardFocus = firstCardFocus,
                                rows = 1,
                                onDownFromLastRow = { _ ->
                                    downCalls.incrementAndGet()
                                    true
                                },
                            )
                        }
                    }
                }
                InitialFocusEffect(firstCardFocus, "recommendation-card")
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.activity.hasWindowFocus() }
        rule.waitUntil(5_000) {
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }

        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)

        rule.waitUntil(3_000) { downCalls.get() > 0 }
        assertEquals(1, downCalls.get())
    }

    @Test
    fun downFromFirstSectionFocusesSecondSectionAndScrolls() {
        val firstCardFocus = FocusRequester()
        val secondCardFocus = FocusRequester()
        val moveStarted = AtomicBoolean(false)
        val moveFinished = AtomicBoolean(false)
        val listStateRef = AtomicReference<androidx.compose.foundation.lazy.LazyListState>()
        Harness.nativeFocus = true
        Harness.scene = {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val nav = rememberNavController()
                val listState = rememberLazyListState()
                val firstRowState = rememberLazyListState()
                val secondRowState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val scale = rememberUiScale()
                SideEffect { listStateRef.set(listState) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "hero") {
                        Spacer(Modifier.height((500f * scale).dp))
                    }
                    item(key = "recommendations-header:0") {
                        HomeRecommendationHeader(title = "大家都在看", scale = scale)
                    }
                    item(key = "recommendations-row:0") {
                        HomeRecommendationRow(
                            nav = nav,
                            entries = testEntries("第一区"),
                            scale = scale,
                            rowState = firstRowState,
                            firstCardFocus = firstCardFocus,
                            rows = 1,
                            onDownFromLastRow = { _ ->
                                moveStarted.set(true)
                                scope.launch {
                                    try {
                                        focusHomeSection(
                                            listState = listState,
                                            targetIndex = 1,
                                            firstCardFocus = secondCardFocus,
                                        )
                                    } finally {
                                        moveFinished.set(true)
                                    }
                                }
                                true
                            },
                        )
                    }
                    item(key = "recommendations-header:1") {
                        HomeRecommendationHeader(title = "最近热门", scale = scale)
                    }
                    item(key = "recommendations-row:1") {
                        Box(Modifier.testTag("second-section")) {
                            HomeRecommendationRow(
                                nav = nav,
                                entries = testEntries("第二区"),
                                scale = scale,
                                rowState = secondRowState,
                                firstCardFocus = secondCardFocus,
                                rows = 1,
                            )
                        }
                    }
                    item(key = "tail") {
                        Spacer(Modifier.height(300.dp))
                    }
                }
                InitialFocusEffect(firstCardFocus, "recommendation-card")
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.activity.hasWindowFocus() }
        rule.waitUntil(5_000) {
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }

        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)

        rule.waitUntil(5_000) { moveFinished.get() }
        val listState = listStateRef.get()
        val diagnostics =
            "started=${moveStarted.get()} firstVisibleIndex=${listState.firstVisibleItemIndex} " +
                "offset=${listState.firstVisibleItemScrollOffset}"
        assertTrue("section move did not start: $diagnostics", moveStarted.get())
        assertEquals(
            "target section header should be at the top: $diagnostics",
            homeSectionHeaderItemIndex(1),
            listState.firstVisibleItemIndex,
        )

        val secondSectionBounds =
            rule.onNodeWithTag("second-section").fetchSemanticsNode().boundsInRoot
        val focusedBounds =
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().single().boundsInRoot
        val viewportBottom = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "focus should be inside the second section: focused=$focusedBounds " +
                "section=$secondSectionBounds",
            secondSectionBounds.contains(focusedBounds.center),
        )
        assertTrue(
            "second section should be inside the viewport: $secondSectionBounds " +
                "viewportBottom=$viewportBottom",
            secondSectionBounds.top >= 0f &&
                secondSectionBounds.bottom <= viewportBottom,
        )
    }

    @Test
    fun rightInsideSecondSectionDoesNotInvokeNeighbouringSectionCallbacks() {
        val firstCardFocus = FocusRequester()
        val secondCardFocus = FocusRequester()
        val sectionMoves = AtomicInteger()
        val focusSecond = AtomicBoolean(false)
        val listState = AtomicReference<androidx.compose.foundation.lazy.LazyListState>()
        Harness.nativeFocus = true
        Harness.scene = {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val nav = rememberNavController()
                val outerListState = rememberLazyListState()
                val firstRowState = rememberLazyListState()
                val secondRowState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val scale = rememberUiScale()
                SideEffect { listState.set(outerListState) }
                LazyColumn(state = outerListState, modifier = Modifier.fillMaxSize()) {
                    item(key = "hero") {
                        Spacer(Modifier.height((500f * scale).dp))
                    }
                    item(key = "recommendations-header:0") {
                        HomeRecommendationHeader(title = "大家都在看", scale = scale)
                    }
                    item(key = "recommendations-row:0") {
                        HomeRecommendationRow(
                            nav = nav,
                            entries = testEntries("第一区"),
                            scale = scale,
                            rowState = firstRowState,
                            firstCardFocus = firstCardFocus,
                            rows = 1,
                            onDownFromLastRow = { _ ->
                                if (!focusSecond.get()) return@HomeRecommendationRow false
                                sectionMoves.incrementAndGet()
                                scope.launch {
                                    focusHomeSection(
                                        listState = outerListState,
                                        targetIndex = 1,
                                        firstCardFocus = secondCardFocus,
                                    )
                                }
                                true
                            },
                            onUpFromTopRow = { _ -> sectionMoves.incrementAndGet(); true },
                        )
                    }
                    item(key = "recommendations-header:1") {
                        HomeRecommendationHeader(title = "最近热门", scale = scale)
                    }
                    item(key = "recommendations-row:1") {
                        Box(Modifier.testTag("second-section")) {
                            HomeRecommendationRow(
                                nav = nav,
                                entries = testEntries("第二区"),
                                scale = scale,
                                rowState = secondRowState,
                                firstCardFocus = secondCardFocus,
                                rows = 1,
                                onDownFromLastRow = { _ -> sectionMoves.incrementAndGet(); true },
                                onUpFromTopRow = { _ -> sectionMoves.incrementAndGet(); true },
                            )
                        }
                    }
                    item(key = "tail") {
                        Spacer(Modifier.height(300.dp))
                    }
                }
                InitialFocusEffect(firstCardFocus, "recommendation-card")
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.activity.hasWindowFocus() }
        rule.waitUntil(5_000) {
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }

        focusSecond.set(true)
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitUntil(5_000) { sectionMoves.get() == 1 }
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.waitForIdle()

        val secondSectionBounds =
            rule.onNodeWithTag("second-section").fetchSemanticsNode().boundsInRoot
        val focusedBounds =
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().single().boundsInRoot
        assertEquals(1, sectionMoves.get())
        assertTrue(listState.get().firstVisibleItemIndex > 0)
        assertTrue(
            "focus should stay inside the second section: focused=$focusedBounds " +
                "section=$secondSectionBounds",
            secondSectionBounds.contains(focusedBounds.center),
        )
    }

    private fun testEntries(prefix: String): List<VodItem> =
        (1..6).map { index ->
            VodItem(
                id = "$prefix-$index",
                sourceId = "test",
                title = "$prefix $index",
                categoryId = "anime",
            )
        }
}
