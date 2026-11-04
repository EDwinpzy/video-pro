package com.qiubo.optimaltv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val HOME_ROUTE = "home"

val HOME_CONTENT_TAB_KEYS = listOf("movie", "series", "anime", "short", "variety")

fun isHomeContentTab(key: String): Boolean = key in HOME_CONTENT_TAB_KEYS

object HomeTabState {
    private var selectedKey by mutableStateOf(HOME_CONTENT_TAB_KEYS.first())

    val currentKey: String
        get() = selectedKey

    fun select(key: String) {
        if (isHomeContentTab(key)) selectedKey = key
    }
}
