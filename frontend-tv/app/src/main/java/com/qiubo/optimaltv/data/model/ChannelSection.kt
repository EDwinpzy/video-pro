package com.qiubo.optimaltv.data.model

/**
 * One titled row of catalogue items.
 *
 * The official Dongpian catalogue, the local seed catalogue and the home feed
 * layout all describe a row the same way, so they share this type instead of
 * depending on a source-specific one.
 */
data class ChannelSection(
    val name: String,
    val items: List<VodItem>,
)
