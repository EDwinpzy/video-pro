package com.qiubo.optimaltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodRetryPolicyTest {
    @Test
    fun `every VOD uses continuous retry without a startup timeout`() {
        assertTrue(VodRetryPolicy.shouldRetry(isLive = false))
        assertFalse(VodRetryPolicy.shouldUseStartupWatchdog(isLive = false))
        assertFalse(VodRetryPolicy.shouldRetry(isLive = true))
        assertTrue(VodRetryPolicy.shouldUseStartupWatchdog(isLive = true))
    }

    @Test
    fun `unavailable lines switch after two attempts while stalls keep retrying`() {
        val unavailable = "所选影视源解析失败（播放线路返回了非视频地址，请切换线路或稍后重试。）"
        assertFalse(VodRetryPolicy.shouldSwitchLineAfterFailure(unavailable, 1))
        assertTrue(VodRetryPolicy.shouldSwitchLineAfterFailure(unavailable, 2))
        assertFalse(VodRetryPolicy.shouldSwitchLineAfterFailure("播放卡顿，继续同线重试", 10))
        assertFalse(VodRetryPolicy.shouldSwitchLineAfterFailure("首帧超时", 10))
    }

    @Test
    fun `boot exceptions keep the VOD retry loop alive without showing a fatal state`() {
        assertTrue(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = false,
            hasFatalMessage = false,
            attemptFailed = true,
        ))
        assertTrue(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = false,
            hasFatalMessage = true,
            attemptFailed = false,
        ))
        assertFalse(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = false,
            hasFatalMessage = false,
            attemptFailed = false,
        ))
        assertFalse(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = true,
            hasFatalMessage = true,
            attemptFailed = true,
        ))
    }

    @Test
    fun `permanent missing-line failures stop the boot retry loop`() {
        assertFalse(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = false,
            hasFatalMessage = true,
            attemptFailed = false,
            fatalReason = "该集没有可用线路",
        ))
        assertFalse(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = false,
            hasFatalMessage = true,
            attemptFailed = false,
            fatalReason = "换集失败，请重试",
        ))
        assertTrue(VodRetryPolicy.shouldRetryBootAfterAttempt(
            isLive = false,
            hasFatalMessage = true,
            attemptFailed = false,
            fatalReason = "懂片帝服务暂时不可用（HTTP 502）。",
        ))
    }

    @Test
    fun `pending seek target is kept while engine position is stale`() {
        assertEquals(184_000L, VodRetryPolicy.resumePositionMs(184_000L, 12_000L))
        assertEquals(12_000L, VodRetryPolicy.resumePositionMs(null, 12_000L))
        assertEquals(0L, VodRetryPolicy.resumePositionMs(null, -4_000L))
    }

    @Test
    fun `seek target is acknowledged only when engine reaches it`() {
        assertFalse(VodRetryPolicy.reachedSeekTarget(600_000L, 1_200_000L))
        assertTrue(VodRetryPolicy.reachedSeekTarget(1_198_000L, 1_200_000L))
    }

    @Test
    fun `new retry generations invalidate delayed work from older selections`() {
        val generations = VodRetryGeneration()
        val old = generations.begin()
        val current = generations.begin()

        assertFalse(generations.isCurrent(old))
        assertTrue(generations.isCurrent(current))

        generations.invalidate()
        assertFalse(generations.isCurrent(current))
    }

    @Test
    fun `episode source matching follows identity and reports a missing source`() {
        assertEquals(1, VodRetryPolicy.matchingSourceIndex("source-b", listOf("source-a", "source-b")))
        assertEquals(null, VodRetryPolicy.matchingSourceIndex("source-a", listOf("source-b")))
        assertEquals(null, VodRetryPolicy.matchingSourceIndex(null, listOf("source-a")))
    }
}
