package com.qiubo.optimaltv.ui.components

/** Source lists may append trailers, cast extras or watch-alongs after the numbered episodes. */
internal fun episodeEntryCustomName(index: Int, sourceName: String?): String? {
    val name = sourceName.orEmpty().trim()
    if (name.isBlank()) return null
    val compact = name.filterNot { it.isWhitespace() }
    val ordinalAlias = Regex("^(?:第)?0*${index + 1}(?:集)?$")
    return name.takeUnless { ordinalAlias.matches(compact) }
}
