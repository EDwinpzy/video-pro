package com.qiubo.optimaltv.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer

/** Keep native traversal inside a visual region and give re-entry a stable anchor. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvFocusRegion(entry: FocusRequester? = null): Modifier =
    if (entry != null) {
        focusProperties { enter = { entry } }.focusGroup()
    } else {
        focusRestorer().focusGroup()
    }

/** Resolve re-entry when navigation occurs, after lazy layout and data have settled. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvFocusRegion(entry: () -> FocusRequester?): Modifier =
    focusProperties { enter = { entry() ?: FocusRequester.Default } }.focusGroup()
