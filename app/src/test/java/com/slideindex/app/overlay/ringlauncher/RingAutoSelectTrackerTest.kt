package com.slideindex.app.overlay.ringlauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「长按自动选中首槽」的手指位移判定。
 *
 * 回归背景（真机反馈）：判定基准曾经是**圆环锚点**（左右贴边固定距屏幕边缘 20dp），
 * 而悬浮球触摸区是 48dp 见方、球心在 24dp 处，于是按在球上的任何一点都先天带着一段
 * 偏置；偏得稍多就超过 16dp 阈值 → 圆环弹出、首槽高亮、松手却什么也不启动。
 * 现在基准改成"圆环弹出那一刻手指的位置"，按下点即原点，偏置归零。
 */
class RingAutoSelectTrackerTest {

    private val slopPx = 16f

    @Test
    fun staysAliveWhenFingerRestsOnWhereItPressed() {
        // 按在球心（24dp）而锚点在 20dp：旧实现一抬手就会因为 4dp 偏置之外再抖一点而作废。
        val tracker = RingAutoSelectTracker(originX = 24f, originY = 1000f, slopPx = slopPx)
        tracker.update(24f, 1000f)
        assertFalse(tracker.moved)
    }

    @Test
    fun staysAliveForOffCenterPressOnBallEdge() {
        // 按在球的内侧边缘（锚点 20dp + 14dp）：旧实现距离 18dp > 16dp，直接作废。
        // 新基准下"手指没动"，必须保住自动选中。
        val tracker = RingAutoSelectTracker(originX = 34f, originY = 1000f, slopPx = slopPx)
        tracker.update(34f, 1000f)
        assertFalse(tracker.moved)
    }

    @Test
    fun staysAliveWhileFingerStaysInsideSlop() {
        val tracker = RingAutoSelectTracker(originX = 34f, originY = 1000f, slopPx = slopPx)
        // 圆环弹出后松手前的正常抖动（约 6dp）
        tracker.update(38f, 1004f)
        assertFalse(tracker.moved)
    }

    @Test
    fun voidsOnceFingerTravelsPastSlop() {
        val tracker = RingAutoSelectTracker(originX = 34f, originY = 1000f, slopPx = slopPx)
        // 手指开始瞄准别的槽位：向右下走 20dp
        tracker.update(54f, 1000f)
        assertTrue(tracker.moved)
    }

    @Test
    fun staysVoidedAfterFingerReturnsToOrigin() {
        val tracker = RingAutoSelectTracker(originX = 34f, originY = 1000f, slopPx = slopPx)
        tracker.update(60f, 1000f)
        assertTrue(tracker.moved)
        // 永久作废：回到原位也不恢复高亮（语义与「动过就作废」一致）
        tracker.update(34f, 1000f)
        assertTrue(tracker.moved)
    }

    @Test
    fun boundaryExactlyAtSlopStaysAlive() {
        val tracker = RingAutoSelectTracker(originX = 0f, originY = 0f, slopPx = slopPx)
        tracker.update(slopPx, 0f)
        assertFalse(tracker.moved)
    }
}
