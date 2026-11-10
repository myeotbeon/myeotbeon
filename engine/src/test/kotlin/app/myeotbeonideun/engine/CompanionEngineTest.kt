package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.intent.PassengerIntent
import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.NavState
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.Signals
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.script.Audience
import app.myeotbeonideun.engine.script.ScriptBook
import app.myeotbeonideun.engine.script.Situation
import app.myeotbeonideun.engine.script.ScriptValidator
import app.myeotbeonideun.engine.stop.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 엔진에 신호와 시간을 넣어 보고 나온 말을 모으는 시험대. 시각은 0부터 손으로 흘린다. */
private class Rig(
    config: TripConfig = TripConfig(place = "큰딸 집", purpose = Purpose.MEAL, interval = IntervalMode.OFF),
    params: EngineParams = EngineParams(),
) {
    val engine = CompanionEngine(TestScripts.book, config, params)
    var now = 0L
    var signals = Signals(
        gear = Gear.PARK,
        brakePressed = false,
        driverSeatOccupied = true,
        driverDoorOpen = false,
        seatOccupied = true,
        seatDoorOpen = false,
        seatWindowOpenPercent = 0,
        seatBeltBuckled = true,
        nav = NavState(guiding = true, destinationName = "큰딸 집", totalSec = 2400, remainSec = 2400),
    )
    val log = ArrayList<Pair<Long, EngineAction>>()

    private fun record(actions: List<EngineAction>) = actions.forEach { log += now to it }

    /** 동행을 시작하고 지금의 차량 신호를 한 번 넣는다(앱이 하는 순서 그대로). */
    fun start() {
        record(engine.start(now))
        record(engine.onSignals(signals, now))
    }
    fun set(change: Signals.() -> Signals) {
        signals = signals.change()
        record(engine.onSignals(signals, now))
    }
    fun nav(change: NavState.() -> NavState) = set { copy(nav = nav.change()) }
    fun repeat() = record(engine.requestRepeat(now))
    fun ask(intent: PassengerIntent) = record(engine.onPassengerAsked(intent, now))
    fun quiet() = record(engine.quiet(now))

    /** 1초씩 흘려보낸다. */
    fun advance(seconds: Int) = kotlin.repeat(seconds) {
        now += 1000
        record(engine.onTick(now))
    }

    /** 출발해서 출발 안내까지 끝난 상태로 만든다. */
    fun startAndDepart() {
        start()
        set { copy(gear = Gear.DRIVE) }
        advance(20)
    }

    fun spoken(): List<Speak> = log.map { it.second }.filterIsInstance<Speak>()
    fun spoken(situation: String): List<Speak> = spoken().filter { it.situation == situation }
    fun notices(): List<DriverNotice> = log.map { it.second }.filterIsInstance<DriverNotice>()
    fun snapshot() = engine.snapshot(now)
    fun clear() = log.clear()
}

class CompanionEngineTest {

    // ───────── 시작과 출발 ─────────

    @Test
    fun `시작하면 차가 먼저 자기를 소개한다`() {
        val rig = Rig()
        rig.start()
        val intro = rig.spoken().single()
        assertEquals("intro", intro.situation)
        assertTrue(intro.text.startsWith("어머니,"))
        assertEquals(Phase.READY, rig.snapshot().phase)
    }

    @Test
    fun `주행 단에 넣으면 소개가 끝난 뒤 출발을 알린다`() {
        val rig = Rig()
        rig.start()
        rig.set { copy(gear = Gear.DRIVE) }
        assertEquals("소개를 끊고 말하지 않는다", 1, rig.spoken().size)
        rig.advance(20)
        val depart = rig.spoken("depart").single()
        assertTrue(depart.text.contains("큰딸 집"))
        assertFalse(depart.interrupt)
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
    }

    @Test
    fun `P단에 있는 동안은 출발을 알리지 않는다`() {
        val rig = Rig()
        rig.start()
        rig.advance(120)
        assertTrue(rig.spoken("depart").isEmpty())
        assertEquals(Phase.READY, rig.snapshot().phase)
    }

    @Test
    fun `변속단과 주행 규제 신호가 모두 없으면 10초 뒤 출발한 것으로 본다`() {
        val rig = Rig()
        rig.signals = Signals()
        rig.start()
        rig.advance(30)
        assertEquals(1, rig.spoken("depart").size)
    }

    // ───────── 다시 말씀드리기와 화제 전환 ─────────

    @Test
    fun `다시 말씀드리기를 두 번 누르면 같은 사실을 다른 표현으로 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.repeat()
        rig.repeat() // 첫 말이 끝나기 전에 또 눌러도 버리지 않고 이어서 말한다
        rig.advance(20)
        val said = rig.spoken()
        assertEquals(2, said.size)
        assertEquals(said[0].situation, said[1].situation)
        assertNotEquals(said[0].text, said[1].text)
        said.forEach { assertTrue(it.text, it.text.contains("큰딸 집")) }
        said.forEach { assertEquals(SpeechKind.DRIVER_REQUEST, it.kind) }
        assertEquals(2, rig.snapshot().counts.driverRequested)
    }

    @Test
    fun `화제 전환 한마디는 대답 두 번에 한 번 붙는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        repeat(4) {
            rig.repeat()
            rig.advance(12)
        }
        val sentences = rig.spoken().map { ScriptValidator.splitSentences(it.text).size }
        assertEquals(listOf(2, 3, 2, 3), sentences)
    }

    @Test
    fun `화제 전환을 붙인 말에서도 호칭은 한 번만 들린다`() {
        for (purpose in listOf(Purpose.CLINIC, Purpose.MEAL, Purpose.FAMILY, Purpose.REST)) {
            val rig = Rig(TripConfig(honorific = "할머니", place = "큰딸 집", purpose = purpose))
            rig.startAndDepart()
            rig.clear()
            repeat(8) { rig.repeat(); rig.advance(12) } // 출발 2분 전에 끝나 구간 안내가 끼지 않는다
            val withRedirect = rig.spoken().filter { ScriptValidator.splitSentences(it.text).size == 3 }
            assertEquals("$purpose", 4, withRedirect.size)
            assertEquals("화제 전환 변형 네 개를 돌려 쓴다", 4, withRedirect.map { it.text.substringAfterLast(". ") }.toSet().size)
            for (line in rig.spoken()) {
                assertTrue(line.text, line.text.startsWith("할머니, "))
                assertEquals(line.text, 1, Regex("할머니").findAll(line.text).count())
                assertEquals(line.text, 1, line.text.count { it == ',' })
            }
        }
    }

    @Test
    fun `직접 쓴 한마디는 그대로 읽는다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", purpose = Purpose.CUSTOM, customPurposeLine = "가서 손주 얼굴 봐요"))
        rig.startAndDepart()
        rig.clear()
        rig.repeat(); rig.advance(12)
        rig.repeat(); rig.advance(12)
        assertTrue(rig.spoken()[1].text.endsWith("가서 손주 얼굴 봐요."))
    }

    @Test
    fun `목적을 정하지 않으면 화제 전환을 붙이지 않는다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", purpose = Purpose.NONE))
        rig.startAndDepart()
        rig.clear()
        repeat(2) { rig.repeat(); rig.advance(12) }
        rig.spoken().forEach { assertEquals(2, ScriptValidator.splitSentences(it.text).size) }
    }

    // ───────── 구간과 먼저 말하기 ─────────

    @Test
    fun `구간이 바뀌면 먼저 말하고 구간은 뒤로 가지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 1400) } // 40%를 넘김
        rig.advance(5)
        assertEquals(1, rig.spoken("stage_half").size)
        assertEquals(Stage.HALF, rig.snapshot().stage)
        assertEquals("절반쯤 왔어요", rig.snapshot().cardStage)

        rig.nav { copy(remainSec = 2300) } // 길을 다시 찾아 남은 시간이 늘었다
        rig.advance(60)
        assertEquals(Stage.HALF, rig.snapshot().stage)
        assertTrue(rig.spoken("stage_onway").isEmpty())
    }

    @Test
    fun `남은 시간이 5분 이하가 되면 곧 도착을 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 290) }
        rig.advance(5)
        val line = rig.spoken("stage_5min").single()
        assertTrue(line.text, line.text.contains("5분쯤"))
        assertEquals(Stage.SOON, rig.snapshot().stage)
    }

    @Test
    fun `분 숫자는 남은 시간이 3분에서 5분 사이일 때만 말한다`() {
        // 명세 6-8: 남은 시간 2분 → "거의 다 왔어요"
        val near = Rig()
        near.startAndDepart()
        near.clear()
        near.nav { copy(remainSec = 120) }
        near.advance(5)
        assertEquals(Stage.SOON, near.snapshot().stage)
        assertEquals("stage_near", near.spoken().single().situation)
        assertTrue(near.spoken("stage_5min").isEmpty())

        // 5분쯤이라고 말한 뒤 더 가까워지면, 다시 시켰을 때는 숫자 없이 말한다
        val rig = Rig()
        rig.startAndDepart()
        rig.nav { copy(remainSec = 300) }
        rig.advance(12)
        assertEquals(1, rig.spoken("stage_5min").size)
        rig.nav { copy(remainSec = 181) }
        rig.repeat(); rig.advance(12)
        assertEquals(2, rig.spoken("stage_5min").size)
        rig.nav { copy(remainSec = 180) }
        rig.repeat(); rig.advance(12)
        assertEquals("stage_near", rig.spoken().last().situation)

        // 5분보다 많이 남았을 때 하는 말에는 숫자가 없다
        val far = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        far.startAndDepart()
        far.nav { copy(remainSec = 1400) }
        far.advance(700)
        far.nav { copy(remainSec = 500) }
        far.advance(400)
        assertTrue(far.spoken().size >= 4)
        far.spoken().forEach { assertFalse(it.text, it.text.any(Char::isDigit)) }
    }

    @Test
    fun `내비 없이 근접 알림으로 곧 도착을 알 때도 분 숫자를 말하지 않는다`() {
        val rig = Rig()
        rig.signals = rig.signals.copy(nav = NavState())
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(nearDestination = true) }
        rig.advance(5)
        assertEquals(Stage.SOON, rig.snapshot().stage)
        assertEquals("stage_near", rig.spoken().single().situation)
    }

    @Test
    fun `5분이 안 되는 짧은 길도 첫마디는 출발 안내다`() {
        val rig = Rig()
        rig.signals = rig.signals.copy(nav = NavState(guiding = true, totalSec = 240, remainSec = 240))
        rig.startAndDepart()
        assertEquals(listOf("intro", "depart"), rig.spoken().map { it.situation })
        assertEquals(Stage.SOON, rig.snapshot().stage)
    }

    @Test
    fun `간격 5분이면 마지막 말 뒤 5분마다 먼저 말한다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.startAndDepart()
        rig.advance(125) // 출발 2분 뒤 '가는 중'
        val first = rig.spoken("stage_onway").single()
        rig.clear()
        rig.advance(270) // 마지막 말(출발 120초)에서 295초
        assertTrue("5분이 되기 전에는 말하지 않는다", rig.spoken().isEmpty())
        rig.advance(15)
        val second = rig.spoken("stage_onway").single()
        assertEquals(SpeechKind.PROACTIVE, second.kind)
        assertNotEquals(first.text, second.text)
    }

    @Test
    fun `간격을 끄면 구간이 바뀔 때만 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.advance(125)
        rig.clear()
        rig.advance(30 * 60)
        assertTrue(rig.spoken().isEmpty())
    }

    @Test
    fun `운전자가 시킨 말 뒤로 간격을 다시 센다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.startAndDepart()
        rig.advance(125)
        rig.advance(200)
        rig.repeat()
        rig.clear()
        rig.advance(290)
        assertTrue(rig.spoken().isEmpty())
        rig.advance(20)
        assertEquals(1, rig.spoken().size)
    }

    // ───────── 멈춤의 순간 ─────────

    @Test
    fun `브레이크를 밟고 20초 서 있으면 신호 대기를 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(brakePressed = true) }
        rig.advance(19)
        assertTrue(rig.spoken().isEmpty())
        rig.advance(1)
        val line = rig.spoken().single()
        assertEquals("stop_signal", line.situation)
        assertEquals(Priority.STOP, line.priority)
        assertEquals(StopReason.SIGNAL_WAIT, rig.snapshot().stopReason)
        assertEquals(1, rig.snapshot().counts.stopNotices)
    }

    @Test
    fun `멈춤 안내는 쿨다운 안에 되풀이하지 않고 오래 서 있으면 다시 한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(brakePressed = true) }
        rig.advance(20)
        assertEquals(1, rig.spoken("stop_signal").size)

        rig.set { copy(brakePressed = false) }
        rig.advance(2)
        rig.set { copy(brakePressed = true) } // 곧바로 다시 섰다
        rig.advance(60)
        assertEquals("쿨다운 60초 안에는 다시 말하지 않는다", 1, rig.spoken("stop_signal").size)
        rig.advance(70)
        assertEquals("2분 넘게 이어지면 다시 안심시킨다", 2, rig.spoken("stop_signal").size)
        assertNotEquals(rig.spoken("stop_signal")[0].text, rig.spoken("stop_signal")[1].text)
    }

    @Test
    fun `내비가 정체를 알리면 길이 막힌다고 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(congested = true) }
        rig.set { copy(brakePressed = true) }
        rig.advance(21)
        assertEquals("stop_traffic", rig.spoken().single().situation)
    }

    @Test
    fun `속도 신호가 살아 있으면 브레이크를 떼고 서 있어도 멈춤을 안다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(speedKph = 40f) }
        rig.advance(5)
        rig.clear()
        rig.set { copy(speedKph = 0f, brakePressed = false) } // 오토홀드
        rig.advance(21)
        assertEquals("stop_signal", rig.spoken().single().situation)
    }

    @Test
    fun `속도가 0에 머무는 에뮬레이터에서는 브레이크로만 멈춤을 본다`() {
        val rig = Rig()
        rig.signals = rig.signals.copy(speedKph = 0f)
        rig.startAndDepart()
        rig.clear()
        rig.advance(60)
        assertTrue(rig.spoken().isEmpty())
    }

    @Test
    fun `도착 전에 P단으로 서면 다 온 것이 아니라고 한 번 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(4)
        assertTrue(rig.spoken().isEmpty())
        rig.advance(60)
        assertEquals("stop_waypoint", rig.spoken().single().situation)
    }

    @Test
    fun `운전자가 내리면 알리고 기다리는 동안 다시 안심시키고 돌아오면 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(2)
        rig.set { copy(driverDoorOpen = true) }
        rig.set { copy(driverSeatOccupied = false) }
        val away = rig.spoken().single()
        assertEquals("driver_away", away.situation)
        assertTrue(away.text.contains("따님"))
        assertEquals(StopReason.DRIVER_AWAY, rig.snapshot().stopReason)

        rig.set { copy(driverDoorOpen = false) }
        rig.advance(74)
        assertTrue(rig.spoken("driver_away_again").isEmpty())
        rig.advance(2)
        assertEquals(1, rig.spoken("driver_away_again").size)

        rig.set { copy(driverDoorOpen = true) }
        rig.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        rig.advance(8) // 하던 안심 말이 끝난 뒤에 이어서 말한다
        assertEquals(1, rig.spoken("driver_back").size)
        rig.advance(60)
        assertTrue("돌아온 뒤에 '잠깐 쉬었다 가요'를 덧붙이지 않는다", rig.spoken("stop_waypoint").isEmpty())
        assertEquals(3, rig.snapshot().counts.stopNotices)
    }

    @Test
    fun `출발 전에도 운전자가 내리면 알린다`() {
        val rig = Rig()
        rig.start()
        rig.advance(15)
        rig.clear()
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        assertEquals("driver_away", rig.spoken().single().situation)
    }

    @Test
    fun `목적지 근처에서 P단에 두면 도착을 알리고 더 말하지 않는다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.startAndDepart()
        rig.nav { copy(remainSec = 30, remainMeters = 100) }
        rig.advance(10)
        rig.clear()
        rig.set { copy(gear = Gear.PARK) }
        val arrive = rig.spoken().single()
        assertEquals("arrive", arrive.situation)
        assertTrue(arrive.text.contains("큰딸 집"))
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        assertTrue(rig.snapshot().cardStage, rig.snapshot().cardStage.contains("다 왔어요"))
        rig.clear()
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(15 * 60)
        assertTrue(rig.spoken().isEmpty())
    }

    @Test
    fun `내비의 도착 알림으로도 도착한다`() {
        val rig = Rig()
        rig.startAndDepart()
        val actions = rig.engine.onNavArrived(rig.now)
        assertEquals("arrive", actions.filterIsInstance<Speak>().single().situation)
    }

    // ───────── 신호 반응 ─────────

    @Test
    fun `주행 중 문이 열리면 하던 말을 끊고 운전자와 동승자에게 바로 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.repeat() // 대답하는 중에
        rig.advance(1)
        rig.set { copy(seatDoorOpen = true) }
        val notice = rig.notices().single()
        assertEquals("door_open_d", notice.situation)
        assertTrue(notice.urgent)
        val line = rig.spoken("door_open_p").single()
        assertEquals(Priority.SAFETY, line.priority)
        assertTrue(line.interrupt)
        assertTrue(rig.snapshot().driverNoticeUrgent)

        rig.advance(16)
        assertEquals("계속 열려 있으면 15초마다 다시 알린다", 2, rig.notices().size)
        assertEquals("한 번의 열림은 한 건으로 센다", 1, rig.snapshot().counts.safetyNotices)

        rig.set { copy(seatDoorOpen = false) }
        assertNull(rig.snapshot().driverNotice)
    }

    @Test
    fun `P단에서는 문이 열려도 알리지 않는다`() {
        val rig = Rig()
        rig.start()
        rig.advance(15)
        rig.clear()
        rig.set { copy(seatDoorOpen = true) }
        assertTrue(rig.notices().isEmpty())
        assertTrue(rig.spoken().isEmpty())
    }

    @Test
    fun `창문이 30퍼센트 넘게 열리면 한 번 알리고 닫았다 다시 열면 또 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(seatWindowOpenPercent = 29) }
        assertTrue(rig.spoken().isEmpty())
        rig.set { copy(seatWindowOpenPercent = 40) }
        assertEquals("window_open_p", rig.spoken().single().situation)
        val notice = rig.notices().single()
        assertEquals("window_open_d", notice.situation)
        assertFalse(notice.urgent)

        rig.set { copy(seatWindowOpenPercent = 60) }
        rig.advance(60)
        assertEquals(1, rig.spoken("window_open_p").size)

        rig.set { copy(seatWindowOpenPercent = 25) } // 20% 아래로 닫히지 않았다
        rig.set { copy(seatWindowOpenPercent = 45) }
        assertEquals(1, rig.spoken("window_open_p").size)

        rig.set { copy(seatWindowOpenPercent = 5) }
        rig.set { copy(seatWindowOpenPercent = 50) }
        assertEquals(2, rig.spoken("window_open_p").size)
    }

    @Test
    fun `안전띠가 풀리면 두 번 권하고 그래도 풀려 있으면 운전자에게 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(seatBeltBuckled = false) }
        assertEquals(1, rig.spoken("belt_off_p").size)
        rig.advance(31)
        assertEquals(2, rig.spoken("belt_off_p").size)
        assertTrue(rig.notices().isEmpty())
        rig.advance(30)
        assertEquals("belt_off_d", rig.notices().single().situation)
        rig.advance(120)
        assertEquals(2, rig.spoken("belt_off_p").size)
        assertEquals(1, rig.notices().size)

        rig.set { copy(seatBeltBuckled = true) }
        assertNull(rig.snapshot().driverNotice)
    }

    @Test
    fun `차가 주지 않는 신호의 기능만 꺼지고 나머지는 돈다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.signals = Signals(gear = Gear.PARK) // 좌석·문·창문·안전띠·브레이크·내비 없음
        rig.start()
        rig.set { copy(gear = Gear.DRIVE) }
        rig.advance(20 * 60)
        assertTrue(rig.notices().isEmpty())
        assertTrue(rig.spoken().none { it.kind == SpeechKind.SAFETY || it.kind == SpeechKind.STOP })
        assertEquals(1, rig.spoken("depart").size)
        assertTrue(rig.spoken("stage_onway").size >= 3)
    }

    // ───────── 우선순위와 잠시 조용히 ─────────

    @Test
    fun `멈춤 안내는 대답을 끊고 먼저 말하기는 대답이 끝나기를 기다린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.repeat()
        rig.nav { copy(remainSec = 1400) } // 구간이 바뀌었지만
        assertEquals("대답 중에는 끼어들지 않는다", 1, rig.spoken().size)
        rig.advance(12)
        assertEquals(1, rig.spoken("stage_half").size)
        assertFalse(rig.spoken("stage_half").single().interrupt)

        rig.repeat()
        rig.set { copy(gear = Gear.PARK, driverDoorOpen = true, driverSeatOccupied = false) }
        val away = rig.spoken("driver_away").single()
        assertTrue("멈춤 안내는 대답을 끊는다", away.interrupt)
    }

    @Test
    fun `우선순위는 안전 알림 다음 멈춤 다음 대답 다음 먼저 말하기 순이다`() {
        assertTrue(Priority.SAFETY > Priority.STOP)
        assertTrue(Priority.STOP > Priority.ANSWER)
        assertTrue(Priority.ANSWER > Priority.PROACTIVE)

        // 대답은 먼저 말하기를 끊는다
        val a = Rig()
        a.startAndDepart()
        a.clear()
        a.nav { copy(remainSec = 1400) }
        a.advance(1)
        assertEquals(Priority.PROACTIVE, a.spoken("stage_half").single().priority)
        a.repeat()
        assertTrue("대답은 먼저 말하기를 끊는다", a.spoken().last().interrupt)
        assertEquals(Priority.ANSWER, a.spoken().last().priority)

        // 대답은 멈춤 안내를 끊지 못하고 기다린다. 안전 알림은 멈춤 안내를 끊는다.
        val b = Rig()
        b.startAndDepart()
        b.clear()
        b.set { copy(brakePressed = true) }
        b.advance(20)
        assertEquals("stop_signal", b.spoken().single().situation)
        b.repeat()
        assertEquals("멈춤 안내 중에는 대답이 기다린다", 1, b.spoken().size)
        b.set { copy(seatDoorOpen = true) }
        val door = b.spoken("door_open_p").single()
        assertTrue("안전 알림은 멈춤 안내를 끊는다", door.interrupt)
        b.set { copy(seatDoorOpen = false) }
        b.advance(12)
        val answer = b.spoken().last()
        assertEquals(SpeechKind.DRIVER_REQUEST, answer.kind)
        assertFalse("기다리던 대답은 안전 알림이 끝난 뒤에 끊지 않고 말한다", answer.interrupt)
    }

    @Test
    fun `문과 창문이 함께 열려도 안전 알림끼리는 끊지 않고 차례로 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(seatDoorOpen = true, seatWindowOpenPercent = 50) }
        assertEquals(listOf("door_open_p"), rig.spoken().map { it.situation })
        assertEquals(2, rig.notices().size)
        assertTrue("운전자 화면에는 급한 알림(문)이 남는다", rig.snapshot().driverNoticeUrgent)
        assertEquals(rig.notices().first { it.situation == "door_open_d" }.text, rig.snapshot().driverNotice)
        rig.advance(12)
        assertEquals(listOf("door_open_p", "window_open_p"), rig.spoken().map { it.situation })
        assertFalse(rig.spoken("window_open_p").single().interrupt)
    }

    @Test
    fun `잠시 조용히를 누르면 하던 말을 멈추고 10분 동안 먼저 말하지 않는다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.startAndDepart()
        rig.advance(125)
        rig.clear()
        rig.repeat()
        rig.quiet()
        assertTrue(rig.log.any { it.second is Silence })
        assertTrue(rig.snapshot().quietUntilMs != null)
        rig.clear()

        rig.set { copy(brakePressed = true) }
        rig.advance(9 * 60)
        assertTrue("조용히 중에는 멈춤 안내도 먼저 말하기도 하지 않는다", rig.spoken().isEmpty())
        rig.set { copy(brakePressed = false) }
        rig.advance(70)
        assertEquals("10분이 지나면 다시 먼저 말한다", 1, rig.spoken().size)
        assertNull(rig.snapshot().quietUntilMs)
    }

    @Test
    fun `조용히 중에도 문 열림과 운전자 이탈은 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.quiet()
        rig.clear()
        rig.set { copy(seatWindowOpenPercent = 50) }
        assertTrue("창문은 운전자 화면에만", rig.spoken().isEmpty())
        assertEquals(1, rig.notices().size)

        rig.set { copy(seatDoorOpen = true) }
        assertEquals(1, rig.spoken("door_open_p").size)
        rig.set { copy(seatDoorOpen = false) }

        rig.advance(10)
        rig.set { copy(gear = Gear.PARK, driverDoorOpen = true, driverSeatOccupied = false) }
        assertEquals(1, rig.spoken("driver_away").size)
    }

    @Test
    fun `조용히 중에 다시 말씀드리기를 누르면 조용히가 풀린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.quiet()
        rig.clear()
        rig.repeat()
        assertEquals(1, rig.spoken().size)
        assertNull(rig.snapshot().quietUntilMs)
    }

    // ───────── 물으면 대답하기 ─────────

    @Test
    fun `물음에 맞는 사실로 대답한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.ask(PassengerIntent.WHERE); rig.advance(12)
        rig.ask(PassengerIntent.WHEN); rig.advance(12)
        rig.ask(PassengerIntent.GO_HOME); rig.advance(12)
        assertEquals(listOf("ask_where", "ask_when_onway", "ask_gohome"), rig.spoken().map { it.situation })
        assertEquals(3, rig.snapshot().counts.answered)

        rig.nav { copy(remainSec = 200) }
        rig.advance(12)
        rig.clear()
        rig.ask(PassengerIntent.ARRIVED_Q); rig.advance(12)
        assertEquals("ask_when_near", rig.spoken().single().situation)
    }

    @Test
    fun `집으로 가는 길에 집에 가자고 하시면 집에 가고 있다고 말한다`() {
        for (config in listOf(
            TripConfig(place = "집", purpose = Purpose.NONE),
            TripConfig(place = "우리 집", purpose = Purpose.MEAL),
            TripConfig(place = "큰딸 집", purpose = Purpose.REST),
        )) {
            val rig = Rig(config)
            rig.startAndDepart()
            rig.clear()
            rig.ask(PassengerIntent.GO_HOME); rig.advance(12)
            val line = rig.spoken().single()
            assertEquals(config.place, "ask_where", line.situation)
            assertFalse(line.text, line.text.contains("들렀다가") || line.text.contains("들르"))
        }
        // 다른 곳에 가는 길이면 마음을 받아 주고, 들렀다가 집에 간다고 말한다
        val rig = Rig(TripConfig(place = "병원", purpose = Purpose.CLINIC))
        rig.startAndDepart()
        rig.clear()
        rig.ask(PassengerIntent.GO_HOME); rig.advance(12)
        assertEquals("ask_gohome", rig.spoken().single().situation)
        assertTrue(rig.spoken().single().text.contains("집에 가요"))
    }

    @Test
    fun `서 있을 때 왜 안 가느냐고 물으면 서 있는 이유를 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(brakePressed = true) }
        rig.advance(30)
        rig.clear()
        rig.ask(PassengerIntent.WHY_STOPPED)
        assertEquals("stop_signal", rig.spoken().single().situation)
        rig.advance(12)
        rig.ask(PassengerIntent.ARRIVED_Q)
        assertEquals("stop_signal", rig.spoken().last().situation)
    }

    // ───────── 기록과 재현성 ─────────

    @Test
    fun `끝내면 횟수와 시간만 남긴다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.repeat(); rig.advance(12)
        rig.set { copy(brakePressed = true) }; rig.advance(21)
        rig.set { copy(brakePressed = false, seatWindowOpenPercent = 50) }; rig.advance(7)
        val record = rig.engine.finish(rig.now)
        assertEquals(60, record.durationSec)
        assertEquals(1, record.counts.proactive) // 출발 안내
        assertEquals(1, record.counts.driverRequested)
        assertEquals(1, record.counts.stopNotices)
        assertEquals(1, record.counts.safetyNotices)
        assertEquals(3, record.counts.handled)
        assertEquals(Phase.ENDED, rig.snapshot().phase)
        assertTrue(rig.engine.onTick(rig.now + 1000).isEmpty())
    }

    @Test
    fun `같은 신호를 같은 순서로 넣으면 같은 말이 나온다`() {
        fun run(): List<Pair<Long, EngineAction>> {
            val rig = Rig(TripConfig(place = "큰딸 집", purpose = Purpose.FAMILY, interval = IntervalMode.AUTO))
            rig.startAndDepart()
            rig.repeat(); rig.advance(9)
            rig.repeat(); rig.advance(30)
            rig.set { copy(brakePressed = true) }; rig.advance(25)
            rig.set { copy(brakePressed = false, seatWindowOpenPercent = 40) }; rig.advance(400)
            rig.nav { copy(remainSec = 1000) }; rig.advance(300)
            rig.set { copy(gear = Gear.PARK) }; rig.advance(3)
            rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }; rig.advance(80)
            rig.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }; rig.advance(5)
            rig.set { copy(gear = Gear.DRIVE) }; rig.advance(30)
            rig.nav { copy(remainSec = 40) }; rig.advance(20)
            rig.set { copy(gear = Gear.PARK) }; rig.advance(10)
            return rig.log.toList()
        }
        val a = run()
        assertEquals(a, run())
        assertTrue(a.size > 10)
    }

    @Test
    fun `엔진이 하는 모든 말은 대본 규칙을 지킨다`() {
        val rig = Rig(TripConfig(honorific = "아버지", place = "주간보호센터", driver = "사위", purpose = Purpose.REST, interval = IntervalMode.AUTO))
        rig.startAndDepart()
        repeat(6) { rig.repeat(); rig.advance(15) }
        rig.set { copy(brakePressed = true) }; rig.advance(25)
        rig.set { copy(brakePressed = false) }; rig.advance(600)
        rig.nav { copy(remainSec = 100) }; rig.advance(30)
        rig.set { copy(gear = Gear.PARK) }; rig.advance(10)
        for (line in rig.spoken()) {
            assertTrue(line.text, line.text.startsWith("아버지,"))
            assertNull(line.text, ScriptValidator.findForbidden(line.text))
            assertTrue(line.text, ScriptValidator.splitSentences(line.text).size <= 3)
            assertFalse(line.text, line.text.contains("{"))
        }
        assertTrue(rig.spoken().any { it.text.contains("사위가") })
    }

    @Test
    fun `카드 문구가 구간별 상황으로 온 대본도 쓰고 서 있을 때는 그 사실을 보인다`() {
        fun card(key: String, line: String) = key to Situation(key, Audience.CARD, listOf(line, "$line(둘째 꼴)"))
        val base = TestScripts.book.situations.filterKeys {
            !it.startsWith("card_stage") && it != "card_stop" && it != "card_driver_away"
        }
        val book = ScriptBook(
            1,
            base + listOf(
                card("card_stage_depart", "이제 출발해요"), card("card_stage_onway", "잘 가고 있어요"),
                card("card_stage_half", "절반쯤 왔어요"), card("card_stage_near", "거의 다 왔어요"),
                card("card_stage_arrive", "{곳}에 다 왔어요"), card("card_stop", "잠깐 멈췄어요"),
                card("card_driver_away", "{운전자:이} 곧 와요"),
            ),
        )
        val engine = CompanionEngine(book, TripConfig(place = "큰딸 집"))
        var now = 0L
        var s = Signals(
            gear = Gear.DRIVE, brakePressed = false, driverSeatOccupied = true, driverDoorOpen = false,
            seatOccupied = true, nav = NavState(guiding = true, totalSec = 2400, remainSec = 2400),
        )
        fun set(change: Signals.() -> Signals) {
            s = s.change()
            engine.onSignals(s, now)
        }
        fun wait(sec: Int) = repeat(sec) {
            now += 1000
            engine.onTick(now)
        }
        fun cardLine() = engine.snapshot(now).cardStage

        engine.start(now)
        set { this }
        assertEquals("이제 출발해요", cardLine())
        set { copy(nav = nav.copy(remainSec = 1400)) }
        assertEquals("절반쯤 왔어요", cardLine())
        set { copy(brakePressed = true) }
        wait(21)
        assertEquals("잠깐 멈췄어요", cardLine())
        set { copy(brakePressed = false) }
        assertEquals("절반쯤 왔어요", cardLine())
        set { copy(nav = nav.copy(remainSec = 200)) } // 곧 도착: 따로 없으면 '거의 다 옴' 카드를 쓴다
        assertEquals("거의 다 왔어요", cardLine())
        set { copy(gear = Gear.PARK, nav = nav.copy(remainSec = 900)) }
        set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        assertEquals("따님이 곧 와요", cardLine())
        set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        wait(3)
        set { copy(gear = Gear.DRIVE, nav = nav.copy(remainSec = 30)) }
        set { copy(gear = Gear.PARK) }
        assertEquals("큰딸 집에 다 왔어요", cardLine())
    }

    @Test
    fun `동승자 카드는 가는 곳과 구간 두 줄이다`() {
        val rig = Rig()
        rig.start()
        assertEquals("큰딸 집에 가는 길", rig.snapshot().cardTitle)
        assertEquals("이제 출발해요", rig.snapshot().cardStage)
    }
}
