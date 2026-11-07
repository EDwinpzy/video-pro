package com.dongpiandi.videopro.data

import java.net.URI

data class DongpianEnvironment(
    val baseUrl: String = DEFAULT_BASE_URL,
    val clientName: String = "movie-search-frontend",
    val clientVersion: String = "1.0.0",
    val buildVersion: String = DEFAULT_BUILD_VERSION,
    val protocolVersion: String = DEFAULT_PROTOCOL_VERSION,
) {
    val origin: String = baseUrl.trimEnd('/')

    init {
        val uri = URI(origin)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Dongpian API must use HTTPS." }
        require(!uri.host.isNullOrBlank()) { "Dongpian API host is missing." }
        require(uri.userInfo == null) { "Dongpian API URL must not contain user information." }
        require(uri.query == null && uri.fragment == null) {
            "Dongpian API base URL must not contain a query or fragment."
        }
    }

    fun url(pathAndQuery: String): String {
        require(pathAndQuery.startsWith('/')) { "API paths must start with '/'." }
        require(!pathAndQuery.startsWith("//")) { "Protocol-relative API paths are not allowed." }
        return origin + pathAndQuery
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ai.dongpian20.com"
        const val DEFAULT_BUILD_VERSION =
            "dongpiandi-v2026.10.30.2-api-build-boundary-ee3385411179-web"
        const val DEFAULT_PROTOCOL_VERSION = "2026-07-05.library-v2.playback-v1"
    }
}
