package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.ChannelSection

internal object HomeFeedLayout {
    fun titles(category: String): List<String> = listOf("大家都在看") + when (category) {
        "movie" -> listOf("高分电影", "电影热播", "院线热映", "即将上映", "冷门好片")
        "tv" -> listOf("国产新剧", "美剧续看", "韩剧在追", "日剧上新")
        "anime" -> listOf("最近热门", "最近更新")
        "short" -> listOf("最近热门", "最近更新")
        "variety" -> emptyList()
        else -> listOf("最近热门", "最近更新", "豆瓣高分")
    }

    fun sections(
        category: String,
        ranking: List<VodItem>,
        official: List<ChannelSection>,
        douban: List<ChannelSection>,
    ): List<ChannelSection> = titles(category).mapIndexed { index, title ->
        val items = when {
            index == 0 -> ranking.filter { matchesCategory(it, category) }
            category == "movie" || category == "tv" -> official.firstOrNull { it.name == title }?.items.orEmpty()
            else -> douban.firstOrNull { it.name == title }?.items
                ?: if (title == "最近更新") douban.firstOrNull { it.name == "最新上映" }?.items.orEmpty() else emptyList()
        }
        val filtered = if (index == 0 || category in setOf("anime", "short", "variety")) items.filter { matchesCategory(it, category) } else items
        ChannelSection(title, filtered.distinctBy(VodItem::id))
    }

    fun matchesCategory(item: VodItem, category: String): Boolean {
        val kind = item.categoryId.removePrefix("douban:")
        if (kind != category && !(category == "tv" && kind == "series")) return false
        if (category != "anime" || item.area.isBlank()) return true
        return listOf("中国", "大陆", "内地", "国产", "香港", "台湾", "China", "CN").any { item.area.contains(it, ignoreCase = true) }
    }
}
