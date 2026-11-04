package com.qiubo.optimaltv.data.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DongpianDanmakuParserTest {
    @Test
    fun manifestUsesMillisecondsAndRelativeSegmentUrls() {
        val manifest =
            DongpianDanmakuParser.parseManifest(
                JSONObject(
                    """
                    {
                      "schema_version": "danmaku.manifest.v1",
                      "timebase": "milliseconds",
                      "duration_ms": 600000,
                      "comment_count": 7,
                      "segments": [
                        {
                          "from_ms": 480000,
                          "to_ms": 540000,
                          "count": 7,
                          "sha256": "${"a".repeat(64)}",
                          "url": "dense.json.gz",
                          "content_encoding": "gzip"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
                "https://example.invalid/danmaku/manifest.json",
            )

        assertEquals(1, manifest.segments.size)
        assertEquals(
            "https://example.invalid/danmaku/dense.json.gz",
            manifest.segments.single().url,
        )
    }

    @Test
    fun segmentKeepsValidItemsAndSkipsMalformedRows() {
        val spec =
            DanmakuSegmentSpec(
                fromMs = 480_000L,
                toMs = 540_000L,
                count = 3,
                sha256 = "a".repeat(64),
                url = "https://example.invalid/dense.json.gz",
            )
        val items =
            DongpianDanmakuParser.parseSegment(
                JSONObject(
                    """
                    {
                      "schema_version": "danmaku.segment.v1",
                      "timebase": "milliseconds",
                      "from_ms": 480000,
                      "to_ms": 540000,
                      "items": [
                        [500000, 1, 16777215, 25, "dense", "one"],
                        [500001, 0, 16777215, 25, "bad mode"],
                        [540000, 1, 16777215, 25, "outside range"]
                      ]
                    }
                    """.trimIndent(),
                ),
                spec,
            )

        assertEquals(1, items.size)
        assertEquals("dense", items.single().content)
        assertEquals("one", items.single().sourceId)
    }

    @Test
    fun segmentWithNoCardinalityIsStillHandled() {
        val spec =
            DanmakuSegmentSpec(
                fromMs = 480_000L,
                toMs = 540_000L,
                count = 1,
                sha256 = "a".repeat(64),
                url = "https://example.invalid/dense.json.gz",
            )
        val items =
            DongpianDanmakuParser.parseSegment(
                JSONObject(
                    """
                    {
                      "schema_version": "danmaku.segment.v1",
                      "timebase": "milliseconds",
                      "from_ms": 480000,
                      "to_ms": 540000,
                      "items": [[500000, 1, 0, 16, "still visible"]]
                    }
                    """.trimIndent(),
                ),
                spec,
            )

        assertEquals(1, items.size)
        assertNull(items.single().sourceId)
        assertTrue(items.single().timeMs in 480_000L until 540_000L)
    }
}
