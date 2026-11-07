package com.qiubo.optimaltv.ui.home

import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.firstUsablePosterUrl
import com.qiubo.optimaltv.data.source.HhkanSource.ChannelSection

/** Selects popular catalogue titles for the TV hero carousel. */
internal object HomeHeroPolicy {
    const val MAX_HEROES = 8
    const val PER_HOME_CATEGORY = 3
    val HOME_CATEGORIES = listOf("movie", "tv", "anime")

    fun forCategory(sections: List<ChannelSection>): List<HeroEntry> {
        val popular = sections.firstOrNull { it.name == "大家都在看" }?.items.orEmpty()
        return fromItems(if (popular.isNotEmpty()) popular else sections.flatMap(ChannelSection::items))
    }

    fun forHome(itemsByCategory: Map<String, List<VodItem>>): List<HeroEntry> {
        val buckets =
            HOME_CATEGORIES.map { category ->
                preferredArtworkItems(itemsByCategory[category].orEmpty())
                    .mapNotNull(::entry)
                    .take(PER_HOME_CATEGORY)
            }
        val mixed = buildList {
            repeat(PER_HOME_CATEGORY) { offset ->
                buckets.forEach { bucket -> bucket.getOrNull(offset)?.let(::add) }
            }
        }
        return mixed.distinctBy(HeroEntry::id).take(MAX_HEROES)
    }

    fun fromItems(items: List<VodItem>): List<HeroEntry> =
        preferredArtworkItems(items)
            .asSequence()
            .mapNotNull(::entry)
            .take(MAX_HEROES)
            .toList()

    /**
     * The catalogue also returns poster-only titles. Keep the popular ordering, but
     * move dedicated backdrop/carousel artwork ahead of those portraits so the
     * short 3.84:1 hero band does not upscale and hard-crop a vertical poster.
     */
    private fun preferredArtworkItems(items: List<VodItem>): List<VodItem> {
        val distinct = items.distinctBy(VodItem::id)
        val dedicated = distinct.filter { item ->
            val hero = item.heroUrl.trim()
            hero.isNotBlank() && hero != item.posterUrl.trim()
        }
        return dedicated.ifEmpty { distinct }
    }

    private fun entry(item: VodItem): HeroEntry? {
        val poster = firstUsablePosterUrl(item.posterUrl)
        val dedicated = firstUsablePosterUrl(item.heroUrl)
        val artwork = dedicated.ifBlank { poster }.takeIf(String::isNotBlank) ?: return null
        return HeroEntry(
            id = item.id,
            title = item.title,
            bg = artwork,
            fallbackBg = poster.takeIf { it.isNotBlank() && it != artwork }.orEmpty(),
            tag = item.meta.ifBlank { item.remark },
        )
    }
}
