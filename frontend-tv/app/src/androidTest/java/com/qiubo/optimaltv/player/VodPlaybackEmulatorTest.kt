package com.qiubo.optimaltv.player

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.navigation.compose.rememberNavController
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.data.db.AppDb
import com.qiubo.optimaltv.data.model.LineInfo
import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.source.DongpianPlaybackProtocol
import org.json.JSONObject
import com.qiubo.optimaltv.data.prefs.SettingsStore
import com.qiubo.optimaltv.data.repo.VodRepository
import com.qiubo.optimaltv.focus.FocusHarnessActivity
import com.qiubo.optimaltv.focus.Harness
import com.qiubo.optimaltv.playback.DecoderStats
import com.qiubo.optimaltv.playback.EnginePlayState
import com.qiubo.optimaltv.playback.EngineRegistry
import com.qiubo.optimaltv.playback.MediaEngine
import com.qiubo.optimaltv.ui.components.OtvNav
import com.qiubo.optimaltv.ui.player.PlayerScreen
import com.qiubo.optimaltv.ui.player.PlaybackStartTrigger
import com.qiubo.optimaltv.ui.player.PlayerUiState
import com.qiubo.optimaltv.ui.player.PlayerViewModel
import com.qiubo.optimaltv.ui.theme.OptimalTvTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Long-running, real-renderer VOD coverage; the host supplies a local HLS fixture via adb reverse. */
class VodPlaybackEmulatorTest {
    @get:Rule val rule = createAndroidComposeRule<FocusHarnessActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context by lazy { instrumentation.targetContext }

    private lateinit var db: AppDb
    private lateinit var settingsContext: IsolatedSettingsContext
    private lateinit var settings: SettingsStore
    private lateinit var vm: PlayerViewModel
    private lateinit var engine: MediaEngine
    private lateinit var engineId: String
    private val foregroundLost = AtomicBoolean(false)
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var windowFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var expectedEngineEpoch = 0
    private var expectedSessionGeneration = 0
    private var runId = ""
    private var seekKeyDownTime = 0L

    @Test fun media3PlaysThroughAndRemoteFastForwardCommitsAfterHold() = runEngine("media3")

    @Test fun vlcPlaysThroughAndRemoteFastForwardCommitsAfterHold() = runEngine("vlc")

    @Test fun deniedOfficialSourceFallsBackAndRendersVideo() {
        setUpSession("media3", listOf(
            officialFixture("denied", "http://127.0.0.1:18093/denied.m3u8"),
            officialFixture("healthy", FIXTURE_URL),
        ))
        startRealSession()
        awaitCondition(30_000L, "HTTP denial must finish by selecting and rendering the healthy source") {
            vm.ui.value.activeLine == 1 && vm.ui.value.firstFrameRendered && vm.ui.value.isPlaying
        }
        installForegroundLatch()
        val before = readSample(0L)
        awaitCondition(10_000L, "backup source must keep rendering and advancing") {
            val sample = readSample(0L)
            sample.positionMs > before.positionMs + 2_000 && sample.renderedFrames > before.renderedFrames + 25
        }
        captureScreenshot("http-fallback")
        assertNull(vm.ui.value.fatalMsg)
        Log.i(TAG, "HTTP denial recovery passed activeLine=1 firstFrame=true")
    }

    @Test fun deniedSourcesFinishWithFocusableRecoveryInsteadOfEndlessBlackScreen() {
        setUpSession("media3", listOf(
            officialFixture("denied-one", "http://127.0.0.1:18093/denied.m3u8"),
            officialFixture("denied-two", "http://127.0.0.1:18093/denied.m3u8"),
        ))
        startRealSession()
        awaitCondition(30_000L, "exhausted denials must show a recoverable error") { vm.ui.value.fatalMsg != null }
        assertFalse(vm.ui.value.sourceResolving)
        assertFalse(vm.ui.value.isPlaying)
        rule.onNode(androidx.compose.ui.test.hasText("重试")).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText("重试")).assertIsFocused()
        rule.onNode(androidx.compose.ui.test.hasText(context.getString(com.qiubo.optimaltv.R.string.playback_change_source)))
            .assertExists()
        Log.i(TAG, "HTTP denial exhaustion passed errorVisible=true retryFocused=true")
    }

    private fun officialFixture(id: String, url: String): LineInfo {
        val line = DongpianPlaybackProtocol.parseLine(JSONObject()
            .put("playback_source_id", id).put("provider_id", id)
            .put("provider_name", id).put("url", url).put("resolve_mode", "direct")
            .put("url_kind", "media"), 0)!!
        return LineInfo(VOD_ID, id, url, official =
            OfficialPlaybackBinding("fictional-episode", 0, 1, "movie", line))
    }

    @Test fun recoveryDeadlineEndsAnUnresponsiveBackup() {
        setUpSession("media3", listOf(
            officialFixture("denied", "http://127.0.0.1:18093/denied.m3u8"),
            officialFixture("unresponsive", "http://127.0.0.1:18093/hang.m3u8"),
        ))
        startRealSession()
        awaitCondition(40_000L, "HTTP recovery must expire even when its backup never responds") {
            vm.ui.value.fatalMsg != null
        }
        assertFalse(vm.ui.value.sourceResolving)
        assertFalse(vm.ui.value.isPlaying)
        Log.i(TAG, "HTTP recovery deadline passed unresponsiveBackup=true")
    }

    @Test fun transientServerFailureRecoversOnTheSelectedSource() {
        setUpSession("media3", listOf(
            officialFixture("temporary", "http://127.0.0.1:18093/temporary.m3u8"),
        ))
        startRealSession()
        awaitCondition(30_000L, "temporary HTTP failure must recover without switching source") {
            vm.ui.value.firstFrameRendered && vm.ui.value.isPlaying
        }
        assertEquals(0, vm.ui.value.activeLine)
        assertNull(vm.ui.value.fatalMsg)
        Log.i(TAG, "HTTP temporary recovery passed activeLine=0")
    }

    private fun runEngine(id: String) {
        setUpSession(id)
        startRealSession()
        awaitFirstFrame()
        installForegroundLatch()

        val playbackStart = SystemClock.elapsedRealtime()
        val snapshots = observeFor(PLAYBACK_WINDOW_MS, captureScreenshots = true)
        assertTrue("$id must play continuously for at least 200 seconds", SystemClock.elapsedRealtime() - playbackStart >= PLAYBACK_WINDOW_MS)
        assertTrue(
            "$id timeline must advance at least 190s during the 200s soak",
            snapshots.last().positionMs - snapshots.first().positionMs >= 190_000L,
        )
        assertTrue(
            "$id rendered at least 20fps during the soak",
            snapshots.last().renderedFrames - snapshots.first().renderedFrames >= 4_000,
        )

        val beforeSeek = captureScreenshot("before-seek")
        val targetMs = holdRightAndAssertAcceleration()
        val finalSample = awaitSeekAndVerifyRecovery(targetMs)
        captureAndAssertVideoChanged(beforeSeek, "after-seek")
        Log.i(
            TAG,
            "engine=$id passed 200s playback + accelerated seek; " +
                "pos=${finalSample.positionMs}ms frames=${finalSample.renderedFrames} " +
                "epoch=$expectedEngineEpoch session=$expectedSessionGeneration runId=$runId",
        )
    }

    private fun setUpSession(id: String, fixtureLines: List<LineInfo> = listOf(LineInfo(VOD_ID, "QA HLS fixture", FIXTURE_URL))) {
        engineId = id
        runId = "$id-${SystemClock.elapsedRealtime()}"
        foregroundLost.set(false)
        expectedEngineEpoch = 0
        expectedSessionGeneration = 0
        Graph.init(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
        settingsContext = IsolatedSettingsContext(context, id)
        settings = SettingsStore(settingsContext)
        val repo = VodRepository(settings, OkHttpClient(), context)

        instrumentation.runOnMainSync {
            Harness.scene = null
            OtvNav.resetForTest()
            vm = PlayerViewModel(VOD_ID, 0, repo, db, settings, autoBoot = false)
            engine = EngineRegistry.create(id, Graph.appContext)
            updateViewModelState(
                vm,
                PlayerUiState(
                    title = "本地长时播放测试",
                    isMovie = true,
                    isLive = false,
                    lines = fixtureLines,
                    engineId = engine.engineId,
                    engineName = engine.displayName,
                ),
            )
            invokeInstallEngine(vm, engine)
            invokeCollectEngineState(vm)
            rule.activity.viewModelStore.put(VM_KEY, vm)
            Harness.scene = {
                OptimalTvTheme {
                    PlayerScreen(rememberNavController(), VOD_ID, 0)
                }
            }
        }
        rule.waitForIdle()
        instrumentation.runOnMainSync {
            assertNotNull("$id render view must be attached to PlayerScreen", engine.renderView.parent)
        }
        Log.i(TAG, "engine=$id test session installed, fixture=$FIXTURE_URL runId=$runId")
    }

    private fun startRealSession() {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        instrumentation.runOnMainSync {
            vm.viewModelScope.launch {
                try {
                    invokeStartSession(vm)
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    completed.countDown()
                }
            }
        }
        assertTrue("$engineId startSession must prepare the fixture", completed.await(30, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("$engineId startSession failed", it) }
    }

    private fun awaitFirstFrame() {
        awaitCondition(60_000L, "$engineId must reach READY and render its first frame") {
            val sample = readSample(0L)
            sample.state == EnginePlayState.READY && sample.isPlaying && sample.firstFrameRendered &&
                sample.reportedState == EnginePlayState.READY && sample.reportedIsPlaying &&
                sample.reportedFirstFrameRendered &&
                sample.reportedEngineId == engineId && sample.engineInstanceMatches &&
                sample.durationMs >= 1_000_000L
        }
        val ready = readSample(0L)
        assertEquals(EnginePlayState.READY, ready.state)
        assertTrue(ready.isPlaying)
        assertTrue(ready.firstFrameRendered)
        assertEquals(engineId, ready.reportedEngineId)
        assertTrue("$engineId VM engine instance changed before first frame", ready.engineInstanceMatches)
        assertTrue("unexpected fixture duration ${ready.durationMs}ms", ready.durationMs >= 1_000_000L)
        expectedEngineEpoch = ready.engineEpoch
        expectedSessionGeneration = ready.sessionGeneration
        assertTrue("$engineId engine epoch must be established after installation", expectedEngineEpoch > 0)
        assertTrue("$engineId session generation must be established after startSession", expectedSessionGeneration > 0)
        Log.i(
            TAG,
            "engine=$engineId READY firstFrame=${ready.firstFrameRendered} pos=${ready.positionMs} " +
                "duration=${ready.durationMs} epoch=$expectedEngineEpoch session=$expectedSessionGeneration",
        )
    }

    private fun installForegroundLatch() {
        val activity = rule.activity
        val lostMessage = AtomicReference<String?>(null)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                foregroundLost.set(true)
                lostMessage.compareAndSet(null, "lifecycle=$event")
            }
        }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) {
                foregroundLost.set(true)
                lostMessage.compareAndSet(null, "window focus lost")
            }
        }
        instrumentation.runOnMainSync {
            activity.lifecycle.addObserver(observer)
            activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
            assertTrue("$engineId harness must remain resumed", activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            assertTrue("$engineId harness must retain window focus", activity.hasWindowFocus())
        }
        lifecycleObserver = observer
        windowFocusListener = focusListener
        Log.i(TAG, "engine=$engineId foreground latch installed")
    }

    private data class PlaybackSample(
        val elapsedMs: Long,
        val positionMs: Long,
        val renderedFrames: Int,
        val state: EnginePlayState,
        val durationMs: Long,
        val isPlaying: Boolean,
        val firstFrameRendered: Boolean,
        val reportedEngineId: String,
        val reportedState: EnginePlayState,
        val reportedIsPlaying: Boolean,
        val reportedFirstFrameRendered: Boolean,
        val engineInstanceMatches: Boolean,
        val engineEpoch: Int,
        val sessionGeneration: Int,
        val engineErrorMsg: String?,
        val fatalMessage: String?,
    )

    private fun observeFor(durationMs: Long, captureScreenshots: Boolean): List<PlaybackSample> {
        val start = SystemClock.elapsedRealtime()
        var nextLogAt = 20_000L
        var lastAdvancedAt = start
        var lastPosition = engine.uiState.value.positionMs
        var lastPlayingAt = start
        var lastVmPlayingAt = start
        var lastRenderedFrames = -1
        var lastRenderedFramesAdvancedAt = start
        var middleShot: Bitmap? = null
        val samples = mutableListOf<PlaybackSample>()

        while (SystemClock.elapsedRealtime() - start < durationMs) {
            val elapsed = SystemClock.elapsedRealtime() - start
            val sample = readSample(elapsed)
            samples += sample
            assertStablePlaybackSession(sample, elapsed)

            val now = SystemClock.elapsedRealtime()
            if (sample.isPlaying) lastPlayingAt = now
            if (sample.reportedIsPlaying) lastVmPlayingAt = now
            assertTrue(
                "$engineId did not report playing for ${MAX_TRANSIENT_NOT_PLAYING_MS}ms at ${elapsed}ms",
                now - lastPlayingAt < MAX_TRANSIENT_NOT_PLAYING_MS,
            )
            assertTrue(
                "$engineId VM did not report playing for ${MAX_TRANSIENT_NOT_PLAYING_MS}ms at ${elapsed}ms",
                now - lastVmPlayingAt < MAX_TRANSIENT_NOT_PLAYING_MS,
            )

            if (sample.positionMs > lastPosition) {
                lastPosition = sample.positionMs
                lastAdvancedAt = now
            }
            assertTrue(
                "$engineId timeline stalled for more than ${PLAYBACK_STALL_TOLERANCE_MS}ms " +
                    "at ${sample.positionMs}ms",
                now - lastAdvancedAt < PLAYBACK_STALL_TOLERANCE_MS,
            )

            if (sample.renderedFrames >= 0) {
                if (lastRenderedFrames < 0) {
                    lastRenderedFrames = sample.renderedFrames
                } else if (sample.renderedFrames > lastRenderedFrames) {
                    lastRenderedFrames = sample.renderedFrames
                    lastRenderedFramesAdvancedAt = now
                }
            }
            assertTrue(
                "$engineId rendered-frame counter did not advance for " +
                    "${MAX_NO_RENDERED_FRAME_ADVANCE_MS}ms at ${elapsed}ms",
                now - lastRenderedFramesAdvancedAt < MAX_NO_RENDERED_FRAME_ADVANCE_MS,
            )

            if (captureScreenshots && middleShot == null && elapsed >= durationMs / 2) {
                middleShot = captureScreenshot("middle")
                Log.i(TAG, "engine=$engineId middle screenshot saved at pos=${sample.positionMs}ms")
            }
            if (elapsed >= nextLogAt) {
                Log.i(
                    TAG,
                    "engine=$engineId soak=${elapsed / 1000}s state=${sample.state} " +
                        "vmState=${sample.reportedState} playing=${sample.isPlaying}/${sample.reportedIsPlaying} " +
                        "pos=${sample.positionMs}ms frames=${sample.renderedFrames} " +
                        "vmEngine=${sample.reportedEngineId}#${sample.engineEpoch} " +
                        "session=${sample.sessionGeneration}",
                )
                nextLogAt += 20_000L
            }
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
        }

        // READY/BUFFERING transitions are expected under emulator load. Give the final sample a
        // bounded settling window instead of failing solely because the 200s boundary hit a
        // transient buffer.
        awaitCondition(FINAL_READY_TIMEOUT_MS, "$engineId must return to READY at the end of the soak") {
            val candidate = readSample(durationMs)
            candidate.state == EnginePlayState.READY && candidate.isPlaying &&
                candidate.firstFrameRendered && candidate.reportedEngineId == engineId &&
                candidate.engineInstanceMatches && candidate.fatalMessage == null
        }
        val final = readSample(durationMs)
        samples += final
        assertStablePlaybackSession(final, durationMs)
        assertTrue("$engineId must still be playing at the end of the soak", final.isPlaying)
        assertTrue("$engineId VM must still report playing at the end of the soak", final.reportedIsPlaying)
        assertEquals("$engineId must finish the soak READY", EnginePlayState.READY, final.state)
        assertEquals("$engineId VM must finish the soak READY", EnginePlayState.READY, final.reportedState)
        if (captureScreenshots) {
            if (middleShot == null) middleShot = captureScreenshot("middle")
            captureEndAndAssertVideoChanged(middleShot)
        }
        return samples
    }

    private fun readSample(elapsedMs: Long): PlaybackSample {
        assertHarnessForeground()
        val stats = AtomicReference<DecoderStats?>(null)
        instrumentation.runOnMainSync { stats.set(engine.decoderStats()) }
        val engineState = engine.uiState.value
        val vmState = vm.ui.value
        val vmEngine = if (vm.hasEngine()) vm.engine else null
        return PlaybackSample(
            elapsedMs = elapsedMs,
            positionMs = engineState.positionMs,
            renderedFrames = stats.get()?.rendered ?: -1,
            state = engineState.state,
            durationMs = engineState.durationMs,
            isPlaying = engineState.isPlaying,
            firstFrameRendered = engineState.firstFrameRendered,
            reportedEngineId = vmState.engineId,
            reportedState = vmState.playState,
            reportedIsPlaying = vmState.isPlaying,
            reportedFirstFrameRendered = vmState.firstFrameRendered,
            engineInstanceMatches = vmEngine === engine,
            engineEpoch = vmState.engineEpoch,
            sessionGeneration = sessionGeneration(vm),
            engineErrorMsg = engineState.errorMsg,
            fatalMessage = vmState.fatalMsg,
        )
    }

    private fun assertHarnessForeground() {
        val activity = rule.activity
        val resumed = AtomicBoolean(false)
        val windowFocused = AtomicBoolean(false)
        val decorShown = AtomicBoolean(false)
        val decorVisible = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            resumed.set(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            windowFocused.set(activity.hasWindowFocus())
            decorShown.set(activity.window.decorView.isShown)
            decorVisible.set(activity.window.decorView.windowVisibility == View.VISIBLE)
        }
        assertFalse("$engineId harness lost foreground during playback", foregroundLost.get())
        assertTrue("$engineId harness is not resumed", resumed.get())
        assertTrue("$engineId harness has no window focus", windowFocused.get())
        assertTrue("$engineId harness decor view is not shown", decorShown.get())
        assertTrue("$engineId harness decor window is not visible", decorVisible.get())
    }

    private fun sessionGeneration(viewModel: PlayerViewModel): Int =
        PlayerViewModel::class.java
            .getDeclaredField("sessionGen")
            .apply { isAccessible = true }
            .getInt(viewModel)

    private fun holdRightAndAssertAcceleration(): Long {
        val initial = vm.ui.value.positionMs
        assertTrue("seek regression must start well past the first second: $initial", initial > 30_000L)
        val downTime = SystemClock.uptimeMillis()
        seekKeyDownTime = downTime
        sendDpadEvent(downTime, downTime, KeyEvent.ACTION_DOWN, 0)

        var expectedTarget = initial + SEEK_STEP_MS
        assertEquals("initial RIGHT should seek by 10s", expectedTarget, vm.ui.value.positionMs)
        var previousTarget = vm.ui.value.positionMs
        var repeatCount = 0
        var sawAcceleratedRepeat = false
        try {
            while (SystemClock.uptimeMillis() - downTime < HOLD_MS) {
                SystemClock.sleep(REPEAT_INTERVAL_MS)
                val eventTime = SystemClock.uptimeMillis()
                val heldMs = eventTime - downTime
                repeatCount++
                sendDpadEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, repeatCount)

                val step = if (heldMs >= ACCELERATION_AFTER_MS) SEEK_STEP_MS * 2 else SEEK_STEP_MS
                expectedTarget += step
                assertEquals("held=${heldMs}ms RIGHT increment", expectedTarget, vm.ui.value.positionMs)
                assertTrue("seek intent must keep advancing, never rewind to the first second", vm.ui.value.positionMs > previousTarget)
                previousTarget = vm.ui.value.positionMs
                if (heldMs >= ACCELERATION_AFTER_MS) sawAcceleratedRepeat = true
            }
        } finally {
            sendDpadEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, repeatCount)
            seekKeyDownTime = 0L
        }
        assertTrue("hold must exercise the accelerated tier", sawAcceleratedRepeat)
        assertTrue("held RIGHT must move far ahead", expectedTarget > initial + 100_000L)
        Log.i(TAG, "engine=$engineId held RIGHT ${HOLD_MS}ms, target=$expectedTarget repeats=$repeatCount")
        return expectedTarget
    }

    private fun awaitSeekAndVerifyRecovery(targetMs: Long): PlaybackSample {
        awaitCondition(SEEK_TARGET_TIMEOUT_MS, "$engineId must seek to the held RIGHT target and resume") {
            val sample = readSample(0L)
            sample.state == EnginePlayState.READY && sample.isPlaying && sample.firstFrameRendered &&
                sample.reportedEngineId == engineId && sample.engineInstanceMatches &&
                kotlin.math.abs(sample.positionMs - targetMs) <= SEEK_TOLERANCE_MS
        }

        val baseline = readSample(0L)
        assertStablePlaybackSession(baseline, 0L)
        assertTrue("$engineId decoder frame counter unavailable after seek", baseline.renderedFrames >= 0)

        val deadline = SystemClock.elapsedRealtime() + POST_SEEK_PROGRESS_TIMEOUT_MS
        var recovered = baseline
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
            recovered = readSample(0L)
            assertStablePlaybackSession(recovered, 0L)
            if (
                recovered.state == EnginePlayState.READY &&
                recovered.isPlaying &&
                recovered.firstFrameRendered &&
                recovered.positionMs >= baseline.positionMs + POST_SEEK_MIN_POSITION_ADVANCE_MS &&
                recovered.renderedFrames >= baseline.renderedFrames + POST_SEEK_MIN_RENDERED_FRAME_ADVANCE
            ) {
                break
            }
        }

        assertEquals(EnginePlayState.READY, recovered.state)
        assertTrue("$engineId did not resume playing after seek", recovered.isPlaying)
        assertEquals(engineId, recovered.reportedEngineId)
        assertTrue("$engineId VM engine instance changed after seek", recovered.engineInstanceMatches)
        assertTrue(
            "$engineId position did not advance after seek: " +
                "${baseline.positionMs} -> ${recovered.positionMs}",
            recovered.positionMs >= baseline.positionMs + POST_SEEK_MIN_POSITION_ADVANCE_MS,
        )
        assertTrue(
            "$engineId rendered-frame counter did not advance after seek: " +
                "${baseline.renderedFrames} -> ${recovered.renderedFrames}",
            recovered.renderedFrames >= baseline.renderedFrames + POST_SEEK_MIN_RENDERED_FRAME_ADVANCE,
        )
        assertTrue("$engineId seek must not snap back to the opening second", recovered.positionMs > 1_000L)
        val postSeekWindowStart = readSample(0L)
        SystemClock.sleep(POST_SEEK_STABILITY_WINDOW_MS)
        val postSeekWindowEnd = readSample(0L)
        assertStablePlaybackSession(postSeekWindowEnd, POST_SEEK_STABILITY_WINDOW_MS)
        assertTrue(
            "$engineId position did not stay live after seek recovery",
            postSeekWindowEnd.positionMs >= postSeekWindowStart.positionMs + POST_SEEK_STABILITY_MIN_POSITION_ADVANCE_MS,
        )
        assertTrue(
            "$engineId frame counter did not stay live after seek recovery",
            postSeekWindowEnd.renderedFrames >=
                postSeekWindowStart.renderedFrames + POST_SEEK_STABILITY_MIN_RENDERED_FRAME_ADVANCE,
        )
        Log.i(
            TAG,
            "engine=$engineId seek recovery target=$targetMs actual=${recovered.positionMs} " +
                "frames=${baseline.renderedFrames}->${recovered.renderedFrames} " +
                "stableFrames=${postSeekWindowStart.renderedFrames}->${postSeekWindowEnd.renderedFrames}",
        )
        return postSeekWindowEnd
    }

    private fun sendDpadEvent(downTime: Long, eventTime: Long, action: Int, repeatCount: Int) {
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            repeatCount,
            0,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            0,
            android.view.InputDevice.SOURCE_KEYBOARD,
        )
        instrumentation.sendKeySync(event)
    }

    private fun captureScreenshot(stage: String): Bitmap {
        assertHarnessForeground()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("$engineId $stage screenshot must be captured", bitmap)
        // A non-debuggable acceptance build cannot export private files through run-as.
        // These fixture-only captures stay in this app's external QA directory.
        val captureDirectory = context.getExternalFilesDir("qa-evidence") ?: context.filesDir
        val output = File(captureDirectory, "vod-playback-$runId-$stage.png")
        val compressed = output.outputStream().use {
            bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("$engineId $stage screenshot compression failed", compressed)
        assertTrue("$engineId $stage screenshot file is empty", output.length() > 0L)
        assertHarnessForeground()
        Log.i(
            TAG,
            "engine=$engineId screenshot=$output size=${bitmap!!.width}x${bitmap.height} " +
                "bytes=${output.length()} epoch=$expectedEngineEpoch session=$expectedSessionGeneration",
        )
        return bitmap
    }

    private fun videoRegionChanged(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        val left = a.width / 4
        val top = a.height / 4
        val width = (a.width / 2).coerceAtLeast(1)
        val height = (a.height / 2).coerceAtLeast(1)
        val cropA = Bitmap.createBitmap(a, left, top, width, height)
        val cropB = Bitmap.createBitmap(b, left, top, width, height)
        val pixelsA = IntArray(cropA.width * cropA.height)
        val pixelsB = IntArray(cropB.width * cropB.height)
        cropA.getPixels(pixelsA, 0, cropA.width, 0, 0, cropA.width, cropA.height)
        cropB.getPixels(pixelsB, 0, cropB.width, 0, 0, cropB.width, cropB.height)
        cropA.recycle()
        cropB.recycle()

        var checked = 0
        var changed = 0
        for (index in pixelsA.indices step PIXEL_SAMPLE_STEP) {
            checked++
            val first = pixelsA[index]
            val second = pixelsB[index]
            val channelDelta =
                kotlin.math.abs(Color.red(first) - Color.red(second)) +
                    kotlin.math.abs(Color.green(first) - Color.green(second)) +
                    kotlin.math.abs(Color.blue(first) - Color.blue(second))
            if (channelDelta >= PIXEL_CHANGE_CHANNEL_DELTA) changed++
        }
        return changed.toDouble() / checked.coerceAtLeast(1) >= MIN_CHANGED_PIXEL_RATIO
    }

    private fun captureEndAndAssertVideoChanged(reference: Bitmap): Bitmap {
        return captureAndAssertVideoChanged(reference, "end")
    }

    private fun captureAndAssertVideoChanged(reference: Bitmap, stage: String): Bitmap {
        var candidate = captureScreenshot(stage)
        if (videoRegionChanged(reference, candidate)) return candidate

        // A screen capture can occasionally race SurfaceView composition even while decoder
        // counters advance. One delayed retry removes that false failure without hiding a
        // genuinely frozen video surface.
        SystemClock.sleep(PIXEL_CHANGE_RETRY_DELAY_MS)
        candidate = captureScreenshot("$stage-retry")
        assertFalse(
            "$engineId video pixels must change during $stage",
            !videoRegionChanged(reference, candidate),
        )
        return candidate
    }

    private fun awaitCondition(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100L)
        }
        assertTrue(description, condition())
    }

    private fun assertNotEqualsError(state: EnginePlayState, elapsedMs: Long) {
        assertTrue("$engineId entered ERROR at ${elapsedMs}ms", state != EnginePlayState.ERROR)
    }

    private fun assertStablePlaybackSession(sample: PlaybackSample, elapsedMs: Long) {
        assertNotEqualsError(sample.state, elapsedMs)
        assertNotEqualsError(sample.reportedState, elapsedMs)
        assertTrue("$engineId reached ENDED at ${elapsedMs}ms", sample.state != EnginePlayState.ENDED)
        assertTrue("$engineId VM reached ENDED at ${elapsedMs}ms", sample.reportedState != EnginePlayState.ENDED)
        assertTrue("$engineId lost first-frame signal at ${elapsedMs}ms", sample.firstFrameRendered)
        assertTrue("$engineId VM lost first-frame signal at ${elapsedMs}ms", sample.reportedFirstFrameRendered)
        assertNull("$engineId engine reported an error", sample.engineErrorMsg)
        assertNull("$engineId playback entered fatal UI state", sample.fatalMessage)
        assertEquals("$engineId VM reported another engine", engineId, sample.reportedEngineId)
        assertTrue("$engineId VM engine instance was replaced", sample.engineInstanceMatches)
        assertEquals("$engineId engine epoch changed", expectedEngineEpoch, sample.engineEpoch)
        assertEquals(
            "$engineId startSession was invoked again during steady playback",
            expectedSessionGeneration,
            sample.sessionGeneration,
        )
    }

    @After fun tearDown() {
        if (seekKeyDownTime != 0L) {
            val now = SystemClock.uptimeMillis()
            runCatching { sendDpadEvent(seekKeyDownTime, now, KeyEvent.ACTION_UP, 1) }
            seekKeyDownTime = 0L
        }
        instrumentation.runOnMainSync {
            lifecycleObserver?.let(rule.activity.lifecycle::removeObserver)
            val observer = rule.activity.window.decorView.viewTreeObserver
            if (observer.isAlive) {
                windowFocusListener?.let(observer::removeOnWindowFocusChangeListener)
            }
            Harness.scene = null
            rule.activity.viewModelStore.clear()
            OtvNav.resetForTest()
        }
        lifecycleObserver = null
        windowFocusListener = null
        if (::settingsContext.isInitialized) runCatching { settingsContext.deleteIsolatedFiles() }
        if (::db.isInitialized) runCatching { db.close() }
        Log.i(TAG, "engine=$engineId test state cleared")
    }

    private fun invokeInstallEngine(viewModel: PlayerViewModel, newEngine: MediaEngine) {
        PlayerViewModel::class.java.getDeclaredMethod("installEngine", MediaEngine::class.java)
            .apply { isAccessible = true }
            .invoke(viewModel, newEngine)
    }

    private fun invokeCollectEngineState(viewModel: PlayerViewModel) {
        PlayerViewModel::class.java.getDeclaredMethod("collectEngineState")
            .apply { isAccessible = true }
            .invoke(viewModel)
    }

    private fun updateViewModelState(viewModel: PlayerViewModel, state: PlayerUiState) {
        @Suppress("UNCHECKED_CAST")
        val mutable = PlayerViewModel::class.java.getDeclaredField("_ui")
            .apply { isAccessible = true }
            .get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PlayerUiState>
        mutable.value = state
    }

    private suspend fun invokeStartSession(viewModel: PlayerViewModel) {
        val method: Method = PlayerViewModel::class.java.getDeclaredMethod(
            "startSession",
            Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            PlaybackStartTrigger::class.java,
            Continuation::class.java,
        ).apply { isAccessible = true }

        try {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(
                    viewModel,
                    0,
                    0L,
                    false,
                    false,
                    PlaybackStartTrigger.USER_ACTION,
                    continuation,
                )
            }
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
    }

    private class IsolatedSettingsContext(base: Context, engineId: String) : ContextWrapper(base) {
        private val isolatedFiles = File(base.cacheDir, "qa-settings-$engineId").apply { mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles

        fun deleteIsolatedFiles() {
            isolatedFiles.deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "OTV-TEST"
        private const val VOD_ID = "douban:qa-fixture"
        private const val VM_KEY = "player:douban:qa-fixture:0:0"
        private const val FIXTURE_URL = "http://127.0.0.1:18093/qa.m3u8"
        private const val PLAYBACK_WINDOW_MS = 200_000L
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val SEEK_STEP_MS = 10_000L
        private const val HOLD_MS = 3_000L
        private const val REPEAT_INTERVAL_MS = 100L
        private const val ACCELERATION_AFTER_MS = 2_000L
        private const val SEEK_TARGET_TIMEOUT_MS = 20_000L
        private const val POST_SEEK_PROGRESS_TIMEOUT_MS = 15_000L
        private const val POST_SEEK_MIN_POSITION_ADVANCE_MS = 3_000L
        private const val POST_SEEK_MIN_RENDERED_FRAME_ADVANCE = 100
        private const val POST_SEEK_STABILITY_WINDOW_MS = 5_000L
        private const val POST_SEEK_STABILITY_MIN_POSITION_ADVANCE_MS = 2_000L
        private const val POST_SEEK_STABILITY_MIN_RENDERED_FRAME_ADVANCE = 50
        private const val SEEK_TOLERANCE_MS = 8_000L
        private const val FINAL_READY_TIMEOUT_MS = 8_000L
        private const val PLAYBACK_STALL_TOLERANCE_MS = 5_000L
        private const val MAX_TRANSIENT_NOT_PLAYING_MS = 5_000L
        private const val MAX_NO_RENDERED_FRAME_ADVANCE_MS = 3_000L
        private const val PIXEL_CHANGE_RETRY_DELAY_MS = 1_000L
        private const val PIXEL_SAMPLE_STEP = 16
        private const val PIXEL_CHANGE_CHANNEL_DELTA = 18
        private const val MIN_CHANGED_PIXEL_RATIO = 0.01
    }
}
