package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DongpianAuthPolicyTest {
    @Test
    fun loginCredentialsFollowOfficialAccountRules() {
        assertEquals(
            "user.name+tv@example",
            DongpianAuthClient.validateLoginCredentials(" user.name+tv@example ", "12345678"),
        )
    }

    @Test
    fun ordinaryUnauthorizedResponseExpiresSession() {
        assertTrue(
            DongpianAuthClient.isSessionRejected(
                401,
                """{"error":"request_error","message":"User session is required."}""",
            ),
        )
    }

    @Test
    fun invalidSignatureDoesNotExpireSession() {
        assertFalse(
            DongpianAuthClient.isSessionRejected(
                401,
                """{"error":"invalid_request_signature","message":"Invalid request signature"}""",
            ),
        )
        assertTrue(
            DongpianAuthClient.isSignatureRejected(
                401,
                """{"error":"invalid_request_signature","message":"Invalid request signature"}""",
            ),
        )
    }

    @Test
    fun ordinaryForbiddenBusinessErrorDoesNotExpireSession() {
        assertFalse(
            DongpianAuthClient.isSessionRejected(
                403,
                """{"error":"playback_not_allowed","message":"This account cannot play this title"}""",
            ),
        )
    }

    @Test
    fun explicitExpiredSessionForbiddenResponseExpiresSession() {
        assertTrue(
            DongpianAuthClient.isSessionRejected(
                403,
                """{"error":"session_expired","message":"登录已失效，请重新登录"}""",
            ),
        )
    }

    @Test
    fun loginErrorsKeepSignatureFailuresSeparateFromWrongPassword() {
        assertEquals(
            "登录服务配置异常，请稍后重试。",
            DongpianAuthClient.loginErrorMessage(
                401,
                """{"error":"invalid_request_signature"}""",
            ),
        )
        assertEquals(
            "账号或密码错误。",
            DongpianAuthClient.loginErrorMessage(
                401,
                """{"error":"account_or_password_is_invalid"}""",
            ),
        )
        assertEquals(
            "操作太频繁，请稍后重试。",
            DongpianAuthClient.loginErrorMessage(429, """{"error":"rate_limited"}"""),
        )
    }

    @Test
    fun playbackWireCodeIsReadFromPayloadOrNestedError() {
        assertEquals(
            "playback_resolve_ticket_expired",
            wireErrorCode(
                """{"payload":{"code":"playback_resolve_ticket_expired"}}""",
            ),
        )
        assertEquals(
            "playback_line_ticket_expired",
            wireErrorCode(
                """{"error":{"code":"playback_line_ticket_expired"}}""",
            ),
        )
        assertEquals("", wireErrorCode("<html>bad gateway</html>"))
    }

    @Test
    fun ticketRefreshCodeIsOnlyReadFromTheTopLevelCodeField() {
        assertEquals(
            "playback_resolve_ticket_expired",
            topLevelWireErrorCode("""{"code":"playback_resolve_ticket_expired"}"""),
        )
        assertEquals(
            "",
            topLevelWireErrorCode("""{"error":{"code":"playback_resolve_ticket_expired"}}"""),
        )
        assertEquals(
            "",
            topLevelWireErrorCode("""{"payload":{"code":"playback_resolve_ticket_expired"}}"""),
        )
        assertEquals("", topLevelWireErrorCode("<html>unavailable</html>"))
    }
}
