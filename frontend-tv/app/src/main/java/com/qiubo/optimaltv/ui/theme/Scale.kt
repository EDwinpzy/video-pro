package com.qiubo.optimaltv.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 设计稿坐标系（1:1 复刻原版 web）：
 * 原版按 1920×1080 CSS px 设计，运行时等比缩放（与原版 fit() 同思路）。
 * 用法：val s = rememberUiScale()
 *   320f.sx(s)  → 320 设计px 的尺寸(dp)
 *   29f.sxs(s)  → 29 设计px 的字号(sp)
 */
@Composable
fun rememberUiScale(): Float {
    val cfg = LocalConfiguration.current
    // scale = 屏宽dp / 1920：sx(v) = v*scale dp → v*scale*density 物理 px = v×(屏宽物理px/1920)
    return cfg.screenWidthDp / 1920f
}

fun Float.sx(scale: Float): Dp = (this * scale).dp

@Composable
fun Float.sxs(scale: Float): TextUnit = with(LocalDensity.current) { (this@sxs * scale).dp.toSp() }

/** 原版 web 色板（index.html :root 与组件 CSS） */
object OtvColors {
    val Bg = Color(0xFF0E0E0F)          // --bg
    val Panel = Color(0xFF232323)       // --panel
    val Live = Color(0xFFFF3A5F)        // --live（LIVE 徽章红）
    val AccentBlue = Color(0xFF0A84FF)
    val White = Color(0xFFFFFFFF)
    val White60 = Color(0x99FFFFFF)     // --muted
    val White75 = Color(0xBFFFFFFF)     // .75
    val White70 = Color(0xB3FFFFFF)     // .7
    val White55 = Color(0x8CFFFFFF)     // .55
    val White50 = Color(0x80FFFFFF)     // .5
    val White30 = Color(0x4DFFFFFF)     // --dim
    val Line = Color(0x1AFFFFFF)        // --line
    val ChipBg = Color(0x1FFFFFFF)      // rgba(255,255,255,.12)
    val TabBarBg = Color(0x801E1E1E)    // rgba(30,30,30,.5)
    val CardBlack80 = Color(0xCC000000) // gc-score rgba(0,0,0,.8)
    val PanelBg = Color(0xF517171C)
    val InputBg = Color(0xFF1C1C22)
}
