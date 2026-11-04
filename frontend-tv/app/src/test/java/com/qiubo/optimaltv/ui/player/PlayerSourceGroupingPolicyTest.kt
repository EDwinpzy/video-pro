package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.LineInfo
import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.source.DongpianPlaybackProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerSourceGroupingPolicyTest {
    @Test fun hhkanMetadataGroupsUnprefixedLinesWithoutGuessingOtherSources() {
        val groups = PlayerSourceGroupingPolicy.groups(
            listOf("HN线路", "高清", "蓝光", "非凡资源·feifan"),
            listOf("hhkan:374249|2|1", "custom:42", "374249|3|1", "hhkan:374249|4|1"),
        )
        assertEquals(listOf("好好看", "其他信号源", "非凡资源"), groups.map { it.provider })
        assertEquals(listOf(0, 2), groups[0].lineIndices)
    }
    @Test fun interleavedProvidersKeepOriginalPlaybackIndices() {
        val groups = PlayerSourceGroupingPolicy.groups(listOf("好好看·HN", "暴风 · 蓝光", "好好看·LZ", "暴风•高清"))
        assertEquals(listOf("好好看", "暴风"), groups.map { it.provider })
        assertEquals(listOf(0, 2), groups[0].lineIndices)
        assertEquals(listOf(1, 3), groups[1].lineIndices)
    }

    @Test fun unprefixedLinesRemainSelectableInOneFallbackGroup() {
        val groups = PlayerSourceGroupingPolicy.groups(listOf("HN线路", "", "LZ线路"))
        assertEquals(listOf(0, 1, 2), groups.single().lineIndices)
        assertEquals("其他信号源", groups.single().provider)
        assertEquals(
            emptyList<PlayerSourceGroupingPolicy.Group>(),
            PlayerSourceGroupingPolicy.groups(lines = emptyList()),
        )
    }

    @Test fun officialLinesGroupByProviderAndKeepOriginalIndices() {
        val lines = listOf(
            officialLine("source-1", "provider-a", "Provider A", 0),
            officialLine("source-2", "provider-b", "Provider B", 1),
            officialLine("source-3", "provider-a", "Provider A", 2),
            officialLine("source-4", "", "Provider C", 3),
        )

        val groups = PlayerSourceGroupingPolicy.groups(lines)

        assertEquals(listOf("Provider A", "Provider B", "Provider C"), groups.map { it.provider })
        assertEquals(listOf(0, 2), groups[0].lineIndices)
        assertEquals(listOf(1), groups[1].lineIndices)
        assertEquals(listOf(3), groups[2].lineIndices)
    }

    @Test fun officialSelectionIdentityUsesPlaybackSourceIdInsteadOfListIndex() {
        val original = officialLine("source-stable", "provider", "Provider", 0)
        val reordered = original.copy(
            official = original.official!!.copy(line = original.official!!.line.copy(sourceIndex = 5)),
        )
        val identity = VodSourceSelectionPolicy.identity(original)

        assertEquals("official:source-stable", identity)
        assertEquals(0, VodSourceSelectionPolicy.matchingIndex(listOf(reordered), identity))
    }

    @Test fun episodeSwitchPrefersSameProviderWhenCanonicalSourceIsMissing() {
        val previous = officialLine("episode-one-source", "provider-a", "Provider A", 0)
        val candidates = listOf(
            officialLine("episode-two-other", "provider-b", "Provider B", 0),
            officialLine("episode-two-provider-line", "provider-a", "Provider A", 1),
        )

        val index = VodSourceSelectionPolicy.selectEpisodeIndex(
            lines = candidates,
            preferredIdentities = listOf(
                VodSourceSelectionPolicy.identity(previous),
                VodSourceSelectionPolicy.episodeIdentity(previous),
            ),
            preferredProviderGroupKey = VodSourceSelectionPolicy.providerGroupKey(previous),
        )

        assertEquals("provider-a", candidates[index!!].official?.line?.providerId)
    }

    @Test fun episodeSwitchFallsBackToAnotherAvailableProvider() {
        val previous = officialLine("episode-one-source", "provider-a", "Provider A", 0)
        val candidates = listOf(
            officialLine("unavailable-line", "provider-b", "Provider B", 0),
            officialLine("available-line", "provider-c", "Provider C", 1),
        )

        val index = VodSourceSelectionPolicy.selectEpisodeIndex(
            lines = candidates,
            preferredIdentities = listOf(VodSourceSelectionPolicy.identity(previous)),
            preferredProviderGroupKey = VodSourceSelectionPolicy.providerGroupKey(previous),
            available = { it.official?.line?.playbackSourceId != "unavailable-line" },
        )

        assertEquals("provider-c", candidates[index!!].official?.line?.providerId)
    }

    @Test fun failedLinePrefersSameProviderAndSkipsAlreadyTriedLines() {
        val lines = listOf(
            officialLine("failed", "provider-a", "Provider A", 0),
            officialLine("already-tried", "provider-a", "Provider A", 1),
            officialLine("other-provider", "provider-b", "Provider B", 2),
            officialLine("same-provider", "provider-a", "Provider A", 3),
        )

        val index = VodSourceSelectionPolicy.selectFallbackIndex(
            lines = lines,
            currentIndex = 0,
            triedIndices = setOf(0, 1),
            preferredProviderGroupKey = VodSourceSelectionPolicy.providerGroupKey(lines[0]),
        )

        assertEquals(3, index)
        assertEquals("same-provider", lines[index!!].official?.line?.playbackSourceId)
    }

    @Test fun failedLineFallsBackToAnotherProviderWhenSameProviderIsExhausted() {
        val lines = listOf(
            officialLine("failed", "provider-a", "Provider A", 0),
            officialLine("other-provider", "provider-b", "Provider B", 1),
        )

        val index = VodSourceSelectionPolicy.selectFallbackIndex(
            lines = lines,
            currentIndex = 0,
            triedIndices = setOf(0),
            preferredProviderGroupKey = VodSourceSelectionPolicy.providerGroupKey(lines[0]),
        )

        assertEquals(1, index)
    }

    private fun officialLine(
        playbackSourceId: String,
        providerId: String,
        providerName: String,
        index: Int,
    ): LineInfo {
        val line =
            DongpianPlaybackProtocol.parseLine(
                JSONObject()
                    .put("id", "internal-$playbackSourceId")
                    .put("playback_source_id", playbackSourceId)
                    .put("provider_id", providerId)
                    .put("provider_name", providerName)
                    .put("source_index", index)
                    .put("resolve_mode", "parse")
                    .put("resolve_ticket", "ticket-$playbackSourceId"),
                index,
            )!!
        return LineInfo(
            vodId = "dongpian:item",
            label = providerName,
            url = "",
            official =
                OfficialPlaybackBinding(
                    episodeToken = "episode",
                    sequenceIndex = 0,
                    episodeCount = 1,
                    contentKind = "movie",
                    line = line,
                ),
        )
    }
}
