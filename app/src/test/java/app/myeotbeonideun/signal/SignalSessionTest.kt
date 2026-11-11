package app.myeotbeonideun.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 차량 신호 구독의 세대·비동기 콜백·해제 순서(결함 14). 안드로이드 없이 돈다. */
class SignalSessionTest {

    @Test
    fun `기다리는 콜백이 없으면 닫는 즉시 리스너를 풀고 SDK 를 놓는다`() {
        val log = ArrayList<String>()
        val s = SignalSession(1)
        s.onRelease { log += "release vehicle" }
        s.onClose { log += "unregister seat" }
        s.onClose { log += "unregister door" }
        assertTrue(s.close())
        assertTrue(s.released)
        // 리스너는 등록한 반대 순서로 풀고, 그 뒤에 SDK 를 놓는다
        assertEquals(listOf("unregister door", "unregister seat", "release vehicle"), log)
    }

    @Test
    fun `비동기 콜백이 끝나기 전에는 SDK 를 놓지 않는다`() {
        val log = ArrayList<String>()
        val s = SignalSession(1)
        s.onRelease { log += "release" }
        s.onClose { log += "unregister" }
        val capability = s.begin()
        val current = s.begin()
        assertEquals(2, s.pendingCalls)

        assertFalse("콜백 둘이 아직 오지 않았다", s.close())
        assertEquals(listOf("unregister"), log) // 리스너는 바로 푼다

        assertFalse("닫힌 뒤에 온 콜백은 본문을 건너뛴다(옛 세대)", capability.finish())
        assertFalse(s.released)
        assertFalse(current.finish())
        assertTrue("마지막 콜백이 돌아오면 그때 놓는다", s.released)
        assertEquals(listOf("unregister", "release"), log)
    }

    @Test
    fun `살아 있는 세대의 콜백은 본문을 실행하고 같은 콜백을 두 번 받아도 한 번만 센다`() {
        val s = SignalSession(1)
        val t = s.begin()
        assertTrue(t.finish())
        assertTrue(t.finish())
        assertEquals(0, s.pendingCalls)
        // 다른 호출이 남아 있는데 같은 표를 두 번 끝내도 그 호출 몫을 덜지 않는다
        val other = s.begin()
        t.finish()
        assertEquals(1, s.pendingCalls)
        assertFalse(s.close())
        other.finish()
        assertTrue(s.released)
    }

    @Test
    fun `콜백이 끝내 오지 않으면 마지막 수단으로 놓는다`() {
        var released = 0
        val s = SignalSession(1)
        s.onRelease { released++ }
        s.begin()
        assertFalse(s.close())
        assertEquals(1, s.forceRelease())
        assertEquals(1, released)
        s.forceRelease()
        assertEquals("두 번 놓지 않는다", 1, released)
    }

    @Test
    fun `닫은 뒤에 더하는 해제는 바로 한다`() {
        val log = ArrayList<String>()
        val s = SignalSession(1)
        s.close()
        s.onClose { log += "late unregister" }
        s.onRelease { log += "late release" }
        assertEquals(listOf("late unregister", "late release"), log)
    }

    @Test
    fun `다시 시작한 세대는 옛 세대와 따로 센다`() {
        val old = SignalSession(1)
        val pending = old.begin()
        old.close()
        val fresh = SignalSession(2)
        val t = fresh.begin()
        assertTrue(t.finish())
        assertFalse("옛 세대의 늦은 콜백은 무시한다", pending.finish())
        assertTrue(old.released)
        assertFalse(fresh.closing)
        assertEquals(2, fresh.generation)
    }

    @Test
    fun `해제 중 하나가 예외를 던져도 나머지는 한다`() {
        val log = ArrayList<String>()
        val s = SignalSession(1)
        s.onRelease { log += "navi" }
        s.onRelease { error("vehicle release failed") }
        s.onClose { error("unregister failed") }
        s.onClose { log += "unregister" }
        assertTrue(s.close())
        assertEquals(listOf("unregister", "navi"), log)
    }

    @Test
    fun `다시 시도는 2초 4초 8초 간격으로 세 번까지`() {
        assertEquals(2_000L, RetryPolicy.delayMs(0))
        assertEquals(4_000L, RetryPolicy.delayMs(1))
        assertEquals(8_000L, RetryPolicy.delayMs(2))
        assertNull(RetryPolicy.delayMs(3))
        assertNull(RetryPolicy.delayMs(-1))
        assertEquals(3, RetryPolicy.MAX_RETRIES)
    }

    @Test
    fun `지원 영역 목록에 든 자리만 등록한다`() {
        data class Area(val values: Set<Int>)
        val supported = listOf(Area(setOf(1)), Area(setOf(4)), Area(setOf(0x10, 0x20)))
        assertEquals(Area(setOf(1)), SupportedAreas.pick(supported, 1, { a, v -> v in a.values }) { Area(setOf(it)) })
        // 여러 자리를 묶은 영역이면 그 영역으로 등록한다
        assertEquals(Area(setOf(0x10, 0x20)), SupportedAreas.pick(supported, 0x10, { a, v -> v in a.values }) { Area(setOf(it)) })
        // 목록에 없는 자리는 등록하지 않는다
        assertNull(SupportedAreas.pick(supported, 0x40, { a, v -> v in a.values }) { Area(setOf(it)) })
        // 차가 목록을 비워 주면 그 자리 하나로 시도한다
        assertEquals(Area(setOf(0x40)), SupportedAreas.pick(emptyList(), 0x40, { a, v -> v in a.values }) { Area(setOf(it)) })
    }

    @Test
    fun `여러 자리를 고를 때 목록에 없는 자리만 빼고, 하나도 맞지 않으면 예전처럼 모두 시도한다`() {
        data class Area(val values: Set<Int>)
        val contains = { a: Area, v: Int -> v in a.values }
        val make = { v: Int -> Area(setOf(v)) }
        // 에뮬레이터처럼 문 6곳(앞뒤 좌우 + 후드·트렁크) 가운데 네 자리
        val doors = listOf(Area(setOf(0x1)), Area(setOf(0x4)), Area(setOf(0x10)), Area(setOf(0x40)), Area(setOf(0x10000000)), Area(setOf(0x20000000)))
        val all = SupportedAreas.pickAll(doors, listOf(0x1, 0x4, 0x10, 0x40), contains, make)
        assertFalse(all.fellBack)
        assertEquals(listOf(Area(setOf(0x1)), Area(setOf(0x4)), Area(setOf(0x10)), Area(setOf(0x40))), all.areas)

        // 뒷좌석이 없는 차: 그 자리만 빠진다
        val twoSeats = SupportedAreas.pickAll(listOf(Area(setOf(0x1)), Area(setOf(0x4))), listOf(0x1, 0x4, 0x10, 0x40), contains, make)
        assertFalse(twoSeats.fellBack)
        assertEquals(listOf(Area(setOf(0x1)), Area(setOf(0x4)), null, null), twoSeats.areas)

        // 영역 표기가 예상과 달라 어느 자리도 맞지 않으면 목록을 믿지 않고 자리마다 시도한다
        val mismatch = SupportedAreas.pickAll(listOf(Area(setOf(1001)), Area(setOf(1002))), listOf(0x1, 0x4), contains, make)
        assertTrue(mismatch.fellBack)
        assertEquals(listOf(Area(setOf(0x1)), Area(setOf(0x4))), mismatch.areas)
    }
}
