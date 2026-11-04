package com.qiubo.optimaltv.focus

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.ui.components.OtvNav
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.player.consumePlayerPanelKeysWhileMoving
import com.qiubo.optimaltv.ui.player.rememberPlayerPanelFocus
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Device coverage for explicit panel-focus movement through the production Harness. */
class PlayerPanelFocusDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @Before
    fun setUp() {
        Harness.scene = null
        // Compose the empty scene before installing the next test's scroll container;
        // otherwise rememberScrollState() can survive across cases and leave row 0
        // outside the viewport during the following initial focus request.
        rule.waitForIdle()
        OtvNav.resetForTest()
    }

    @Test
    fun okDuringRevealDoesNotActivatePreviousChip() {
        val sourceClicks = AtomicInteger()
        val targetClicks = AtomicInteger()
        val rows = List(12) { 1 }
        scene(rows, initial = 0 to 0) { row, _ ->
            if (row == 0) sourceClicks.incrementAndGet() else targetClicks.incrementAndGet()
        }
        awaitFocused("panel-0-0")

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitFocused("panel-1-0")
        rule.waitForIdle()
        assertEquals("OK must not activate the pre-scroll chip", 0, sourceClicks.get())
        assertEquals("OK during reveal must be consumed", 0, targetClicks.get())
    }

    @Test
    fun rapidDirectionPressesQueueTheirLogicalTarget() {
        val clicks = AtomicInteger()
        val rows = List(12) { 1 }
        scene(rows, initial = 2 to 0) { _, _ -> clicks.incrementAndGet() }
        awaitFocused("panel-2-0")

        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitFocused("panel-0-0")
        rule.waitForIdle()
        assertEquals("queued movement controls the next focus target", 0, clicks.get())
    }

    @Test
    fun realOtvNavDownUsesNearestColumnInShorterRow() {
        val clicks = AtomicInteger()
        val rows = listOf(5, 3)
        scene(rows, initial = 0 to 4) { row, column ->
            if (row == 1 && column == 2) clicks.incrementAndGet()
        }
        awaitFocused("panel-0-4")

        key(KeyEvent.KEYCODE_DPAD_DOWN)

        awaitFocused("panel-1-2")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitForIdle()
        assertEquals(1, clicks.get())
    }

    @Test
    fun rapidUpFromToggleRowKeepsFirstEpisodeRowFocusedThroughScroll() {
        val row1Clicks = AtomicInteger()
        val row2Clicks = AtomicInteger()
        val rows = listOf(5, 5, 5, 1, 3, 3, 4, 5, 7)
        scene(rows, initial = 8 to 2) { row, _ ->
            when (row) {
                1 -> row1Clicks.incrementAndGet()
                2 -> row2Clicks.incrementAndGet()
            }
        }
        awaitFocused("panel-8-2")

        repeat(5) { key(KeyEvent.KEYCODE_DPAD_UP) }
        awaitFocused("panel-3-0")

        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitFocused("panel-1-0")
        rule.waitForIdle()
        assertEquals("OK during reveal must be consumed", 0, row1Clicks.get())
        assertEquals("OK must not fall back to the old short episode row", 0, row2Clicks.get())
    }

    @Test
    fun selectingChipKeepsFocusThroughItsActiveStateRecomposition() {
        val rows = listOf(5, 4)
        val clicks = AtomicInteger()
        scene(rows, initial = 0 to 1) { _, _ -> clicks.incrementAndGet() }
        awaitFocused("panel-0-1")

        key(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitFocused("panel-0-1")
        rule.waitForIdle()
        assertEquals(1, clicks.get())
    }

    private fun scene(
        rows: List<Int>,
        initial: Pair<Int, Int>,
        onChip: (row: Int, column: Int) -> Unit,
    ) {
        Harness.scene = {
            PlayerPanelFocusScene(rows, initial, onChip)
        }
        rule.waitForIdle()
    }

    private fun key(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private fun awaitFocused(tag: String, timeoutMillis: Long = 5_000) {
        rule.waitUntil(timeoutMillis) {
            runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess
        }
    }
}

@Composable
private fun PlayerPanelFocusScene(
    rows: List<Int>,
    initial: Pair<Int, Int>,
    onChip: (row: Int, column: Int) -> Unit,
) {
    val focus = rememberPlayerPanelFocus(rows)
    var selectedColumn by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focus) { focus.focusInitial(initial.first, initial.second) }
    Column(
        Modifier
            .fillMaxSize()
            .consumePlayerPanelKeysWhileMoving(focus)
            .verticalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { row, count ->
            Row(Modifier.fillMaxWidth().height(180.dp)) {
                repeat(count) { column ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("panel-$row-$column")
                            .background(
                                if (row == 0 && column == selectedColumn) {
                                    Color(0xFF315A8A)
                                } else {
                                    Color(0xFF203047)
                                },
                            )
                            .then(focus.modifier(row, column))
                            .dpadFocusable(
                                scaleFocused = 1f,
                                externalFocusRequester = focus.requester(row, column),
                                navGroup = "player-panel-test",
                                navOverride = { direction -> focus.navigate(row, column, direction) },
                                onClick = {
                                    focus.activate(row, column) {
                                        if (row == 0) selectedColumn = column
                                        onChip(row, column)
                                    }
                                },
                            ),
                    )
                }
            }
        }
    }
}
