package com.dongpiandi.videopro.data

enum class DongpianFailureKind {
    INVALID_INPUT,
    NETWORK,
    TRANSPORT,
    UNAUTHORIZED,
    HTTP,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    SIGNING_KEY_UNAVAILABLE,
    SESSION_STORAGE,
    SYNC_NOT_ACCEPTED,
}

/**
 * A deliberately sanitized failure. Messages never include response bodies, cookies, passwords, or full URLs.
 */
class DongpianException(
    val kind: DongpianFailureKind,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
