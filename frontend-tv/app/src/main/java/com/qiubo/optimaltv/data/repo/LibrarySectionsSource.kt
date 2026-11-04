package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.source.HhkanSource
import com.qiubo.optimaltv.data.source.VodApiSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/** Legacy category cards come from the channel endpoint; Douban is a fallback. */
internal fun loadLegacyLibrarySections(
    base: String,
    client: OkHttpClient,
    category: String,
    cid: Int,
    refresh: Boolean,
): List<HhkanSource.ChannelSection> {
    val coldClient = client.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()
    val channel = try {
        HhkanSource.fetchChannelSections(base, coldClient, cid)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    if (channel.any { it.items.isNotEmpty() }) return channel
    return VodApiSource.fetchHome(base, coldClient, category, refresh).sections
}
