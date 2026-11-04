package com.qiubo.optimaltv.auth

import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.AppStartup
import com.qiubo.optimaltv.BuildConfig
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.MainActivity
import com.qiubo.optimaltv.data.db.FavoriteEntity
import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.db.LastGoodSourceEntity
import com.qiubo.optimaltv.data.db.ProgressEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in: credentials are supplied at runtime, never fixtures or log output. */
class AccountRoundTripDeviceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun existingOwnerSurvivesSecondOwnerRoundTrip() = runTest(timeout = 240.seconds) {
        val args = InstrumentationRegistry.getArguments()
        val accountB = args.getString("qaAccountB").orEmpty()
        val passwordB =
            args.getString("qaPasswordBBase64")?.let {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            } ?: args.getString("qaPasswordB").orEmpty()
        assumeTrue(BuildConfig.DEBUG && listOf(accountB, passwordB).all { it.isNotBlank() })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(context)

        val a =
            checkNotNull(DongpianAuthClient.restore(context)) {
                "The device must already contain the original encrypted session"
            }
        val capturedA =
            checkNotNull(DongpianAuthClient.currentSession(context)) {
                "The validated original session must remain active"
            }
        val originalFavorites = Graph.officialLibrary.favorites(expectedUserId = a.userId)
        val originalHistory = Graph.officialLibrary.history(expectedUserId = a.userId)
        val originalProgress = readProgressRows()
        val originalSources = Graph.db.lastGoodSourceDao().all()
        var originalRestored = false
        try {
            assertEquals("A must match the captured in-memory owner", a.userId, capturedA.userId)
            verifyLibrary("A", a)

            val b = DongpianAuthClient.login(context, accountB, passwordB)
            assertNotEquals("The two real accounts must have distinct official owners", a.userId, b.userId)
            verifyLibrary("B", b)
            assertEquals("A's local playback progress must be cleared for B", 0, rowCount("progress"))
            assertEquals("A's recent source choices must be cleared for B", 0, rowCount("last_good_source"))

            val restored = DongpianAuthClient.restoreCapturedSessionForTesting(context, capturedA)
            assertTrue("A/B/A must restore the same official owner", restored.userId == a.userId)
            verifyLibrary("A-restored", restored)
            assertEquals(
                "A's favorites must be preserved exactly across the round trip",
                originalFavorites.map { it.vodId }.toSet(),
                Graph.db.vodDao().favoritesFlow().first().map { it.vodId }.toSet(),
            )
            assertEquals(
                "A's history must be preserved exactly across the round trip",
                originalHistory.map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet(),
                Graph.db.vodDao().historyFlow(Int.MAX_VALUE).first()
                    .map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet(),
            )
            restoreLocalPlaybackState(originalProgress, originalSources)
            assertEquals(
                "A's local playback progress must be restored after verification",
                originalProgress.toSet(),
                readProgressRows().toSet(),
            )
            assertEquals(
                "A's recent source choices must be restored after verification",
                originalSources.toSet(),
                Graph.db.lastGoodSourceDao().all().toSet(),
            )
            originalRestored = true
        } finally {
            if (!originalRestored) {
                DongpianAuthClient.restoreCapturedSessionForTesting(context, capturedA)
                verifyLibrary("A-restored-finally", capturedA)
                restoreLocalPlaybackState(originalProgress, originalSources)
            }
            assertEquals(
                "The device must finish on the original owner",
                capturedA.userId,
                DongpianAuthClient.currentSession(context)?.userId,
            )
        }
    }

    @Test
    fun bothRealCredentialsResolveAndRestoreOriginalOwner() = runTest(timeout = 300.seconds) {
        val args = InstrumentationRegistry.getArguments()
        val accountA = args.getString("qaAccountA").orEmpty()
        val passwordA =
            args.getString("qaPasswordABase64")?.let {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            } ?: args.getString("qaPasswordA").orEmpty()
        val accountB = args.getString("qaAccountB").orEmpty()
        val passwordB =
            args.getString("qaPasswordBBase64")?.let {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            } ?: args.getString("qaPasswordB").orEmpty()
        assumeTrue(
            BuildConfig.DEBUG &&
                listOf(accountA, passwordA, accountB, passwordB).all { it.isNotBlank() },
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(context)

        val storedA =
            checkNotNull(DongpianAuthClient.restore(context)) {
                "The device must already contain the original encrypted session"
            }
        val capturedA =
            checkNotNull(DongpianAuthClient.currentSession(context)) {
                "The validated original session must remain active"
            }
        val originalFavorites = Graph.officialLibrary.favorites(expectedUserId = storedA.userId)
        val originalHistory = Graph.officialLibrary.history(expectedUserId = storedA.userId)
        val originalProgress = readProgressRows()
        val originalSources = Graph.db.lastGoodSourceDao().all()
        try {
            val loginA = DongpianAuthClient.login(context, accountA, passwordA)
            assertEquals("A credentials must resolve to the stored owner", storedA.userId, loginA.userId)
            verifyLibrary("A-credentials", loginA)

            val loginB = DongpianAuthClient.login(context, accountB, passwordB)
            assertNotEquals(
                "The two real accounts must have distinct official owners",
                loginA.userId,
                loginB.userId,
            )
            verifyLibrary("B-credentials", loginB)
            assertEquals("A's local playback progress must be cleared for B", 0, rowCount("progress"))
            assertEquals("A's recent source choices must be cleared for B", 0, rowCount("last_good_source"))
        } finally {
            val restored = DongpianAuthClient.restoreCapturedSessionForTesting(context, capturedA)
            assertEquals("The original owner must be restored", storedA.userId, restored.userId)
            verifyLibrary("A-restored", restored)
            restoreLocalPlaybackState(originalProgress, originalSources)
            assertEquals(
                "A's favorites must be restored",
                originalFavorites.map { it.vodId }.toSet(),
                Graph.db.vodDao().favoritesFlow().first().map { it.vodId }.toSet(),
            )
            assertEquals(
                "A's history must be restored",
                originalHistory.map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet(),
                Graph.db.vodDao().historyFlow(Int.MAX_VALUE).first()
                    .map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet(),
            )
            assertEquals(
                "The device must finish on the original owner",
                capturedA.userId,
                DongpianAuthClient.currentSession(context)?.userId,
            )
        }
    }

    @Test
    fun remoteUiRoundTripPreservesOriginalState() = runTest(timeout = 480.seconds) {
        val args = InstrumentationRegistry.getArguments()
        val accountA = args.getString("qaAccountA").orEmpty()
        val passwordA =
            args.getString("qaPasswordABase64")?.let {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            } ?: args.getString("qaPasswordA").orEmpty()
        val accountB = args.getString("qaAccountB").orEmpty()
        val passwordB =
            args.getString("qaPasswordBBase64")?.let {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            } ?: args.getString("qaPasswordB").orEmpty()
        assumeTrue(
            BuildConfig.DEBUG &&
                listOf(accountA, passwordA, accountB, passwordB).all { it.isNotBlank() },
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(context)

        logUiPhase("baseline-restore-start")
        val storedA =
            checkNotNull(DongpianAuthClient.restore(context)) {
                "The device must already contain the original encrypted session"
            }
        logUiPhase("baseline-restore-complete")
        val capturedA =
            checkNotNull(DongpianAuthClient.currentSession(context)) {
                "The validated original session must remain active"
            }
        assertTrue(
            "A must match the captured in-memory owner",
            storedA.userId == capturedA.userId,
        )
        val originalFavorites = Graph.officialLibrary.favorites(expectedUserId = capturedA.userId)
        val originalHistory = Graph.officialLibrary.history(expectedUserId = capturedA.userId)
        val originalProgress = readProgressRows()
        val originalSources = Graph.db.lastGoodSourceDao().all()
        logUiPhase("baseline-library-captured")
        assertTrue(
            "Original A needs a favorite to exercise the real first-row focus path",
            originalFavorites.isNotEmpty(),
        )

        var scenario: ActivityScenario<MainActivity>? = null
        var primaryFailure: Throwable? = null
        try {
            if (!AppStartup.ready.value) AppStartup.begin()
            logUiPhase("activity-launch-start")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            logUiPhase("activity-launched")

            navigateFromMovieTabToMe()
            logUiPhase("a-me-focused")
            val firstFavoriteTitle =
                checkNotNull(Graph.db.vodDao().favoritesFlow().first().firstOrNull()) {
                    "A's local favorites must be ready before the UI round trip"
                }.title
            logUiPhase("a-first-favorite-down")
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocused(hasContentDescription(firstFavoriteTitle) and hasClickAction())
            logUiPhase("a-first-favorite-focused")
            keyUntilFocused(KeyEvent.KEYCODE_DPAD_UP, clickableText("退出登录"))
            logUiPhase("a-logout-focused")
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitLoginUi()
            logUiPhase("a-logout-login-visible")
            loginThroughUi("b-login", accountB, passwordB)
            awaitFocused(clickableText("电影"), LOGIN_TIMEOUT_MS)
            logUiPhase("b-home")

            navigateFromMovieTabToMe()
            logUiPhase("b-me-focused")
            awaitExists(hasText("暂无收藏"), LOGIN_TIMEOUT_MS)
            awaitFocused(clickableText("我的"))
            logUiPhase("b-empty-favorites-visible")
            val sessionB =
                checkNotNull(DongpianAuthClient.currentSession(context)) {
                    "The B UI login must produce a session"
                }
            assertTrue(
                "The two real accounts must have distinct official owners",
                sessionB.userId != capturedA.userId,
            )
            assertTrue(
                "B must have no local favorites after the real UI login",
                Graph.db.vodDao().favoritesFlow().first().isEmpty(),
            )
            verifyLibrary("B-ui", sessionB)
            logUiPhase("b-library-verified")
            awaitFocused(clickableText("我的"))

            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocused(clickableText("退出登录"))
            logUiPhase("b-logout-focused")
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitLoginUi()
            logUiPhase("b-logout-login-visible")
            loginThroughUi("a-login", accountA, passwordA)
            awaitFocused(clickableText("电影"), LOGIN_TIMEOUT_MS)
            logUiPhase("a-home-restored")
            val sessionA =
                checkNotNull(DongpianAuthClient.currentSession(context)) {
                    "The A UI login must produce a session"
                }
            assertTrue(
                "A credentials must resolve to the captured owner",
                sessionA.userId == capturedA.userId,
            )
            verifyLibrary("A-ui-credentials", sessionA)
            logUiPhase("a-library-verified")
        } catch (failure: Throwable) {
            logUiPhase("ui-body-failed")
            primaryFailure = failure
        } finally {
            logUiPhase("cleanup-start")
            val cleanupFailures =
                withContext(NonCancellable) {
                    val failures = mutableListOf<Throwable>()
                    scenario?.let { activeScenario ->
                        captureCleanupFailure(failures) { activeScenario.close() }
                    }
                    failures += restoreOriginalAccountAndLocalState(
                        context = context,
                        capturedA = capturedA,
                        originalFavorites = originalFavorites,
                        originalHistory = originalHistory,
                        originalProgress = originalProgress,
                        originalSources = originalSources,
                    )
                    failures
                }
            logUiPhase(if (cleanupFailures.isEmpty()) "cleanup-complete" else "cleanup-failed")
            val bodyFailure = primaryFailure
            if (bodyFailure != null) {
                cleanupFailures.forEach(bodyFailure::addSuppressed)
                throw bodyFailure
            }
            cleanupFailures.firstOrNull { it is CancellationException }?.let { throw it }
            if (cleanupFailures.isNotEmpty()) {
                val cleanupFailure = AssertionError("Original account data could not be fully restored")
                cleanupFailures.forEach(cleanupFailure::addSuppressed)
                throw cleanupFailure
            }
        }
    }

    private fun navigateFromMovieTabToMe() {
        awaitFocused(clickableText("电影"), LOGIN_TIMEOUT_MS)
        keyUntilFocused(KeyEvent.KEYCODE_DPAD_RIGHT, clickableText("电视剧"))
        keyUntilFocused(KeyEvent.KEYCODE_DPAD_RIGHT, clickableText("国漫"))
        keyUntilFocused(KeyEvent.KEYCODE_DPAD_RIGHT, clickableText("短剧"))
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused(clickableText("a"))
        keyUntilFocused(KeyEvent.KEYCODE_DPAD_UP, clickableText("搜索"))
        keyUntilFocused(KeyEvent.KEYCODE_DPAD_RIGHT, clickableText("我的"))
    }

    private fun loginThroughUi(phase: String, account: String, password: String) {
        logUiPhase("$phase-fields-start")
        awaitFocused(clickableText("账号"))
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused(hasSetTextAction())
        composeRule.onNode(hasSetTextAction() and isFocused()).performTextInput(account)
        sendKey(KeyEvent.KEYCODE_BACK)

        keyUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, clickableText("密码"))
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocused(hasSetTextAction())
        composeRule.onNode(hasSetTextAction() and isFocused()).performTextInput(password)
        sendKey(KeyEvent.KEYCODE_BACK)

        keyUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, clickableText("登录"))
        logUiPhase("$phase-submit-focused")
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        logUiPhase("$phase-submitted")
    }

    private fun awaitLoginUi() {
        awaitExists(clickableText("账号"), LOGIN_TIMEOUT_MS)
        awaitFocused(clickableText("账号"), LOGIN_TIMEOUT_MS)
    }

    private fun clickableText(value: String): SemanticsMatcher = hasText(value) and hasClickAction()

    private fun logUiPhase(phase: String) {
        Log.i("VideoProAccountQA", "uiPhase=$phase")
    }

    private fun awaitExists(matcher: SemanticsMatcher, timeoutMillis: Long = UI_STEP_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun awaitFocused(matcher: SemanticsMatcher, timeoutMillis: Long = UI_STEP_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                composeRule.onAllNodes(matcher).fetchSemanticsNodes().any { node ->
                    node.config.getOrNull(SemanticsProperties.Focused) == true
                }
            }.getOrDefault(false)
        }
    }

    private fun keyUntilFocused(
        keyCode: Int,
        matcher: SemanticsMatcher,
        timeoutMillis: Long = UI_STEP_TIMEOUT_MS,
    ) {
        sendKey(keyCode)
        awaitFocused(matcher, timeoutMillis)
    }

    private fun sendKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private suspend fun captureCleanupFailure(
        failures: MutableList<Throwable>,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            // Record cancellation so the remaining recovery steps can still run, then rethrow below.
            failures += cancelled
        } catch (failure: Throwable) {
            failures += failure
        }
    }

    private suspend fun restoreOriginalAccountAndLocalState(
        context: android.content.Context,
        capturedA: DongpianAuthSession,
        originalFavorites: List<FavoriteEntity>,
        originalHistory: List<HistoryEntity>,
        originalProgress: List<ProgressEntity>,
        originalSources: List<LastGoodSourceEntity>,
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        var restored: DongpianAuthSession? = null
        captureCleanupFailure(failures) {
            restored = DongpianAuthClient.restoreCapturedSessionForTesting(context, capturedA)
        }
        captureCleanupFailure(failures) {
            assertTrue(
                "The original owner must be restored",
                restored?.userId == capturedA.userId,
            )
        }
        restored?.let { session ->
            captureCleanupFailure(failures) {
                verifyLibrary("A-ui-restored-finally", session)
            }
            captureCleanupFailure(failures) {
                assertTrue(
                    "A's favorites must match the captured original",
                    Graph.db.vodDao().favoritesFlow().first().map { it.vodId }.toSet() ==
                        originalFavorites.map { it.vodId }.toSet(),
                )
            }
            captureCleanupFailure(failures) {
                assertTrue(
                    "A's history must match the captured original",
                    Graph.db.vodDao().historyFlow(Int.MAX_VALUE).first()
                        .map { Triple(it.vodId, it.epIndex, it.positionMs) }
                        .toSet() ==
                        originalHistory.map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet(),
                )
            }
        }
        captureCleanupFailure(failures) {
            restoreLocalPlaybackState(originalProgress, originalSources)
        }
        captureCleanupFailure(failures) {
            assertTrue(
                "A's playback progress must be restored exactly",
                readProgressRows().toSet() == originalProgress.toSet(),
            )
        }
        captureCleanupFailure(failures) {
            assertTrue(
                "A's recent source choices must be restored exactly",
                Graph.db.lastGoodSourceDao().all().toSet() == originalSources.toSet(),
            )
        }
        captureCleanupFailure(failures) {
            assertTrue(
                "The device must finish on the captured original owner",
                DongpianAuthClient.currentSession(context)?.userId == capturedA.userId,
            )
        }
        return failures
    }

    private suspend fun verifyLibrary(phase: String, session: DongpianAuthSession) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val me = DongpianAuthClient.apiJsonForUser(context, "/v1/users/me", expectedUserId = session.userId)
        assertTrue("Official identity must match the active session", me.optString("id") == session.userId)
        val library = Graph.officialLibrary
        val favorites = library.favorites(expectedUserId = session.userId)
        val history = library.history(expectedUserId = session.userId)
        val snapshot = library.syncToRoom(Graph.db, force = true)
        assertTrue("Snapshot must belong to the active owner", snapshot.userId == session.userId)
        val localFavorites = Graph.db.vodDao().favoritesFlow().first()
        val localHistory = Graph.db.vodDao().historyFlow(Int.MAX_VALUE).first()
        assertTrue("Favorites must exactly match the official account", localFavorites.map { it.vodId }.toSet() == favorites.map { it.vodId }.toSet())
        assertTrue("History must exactly match the official account", localHistory.map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet() == history.map { Triple(it.vodId, it.epIndex, it.positionMs) }.toSet())
        Log.i("VideoProAccountQA", "phase=$phase favorites=${favorites.size} history=${history.size} officialIdentityMatches=true localLibraryMatches=true")
    }

    private fun rowCount(table: String): Int =
        Graph.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun readProgressRows(): List<ProgressEntity> =
        Graph.db.openHelper.readableDatabase
            .query(
                "SELECT vodKey, vodId, epIndex, positionMs, durationMs, updatedAt FROM progress",
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ProgressEntity(
                                vodKey = cursor.getString(0),
                                vodId = cursor.getString(1),
                                epIndex = cursor.getInt(2),
                                positionMs = cursor.getLong(3),
                                durationMs = cursor.getLong(4),
                                updatedAt = cursor.getLong(5),
                            ),
                        )
                    }
                }
            }

    private suspend fun restoreLocalPlaybackState(
        progress: List<ProgressEntity>,
        sources: List<LastGoodSourceEntity>,
    ) {
        Graph.db.vodDao().clearAllProgress()
        progress.forEach { Graph.db.vodDao().upsertProgress(it) }
        Graph.db.lastGoodSourceDao().clearAll()
        sources.forEach { Graph.db.lastGoodSourceDao().upsert(it) }
    }

    private companion object {
        const val UI_STEP_TIMEOUT_MS = 20_000L
        const val LOGIN_TIMEOUT_MS = 120_000L
    }
}
