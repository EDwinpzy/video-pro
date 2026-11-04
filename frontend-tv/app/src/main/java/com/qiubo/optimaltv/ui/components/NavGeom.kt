package com.qiubo.optimaltv.ui.components

import androidx.compose.ui.geometry.Rect

internal fun navDx(dir: Int) = when (dir) { NAV_LEFT -> -1; NAV_RIGHT -> 1; else -> 0 }
internal fun navDy(dir: Int) = when (dir) { NAV_UP -> -1; NAV_DOWN -> 1; else -> 0 }

/**
 * OtvNav 几何搜索的纯函数核心：只依赖 Rect 与方向常量，不触 Android/Compose 运行时，
 * 供 JVM 单元测试直接覆盖导航规则（同行判定、层容差、水平严格性）。
 * 引擎侧（Focus.kt OtvNav.searchGeom）只负责注册表过滤
 *（valid/hidden/分组/exclude/autoSelect 排除），把候选映射成 Cand 后调这里。
 */
object NavGeom {

    /**
     * 候选节点的几何视图。
     *
     * stableKey 必须由注册表的稳定 id 提供。布局坐标完全相同或评分完全相同的
     * 两张卡，不能因为 ConcurrentHashMap 的枚举顺序不同而落到不同目标。
     */
    data class Cand(
        val bounds: Rect,
        val floating: Boolean = false,
        val stableKey: Int = 0,
    )

    /**
     * 纵向重叠是否达到同行标准（水平移动的同行判定）。
     * v1.10 规则保留：重叠 ≥ 【较大】高度的一半——本应用真实同行元素高度比 ≤ 2:1，
     * 高度比 > 2:1 的滚动残节点彻底出局（防乱跑）。
     */
    fun sameRow(a: Rect, b: Rect): Boolean {
        val ov = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return ov > 0f && ov >= 0.5f * maxOf(a.height, b.height)
    }

    /**
     * 确定性几何搜索（借鉴 BBC lrud-spatial / W3C spatial-nav 打分模型）：
     *  - 水平：同行内严格左/右最近（边距）；
     *  - 垂直：先取「边距最近层」，层内容差 < 当前焦点元素高度的一半（下限 8px）
     *    视为同层，层内按 |中心 x 差| 最近、边距决胜。
     *    自缩放取代旧版每屏调 60/100px 魔法容差：同层微错位必并入、
     *    相邻区域（行距通常 > 较小元素高度的一半）必分离。
     * candidates 已由调用方完成 valid/hidden/分组/exclude/autoSelect 排除过滤。
     */
    fun search(cb: Rect, dir: Int, candidates: List<Cand>): Cand? {
        val ccx = cb.center.x
        return if (navDx(dir) != 0) {
            candidates
                .filter { sameRow(it.bounds, cb) }
                .filter {
                    if (navDx(dir) > 0) it.bounds.left >= cb.right - 1f else it.bounds.right <= cb.left + 1f
                }
                .minWithOrNull(
                    compareBy<Cand>(
                        { if (navDx(dir) > 0) it.bounds.left else -it.bounds.right },
                        { it.stableKey },
                    ),
                )
        } else {
            val spaced = candidates
                .filter { !it.floating }
                .filter {
                    if (navDy(dir) > 0) it.bounds.top >= cb.bottom - 1f else it.bounds.bottom <= cb.top + 1f
                }
                .map { c ->
                    val gap = if (navDy(dir) > 0) c.bounds.top - cb.bottom else cb.top - c.bounds.bottom
                    Triple(c, gap, c.bounds.height)
                }
                .toList()
            if (spaced.isEmpty()) return null
            val minGap = spaced.minOf { it.second }
            // 容差只能依赖当前焦点的稳定几何，不能取“第一个最小 gap 候选”的
            // 高度；后者来自并发注册表的弱一致迭代，会让相同画面按出不同落点。
            val eps = maxOf(8f, 0.5f * cb.height)
            spaced.filter { it.second - minGap < eps }
                .minWithOrNull(
                    compareBy<Triple<Cand, Float, Float>>(
                        { kotlin.math.abs(it.first.bounds.center.x - ccx) },
                        { it.second },
                        { it.first.stableKey },
                    ),
                )?.first
        }
    }
}
