package com.qiubo.optimaltv.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.data.prefs.DanmakuDisplayArea
import com.qiubo.optimaltv.data.source.DanmakuItem
import com.qiubo.optimaltv.focus.FocusHarnessActivity
import com.qiubo.optimaltv.focus.Harness
import com.qiubo.optimaltv.ui.theme.OptimalTvTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DanmakuEmulatorDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @Before
    fun reset() {
        Harness.scene = null
    }

    @After
    fun clearScene() {
        Harness.scene = null
    }

    @Test
    fun denseOverlayRendersFixedFullAndFadedHalves() {
        val fullItems =
            (0 until 8).map { index ->
                DanmakuItem(
                    timeMs = index * 100L,
                    sourceMode = 1,
                    color = 0xFFFFFF,
                    fontSize = 24,
                    content = "full danmaku $index",
                    sourceId = "full-fixture-$index",
                )
            }
        val fadedItems =
            (0 until 8).map { index ->
                DanmakuItem(
                    timeMs = index * 100L,
                    sourceMode = 1,
                    color = 0xFFFFFF,
                    fontSize = 24,
                    content = "faded danmaku $index",
                    sourceId = "faded-fixture-$index",
                )
            }
        rule.runOnUiThread {
            Harness.scene = {
                OptimalTvTheme {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                DanmakuOverlay(
                                    items = fullItems,
                                    positionMs = 3_000L,
                                    playbackRate = 1f,
                                    isPlaying = false,
                                    displayArea = DanmakuDisplayArea.FULL,
                                    fontScale = 1f,
                                    opacity = 1f,
                                    scrollSpeed = 1f,
                                    surfaceRenderingEnabled = false,
                                )
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                DanmakuOverlay(
                                    items = fadedItems,
                                    positionMs = 3_000L,
                                    playbackRate = 1f,
                                    isPlaying = false,
                                    displayArea = DanmakuDisplayArea.FULL,
                                    fontScale = 1f,
                                    opacity = 0.4f,
                                    scrollSpeed = 1f,
                                    surfaceRenderingEnabled = false,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()

        val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        save(screenshot, File(filesDir, "danmaku-emulator-fixed-opacity.png"))
        val fullBrightness = brightness(screenshot, 0, screenshot.width / 2, 0, screenshot.height)
        val fadedBrightness =
            brightness(screenshot, screenshot.width / 2, screenshot.width, 0, screenshot.height)
        assertTrue("full danmaku must render pixels", fullBrightness > 0L)
        assertTrue(
            "lower opacity must reduce rendered brightness",
            fadedBrightness < fullBrightness,
        )
    }

    @Test
    fun quarterAreaKeepsDenseDanmakuOnSeparateTracks() {
        val items =
            (0 until 8).map { index ->
                DanmakuItem(
                    timeMs = 0L,
                    sourceMode = 1,
                    color = 0xFFFFFF,
                    fontSize = 30,
                    content = "quarter danmaku $index",
                    sourceId = "quarter-fixture-$index",
                )
            }
        rule.runOnUiThread {
            Harness.scene = {
                OptimalTvTheme {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    ) {
                        DanmakuOverlay(
                            items = items,
                            positionMs = 100L,
                            playbackRate = 1f,
                            isPlaying = false,
                            displayArea = DanmakuDisplayArea.QUARTER,
                            fontScale = 1.2f,
                            opacity = 0.8f,
                            scrollSpeed = 1f,
                            surfaceRenderingEnabled = false,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
        val overlayBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val topQuarterEnd = (overlayBounds.height / 4f).roundToInt()
        val renderedBands =
            brightBands(
                bitmap = screenshot,
                xStart = overlayBounds.left.roundToInt(),
                xEnd = overlayBounds.right.roundToInt(),
                yStart = overlayBounds.top.roundToInt(),
                yEnd = overlayBounds.top.roundToInt() + topQuarterEnd,
            )

        assertTrue("dense burst should remain visibly populated", renderedBands.isNotEmpty())
        assertTrue("quarter area must cap dense tracks", renderedBands.size <= 2)
        assertTrue("visible danmaku must use distinct vertical tracks", renderedBands.size == 2)
        val brightnessBelowQuarter =
            brightness(
                bitmap = screenshot,
                startX = overlayBounds.left.roundToInt(),
                endX = overlayBounds.right.roundToInt(),
                startY = overlayBounds.top.roundToInt() + topQuarterEnd,
                endY = overlayBounds.bottom.roundToInt(),
            )
        assertTrue("danmaku must not draw below the quarter area", brightnessBelowQuarter == 0L)
    }

    @Test
    fun playingOverlayMovesOnFrameClockWithoutTextNodes() {
        val items =
            listOf(
                DanmakuItem(
                    timeMs = 0L,
                    sourceMode = 1,
                    color = 0xFFFFFF,
                    fontSize = 24,
                    content = "moving danmaku",
                    sourceId = "moving-fixture",
                ),
            )
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            Harness.scene = {
                OptimalTvTheme {
                    DanmakuOverlay(
                        items = items,
                        positionMs = 1_000L,
                        playbackRate = 1f,
                        isPlaying = true,
                        displayArea = DanmakuDisplayArea.FULL,
                        fontScale = 1f,
                        opacity = 1f,
                        scrollSpeed = 1f,
                        surfaceRenderingEnabled = false,
                    )
                }
            }
        }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }

        val overlay = rule.onNodeWithTag("danmaku-overlay")
        val first = overlay.captureToImage().asAndroidBitmap()
        repeat(30) { rule.mainClock.advanceTimeByFrame() }
        val second = overlay.captureToImage().asAndroidBitmap()

        val firstCenter = horizontalBrightnessCenter(first)
        val secondCenter = horizontalBrightnessCenter(second)
        assertTrue("first frame must contain rendered danmaku", firstCenter > 0f)
        assertTrue(
            "danmaku must move left as the frame clock advances",
            secondCenter < firstCenter - 20f,
        )
        assertTrue(
            "canvas rendering should not expose one semantics node per comment",
            rule.onAllNodesWithText("moving danmaku").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun save(
        bitmap: Bitmap,
        file: File,
    ) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun brightness(
        bitmap: Bitmap,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int,
    ): Long {
        var total = 0L
        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                val color = bitmap.getPixel(x, y)
                total +=
                    (android.graphics.Color.red(color) +
                        android.graphics.Color.green(color) +
                        android.graphics.Color.blue(color)).toLong()
            }
        }
        return total
    }

    private fun brightBands(
        bitmap: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
    ): List<IntRange> {
        val brightRows =
            (yStart until yEnd).filter { y ->
                (xStart until xEnd step 2).count { x ->
                    val color = bitmap.getPixel(x, y)
                    maxOf(
                        android.graphics.Color.red(color),
                        android.graphics.Color.green(color),
                        android.graphics.Color.blue(color),
                    ) > 64
                } >= 3
            }
        if (brightRows.isEmpty()) return emptyList()

        val bands = mutableListOf<IntRange>()
        var start = brightRows.first()
        var previous = start
        brightRows.drop(1).forEach { row ->
            if (row > previous + 1) {
                bands += start..previous
                start = row
            }
            previous = row
        }
        bands += start..previous
        return bands
    }

    private fun horizontalBrightnessCenter(bitmap: Bitmap): Float {
        var weightedX = 0.0
        var totalBrightness = 0.0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val color = bitmap.getPixel(x, y)
                val brightness =
                    android.graphics.Color.red(color) +
                        android.graphics.Color.green(color) +
                        android.graphics.Color.blue(color)
                if (brightness > 48) {
                    weightedX += x * brightness
                    totalBrightness += brightness
                }
            }
        }
        return if (totalBrightness <= 0.0) 0f else (weightedX / totalBrightness).toFloat()
    }
}
