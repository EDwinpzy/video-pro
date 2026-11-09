package com.qiubo.optimaltv.data.model

/**
 * Data shapes the catalogue UI renders.
 *
 * They used to live inside the removed My TV source classes. The local seed
 * catalogue is gone, but the official catalogue mapping and TV UI still use
 * these shapes, so they are plain model types now.
 */
data class CarouselItem(
    val vid: String,
    val title: String,
    val backdrop: String,
    val tags: List<String>,
)

data class LineDetail(
    val name: String,
    val episodes: List<Triple<String, String, String>>,
)

data class DetailInfo(
    val title: String,
    val year: String,
    val desc: String,
    val poster: String,
    val rating: Double,
    val meta: String = "",
    val actors: String = "",
    val director: String = "",
    val lines: List<LineDetail> = emptyList(),
)

data class ShowPage(
    val items: List<VodItem>,
    val hasMore: Boolean,
    val upstreamAvailable: Boolean = true,
)
