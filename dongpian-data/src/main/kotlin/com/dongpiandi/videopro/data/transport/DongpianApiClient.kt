package com.dongpiandi.videopro.data.transport

import com.dongpiandi.videopro.data.DongpianEnvironment
import com.dongpiandi.videopro.data.DongpianException
import com.dongpiandi.videopro.data.DongpianFailureKind
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale

enum class DongpianHttpMethod {
    GET,
    POST,
}

data class DongpianRequest(
    val method: DongpianHttpMethod,
    val pathAndQuery: String,
    val body: String? = null,
    val cookie: String? = null,
    val retryOnSignatureFailure: Boolean = method == DongpianHttpMethod.GET,
)

data class DongpianHttpResponse(
    val status: Int,
    val body: String,
    val setCookies: List<String>,
) {
    val isSuccess: Boolean
        get() = status in 200..299

    fun requireSuccess(): DongpianHttpResponse {
        if (isSuccess) return this
        val kind =
            when (status) {
                HttpStatusCode.Unauthorized.value,
                HttpStatusCode.Forbidden.value,
                -> DongpianFailureKind.UNAUTHORIZED
                else -> DongpianFailureKind.HTTP
            }
        throw DongpianException(
            kind = kind,
            message = "Dongpian request failed with HTTP $status.",
            httpStatus = status,
        )
    }
}

data class DongpianApiClientOptions(
    val clockMillis: () -> Long = System::currentTimeMillis,
    val nonceProvider: () -> String = defaultNonce(),
    val maxResponseBytes: Int = DongpianApiClient.MAX_RESPONSE_BYTES,
)

class DongpianApiClient(
    private val client: HttpClient,
    private val environment: DongpianEnvironment,
    private val signingKeyProvider: DongpianSigningKeyProvider,
    private val options: DongpianApiClientOptions = DongpianApiClientOptions(),
) {
    suspend fun execute(request: DongpianRequest): DongpianHttpResponse {
        require(request.pathAndQuery.startsWith("/v1/")) { "Only /v1/ API paths are supported." }
        var response = executeOnce(request, forceSigningKeyRefresh = false)
        if (request.retryOnSignatureFailure &&
            response.status == HttpStatusCode.Unauthorized.value &&
            INVALID_SIGNATURE_MARKER in response.body
        ) {
            signingKeyProvider.invalidate()
            response = executeOnce(request, forceSigningKeyRefresh = true)
        }
        return response
    }

    private suspend fun executeOnce(
        request: DongpianRequest,
        forceSigningKeyRefresh: Boolean,
    ): DongpianHttpResponse {
        val timestamp = options.clockMillis().toString()
        val nonce = options.nonceProvider()
        val key = signingKeyProvider.signingKey(forceRefresh = forceSigningKeyRefresh)
        val signature =
            DongpianRequestSigner.sign(
                key = key,
                method = request.method.name,
                pathAndQuery = request.pathAndQuery,
                timestamp = timestamp,
                nonce = nonce,
            )
        try {
            val response =
                client.request(environment.url(request.pathAndQuery)) {
                    method = HttpMethod.parse(request.method.name)
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(HEADER_TIMESTAMP, timestamp)
                    header(HEADER_NONCE, nonce)
                    header(HEADER_SIGNATURE, signature)
                    header(HEADER_CLIENT_NAME, environment.clientName)
                    header(HEADER_CLIENT_VERSION, environment.clientVersion)
                    header(HEADER_BUILD_VERSION, environment.buildVersion)
                    header(HEADER_PROTOCOL_VERSION, environment.protocolVersion)
                    request.cookie?.takeIf(String::isNotBlank)?.let { header(HttpHeaders.Cookie, it) }
                    request.body?.let {
                        contentType(ContentType.Application.Json)
                        setBody(it)
                    }
                }
            val body = response.bodyAsText()
            if (body.toByteArray(Charsets.UTF_8).size > options.maxResponseBytes) {
                responseTooLarge()
            }
            return DongpianHttpResponse(
                status = response.status.value,
                body = body,
                setCookies = cookiePairs(response.headers.getAll(HttpHeaders.SetCookie).orEmpty()),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw DongpianException(
                kind = DongpianFailureKind.NETWORK,
                message = "Dongpian request could not be completed.",
                cause = failure,
            )
        }
    }

    private fun cookiePairs(values: List<String>): List<String> =
        buildList {
            val byName = LinkedHashMap<String, String>()
            for (value in values) {
                val pair = value.substringBefore(';').trim()
                val separator = pair.indexOf('=')
                if (separator > 0) byName[pair.substring(0, separator)] = pair
            }
            addAll(byName.values)
        }

    private fun responseTooLarge(): Nothing =
        throw DongpianException(
            kind = DongpianFailureKind.RESPONSE_TOO_LARGE,
            message = "Dongpian response exceeded the size limit.",
        )

    companion object {
        const val MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
        private const val INVALID_SIGNATURE_MARKER = "invalid_request_signature"
        private const val HEADER_TIMESTAMP = "x-ai-movie-timestamp"
        private const val HEADER_NONCE = "x-ai-movie-nonce"
        private const val HEADER_SIGNATURE = "x-ai-movie-signature"
        private const val HEADER_CLIENT_NAME = "x-ai-movie-client-name"
        private const val HEADER_CLIENT_VERSION = "x-ai-movie-client-version"
        private const val HEADER_BUILD_VERSION = "x-ai-movie-build-version"
        private const val HEADER_PROTOCOL_VERSION = "x-ai-movie-protocol-version"
    }
}

private fun defaultNonce(): () -> String {
    val random = SecureRandom()
    return {
        val bytes = ByteArray(16).also(random::nextBytes)
        buildString(32) {
            for (byte in bytes) append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        }
    }
}
