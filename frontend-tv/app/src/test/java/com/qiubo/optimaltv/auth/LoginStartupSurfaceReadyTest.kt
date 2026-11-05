package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginStartupSurfaceReadyTest {

    @Test
    fun `manual login waits for remembered account auto login to settle`() {
        assertEquals(
            false,
            resolveLoginStartupSurfaceReady(
                restoring = false,
                canRetryRestore = false,
                hasSession = false,
                loginSurfaceReady = false,
                hasBlockingSyncError = false,
                accountContentPrepared = false,
            ),
        )
    }

    @Test
    fun `login screen is released after no account or auto login failure`() {
        assertEquals(
            true,
            resolveLoginStartupSurfaceReady(
                restoring = false,
                canRetryRestore = false,
                hasSession = false,
                loginSurfaceReady = true,
                hasBlockingSyncError = false,
                accountContentPrepared = false,
            ),
        )
    }

    @Test
    fun `restored session waits for local account preparation`() {
        assertEquals(
            false,
            resolveLoginStartupSurfaceReady(
                restoring = false,
                canRetryRestore = false,
                hasSession = true,
                loginSurfaceReady = true,
                hasBlockingSyncError = false,
                accountContentPrepared = false,
            ),
        )
        assertEquals(
            null,
            resolveLoginStartupSurfaceReady(
                restoring = false,
                canRetryRestore = false,
                hasSession = true,
                loginSurfaceReady = true,
                hasBlockingSyncError = false,
                accountContentPrepared = true,
            ),
        )
    }

    @Test
    fun `restore and blocking sync failures become actionable surfaces`() {
        assertEquals(
            true,
            resolveLoginStartupSurfaceReady(
                restoring = false,
                canRetryRestore = true,
                hasSession = false,
                loginSurfaceReady = false,
                hasBlockingSyncError = false,
                accountContentPrepared = false,
            ),
        )
        assertEquals(
            true,
            resolveLoginStartupSurfaceReady(
                restoring = false,
                canRetryRestore = false,
                hasSession = true,
                loginSurfaceReady = false,
                hasBlockingSyncError = true,
                accountContentPrepared = false,
            ),
        )
    }
}
