package com.qiubo.optimaltv.data.source

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30])
class DoubanShortCategoryTest {
    @Test fun requestedShortCategoryDoesNotRelabelAnOrdinarySeries() {
        val row = JSONObject().put("id", "fictional-series").put("title", "Fictional series").put("category", "tv")
        assertEquals("douban:tv", VodApiSource.parseItems(JSONArray().put(row), "short").single().categoryId)
    }

    @Test fun unknownTypeRequiresPositiveShortDramaMetadata() {
        val row = JSONObject().put("id", "fictional").put("title", "Fictional").put("genres", JSONArray().put("剧情"))
        assertEquals("douban:", VodApiSource.parseItems(JSONArray().put(row), "short").single().categoryId)
        row.put("genres", JSONArray().put("微短剧"))
        assertEquals("douban:short", VodApiSource.parseItems(JSONArray().put(row), "short").single().categoryId)
    }
}
