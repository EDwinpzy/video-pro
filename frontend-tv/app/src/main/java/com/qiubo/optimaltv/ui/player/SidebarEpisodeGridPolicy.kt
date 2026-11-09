package com.qiubo.optimaltv.ui.player

internal data class SidebarEpisodeCardLayout(
    val episode: Int,
    val widthPx: Int,
)

internal object SidebarEpisodeGridPolicy {
    fun packRows(
        cards: List<SidebarEpisodeCardLayout>,
        availableWidthPx: Int,
        spacingPx: Int,
    ): List<List<SidebarEpisodeCardLayout>> {
        if (cards.isEmpty() || availableWidthPx <= 0) return emptyList()
        val available = availableWidthPx.coerceAtLeast(1)
        val gap = spacingPx.coerceAtLeast(0)
        val rows = mutableListOf<MutableList<SidebarEpisodeCardLayout>>()
        var row = mutableListOf<SidebarEpisodeCardLayout>()
        var rowWidth = 0
        cards.forEach { card ->
            val width = card.widthPx.coerceIn(1, available)
            val required = if (row.isEmpty()) width else rowWidth + gap + width
            if (row.isNotEmpty() && required > available) {
                rows += row
                row = mutableListOf()
                rowWidth = 0
            }
            row += card.copy(widthPx = width)
            rowWidth = if (row.size == 1) width else rowWidth + gap + width
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }
}
