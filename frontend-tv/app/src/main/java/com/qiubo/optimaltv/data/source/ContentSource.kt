package com.qiubo.optimaltv.data.source

import com.qiubo.optimaltv.data.model.Category
import com.qiubo.optimaltv.data.model.VodItem

data class SourceSnapshot(
    val sourceId: String,
    val sourceName: String,
    val categories: List<Category>,
    val items: List<VodItem>,
)

/** 内容源统一契约（技术方案 §3.3）：内置演示库与远程接口源都实现它 */
interface ContentSource {
    val id: String
    val displayName: String
    suspend fun snapshot(): SourceSnapshot
}

data class SourceState(val id: String, val name: String, val ok: Boolean, val message: String)
