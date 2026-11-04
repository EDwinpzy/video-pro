package com.qiubo.optimaltv.focus

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.tvFocusRegion
import com.qiubo.optimaltv.ui.player.EpisodeFocusAnchor
import com.qiubo.optimaltv.ui.player.rememberPlayerPanelFocus
import com.qiubo.optimaltv.ui.components.TvRowFocusMemory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.onFocusChanged
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeComposeFocusDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @Before
    fun useNativeInput() {
        Harness.nativeFocus = true
        Harness.scene = null
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.activity.hasWindowFocus() }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.waitForIdle()
    }

    @After
    fun clearScene() {
        Harness.scene = null
        rule.waitForIdle()
        Harness.nativeFocus = false
    }

    @Test
    fun nativeDirectionSkipsDisabledTargetAndOkActivatesOnce() {
        val clicks = AtomicInteger()
        rule.runOnIdle {
            Harness.scene = {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                Row {
                    Box(Modifier.size(100.dp).testTag("first").focusRequester(first)
                        .dpadFocusable { })
                    Box(Modifier.size(100.dp).testTag("disabled")
                        .dpadFocusable(enabled = false) { clicks.addAndGet(100) })
                    Box(Modifier.size(100.dp).testTag("last")
                        .dpadFocusable { clicks.incrementAndGet() })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("first").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("last")
        rule.onNodeWithTag("last").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, clicks.get())
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("first")
        rule.onNodeWithTag("first").assertIsFocused()
    }

    private fun key(code: Int) {
        rule.waitUntil(5_000) { rule.activity.hasWindowFocus() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        rule.waitForIdle()
    }

    @Test
    fun enteringRegionUsesCurrentAnchorInsteadOfFirstItem() {
        rule.runOnIdle {
            Harness.scene = {
                val top = remember { FocusRequester() }
                val selected = remember { FocusRequester() }
                LaunchedEffect(Unit) { top.requestFocus() }
                Column {
                    Box(Modifier.size(100.dp).testTag("top").focusRequester(top)
                        .dpadFocusable { })
                    Row(Modifier.tvFocusRegion(selected)) {
                        Box(Modifier.size(100.dp).testTag("first-tab").dpadFocusable { })
                        Box(Modifier.size(100.dp).testTag("current-tab").focusRequester(selected)
                            .dpadFocusable { })
                    }
                }
            }
        }
        awaitFocused("top")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("current-tab")
    }

    private fun awaitFocused(tag: String) {
        try {
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Expected focus: $tag\n${rule.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
    }

    @Test
    fun reenteringScrolledRowRestoresItsRememberedItem() {
        rule.runOnIdle {
            Harness.scene = {
                val requesters = remember { List(4) { FocusRequester() } }
                val memory = remember { TvRowFocusMemory().apply { focused("b") } }
                LaunchedEffect(Unit) { requesters[1].requestFocus() }
                Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                    repeat(2) { row ->
                        Row(Modifier.height(180.dp).tvFocusRegion {
                            if (row == 0) {
                                memory.entryKey(listOf("a", "b"), setOf("a", "b"))
                                    ?.let { requesters[if (it == "a") 0 else 1] }
                            } else null
                        }) {
                            repeat(2) { column ->
                                val index = row * 2 + column
                                Box(Modifier.size(width = 100.dp, height = 180.dp).testTag("row-item-$index")
                                    .focusRequester(requesters[index])
                                    .onFocusChanged {
                                        if (it.isFocused && row == 0) memory.focused(if (column == 0) "a" else "b")
                                    }
                                    .dpadFocusable { })
                            }
                        }
                    }
                }
            }
        }
        awaitFocused("row-item-1")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("row-item-3")
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocused("row-item-1")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("row-item-0")
    }

    @Test
    fun insertedEpisodeKeepsFocusedContentAcrossARowBoundaryAndNextKeyWorks() {
        var entries by mutableStateOf((1..5).map { "episode-$it" } + listOf("extra-a", "extra-b"))
        val anchor = EpisodeFocusAnchor()
        val events = mutableListOf<String>()
        rule.runOnIdle {
            Harness.scene = {
                val rows = entries.chunked(6)
                val focus = rememberPlayerPanelFocus(rows.map { it.size })
                val request = remember(entries) {
                    anchor.captureRefresh(entries, entries.indexOf("extra-a"))
                }
                LaunchedEffect(entries) {
                    if (request != null && !anchor.isCurrent(request)) return@LaunchedEffect
                    val index = request?.index ?: entries.indexOf("extra-a")
                    events.add("restore index=$index size=${entries.size}")
                    focus.focusInitial(index / 6, index % 6)
                    events.add("restore returned index=$index")
                }
                Column {
                    rows.forEachIndexed { row, items ->
                        Row {
                            items.forEachIndexed { column, identity ->
                                key(identity) {
                                    Box(
                                        Modifier.size(100.dp).testTag(identity)
                                            .then(focus.modifier(row, column))
                                            .onFocusChanged {
                                                events.add("$identity focused=${it.isFocused}")
                                                if (it.isFocused) anchor.focused(identity)
                                            }
                                            .dpadFocusable(
                                                externalFocusRequester = focus.requester(row, column),
                                                navOverride = {
                                                    anchor.movedByUser()
                                                    focus.navigate(row, column, it)
                                                },
                                            ) { },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitUntil(5_000) { rule.activity.hasWindowFocus() }
        awaitFocused("extra-a")
        rule.runOnIdle {
            entries = (1..6).map { "episode-$it" } + listOf("extra-a", "extra-b")
        }
        rule.waitForIdle()
        try {
            awaitFocused("extra-a")
        } catch (failure: AssertionError) {
            throw AssertionError(events.joinToString("\n"), failure)
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("extra-b")
    }
}
