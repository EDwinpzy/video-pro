package com.qiubo.optimaltv.data.source

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DongpianDanmakuClientTransferTest {
    private lateinit var context: Context
    private lateinit var clientTrustManager: X509TrustManager
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val localhost =
            HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build()
        val serverCertificates =
            HandshakeCertificates.Builder()
                .heldCertificate(localhost)
                .build()
        clientTrustManager =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            }
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(timeout = 30_000L)
    fun interruptedShortRangeResumesFromExactPartialOffset() = runTest {
        val payload = "controlled-danmaku-segment-payload".toByteArray()
        val split = 11
        val ranges = mutableListOf<String?>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")
                    ranges += range
                    val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val end = if (offset == 0) split - 1 else payload.lastIndex
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $offset-$end/${payload.size}")
                        .setBody(Buffer().write(payload, offset, end - offset + 1))
                        .apply {
                            if (offset == 0) setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                        }
                }
            }
        val partial = partialFile()

        val actual = client().downloadSegmentWithResume(spec(payload), server.url("/segment").toString(), partial, 0)

        assertArrayEquals(payload, actual)
        assertEquals(listOf(null, "bytes=$split-"), ranges)
    }

    @Test(timeout = 30_000L)
    fun http200IgnoringRangeReplacesPartialAndPassesDigest() = runTest {
        val payload = "full-replacement-after-range-is-ignored".toByteArray()
        var requestedRange: String? = null
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestedRange = request.getHeader("Range")
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(payload))
                }
            }
        val partial = partialFile().apply { writeBytes(payload.copyOfRange(0, 9)) }

        val actual = client().downloadSegmentWithResume(spec(payload), server.url("/segment").toString(), partial, 0)

        assertEquals("bytes=9-", requestedRange)
        assertArrayEquals(payload, actual)
        assertArrayEquals(payload, partial.readBytes())
    }

    @Test(timeout = 30_000L)
    fun unsatisfiedRangeCompletesOnlyWhenServerTotalMatchesPartial() = runTest {
        val payload = "already-complete-partial".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */${payload.size}"),
        )
        val partial = partialFile().apply { writeBytes(payload) }

        val actual = client().downloadSegmentWithResume(spec(payload), server.url("/segment").toString(), partial, 0)

        assertArrayEquals(payload, actual)
        assertEquals("bytes=${payload.size}-", server.takeRequest().getHeader("Range"))
    }

    @Test(timeout = 30_000L)
    fun cancellingSlowResponseKeepsCommittedPartial() = runTest {
        val payload = "slow-network-segment".toByteArray()
        val prefix = payload.copyOfRange(0, 8)
        val requestStarted = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 8-${payload.lastIndex}/${payload.size}")
                        .setBody(Buffer().write(payload, 8, payload.size - 8))
                        .setBodyDelay(2, TimeUnit.SECONDS)
                }
            }
        val partial = partialFile().apply { writeBytes(prefix) }
        val client = client()
        val job =
            async(start = CoroutineStart.UNDISPATCHED) {
                client.downloadSegmentWithResume(spec(payload), server.url("/segment").toString(), partial, 0)
            }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertArrayEquals(prefix, partial.readBytes())
        assertFalse(partial.length() == payload.size.toLong())
    }

    @Test(timeout = 30_000L)
    fun knownCompleteDigestMismatchDeletesPoisonedPartial() = runTest {
        val payload = "expected-segment-bytes".toByteArray()
        val corrupted = payload.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(corrupted)))
        val partial = partialFile()

        try {
            client().downloadSegmentWithResume(spec(payload), server.url("/segment").toString(), partial, 0)
            fail("digest mismatch must fail")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("digest mismatch"))
        }

        assertFalse(partial.exists())
    }

    private fun client(): DongpianDanmakuClient {
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(
                    SSLContext.getInstance("TLS")
                        .apply { init(null, arrayOf(clientTrustManager), SecureRandom()) }
                        .socketFactory,
                    clientTrustManager,
                )
                .hostnameVerifier { _, _ -> true }
                .build()
        return DongpianDanmakuClient(context, client, client)
    }

    private fun spec(payload: ByteArray): DanmakuSegmentSpec =
        DanmakuSegmentSpec(
            fromMs = 480_000L,
            toMs = 540_000L,
            count = 1,
            sha256 = sha256(payload),
            url = server.url("/segment").toString(),
        )

    private fun partialFile(): File = File(context.cacheDir, "danmaku-${UUID.randomUUID()}.part")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
