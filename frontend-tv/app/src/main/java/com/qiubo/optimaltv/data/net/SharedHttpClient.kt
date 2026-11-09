package com.qiubo.optimaltv.data.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The shared client every data source builds on.
 *
 * It keeps the original timeouts of the removed My TV remote source: a 10s
 * connect timeout and a 10s read timeout with system TLS validation, so a
 * catalogue or playback address is never replaced by a man in the middle.
 * Longer cold paths derive their own client from this one.
 */
object SharedHttpClient {
    fun default(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
}
