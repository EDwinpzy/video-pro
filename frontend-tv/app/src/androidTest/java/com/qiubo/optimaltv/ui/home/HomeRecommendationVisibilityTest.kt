package com.qiubo.optimaltv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.focus.FocusHarnessActivity
import com.qiubo.optimaltv.focus.Harness
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class HomeRecommendationVisibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @After
    fun tearDown() {
        Harness.scene = null
        Harness.nativeFocus = false
    }

    @Test
    fun focusedCardBringsItsTitleIntoView() {
        val firstCardFocus = FocusRequester()
        Harness.nativeFocus = true
        Harness.scene = {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val nav = rememberNavController()
                val rowState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column {
                            // Leaves the poster mostly visible but its title below the fold.
                            Spacer(Modifier.height(200.dp))
                            HomeRecommendationRow(
                                nav = nav,
                                entries =
                                    listOf(
                                        VodItem(
                                            id = "test-vod",
                                            sourceId = "test",
                                            title = "底部署名测试",
                                            categoryId = "movie",
                                        ),
                                    ),
                                scale = 1f,
                                rowState = rowState,
                                firstCardFocus = firstCardFocus,
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

        val titleBounds =
            rule.onNodeWithText("底部署名测试")
                .fetchSemanticsNode()
                .boundsInRoot
        val viewportBottom = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(
            "card title should be fully inside the viewport, bounds=$titleBounds viewportBottom=$viewportBottom",
            titleBounds.width > 0f &&
                titleBounds.height > 0f &&
                titleBounds.top >= 0f &&
                titleBounds.bottom <= viewportBottom,
        )
    }
}
