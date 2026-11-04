package com.qiubo.optimaltv.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.data.model.firstUsablePosterUrl
import com.qiubo.optimaltv.focus.FocusHarnessActivity
import com.qiubo.optimaltv.focus.Harness
import com.qiubo.optimaltv.ui.theme.OptimalTvTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemotePosterFallbackDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<FocusHarnessActivity>()

    @Test
    fun failedPrimaryLoadsRegisteredFallbackAndRendersPixels() {
        val png = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.RED)
        }
        val body =
            ByteArrayOutputStream().use { output ->
                png.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        val server =
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
            }
        val helper =
            thread(isDaemon = true, name = "poster-fallback-test-server") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { client ->
                        runCatching {
                            val reader = client.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) {
                                // Consume the complete request header before writing the response.
                            }
                            val header =
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: image/png\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            client.getOutputStream().apply {
                                write(header.toByteArray())
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            }

        try {
            val deadPrimary = "http://127.0.0.1:9/dead-poster.png"
            val healthyFallback = "http://127.0.0.1:${server.localPort}/poster.png"
            val primary = firstUsablePosterUrl(deadPrimary, healthyFallback)
            Harness.scene = {
                OptimalTvTheme {
                    Box(
                        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        RemotePoster(
                            url = primary,
                            title = "poster fallback",
                            seed = 42,
                            modifier = Modifier.width(232.dp).height(352.dp),
                        )
                    }
                }
            }
            rule.waitForIdle()
            rule.waitUntil(timeoutMillis = 30_000) {
                runCatching {
                    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
                    val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    Color.red(center) > 200 && Color.alpha(center) == 255
                }.getOrDefault(false)
            }
            rule.waitForIdle()

            val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
            val center = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
            assertTrue("fallback poster must render red", Color.red(center) > 200)
            assertTrue("fallback poster must be opaque", Color.alpha(center) == 255)
            val output = File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("qa-evidence")
                    ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "qa-poster-fallback.png",
            )
            output.outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            Harness.scene = null
            server.close()
            helper.join(1_000)
            png.recycle()
        }
    }
}
