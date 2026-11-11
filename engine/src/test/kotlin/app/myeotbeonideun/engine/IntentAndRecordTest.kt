package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.intent.IntentMatcher
import app.myeotbeonideun.engine.intent.PassengerIntent
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.Motion
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.Signals
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.engine.record.RecordAggregator
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.record.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentMatcherTest {
    @Test
    fun `다섯 가지 물음을 알아듣는다`() {
        assertEquals(PassengerIntent.WHERE, IntentMatcher.match("우리 어디 가?"))
        assertEquals(PassengerIntent.WHEN, IntentMatcher.match("언제 도착해"))
        assertEquals(PassengerIntent.ARRIVED_Q, IntentMatcher.match("다 왔어?"))
        assertEquals(PassengerIntent.WHY_STOPPED, IntentMatcher.match("왜 안 가"))
        assertEquals(PassengerIntent.GO_HOME, IntentMatcher.match("집에 가자"))
    }

    @Test
    fun `띄어쓰기와 말끝이 달라도 알아듣는다`() {
        assertEquals(PassengerIntent.WHERE, IntentMatcher.match("어디로 가는 거야"))
        assertEquals(PassengerIntent.WHEN, IntentMatcher.match("얼마나 더 가야 돼"))
        assertEquals(PassengerIntent.GO_HOME, IntentMatcher.match("나 집에 갈래"))
    }

    @Test
    fun `모르는 말은 대답하지 않는다`() {
        assertNull(IntentMatcher.match("오늘 날씨 좋다"))
        assertNull(IntentMatcher.match("   "))
    }

    @Test
    fun `동승자가 흔히 묻는 꼴은 그대로 알아듣는다`() {
        val cases = mapOf(
            "여기가 어디야?" to PassengerIntent.WHERE,
            "여기 어디야" to PassengerIntent.WHERE,
            "우리 어딜 가는 거니" to PassengerIntent.WHERE,
            "얼마나 남았어?" to PassengerIntent.WHEN,
            "얼마나 걸려" to PassengerIntent.WHEN,
            "언제 다 와?" to PassengerIntent.WHEN,
            "아직 멀었어?" to PassengerIntent.WHEN,
            "몇 분 남았니" to PassengerIntent.WHEN,
            "왜 서?" to PassengerIntent.WHY_STOPPED,
            "왜 서 있어" to PassengerIntent.WHY_STOPPED,
            "왜 멈췄어" to PassengerIntent.WHY_STOPPED,
            "내려도 돼?" to PassengerIntent.ARRIVED_Q,
            "이제 내려?" to PassengerIntent.ARRIVED_Q,
            "집에 가고 싶어" to PassengerIntent.GO_HOME,
            "집에 언제 가" to PassengerIntent.GO_HOME,
            "집에 가야 돼" to PassengerIntent.GO_HOME,
        )
        for ((said, intent) in cases) assertEquals(said, intent, IntentMatcher.match(said))
    }

    @Test
    fun `어디·언제·얼마나 낱말 하나만으로는 대답하지 않는다(운전자 통화·라디오·노래)`() {
        val notQuestions = listOf(
            // 운전자 통화
            "너 지금 어디야",
            "엄마 언제 와",
            "그럼 언제 시간 돼?",
            "회의 언제야",
            "그거 얼마야",
            "나 오늘 회사 안 가고 집에 있어",
            "지금 집에 가는 중이야",
            "얼마나 걸렸어? 오래 있었어?",
            "그 사람 어디 사람이야",
            // 라디오·노래
            "언제나 너를 사랑해",
            "어디서든 너를 찾을게",
            "얼마나 좋을까",
            "얼마나 사랑했는지",
            "내일은 전국에 비가 내려도 오후에는 그칩니다",
            "다음 소식입니다",
            "왜 서운해 하는지 몰라",
        )
        for (said in notQuestions) assertNull(said, IntentMatcher.match(said))
    }
}

class RecordAggregatorTest {
    private fun record(at: Long, proactive: Int, asked: Int = 0) =
        TripRecord(at, 600, TripCounts(proactive = proactive, driverRequested = asked, safetyNotices = 1))

    @Test
    fun `하루치 횟수를 합친다`() {
        val records = listOf(record(100, 3, 1), record(200, 2), record(5_000, 9))
        val day = RecordAggregator.summarize(records, 0, 1_000)
        assertEquals(2, day.trips)
        assertEquals(1200, day.durationSec)
        assertEquals(5, day.counts.proactive)
        assertEquals(6, day.counts.spokenOnBehalf) // 안전 안내는 '차가 대신 말한 횟수'에 넣지 않는다
        assertEquals(2, day.counts.safetyNotices)
    }

    @Test
    fun `건너뛴 말도 하루치로 합치지만 대신 말한 횟수에는 넣지 않는다`() {
        val a = TripRecord(100, 600, TripCounts(proactive = 2, skipped = 3))
        val b = TripRecord(200, 600, TripCounts(stopNotices = 1, skipped = 1))
        val day = RecordAggregator.summarize(listOf(a, b), 0, 1_000)
        assertEquals(4, day.counts.skipped)
        assertEquals(3, day.counts.spokenOnBehalf)
        assertEquals(listOf(2, 1), RecordAggregator.recentTrend(listOf(b, a)))
    }

    @Test
    fun `최근 8번의 추이는 오래된 것부터다`() {
        val records = (1..10).map { record(it * 10L, it) }.shuffled(java.util.Random(7))
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9, 10), RecordAggregator.recentTrend(records, 8))
    }

    @Test
    fun `보관 수를 넘으면 오래된 기록부터 버린다`() {
        val kept = RecordAggregator.trim((1..70).map { record(it.toLong(), 1) }, keep = 60)
        assertEquals(60, kept.size)
        assertEquals(11L, kept.first().startedAtMs)
    }
}

class SignalsTest {
    @Test
    fun `변속단을 알면 그것으로 모르면 주행 규제로 본다`() {
        assertEquals(Motion.PARKED, Signals(gear = Gear.PARK, uxRestricted = true).motion)
        assertEquals(Motion.IN_DRIVE, Signals(gear = Gear.DRIVE).motion)
        assertEquals(Motion.IN_DRIVE, Signals(gear = Gear.NEUTRAL).motion)
        assertEquals(Motion.IN_DRIVE, Signals(uxRestricted = true).motion)
        assertEquals(Motion.PARKED, Signals(uxRestricted = false).motion)
        assertEquals(Motion.UNKNOWN, Signals().motion)
    }

    @Test
    fun `앉은 자리의 신호만 뽑고 사람이 앉은 자리를 고른다`() {
        val v = VehicleState(
            seatOccupied = mapOf(SeatPosition.DRIVER to true, SeatPosition.REAR_LEFT to true, SeatPosition.REAR_RIGHT to true),
            doorOpen = mapOf(SeatPosition.REAR_LEFT to true),
            windowOpenPercent = mapOf(SeatPosition.REAR_RIGHT to 40),
        )
        assertEquals(listOf(SeatPosition.REAR_RIGHT, SeatPosition.REAR_LEFT), v.occupiedPassengerSeats())
        val s = v.forSeat(SeatPosition.REAR_RIGHT)
        assertEquals(true, s.driverSeatOccupied)
        assertEquals(40, s.seatWindowOpenPercent)
        assertNull(s.seatDoorOpen) // 왼쪽 문은 이 자리의 문이 아니다
        assertNull(s.seatBeltBuckled) // 지원하지 않는 신호는 null
    }
}
