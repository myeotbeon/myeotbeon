package app.myeotbeonideun.gleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 바깥 호출의 연속 호출 제한(결함 18). 순수 규칙. */
class CallLimiterTest {
    @Test
    fun `최소 간격 안의 호출은 받지 않고 세지도 않는다`() {
        val l = CallLimiter(minGapMs = 4_000, maxInWindow = 4, windowMs = 60_000)
        assertTrue(l.tryAcquire(0))
        assertFalse(l.tryAcquire(1_000))
        assertFalse(l.tryAcquire(3_999))
        assertTrue("거절한 호출은 세지 않아 4초 뒤에는 받는다", l.tryAcquire(4_000))
    }

    @Test
    fun `창 안의 최대 횟수를 넘기면 받지 않고 창이 지나면 다시 받는다`() {
        val l = CallLimiter(minGapMs = 4_000, maxInWindow = 4, windowMs = 60_000)
        val accepted = (0 until 20).map { i -> l.tryAcquire(i * 5_000L) }
        assertEquals("1분에 네 번까지", listOf(true, true, true, true), accepted.take(4))
        assertEquals(4, accepted.take(12).count { it }) // 0~55초
        assertTrue("첫 호출에서 1분이 지나면 다시 받는다", accepted[12]) // 60초
    }

    @Test
    fun `먼저 보고 실제로 일을 했을 때만 세면 거절된 호출은 제한을 쓰지 않는다`() {
        val l = CallLimiter(minGapMs = 4_000, maxInWindow = 4, windowMs = 60_000)
        repeat(10) { i -> assertTrue("보기만 하면 세지 않는다", l.canAcquire(i * 5_000L)) }
        l.record(50_000)
        assertFalse(l.canAcquire(52_000))
        assertTrue(l.canAcquire(54_000))
    }

    @Test
    fun `Gleo 함수별 제한 값`() {
        val g = GleoLimits()
        // 말하기: 4초 간격, 1분에 네 번
        assertTrue(g.speak.tryAcquire(0))
        assertFalse(g.speak.tryAcquire(2_000))
        // 동행 시작: 10초 간격, 10분에 세 번
        assertTrue(g.start.tryAcquire(0))
        assertFalse(g.start.tryAcquire(9_999))
        assertTrue(g.start.tryAcquire(10_000))
        assertTrue(g.start.tryAcquire(20_000))
        assertFalse("10분에 세 번까지", g.start.tryAcquire(30_000))
        assertTrue(g.start.tryAcquire(10 * 60_000L))
        // 조용히는 말하기와 따로 센다
        assertTrue(g.pause.tryAcquire(2_000))
    }
}
