package com.qiubo.optimaltv.auth

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.qiubo.optimaltv.data.db.AppDb
import com.qiubo.optimaltv.data.db.FavoriteEntity
import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.db.LastGoodSourceEntity
import com.qiubo.optimaltv.data.db.ProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AccountLibraryIsolationTest {
    private lateinit var db: AppDb

    @Before
    fun createDatabase() {
        db =
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun switchingOwnerClearsSharedLibraryRowsAndRejectsLateOldWrites() = runBlocking {
        var currentOwner: String? = "owner-a"
        val gate = AccountWriteGate { currentOwner }
        gate.forCurrentOwner("owner-a") {
            db.vodDao().addFavorite(FavoriteEntity("favorite-a", "A", "", 1L))
            db.vodDao().upsertHistory(
                HistoryEntity("history-a", "A", "", 0, 1_000L, 2_000L, 1L),
            )
            db.vodDao().upsertProgress(
                ProgressEntity("progress-a", "history-a", 0, 1_000L, 2_000L, 1L),
            )
            db.lastGoodSourceDao().upsert(
                LastGoodSourceEntity(1L, 0, "source-a", "content-a", 0, 1L),
            )
        }

        currentOwner = "owner-b"
        val lateResult = gate.forCurrentOwner("owner-a") {
            db.vodDao().addFavorite(FavoriteEntity("late-a", "late A", "", 2L))
        }
        assertNull(lateResult)

        gate.exclusive {
            db.vodDao().clearFavorites()
            db.vodDao().clearHistory()
            db.vodDao().clearAllProgress()
            db.lastGoodSourceDao().clearAll()
        }
        gate.forCurrentOwner("owner-b") {
            db.vodDao().addFavorite(FavoriteEntity("favorite-b", "B", "", 3L))
            db.vodDao().upsertHistory(
                HistoryEntity("history-b", "B", "", 1, 3_000L, 4_000L, 3L),
            )
        }

        assertEquals(listOf("favorite-b"), db.vodDao().favoritesFlow().first().map { it.vodId })
        assertEquals(listOf("history-b"), db.vodDao().historyFlow().first().map { it.vodId })
        assertNull(db.vodDao().getProgress("progress-a"))
        assertEquals(emptyList<LastGoodSourceEntity>(), db.lastGoodSourceDao().all())
    }

    @Test
    fun roomReplacementRollsBackWhenLogicalSessionChangesMidTransaction() = runBlocking {
        var current = AccountSessionKey(1L, "owner-a")
        val captured = current
        val gate = AccountSessionWriteGate { current }

        val failure =
            runCatching {
                gate.forCurrentSession(captured) {
                    db.withTransaction {
                        db.vodDao().addFavorite(FavoriteEntity("stale", "stale", "", 1L))
                        current = AccountSessionKey(3L, "owner-a")
                        gate.requireCurrent(captured)
                    }
                }
            }.exceptionOrNull()

        assertTrue(failure is StaleAccountSessionException)
        assertEquals(emptyList<FavoriteEntity>(), db.vodDao().favoritesFlow().first())
    }
}
