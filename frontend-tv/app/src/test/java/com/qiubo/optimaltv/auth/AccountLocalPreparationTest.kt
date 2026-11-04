package com.qiubo.optimaltv.auth

import android.app.Application
import androidx.room.Room
import com.qiubo.optimaltv.data.db.AppDb
import com.qiubo.optimaltv.data.db.FavoriteEntity
import com.qiubo.optimaltv.data.source.DongpianOfficialLibraryClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AccountLocalPreparationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).allowMainThreadQueries().build()
    private var snapshot = snapshot(1L, "fictional-a")
    private val client = DongpianOfficialLibraryClient(context, currentSnapshot = { snapshot })

    @After fun close() = db.close()

    @Test fun sameAccountCacheRemainsUsableWithoutARequestAndOtherAccountNeverSeesIt() = runTest {
        client.prepareLocalAccount(db, 1L)
        db.vodDao().addFavorite(FavoriteEntity("fictional-film", "Fictional", "", 1L))
        client.prepareLocalAccount(db, 1L)
        assertEquals(listOf("fictional-film"), db.vodDao().favoritesFlow().first().map { it.vodId })
        snapshot = snapshot(2L, "fictional-b")
        client.prepareLocalAccount(db, 2L)
        assertTrue(db.vodDao().favoritesFlow().first().isEmpty())
    }

    @Test fun stalePreparationCannotClearCurrentAccountsRows() = runTest {
        client.prepareLocalAccount(db, 1L)
        db.vodDao().addFavorite(FavoriteEntity("current-film", "Fictional", "", 1L))
        try {
            client.prepareLocalAccount(db, 0L)
            error("Expected stale session rejection")
        } catch (_: StaleAccountSessionException) {
            assertEquals(1, db.vodDao().favoritesFlow().first().size)
        }
    }

    @Test fun unknownLegacyOwnerIsNotExposedAsNewAccountsCache() = runTest {
        db.vodDao().addFavorite(FavoriteEntity("unknown-owner", "Fictional", "", 1L))
        client.prepareLocalAccount(db, 1L)
        assertTrue(db.vodDao().favoritesFlow().first().isEmpty())
    }

    private fun snapshot(revision: Long, user: String) =
        DongpianAuthSessionSnapshot(revision, DongpianAuthSession(user, user, "fictional-cookie"))
}
