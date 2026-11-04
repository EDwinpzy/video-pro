package com.qiubo.optimaltv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qiubo.optimaltv.R

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

/** Branded launch surface used while the app restores the session and prepares home. */
@Composable
fun VideoProStartupLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF050507)).tvReducedSemantics(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.video_pro_startup_icon),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(168.dp),
        )
        Spacer(Modifier.height(30.dp))
        AppleLoading(size = 42.dp)
    }
}
