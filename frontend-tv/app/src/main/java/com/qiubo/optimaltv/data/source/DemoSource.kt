package com.qiubo.optimaltv.data.source

import android.content.Context
import android.util.Log
import com.qiubo.optimaltv.data.model.Category
import com.qiubo.optimaltv.data.model.Episode
import com.qiubo.optimaltv.data.model.VodItem
import org.json.JSONObject

/**
 * 内置演示片库：assets/demo_source.json。
 * 片目全部指向公网可达的真实测试流（mux/apple/unified-streaming），
 * 并包含「故意失效线路」用于验收看门狗自动换线与错误兜底。
 */
class DemoSource(context: Context) : ContentSource {

    override val id = "demo"
    override val displayName = "演示片库"

    private val snapshot: SourceSnapshot by lazy { parse(loadJson(context)) }

    private fun loadJson(context: Context): JSONObject =
        context.assets.open("demo_source.json").bufferedReader().use { JSONObject(it.readText()) }

    override suspend fun snapshot(): SourceSnapshot = snapshot

    companion object {
        const val TAG = "OTV"

        /** demo_source.json 与远程 /catalog.json 共用同一 schema */
        fun parse(root: JSONObject): SourceSnapshot {
            val src = root.getJSONObject("source")
            val sourceId = src.getString("id")
            val sourceName = src.optString("name", sourceId)
            val cats = root.getJSONArray("categories").let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Category(o.getString("id"), o.getString("name"))
                }
            }
            val items = root.getJSONArray("items").let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    try {
                        val o = arr.getJSONObject(i)
                        val eps = o.getJSONArray("episodes").let { ea ->
                            (0 until ea.length()).map { j ->
                                val e = ea.getJSONObject(j)
                                Episode(j, e.optString("name", "第${j + 1}集"), e.getString("url"))
                            }
                        }
                        VodItem(
                            id = "$sourceId:${o.getString("id")}",
                            sourceId = sourceId,
                            title = o.getString("title"),
                            categoryId = o.getString("categoryId"),
                            year = o.optString("year"),
                            area = o.optString("area"),
                            rating = o.optDouble("rating", 0.0),
                            desc = o.optString("desc"),
                            posterUrl = o.optString("poster"),
                            tags = o.optJSONArray("tags")?.let { ta ->
                                (0 until ta.length()).map { t -> ta.getString(t) }
                            } ?: emptyList(),
                            episodes = eps,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "skip bad item #$i: ${e.message}")
                        null
                    }
                }
            }
            return SourceSnapshot(sourceId, sourceName, cats, items)
        }
    }
}
