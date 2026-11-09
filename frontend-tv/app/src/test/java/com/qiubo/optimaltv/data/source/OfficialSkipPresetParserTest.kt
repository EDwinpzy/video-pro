package com.qiubo.optimaltv.data.source

import com.qiubo.optimaltv.data.model.OfficialSkipPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialSkipPresetParserTest {
    @Test
    fun parsesVerifiedCommunityPreset() {
        val preset =
            OfficialSkipPresetParser.parse(
                JSONObject()
                    .put("source", "community_median")
                    .put("intro_end_seconds", 90)
                    .put("outro_from_end_seconds", 120)
                    .put("sample_count", 8)
                    .put("report_count", 5),
            )

        assertEquals(OfficialSkipPreset(90, 120, 8, 5), preset)
    }

    @Test
    fun acceptsOneSidedPresetAndRejectsEmptyPreset() {
        val introOnly =
            OfficialSkipPresetParser.parse(
                communityPreset().put("outro_from_end_seconds", 0),
            )
        val empty =
            OfficialSkipPresetParser.parse(
                communityPreset()
                    .put("intro_end_seconds", 0)
                    .put("outro_from_end_seconds", 0),
            )

        assertEquals(90, introOnly?.introEndSeconds)
        assertEquals(0, introOnly?.outroFromEndSeconds)
        assertNull(empty)
    }

    @Test
    fun rejectsUnverifiedCommunityData() {
        assertNull(OfficialSkipPresetParser.parse(communityPreset().put("sample_count", 2)))
        assertNull(OfficialSkipPresetParser.parse(communityPreset().put("report_count", 2)))
    }

    @Test
    fun rejectsUnexpectedSourceAndInvalidRanges() {
        assertNull(OfficialSkipPresetParser.parse(communityPreset().put("source", "user")))
        assertNull(OfficialSkipPresetParser.parse(communityPreset().put("intro_end_seconds", -1)))
        assertNull(
            OfficialSkipPresetParser.parse(
                communityPreset().put("outro_from_end_seconds", 601),
            ),
        )
        assertNull(OfficialSkipPresetParser.parse(null))
    }

    private fun communityPreset(): JSONObject =
        JSONObject()
            .put("source", "community_median")
            .put("intro_end_seconds", 90)
            .put("outro_from_end_seconds", 120)
            .put("sample_count", 8)
            .put("report_count", 5)
}
