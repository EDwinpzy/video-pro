package com.qiubo.optimaltv.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtvNav 几何搜索规则单测（NavGeom 纯函数层）。
 * 用例直接对应导航硬约束：同行判定（高度比 ≤ 2:1）、水平严格左右、
 * 垂直「最近层 + 自缩放层容差」、悬浮层排除、x 中心就近决胜。
 * 坐标单位为 px（boundsInWindow 原始坐标系），不用 dp——引擎比较的就是 px。
 */
class NavGeomTest {

    private fun cand(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        floating: Boolean = false,
        stableKey: Int = 0,
    ) = NavGeom.Cand(Rect(l, t, r, b), floating, stableKey)

    // ---------- sameRow：同行判定 ----------

    @Test
    fun `同行等高元素判定为同行`() {
        val a = Rect(100f, 500f, 400f, 600f)
        val b = Rect(500f, 500f, 800f, 600f)
        assertTrue(NavGeom.sameRow(a, b))
    }

    @Test
    fun `微小垂直重叠但不足大者一半 不算同行_防滚动残节点乱跳`() {
        // 200px 高列与 40px 卡片只叠 30px < 0.5*200=100 → 不同行
        val col = Rect(100f, 0f, 300f, 200f)
        val card = Rect(400f, 170f, 600f, 210f)
        assertFalse(NavGeom.sameRow(col, card))
    }

    @Test
    fun `完全无垂直重叠 不算同行`() {
        val a = Rect(100f, 0f, 300f, 100f)
        val b = Rect(100f, 200f, 300f, 300f)
        assertFalse(NavGeom.sameRow(a, b))
    }

    @Test
    fun `恰好一半重叠算同行_边界值`() {
        // 高度 200 的大者与 100px 重叠 = 恰好一半
        val tall = Rect(0f, 0f, 100f, 200f)
        val small = Rect(200f, 100f, 300f, 200f)
        assertTrue(NavGeom.sameRow(tall, small))
    }

    // ---------- 水平搜索 ----------

    @Test
    fun `RIGHT 取同行右侧最近者`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val near = cand(700f, 500f, 800f, 600f)
        val far = cand(900f, 500f, 1000f, 600f)
        assertEquals(near, NavGeom.search(cb, NAV_RIGHT, listOf(far, near)))
    }

    @Test
    fun `LEFT 取同行左侧最近者_按右边缘计距`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        // 两候选右边缘 380 / 300：更近者胜
        val near = cand(200f, 500f, 380f, 600f)
        val far = cand(100f, 500f, 300f, 600f)
        assertEquals(near, NavGeom.search(cb, NAV_LEFT, listOf(far, near)))
    }

    @Test
    fun `右侧候选与当前横向交叠 不作为 RIGHT 候选_严格不越界`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        // left=550 < cb.right=600：横向交叠，即使同行也排除
        val straddle = cand(550f, 500f, 900f, 600f)
        assertNull(NavGeom.search(cb, NAV_RIGHT, listOf(straddle)))
    }

    @Test
    fun `不同行即使横向贴邻也不选`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val above = cand(610f, 300f, 700f, 420f)
        assertNull(NavGeom.search(cb, NAV_RIGHT, listOf(above)))
    }

    @Test
    fun `方向上无候选返回 null_引擎据此封锁或滚动找回`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        assertNull(NavGeom.search(cb, NAV_RIGHT, emptyList()))
        assertNull(NavGeom.search(cb, NAV_LEFT, listOf(cand(700f, 500f, 800f, 600f))))
    }

    @Test
    fun `RIGHT 边界1px容差_紧贴右边缘的候选可选`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val touching = cand(599.5f, 500f, 800f, 600f)  // left >= 600-1 通过
        assertEquals(touching, NavGeom.search(cb, NAV_RIGHT, listOf(touching)))
    }

    // ---------- 垂直搜索 ----------

    @Test
    fun `DOWN 取下方最近层内 x 中心最近者`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val belowLeft = cand(100f, 700f, 300f, 800f)
        val belowNear = cand(450f, 700f, 650f, 800f)
        assertEquals(belowNear, NavGeom.search(cb, NAV_DOWN, listOf(belowLeft, belowNear)))
    }

    @Test
    fun `DOWN 排除悬浮层_tab栏筛选行不作为垂直候选`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val floatingTab = cand(400f, 700f, 600f, 800f, floating = true)
        assertNull(NavGeom.search(cb, NAV_DOWN, listOf(floatingTab)))
    }

    @Test
    fun `同层微错位并入容差_x 最近者胜_不看 gap`() {
        // cb 高 100；同行两卡高 100，tops 700 与 725（错位 25 < eps=50）
        // x 近的 A gap 更大也必须胜出——同层判定优先于 gap
        val cb = Rect(400f, 500f, 600f, 600f)
        val a = cand(420f, 725f, 620f, 825f)   // |cx-500|=20, gap=125
        val b = cand(100f, 700f, 300f, 800f)   // |cx-500|=300, gap=100
        assertEquals(a, NavGeom.search(cb, NAV_DOWN, listOf(a, b)))
    }

    @Test
    fun `gap 差超层容差 视为相邻层_最近层胜出即使 x 更远`() {
        // cb 高 100 → eps=max(8, 0.5*100)=50；B gap=100，A gap=200，差 100 > 50
        // A 只属于下一层，本层唯一候选 B 胜出
        val cb = Rect(400f, 500f, 600f, 600f)
        val b = cand(100f, 700f, 300f, 800f)   // gap=100（最近层）
        val a = cand(450f, 800f, 650f, 900f)   // gap=200
        assertEquals(b, NavGeom.search(cb, NAV_DOWN, listOf(a, b)))
    }

    @Test
    fun `eps 下限8px_小元素5px错位视为同层`() {
        // 两行小条目高 10：eps = max(8, 5) = 8，gap 差 5 < 8 → 同层，x 近者胜
        val cb = Rect(400f, 500f, 600f, 510f)
        val a = cand(402f, 522f, 598f, 532f)   // gap=22, |cx-500|=2
        val b = cand(100f, 517f, 300f, 527f)   // gap=17, |cx-500|=300
        assertEquals(a, NavGeom.search(cb, NAV_DOWN, listOf(a, b)))
    }

    @Test
    fun `UP 对称_取上方最近层`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val aboveNear = cand(450f, 300f, 650f, 400f)
        val below = cand(450f, 700f, 650f, 800f)
        assertEquals(aboveNear, NavGeom.search(cb, NAV_UP, listOf(aboveNear, below)))
    }

    @Test
    fun `与当前交叠的候选不作为垂直候选`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val overlap = cand(400f, 590f, 600f, 700f)  // top=590 < cb.bottom=600
        assertNull(NavGeom.search(cb, NAV_DOWN, listOf(overlap)))
    }

    @Test
    fun `同 gap 平局按 x 中心距离决胜`() {
        val cb = Rect(400f, 500f, 600f, 600f)
        val l = cand(0f, 700f, 200f, 800f)     // cx=100, 距 400
        val r = cand(700f, 700f, 900f, 800f)   // cx=800, 距 300 更近
        assertEquals(r, NavGeom.search(cb, NAV_DOWN, listOf(l, r)))
    }

    @Test
    fun `同一几何候选重排仍选同目标_防并发注册表迭代乱跳`() {
        /*
         * cards 是并发注册表，候选枚举顺序不应改变用户按 DOWN 的结果。
         * 旧算法用“第一个最小 gap 候选”的高度推导层容差：小卡先出现时，
         * 下一视觉行被排除；大卡先出现时又被并入，导致同一布局随机落点。
         */
        val current = Rect(400f, 500f, 600f, 600f)
        val shortFar = cand(0f, 700f, 200f, 710f)       // gap=100, h=10, x 远
        val tallFar = cand(700f, 700f, 900f, 900f)       // gap=100, h=200, x 较近
        val alignedNext = cand(450f, 740f, 650f, 840f)   // gap=140, 同一视觉层、x 对齐
        val a = listOf(shortFar, tallFar, alignedNext)
        val b = listOf(tallFar, shortFar, alignedNext)
        assertEquals(alignedNext, NavGeom.search(current, NAV_DOWN, a))
        assertEquals(alignedNext, NavGeom.search(current, NAV_DOWN, b))
    }

    @Test
    fun `完全同分候选按稳定注册键决胜_不依赖并发枚举顺序`() {
        val current = Rect(400f, 500f, 600f, 600f)
        val later = cand(450f, 700f, 650f, 800f, stableKey = 9)
        val earlier = cand(450f, 700f, 650f, 800f, stableKey = 2)
        assertEquals(earlier, NavGeom.search(current, NAV_DOWN, listOf(later, earlier)))
        assertEquals(earlier, NavGeom.search(current, NAV_DOWN, listOf(earlier, later)))
    }

    // ---------- 真实页面几何回归（按 1920×1080 横屏系构造） ----------

    @Test
    fun `回归_影视页网格卡DOWN落在下一行同列卡`() {
        // 网格 4 列卡宽 ~420 行高 ~300+24：从第2列 DOWN 应落第2列下一行
        val card12 = Rect(460f, 400f, 880f, 700f)   // 当前（第2列）
        val card21 = Rect(20f, 724f, 440f, 1024f)
        val card22 = Rect(460f, 724f, 880f, 1024f)  // 期望目标
        val card23 = Rect(900f, 724f, 1320f, 1024f)
        assertEquals(card22, NavGeom.search(card12, NAV_DOWN, listOf(card21, card22, card23).map { NavGeom.Cand(it) })!!.bounds)
    }

    @Test
    fun `回归_chips行高48与网格卡高300_DOWN不得从chips斜跳进网格中间列x远处`() {
        // chips cx=500；网格首行三卡 cx=220/700/1180。chips DOWN：
        // 三卡 top 相同（同层），x 最近 = cx=700 的卡（|500-700|=200）
        val chips = Rect(460f, 96f, 540f, 144f)
        val c1 = NavGeom.Cand(Rect(20f, 200f, 420f, 500f))
        val c2 = NavGeom.Cand(Rect(500f, 200f, 900f, 500f))
        val c3 = NavGeom.Cand(Rect(980f, 200f, 1380f, 500f))
        assertEquals(c2, NavGeom.search(chips, NAV_DOWN, listOf(c1, c2, c3)))
    }

    @Test
    fun `回归_电视剧标签DOWN进入视觉最近的最近热门第二张卡`() {
        /*
         * 用户可见布局：当前「电视剧」标签在第二张卡正上方。DOWN 必须按实际
         * 屏幕 x 中心落第二张，不能因为数据顺序或 LazyColumn 索引跳到第一张。
         */
        val tvTab = Rect(390f, 40f, 510f, 96f)
        val first = cand(40f, 180f, 260f, 510f)
        val second = cand(340f, 180f, 560f, 510f)
        val third = cand(640f, 180f, 860f, 510f)
        assertEquals(second, NavGeom.search(tvTab, NAV_DOWN, listOf(first, second, third)))
    }
}
