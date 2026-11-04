package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.LineInfo

/** Keep original line indices: selecting a provider must not change switchLine's index. */
internal object PlayerSourceGroupingPolicy {
    data class Group(val provider: String, val lineIndices: List<Int>)

    /**
     * Official lines group by provider_id first and provider_name second. The first
     * display name is retained, while original playback indices never change.
     */
    fun groups(lines: List<LineInfo>): List<Group> {
        val groups =
            linkedMapOf<String, Pair<String, MutableList<Int>>>()
        lines.forEachIndexed { index, line ->
            val official = line.official?.line
            val legacy = legacyProvider(line.label, line.playRef.ifBlank { line.vodId })
            val key =
                official?.providerGroupKey?.takeIf(String::isNotBlank)
                    ?: legacy.first
            val display =
                official?.providerDisplayName?.takeIf(String::isNotBlank)
                    ?: legacy.second
            val existing = groups[key]
            if (existing == null) {
                groups[key] = display to mutableListOf(index)
            } else {
                existing.second += index
            }
        }
        return groups.values.map { (provider, indices) ->
            Group(provider, indices.toList())
        }
    }

    fun groups(labels: List<String>, sourceRefs: List<String> = emptyList()): List<Group> {
        val groups = linkedMapOf<String, MutableList<Int>>()
        labels.forEachIndexed { index, label ->
            val (_, provider) =
                legacyProvider(label, sourceRefs.getOrNull(index).orEmpty())
            groups.getOrPut(provider.ifBlank { "其他信号源" }) { mutableListOf() }.add(index)
        }
        return groups.map { (provider, indices) -> Group(provider, indices.toList()) }
    }

    private fun legacyProvider(
        label: String,
        sourceRef: String,
    ): Pair<String, String> {
        val separator = label.indexOfFirst { it == '·' || it == '•' || it == '・' }
        val ref = sourceRef.substringBefore('|')
        val fallback =
            if (
                ref.startsWith("hhkan:") ||
                ref.startsWith("hhkanplay:") ||
                (sourceRef.contains('|') && ref.toLongOrNull() != null)
            ) {
                "好好看"
            } else {
                "其他信号源"
            }
        val provider =
            if (separator > 0) label.substring(0, separator).trim() else fallback
        return "legacy:${provider.ifBlank { "其他信号源" }}" to provider
    }
}
