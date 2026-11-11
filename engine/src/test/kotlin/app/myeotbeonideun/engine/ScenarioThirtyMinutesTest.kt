package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.NavState
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.Signals
import app.myeotbeonideun.engine.model.TripConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기획서 4①(토요일 오전, 큰딸 집까지 약 30분)의 신호를 시간 순서대로 엔진에 넣고,
 * 차가 동승자에게 한 말을 센다. 기획서의 "30분 동안 차가 한 말은 N번" 이 이 시험에서 나온다.
 *
 * - 운전 시간 30분(내비 전체 1,800초), 약국 앞 4분 정차는 운전 시간에 넣지 않는다(정차 중 남은 시간은 그대로).
 * - 속도 신호는 쓰지 않는다(속도 허용을 하지 않은 경우: 브레이크만으로는 35초가 지나야 신호 대기로 본다).
 * - 내비 값은 10초마다, 남은 거리는 초속 10m(시속 36km)로 줄인다.
 * - 운전자 조작은 '다시 말씀드리기' 두 번뿐이다.
 */
class ScenarioThirtyMinutesTest {

    private class Sim {
        val engine = CompanionEngine(
            TestScripts.book,
            TripConfig(place = "큰딸 집", purpose = Purpose.MEAL, interval = IntervalMode.AUTO),
        )
        var now = 0L
        var remain = 1800
        val meters get() = remain * 10
        var signals = Signals(
            gear = Gear.PARK,
            brakePressed = false,
            driverSeatOccupied = true,
            driverDoorOpen = false,
            seatOccupied = true,
            seatDoorOpen = false,
            seatWindowOpenPercent = 0,
            seatBeltBuckled = true,
            nav = NavState(guiding = true, destinationName = "큰딸 집", totalSec = 1800, remainSec = 1800, remainMeters = 18000),
        )
        val spoken = ArrayList<Pair<Long, Speak>>()

        private fun take(actions: List<EngineAction>) = actions.forEach { a ->
            if (a is Speak) {
                spoken += now to a
                engine.onUtteranceResult(a.id, true)
            }
        }

        fun start() {
            take(engine.start(now))
            take(engine.onSignals(signals, now))
        }

        fun set(change: Signals.() -> Signals) {
            signals = signals.change()
            take(engine.onSignals(signals, now))
        }

        /** 1초씩 흘린다. 주행 단이고 브레이크를 밟지 않았으면 남은 시간이 1초씩 줄고, 10초마다 내비 값을 넣는다. */
        fun drive(seconds: Int) = repeat(seconds) {
            now += 1000
            val moving = signals.gear == Gear.DRIVE && signals.brakePressed != true
            if (moving && remain > 0) remain -= 1
            if ((now / 1000) % 10 == 0L) {
                if (signals.nav.guiding) signals = signals.copy(nav = signals.nav.copy(remainSec = remain, remainMeters = meters))
                take(engine.onSignals(signals, now))
            }
            take(engine.onTick(now))
        }

        fun repeatButton() = take(engine.requestRepeat(now))
    }

    @Test
    fun saturdayTripToEldestDaughter() {
        val s = Sim()
        s.start() // 출발 전 P단: 소개
        s.drive(10)
        s.set { copy(gear = Gear.DRIVE) } // 1. 출발
        s.drive(60)
        s.repeatButton() // 2. "어디 가?" → 버튼
        s.drive(30)
        s.repeatButton() // 3. 또 물으심 → 버튼
        s.drive(170)
        s.set { copy(brakePressed = true) } // 4. 신호에서 40초
        s.drive(40)
        s.set { copy(brakePressed = false) }
        s.drive(600) // 절반을 지난다
        // 5. 약국 앞: P단, 딸이 내렸다가 4분 뒤 돌아옴
        s.set { copy(brakePressed = true) }
        s.drive(2)
        s.set { copy(gear = Gear.PARK, brakePressed = false) }
        s.drive(15)
        s.set { copy(driverDoorOpen = true) }
        s.drive(1)
        s.set { copy(driverSeatOccupied = false, driverDoorOpen = false) }
        s.drive(240)
        s.set { copy(driverDoorOpen = true) }
        s.drive(1)
        s.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        s.drive(20)
        s.set { copy(gear = Gear.DRIVE) }
        s.drive(300)
        // 6. 창문 40%
        s.set { copy(seatWindowOpenPercent = 40) }
        s.drive(20)
        s.set { copy(seatWindowOpenPercent = 0) }
        // 7. 남은 시간이 0이 될 때까지 달린 뒤 P단(도착)
        while (s.remain > 0) s.drive(10)
        s.drive(10)
        s.set { copy(brakePressed = true) }
        s.drive(2)
        s.set { copy(gear = Gear.PARK, brakePressed = false) }
        s.drive(30)

        val bySituation = s.spoken.groupingBy { it.second.situation }.eachCount()
        println("== 4① 30분 길: 차가 동승자에게 한 말 ${s.spoken.size}번 ==")
        s.spoken.forEach { (t, sp) -> println("%5d초  %-22s %s".format(t / 1000, sp.situation, sp.text)) }
        println("상황별: $bySituation")
        assertTrue("말이 하나도 없으면 시험이 잘못됐다", s.spoken.isNotEmpty())
        assertEquals("버튼은 두 번", 2, s.spoken.count { it.second.kind == SpeechKind.DRIVER_REQUEST })
    }

    @Test
    fun staleLinesAreDroppedAfterParkingOrArrival() {
        // 다른 말 뒤에서 기다리던 구간 말은 그새 P단으로 섰으면 맞지 않고, 정차 말은 도착 뒤에 늦다(에뮬레이터 시연에서 본 일)
        val s = Sim()
        s.signals = s.signals.copy(nav = NavState())
        s.start()
        s.drive(10)
        s.set { copy(gear = Gear.DRIVE) }
        s.drive(30)
        assertTrue(s.engine.stillCurrent("stage_onway", SpeechKind.PROACTIVE))
        s.set { copy(gear = Gear.PARK) }
        s.drive(1)
        assertFalse(s.engine.stillCurrent("stage_onway", SpeechKind.PROACTIVE))
        assertTrue("운전자가 시킨 말은 그대로", s.engine.stillCurrent("stage_onway", SpeechKind.DRIVER_REQUEST))
        assertTrue(s.engine.stillCurrent("stop_parked_neutral", SpeechKind.STOP))
        s.engine.driverConfirmedArrival(s.now)
        assertFalse(s.engine.stillCurrent("stop_parked_neutral", SpeechKind.STOP))
        assertTrue("도착 안내는 그대로", s.engine.stillCurrent("arrive", SpeechKind.PROACTIVE))
    }

    /** 4②: 내비를 켜지 않은 20분 병원 길. 먼저 말하기 간격(모르면 5분)과, 도착을 단정하지 않는 정차, 운전자의 '도착했어요'. */
    @Test
    fun weekdayClinicWithoutNavigation() {
        val s = Sim()
        s.signals = s.signals.copy(nav = NavState())
        s.start()
        s.drive(10)
        s.set { copy(gear = Gear.DRIVE) }
        s.drive(20 * 60)
        s.set { copy(brakePressed = true) }
        s.drive(2)
        s.set { copy(gear = Gear.PARK, brakePressed = false) }
        s.drive(15)
        val before = s.spoken.size
        s.engine.driverConfirmedArrival(s.now).filterIsInstance<Speak>().forEach { s.spoken += s.now to it }
        println("== 4② 내비 없는 20분 길: 차가 한 말 ${s.spoken.size}번 ('도착했어요'는 ${before}번째 말 뒤) ==")
        s.spoken.forEach { (t, sp) -> println("%5d초  %-22s %s".format(t / 1000, sp.situation, sp.text)) }
        // 내비가 없으면 P단 정차를 경유지로 단정하지 않고, 운전자가 '도착했어요'를 누르면 도착을 말한다
        assertTrue(s.spoken.any { it.second.situation == "stop_parked_neutral" })
        assertTrue(s.spoken.none { it.second.situation == "stop_waypoint" })
        assertTrue(s.spoken.drop(before).any { it.second.situation == "arrive" })
    }
}
