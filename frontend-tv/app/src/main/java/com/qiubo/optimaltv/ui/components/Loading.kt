package com.qiubo.optimaltv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 加载转圈（Apple 风格配色）：系统灰白轨道 + 白色进度弧，
 * 对齐 tvOS 加载指示（白系、无彩色），替换原 Material 蓝。
 */
@Composable
fun AppleLoading(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.18f),
        strokeWidth = 3.5.dp,
    )
}
