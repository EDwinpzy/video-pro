package com.qiubo.optimaltv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val HOME_ROUTE = "home"
const val HOME_TAB_KEY = "home"

val HOME_CONTENT_TAB_KEYS =
    listOf(HOME_TAB_KEY, "movie", "series", "anime", "short", "variety")

fun isHomeContentTab(key: String): Boolean = key in HOME_CONTENT_TAB_KEYS

object HomeTabState {
    private var selectedKey by mutableStateOf(HOME_CONTENT_TAB_KEYS.first())

    val currentKey: String
        get() = selectedKey

    fun select(key: String) {
        if (isHomeContentTab(key)) selectedKey = key
    }
}
