package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRevisionGuardTest {
    @Test
    fun lateCookieRefreshCannotRestoreLoggedOutSession() {
        val guard = SessionRevisionGuard()
        var session: String? = "account-a"
        val (requestRevision, _) = guard.snapshot { session }
        guard.change { session = null }

        assertFalse(guard.commit(requestRevision) { session = "account-a-refreshed" })
        assertEquals(null, session)
    }

    @Test
    fun oldUnauthorizedResponseCannotClearNewAccount() {
        val guard = SessionRevisionGuard()
        var session: String? = "account-a"
        val (requestRevision, _) = guard.snapshot { session }
        val loginRevision = guard.change()
        assertTrue(guard.commit(loginRevision) { session = "account-b" })

        assertFalse(guard.commit(requestRevision) { session = null })
        assertEquals("account-b", session)
    }

    @Test
    fun onlyLatestLoginCanCommitEvenWhenOwnerMatchesAgain() {
        val guard = SessionRevisionGuard()
        var session = "account-a"
        val firstLogin = guard.change()
        val secondLogin = guard.change()
        assertTrue(guard.commit(secondLogin) { session = "account-a-new-session" })
        assertFalse(guard.commit(firstLogin) { session = "account-a-old-session" })
        assertEquals("account-a-new-session", session)
    }

    @Test
    fun loginTransitionRejectsRequestsThatCaptureThePreviousStoredSession() {
        val guard = SessionRevisionGuard()
        var session: String? = "account-a"
        val loginRevision = guard.beginTransition()
        val (requestRevision, requestSession) = guard.snapshot { session }

        assertTrue(guard.isTransitioning(loginRevision))
        assertEquals(requestRevision, loginRevision)
        assertEquals("account-a", requestSession)
        assertFalse(guard.commit(requestRevision) { session = "account-a-refreshed" })
    }

    @Test
    fun oldRequestStillCannotCommitAfterSameOwnerLoginCompletes() {
        val guard = SessionRevisionGuard()
        var session = "account-a"
        val oldRequestRevision = guard.snapshot { session }.first
        val loginRevision = guard.beginTransition()
        assertTrue(guard.commitTransition(loginRevision) { session = "account-a-new" })
        guard.finishTransition(loginRevision)

        assertFalse(guard.commit(oldRequestRevision) { session = "account-a-refreshed" })
        assertEquals("account-a-new", session)
    }
}
