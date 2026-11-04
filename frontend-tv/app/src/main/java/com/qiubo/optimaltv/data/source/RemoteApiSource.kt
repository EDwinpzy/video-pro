package com.qiubo.optimaltv.data.source

import com.qiubo.optimaltv.data.model.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程接口源（技术方案 §3.3 形态B简化契约）：
 * GET {base}/catalog.json 返回与内置演示库相同 schema 的 JSON。
 * 超时 10s；失败抛 IOException，由仓库层记录到 SourceState 展示在 UI。
 */
class RemoteApiSource(
    private val baseUrl: String,
    private val okHttp: OkHttpClient,
) : ContentSource {

    override val id: String
    override val displayName: String

    init {
        val host = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
        id = host.ifBlank { "remote" }
        displayName = "远程源·$host"
    }

    private var cache: Pair<Long, SourceSnapshot>? = null

    override suspend fun snapshot(): SourceSnapshot = withContext(Dispatchers.IO) {
        cache?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second?.let { return@withContext it }
        val url = baseUrl.trimEnd('/') + "/catalog.json"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) OptimalTV/1.0")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body?.string().orEmpty()
            require(body.isNotBlank()) { "空响应" }
            val snap = DemoSource.parse(JSONObject(body))
            cache = System.currentTimeMillis() to snap
            snap
        }
    }

    companion object {
        /** 独立 OkHttpClient：使用系统 TLS 校验，避免目录/播放地址被中间人替换。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
