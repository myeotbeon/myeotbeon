package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.intent.PassengerIntent
import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.NavState
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.Signals
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.record.TripCounts
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

/**
 * 엔진에 신호와 시간을 넣어 보고 나온 말을 모으는 시험대. 시각은 0부터 손으로 흘린다.
 *
 * @param speakResult 엔진이 시킨 말마다 음성 쪽이 돌려줄 결과. true = 소리 냄, false = 소리 내지 못함,
 *   null = 결과를 알려 주지 않음. 기록의 횟수는 이 결과로만 올라간다.
 */
private class Rig(
    config: TripConfig = TripConfig(place = "큰딸 집", purpose = Purpose.MEAL, interval = IntervalMode.OFF),
    params: EngineParams = EngineParams(),
    var speakResult: Boolean? = true,
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

    private fun record(actions: List<EngineAction>) = actions.forEach { action ->
        log += now to action
        val result = speakResult
        if (action is Speak && result != null) engine.onUtteranceResult(action.id, result)
    }

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
    fun confirmArrival() = record(engine.driverConfirmedArrival(now))
    fun navArrived() = record(engine.onNavArrived(now))

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
    fun arrivals(): List<ArrivalSource> = log.map { it.second }.filterIsInstance<Arrived>().map { it.source }
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
    fun `변속단과 주행 규제 신호가 모두 없으면 시간만으로 출발했다고 하지 않는다`() {
        val rig = Rig()
        rig.signals = Signals()
        rig.start()
        rig.advance(60)
        assertTrue("P단일 수도 있으므로 '이제 출발해요'를 하지 않는다", rig.spoken("depart").isEmpty())
        assertEquals(Phase.READY, rig.snapshot().phase)
        // 움직였다는 증거(속도)가 오면 그때 출발로 본다
        rig.set { copy(speedKph = 30f) }
        rig.advance(12)
        assertEquals(1, rig.spoken("depart").size)
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
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
    fun `직접 쓴 한마디가 대본 규칙을 통과하지 못하면 읽지 않는다`() {
        val bad = listOf(
            "또 손주 보러 가요", // 금지어
            "가서 손주 얼굴 보고 맛있는 저녁도 같이 먹고 와요", // 20음절
            "가요. 손주 봐요", // 두 문장
        )
        for (line in bad) {
            assertTrue(line, CompanionEngine.checkCustomPurposeLine(line, "어머니").isNotEmpty())
            val rig = Rig(TripConfig(place = "큰딸 집", purpose = Purpose.CUSTOM, customPurposeLine = line))
            rig.startAndDepart()
            rig.clear()
            repeat(2) { rig.repeat(); rig.advance(12) }
            assertEquals(2, rig.spoken().size)
            rig.spoken().forEach { assertEquals(line, 2, ScriptValidator.splitSentences(it.text).size) }
        }
    }

    @Test
    fun `직접 쓴 한마디 검사는 앞의 호칭 부름을 떼고 마침표를 붙인 꼴로 본다`() {
        assertEquals("가서 손주 봐요.", CompanionEngine.customRedirectText("  어머니, 가서 손주 봐요 ", "어머니"))
        assertNull(CompanionEngine.customRedirectText("  . ", "어머니"))
        assertTrue(CompanionEngine.checkCustomPurposeLine("어머니, 가서 손주 봐요", "어머니").isEmpty())
        assertEquals(listOf("F-빈줄"), CompanionEngine.checkCustomPurposeLine(" ", "어머니").map { it.rule })
        assertEquals(listOf("R3"), CompanionEngine.checkCustomPurposeLine("잊지 말고 약 드세요", "어머니").map { it.rule })
        assertEquals(listOf("D"), CompanionEngine.checkCustomPurposeLine("치매 검사 받으러 가요", "어머니").map { it.rule })
        assertEquals(listOf("S"), CompanionEngine.checkCustomPurposeLine("손을 내밀어 보세요", "어머니").map { it.rule })

        // 통과한 한마디는 호칭 부름을 뗀 꼴로 대답 뒤에 붙는다
        val rig = Rig(TripConfig(place = "큰딸 집", purpose = Purpose.CUSTOM, customPurposeLine = "어머니, 가서 손주 봐요"))
        rig.startAndDepart()
        rig.clear()
        repeat(2) { rig.repeat(); rig.advance(12) }
        assertTrue(rig.spoken()[1].text, rig.spoken()[1].text.endsWith(". 가서 손주 봐요."))
        assertEquals(1, Regex("어머니").findAll(rig.spoken()[1].text).count())
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
        rig.advance(12) // 표본 하나로는 바로 가지 않고 10초 이어지면 간다
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
        rig.advance(12)
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
        near.advance(12)
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
    fun `남은 시간 0 표본은 남은 거리도 가까울 때만 받는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 0) } // 경로가 바뀌는 순간의 기본값 같은 것. 남은 거리는 모른다
        rig.advance(30)
        assertEquals(Stage.JUST_DEPARTED, rig.snapshot().stage)
        rig.nav { copy(remainSec = 0, remainMeters = 5_000) } // 남은 거리는 멀다
        rig.advance(30)
        assertEquals(Stage.JUST_DEPARTED, rig.snapshot().stage)
        assertTrue("'거의 다 왔어요'를 말하지 않는다", rig.spoken().isEmpty())
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(10)
        assertNotEquals("가짜 0 으로 도착하지 않는다", Phase.ARRIVED, rig.snapshot().phase)
        rig.nav { copy(remainSec = 0, remainMeters = 40) } // 남은 거리도 가까우면 받는다
        assertNotEquals("표본 하나로는 도착으로 보지 않는다", Phase.ARRIVED, rig.snapshot().phase)
        rig.advance(10) // 같은 판단이 10초 이어지면
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
    }

    @Test
    fun `남은 거리 0 과 남은 시간 0 이 함께 와도 도착 이벤트 없이는 도착으로 보지 않는다`() {
        // 경로를 찾는 동안의 기본값(0초·0m) 같은 표본(검토 결함 4)
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 0, remainMeters = 0) }
        rig.nav { copy(remainSec = 0, remainMeters = 0, congested = true) }
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(30)
        assertNotEquals(Phase.ARRIVED, rig.snapshot().phase)
        assertTrue(rig.spoken("arrive").isEmpty())
        assertEquals("남은 거리 0 은 넉넉히 남았다는 근거도 아니므로 도착을 부정하지 않는 정차 문장", 1, rig.spoken("stop_parked_neutral").size)
        // 내비 도착 이벤트가 오면 그때 도착이다
        rig.set { copy(gear = Gear.DRIVE) }
        rig.navArrived()
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
    }

    @Test
    fun `남은 시간이 작아도 남은 거리가 멀면 목적지 근처로 보지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 40, remainMeters = 2_000) } // 어긋난 표본: 2km 에 40초
        rig.nav { copy(remainSec = 38, remainMeters = 1_980) }
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(30)
        assertNotEquals(Phase.ARRIVED, rig.snapshot().phase)
        assertTrue("'다 왔어요'를 먼저 말하지 않는다", rig.spoken("arrive").isEmpty())
        // 거리도 300m 안이면 근처다(두 번째 표본으로 확인)
        rig.set { copy(gear = Gear.DRIVE) }
        rig.nav { copy(remainSec = 30, remainMeters = 250) }
        rig.nav { copy(remainSec = 25, remainMeters = 200) }
        rig.set { copy(gear = Gear.PARK) }
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
    }

    @Test
    fun `목적지 근처라는 표본 하나로는 도착하지 않고 두 번째 표본이나 10초 뒤에 도착한다`() {
        val a = Rig()
        a.startAndDepart()
        a.clear()
        a.nav { copy(remainSec = 30, remainMeters = 100) }
        a.set { copy(gear = Gear.PARK) }
        a.advance(4)
        assertNotEquals("표본 하나", Phase.ARRIVED, a.snapshot().phase)
        assertTrue(a.spoken("arrive").isEmpty())
        a.nav { copy(remainSec = 25, remainMeters = 90) } // 두 번째 표본
        assertEquals(Phase.ARRIVED, a.snapshot().phase)
        assertEquals(listOf("arrive"), a.spoken().map { it.situation })

        val b = Rig()
        b.startAndDepart()
        b.nav { copy(remainSec = 30, remainMeters = 100) }
        b.advance(9)
        b.set { copy(gear = Gear.PARK) }
        assertNotEquals(Phase.ARRIVED, b.snapshot().phase)
        b.advance(1) // 같은 판단이 10초 이어짐
        assertEquals(Phase.ARRIVED, b.snapshot().phase)

        // 근접 알림(기억한 도착 지점 300m 안)은 표본이 아니라 바로 믿는다
        val c = Rig()
        c.signals = c.signals.copy(nav = NavState())
        c.startAndDepart()
        c.nav { copy(nearDestination = true) }
        c.set { copy(gear = Gear.PARK) }
        assertEquals(Phase.ARRIVED, c.snapshot().phase)
    }

    @Test
    fun `정체 표시만 바뀐 것은 새 표본으로 세지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 1400) }
        rig.nav { copy(congested = true) } // 남은 시간은 그대로
        rig.nav { copy(congested = false) }
        assertEquals("같은 표본이라 아직 구간을 바꾸지 않는다", Stage.JUST_DEPARTED, rig.snapshot().stage)
        rig.nav { copy(remainSec = 1390) } // 두 번째 표본
        assertEquals(Stage.HALF, rig.snapshot().stage)
    }

    @Test
    fun `작은 남은 시간이 한 번만 들어와도 구간이 굳지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.nav { copy(remainSec = 100) } // 표본 하나(이전 경로가 남아 있음 등)
        rig.advance(3)
        rig.nav { copy(remainSec = 2300) } // 다음 표본은 제 값
        rig.advance(30)
        assertEquals(Stage.JUST_DEPARTED, rig.snapshot().stage)
        assertTrue(rig.spoken().isEmpty())
    }

    @Test
    fun `같은 구간을 가리키는 표본이 두 번 연달아 오거나 10초 이어지면 구간이 바뀐다`() {
        val a = Rig()
        a.startAndDepart()
        a.clear()
        a.nav { copy(remainSec = 1400) }
        a.advance(2)
        assertEquals(Stage.JUST_DEPARTED, a.snapshot().stage)
        a.nav { copy(remainSec = 1390) } // 두 번째 표본
        assertEquals(Stage.HALF, a.snapshot().stage)
        a.advance(2)
        assertEquals(1, a.spoken("stage_half").size)

        val b = Rig()
        b.startAndDepart()
        b.clear()
        b.nav { copy(remainSec = 1400) }
        b.advance(9)
        assertEquals(Stage.JUST_DEPARTED, b.snapshot().stage)
        b.advance(1) // 같은 표본이 10초 이어짐
        assertEquals(Stage.HALF, b.snapshot().stage)
    }

    @Test
    fun `전체 시간이 크게 늘면 새 경로로 보고 구간을 다시 정한다`() {
        // 이전 목적지의 짧은 경로가 남아 있다가 출발 뒤에 새 목적지를 정한 경우
        val rig = Rig()
        rig.signals = rig.signals.copy(nav = NavState(guiding = true, totalSec = 240, remainSec = 200))
        rig.startAndDepart()
        assertEquals(Stage.SOON, rig.snapshot().stage)
        rig.clear()
        rig.nav { copy(totalSec = 2400, remainSec = 2400, remainMeters = 20_000) }
        assertEquals("새 경로에서는 지금 사실로 다시 정한다", Stage.JUST_DEPARTED, rig.snapshot().stage)
        assertEquals("이제 출발해요", rig.snapshot().cardStage)
        rig.advance(5)
        assertTrue("되돌아간 구간을 따로 말하지는 않는다", rig.spoken().isEmpty())
        rig.nav { copy(remainSec = 1400) }
        rig.advance(12)
        assertEquals(1, rig.spoken("stage_half").size)
        // 길을 다시 찾아 조금 늘어난 것은 새 경로가 아니다(구간은 뒤로 가지 않는다)
        rig.nav { copy(totalSec = 2600, remainSec = 1900) }
        rig.advance(12)
        assertEquals(Stage.HALF, rig.snapshot().stage)
    }

    @Test
    fun `새 경로로 구간을 다시 정하면 아직 말하지 못한 앞 구간 안내는 버린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.repeat() // 대답하는 동안에는 구간 안내가 기다린다
        rig.nav { copy(remainSec = 1400) }
        rig.advance(1)
        rig.nav { copy(remainSec = 1390) } // 두 번째 표본: 절반으로 확인됐지만 아직 말하지 못함
        assertEquals(Stage.HALF, rig.snapshot().stage)
        rig.nav { copy(totalSec = 4_800, remainSec = 4_700) } // 그사이 목적지가 바뀌어 새 경로
        rig.advance(20)
        assertEquals(Stage.JUST_DEPARTED, rig.snapshot().stage)
        assertTrue("바뀐 경로에서 '절반쯤 왔어요'를 말하지 않는다", rig.spoken("stage_half").isEmpty())
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
    fun `속도 신호가 없으면 브레이크를 35초 밟고 서 있어야 신호 대기를 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(brakePressed = true) }
        rig.advance(20)
        assertTrue("서행 중에 브레이크를 오래 밟는 것일 수 있어 20초로는 말하지 않는다", rig.spoken().isEmpty())
        rig.advance(14)
        assertTrue(rig.spoken().isEmpty())
        rig.advance(1)
        val line = rig.spoken().single()
        assertEquals("stop_signal", line.situation)
        assertEquals(Priority.STOP, line.priority)
        assertEquals(StopReason.SIGNAL_WAIT, rig.snapshot().stopReason)
        assertEquals(1, rig.snapshot().counts.stopNotices)

        // 속도 신호가 살아 있으면 속도로 보고 20초다
        val speed = Rig()
        speed.signals = speed.signals.copy(speedKph = 40f)
        speed.startAndDepart()
        speed.clear()
        speed.set { copy(speedKph = 0f, brakePressed = true) }
        speed.advance(19)
        assertTrue(speed.spoken().isEmpty())
        speed.advance(1)
        assertEquals("stop_signal", speed.spoken().single().situation)
    }

    @Test
    fun `멈춤 안내는 쿨다운 안에 되풀이하지 않고 오래 서 있으면 다시 한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(brakePressed = true) }
        rig.advance(35)
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
        rig.advance(36)
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
    fun `내비 없이 간 길에서 P단으로 서면 도착을 부정하지 않고 운전자가 도착을 알릴 수 있다`() {
        val rig = Rig()
        rig.signals = rig.signals.copy(nav = NavState()) // 매일 가는 병원·주간보호센터는 내비를 켜지 않는 일이 흔하다
        rig.startAndDepart()
        rig.advance(300)
        assertFalse("달리는 동안은 도착을 알리는 버튼을 보이지 않는다", rig.snapshot().canConfirmArrival)
        rig.clear()
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(6)
        val line = rig.spoken().single()
        assertEquals("stop_parked_neutral", line.situation)
        assertFalse(line.text, line.text.contains("아직") || line.text.contains("다 온 건"))
        assertEquals(StopReason.PARKED_UNSURE, rig.snapshot().stopReason)
        assertEquals("잠깐 멈췄어요", rig.snapshot().cardStage)
        assertTrue(rig.snapshot().canConfirmArrival)
        assertTrue("차가 스스로 도착이라고 하지 않는다", rig.arrivals().isEmpty())

        // 다 왔느냐고 물어도, 다시 말씀드리기를 눌러도 같은 중립의 말로 대답한다
        rig.advance(10)
        rig.ask(PassengerIntent.ARRIVED_Q); rig.advance(10)
        rig.repeat(); rig.advance(10)
        assertEquals(listOf("stop_parked_neutral", "stop_parked_neutral", "stop_parked_neutral"), rig.spoken().map { it.situation })

        // 운전자가 도착을 알린다 → 도착 안내, 도착 단계, 도착 이벤트(도착 지점을 기억하는 계기)
        rig.clear()
        rig.confirmArrival()
        val arrive = rig.spoken().single()
        assertEquals("arrive", arrive.situation)
        assertTrue(arrive.text.contains("큰딸 집"))
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        assertEquals(listOf(ArrivalSource.DRIVER), rig.arrivals())
        assertTrue(rig.snapshot().cardStage, rig.snapshot().cardStage.contains("다 왔어요"))
        assertFalse(rig.snapshot().canConfirmArrival)
        rig.confirmArrival() // 두 번 눌러도 한 번
        rig.advance(30)
        assertEquals(1, rig.spoken("arrive").size)
        assertEquals(1, rig.arrivals().size)
        assertEquals(StopReason.ARRIVED, rig.snapshot().stopReason)
    }

    @Test
    fun `내비가 남은 시간이 넉넉하다고 할 때만 아직 다 온 것이 아니라고 말한다`() {
        fun parkedLine(nav: NavState): String {
            val rig = Rig()
            rig.signals = rig.signals.copy(nav = nav)
            rig.startAndDepart()
            rig.clear()
            rig.set { copy(gear = Gear.PARK) }
            rig.advance(6)
            return rig.spoken().single().situation
        }
        assertEquals("stop_waypoint", parkedLine(NavState(guiding = true, totalSec = 2400, remainSec = 1500)))
        assertEquals("stop_waypoint", parkedLine(NavState(guiding = true, totalSec = 2400, remainSec = 1500, remainMeters = 9_000)))
        assertEquals("곧 도착 구간", "stop_parked_neutral", parkedLine(NavState(guiding = true, totalSec = 2400, remainSec = 200)))
        assertEquals("남은 거리 1km 안", "stop_parked_neutral", parkedLine(NavState(guiding = true, totalSec = 2400, remainSec = 600, remainMeters = 800)))
        assertEquals("내비 없음", "stop_parked_neutral", parkedLine(NavState()))
    }

    @Test
    fun `가짜 도착 뒤 다시 달리면 속도를 알 때 15km 넘게 30초 달린 뒤 도착을 거두고 주행 중으로 돌아간다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.signals = rig.signals.copy(speedKph = 40f)
        rig.startAndDepart()
        rig.nav { copy(remainSec = 50, remainMeters = 140) }
        rig.nav { copy(remainSec = 45, remainMeters = 130) }
        rig.set { copy(gear = Gear.PARK, speedKph = 0f) } // 목적지 앞 약국에 잠깐 섰다
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.advance(30)
        rig.clear()
        rig.set { copy(gear = Gear.DRIVE, speedKph = 30f) }
        rig.advance(29)
        assertEquals("30초가 안 되면 아직 도착으로 둔다", Phase.ARRIVED, rig.snapshot().phase)
        rig.advance(1)
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
        assertTrue(rig.log.any { it.second == ArrivalCancelled })
        assertNotEquals(Stage.ARRIVED, rig.snapshot().stage)
        assertNotEquals(StopReason.ARRIVED, rig.snapshot().stopReason)
        assertEquals("도착 표시를 풀고 지금 구간(남은 50초)을 보인다", "거의 다 왔어요", rig.snapshot().cardStage)
        assertTrue("다시 달리는 중에 '다 왔어요'를 다시 하지 않는다", rig.spoken("arrive").isEmpty())
        rig.repeat(); rig.advance(10)
        assertNotEquals("다시 말씀드리기도 '다 왔어요'가 아니다", "arrive", rig.spoken().last().situation)
        // 진짜 목적지에 서면 다시 도착을 알린다
        rig.nav { copy(remainSec = 20, remainMeters = 30) }
        rig.set { copy(gear = Gear.PARK, speedKph = 0f) }
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        assertEquals(1, rig.spoken("arrive").size)
        assertEquals(listOf(ArrivalSource.NEAR_DESTINATION), rig.arrivals())
    }

    @Test
    fun `속도를 모르면 도착 뒤 D단이 2분 이어져야 거두고 후진이나 P단이 끼면 처음부터 잰다`() {
        // 내려 드린 뒤 주차 자리로 옮기는 것은 떠난 것이 아니다(검토 NEW-7)
        val rig = Rig()
        rig.startAndDepart()
        rig.nav { copy(remainSec = 50, remainMeters = 140) }
        rig.nav { copy(remainSec = 45, remainMeters = 130) }
        rig.set { copy(gear = Gear.PARK) }
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.set { copy(gear = Gear.DRIVE) }
        rig.advance(100)
        rig.set { copy(gear = Gear.REVERSE) } // 후진으로 자리에 넣다가
        rig.advance(10)
        rig.set { copy(gear = Gear.DRIVE) } // 다시 앞으로
        rig.advance(100)
        assertEquals("주차 자리로 옮기는 것으로는 거두지 않는다", Phase.ARRIVED, rig.snapshot().phase)
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(30)
        assertEquals("다시 '다 왔어요'를 하지 않는다", 1, rig.spoken("arrive").size)
        assertFalse(rig.log.any { it.second == ArrivalCancelled })
        // 정말 다시 떠나면 D단 2분 뒤 거둔다
        rig.set { copy(gear = Gear.DRIVE) }
        rig.advance(119)
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.advance(1)
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
    }

    @Test
    fun `속도를 알면 천천히 자리를 옮기는 것으로는 거두지 않고 15km 넘게 달린 시간이 모두 30초면 거둔다`() {
        val rig = Rig()
        rig.signals = rig.signals.copy(speedKph = 40f)
        rig.startAndDepart()
        rig.nav { copy(remainSec = 50, remainMeters = 140) }
        rig.nav { copy(remainSec = 45, remainMeters = 130) }
        rig.set { copy(gear = Gear.PARK, speedKph = 0f) }
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.set { copy(gear = Gear.DRIVE, speedKph = 8f) } // 주차장 안을 천천히(D단 3분)
        rig.advance(180)
        assertEquals("속도를 알면 D단 시간만으로 거두지 않는다", Phase.ARRIVED, rig.snapshot().phase)
        rig.set { copy(speedKph = 40f) }
        rig.advance(20)
        rig.set { copy(speedKph = 0f, brakePressed = true) } // 신호 대기
        rig.advance(60)
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.set { copy(speedKph = 35f, brakePressed = false) }
        rig.advance(9)
        assertEquals("빠르게 달린 시간이 모두 29초", Phase.ARRIVED, rig.snapshot().phase)
        rig.advance(1)
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
    }

    @Test
    fun `내비 도착 이벤트가 굴러가는 중에 왔으면 P단을 거치기 전에는 거두지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.navArrived() // 큰 병원 정문에서 도착 이벤트. 아직 주차하러 가는 중
        rig.advance(60)
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(5)
        rig.set { copy(gear = Gear.DRIVE) } // 주차했다가 다시 나간다(속도를 모르는 차: D단 2분)
        rig.advance(119)
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.advance(1)
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
        assertTrue(rig.log.any { it.second == ArrivalCancelled })
    }

    @Test
    fun `남은 시간으로 도착을 봤는데 같은 경로의 남은 시간이 다시 늘면 도착을 거둔다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.nav { copy(remainSec = 40, remainMeters = 140) } // 목적지 앞(두 표본으로 확인)
        rig.nav { copy(remainSec = 38, remainMeters = 130) }
        rig.set { copy(gear = Gear.PARK) }
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.clear()
        rig.nav { copy(remainSec = 50) } // 조금 흔들리는 것으로는 거두지 않는다
        rig.advance(3)
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        rig.nav { copy(remainSec = 900, remainMeters = 9_000) } // 같은 경로에서 다시 15분
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
        assertTrue(rig.log.any { it.second == ArrivalCancelled })
        rig.advance(6)
        assertEquals("이제는 사실대로 아직 다 온 것이 아니라고 말한다", "stop_waypoint", rig.spoken().single().situation)
    }

    @Test
    fun `운전자가 알린 도착은 돌아갈 길을 내비에 넣어도 거두지 않는다`() {
        val rig = Rig()
        rig.signals = rig.signals.copy(nav = NavState())
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(6)
        rig.confirmArrival()
        rig.nav { copy(guiding = true, totalSec = 1_200, remainSec = 1_200, remainMeters = 10_000) }
        rig.advance(60)
        assertEquals(Phase.ARRIVED, rig.snapshot().phase)
        assertFalse(rig.log.any { it.second == ArrivalCancelled })
    }

    @Test
    fun `내비 도착이나 목적지 근처 도착도 도착 이벤트를 낸다`() {
        val nav = Rig()
        nav.startAndDepart()
        nav.clear()
        nav.navArrived()
        assertEquals(listOf(ArrivalSource.NAV_EVENT), nav.arrivals())
        assertEquals("arrive", nav.spoken().single().situation)

        val near = Rig()
        near.startAndDepart()
        near.nav { copy(remainSec = 30, remainMeters = 100) }
        near.nav { copy(remainSec = 25, remainMeters = 90) }
        near.set { copy(gear = Gear.PARK) }
        assertEquals(listOf(ArrivalSource.NEAR_DESTINATION), near.arrivals())
        assertEquals(Phase.ARRIVED, near.snapshot().phase)
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
        assertTrue("좌석 값은 2.5초 그대로일 때 받는다", rig.spoken().isEmpty())
        rig.advance(3)
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
        assertTrue(rig.spoken("stop_parked_neutral").isEmpty())
        assertEquals(3, rig.snapshot().counts.stopNotices)
    }

    @Test
    fun `도착일 수도 있는 정차에서 운전자가 돌아오면 다시 출발한다고 하지 않고 바로 이어 도착을 알려도 어긋나지 않는다`() {
        fun backLine(nav: NavState, depart: Boolean = true): Pair<Rig, Speak> {
            val rig = Rig()
            rig.signals = rig.signals.copy(nav = nav)
            if (depart) rig.startAndDepart() else { rig.start(); rig.advance(15) }
            rig.set { copy(gear = Gear.PARK) }
            rig.advance(8)
            rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
            rig.advance(10)
            rig.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
            rig.advance(10)
            return rig to rig.spoken().last { it.situation.startsWith("driver_back") }
        }
        val forbidden = listOf("출발", "가요", "다 왔", "도착", "아직")

        // 내비 없이 간 길(시연 순서 A): 정차 문장 → 내림 → 돌아옴 → '도착했어요'
        val (rig, back) = backLine(NavState())
        assertEquals("driver_back_parked", back.situation)
        assertTrue(back.text, back.text.startsWith("어머니, 따님이"))
        forbidden.forEach { assertFalse("$it: ${back.text}", back.text.contains(it)) }
        rig.clear()
        rig.confirmArrival() // 돌아온 운전자의 말이 끝나면 이어서 "다 왔어요"
        rig.advance(5)
        assertEquals(listOf("arrive"), rig.spoken().map { it.situation })

        // 곧 도착 구간의 정차와 출발 전에도 출발을 말하지 않는다
        assertEquals("driver_back_parked", backLine(NavState(guiding = true, totalSec = 2400, remainSec = 200)).second.situation)
        assertEquals("driver_back_parked", backLine(NavState(), depart = false).second.situation)
        // 내비가 넉넉히 남았다고 하는 정차(경유지)에서만 "다시 출발해요"
        val far = backLine(NavState(guiding = true, totalSec = 2400, remainSec = 1500, remainMeters = 9_000)).second
        assertEquals("driver_back", far.situation)
        assertTrue(far.text, far.text.contains("출발"))

        // 대본의 네 변형 모두 출발·도착을 단정하지 않는다
        val lines = TestScripts.book.situation("driver_back_parked")!!.lines
        assertEquals(4, lines.size)
        for (line in lines) forbidden.forEach { assertFalse("$it: $line", line.contains(it)) }
    }

    @Test
    fun `좌석 점유가 잠깐 흔들리면 운전자가 내렸다고 하지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.set { copy(driverDoorOpen = true) }
        rig.set { copy(driverDoorOpen = false) } // 창밖 일로 문을 잠깐 열었다 닫음
        rig.clear()
        repeat(5) {
            rig.set { copy(driverSeatOccupied = false) } // 앉은 채 몸을 들었다
            rig.advance(2)
            rig.set { copy(driverSeatOccupied = true) }
            rig.advance(2)
        }
        assertTrue(rig.spoken("driver_away").isEmpty())
        assertTrue(rig.spoken("driver_back").isEmpty())
        assertNotEquals(StopReason.DRIVER_AWAY, rig.snapshot().stopReason)
        // 동승 좌석 점유가 흔들려도 안전띠·이탈 판단이 흔들리지 않는다
        rig.set { copy(seatOccupied = false) }
        rig.advance(2)
        rig.set { copy(seatOccupied = true) }
        rig.advance(5)
        assertTrue(rig.spoken().none { it.kind == SpeechKind.STOP && it.situation.startsWith("driver_") })
    }

    @Test
    fun `운전자가 돌아온 뒤에는 운전석 문이 새로 열려야 다시 내렸다고 본다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(3)
        rig.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        rig.advance(10)
        assertEquals(1, rig.spoken("driver_back").size)
        rig.clear()
        rig.set { copy(driverSeatOccupied = false) } // 앉은 채로 좌석 값이 꺼짐(문은 닫힌 채)
        rig.advance(60)
        assertTrue(rig.spoken("driver_away").isEmpty())
        rig.set { copy(driverDoorOpen = true) } // 이번에는 정말 내린다
        rig.advance(3)
        assertEquals(1, rig.spoken("driver_away").size)
    }

    @Test
    fun `30초 안에 다시 내리면 내렸다는 말을 되풀이하지 않고 돌아왔다는 말도 하지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(3)
        rig.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        rig.advance(8)
        assertEquals(1, rig.spoken("driver_away").size)
        assertEquals(1, rig.spoken("driver_back").size)
        // 짐을 두고 와 곧바로 다시 내렸다가 돌아옴
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(3)
        rig.set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        rig.advance(8)
        assertEquals("30초 안의 재진입은 말하지 않는다", 1, rig.spoken("driver_away").size)
        assertEquals("말없이 지나간 이탈 뒤에는 돌아왔다고도 하지 않는다", 1, rig.spoken("driver_back").size)
        // 30초가 지나 다시 내리면 알린다
        rig.advance(20)
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(3)
        assertEquals(2, rig.spoken("driver_away").size)
    }

    @Test
    fun `운전자가 내린 동안 동승 좌석 문이 열리면 바로 다시 안심시키고 돌아온 운전자에게 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(3)
        rig.set { copy(driverDoorOpen = false) }
        rig.advance(10)
        assertEquals(1, rig.spoken("driver_away").size)
        rig.clear()

        rig.set { copy(seatDoorOpen = true) } // 혼자 남은 어르신이 문을 연다
        val line = rig.spoken().single()
        assertEquals("driver_away_again", line.situation)
        assertEquals(Priority.SAFETY, line.priority)
        assertTrue(line.text, line.text.contains("기다려"))
        assertTrue("운전자가 없는 동안에는 화면 알림·알림음을 내지 않는다", rig.notices().isEmpty())
        assertEquals(StopReason.DRIVER_AWAY, rig.snapshot().stopReason)
        rig.set { copy(seatDoorOpen = false) }
        rig.advance(5)
        rig.set { copy(seatDoorOpen = true) } // 10초 안에 또 열면 되풀이하지 않는다
        assertEquals(1, rig.spoken("driver_away_again").size)
        rig.set { copy(seatDoorOpen = false) }
        rig.advance(20)

        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = true) } // 운전자가 돌아온다
        rig.advance(3)
        val notice = rig.notices().single()
        assertEquals("away_door_d", notice.situation)
        assertTrue(notice.urgent)
        assertEquals(notice.text, rig.snapshot().driverNotice)
        assertTrue(rig.snapshot().driverNoticeUrgent)
        rig.advance(8)
        assertEquals(1, rig.spoken("driver_back").size)
        rig.advance(60)
        assertNull("이미 지난 일이라 1분 뒤에는 지운다", rig.snapshot().driverNotice)
        assertEquals(1, rig.snapshot().counts.safetyNotices)
    }

    @Test
    fun `운전자가 내린 동안 동승자 자리가 비면 이탈을 풀지 않고 돌아온 운전자에게 알린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(13)
        rig.clear()
        rig.set { copy(seatOccupied = false) } // 어르신이 자리에서 일어났다(문 신호가 없는 차여도)
        rig.advance(3)
        assertEquals("운전자가 돌아올 때까지 이탈로 본다", StopReason.DRIVER_AWAY, rig.snapshot().stopReason)
        assertEquals("driver_away_again", rig.spoken().single().situation)
        assertTrue("'잠깐 쉬었다 가요' 같은 정차 안내로 넘어가지 않는다", rig.spoken().none { it.situation.startsWith("stop_") })
        rig.advance(80)
        assertEquals("자리가 빈 동안에는 75초마다 하는 안심 말을 빈 자리에 하지 않는다", 1, rig.spoken("driver_away_again").size)
        assertEquals("운전자가 돌아올 때까지 이탈로 본다", StopReason.DRIVER_AWAY, rig.snapshot().stopReason)

        rig.set { copy(driverSeatOccupied = true) } // 운전자가 돌아온다
        rig.advance(3)
        assertEquals("away_seat_d", rig.notices().single().situation)
        assertTrue(rig.snapshot().driverNoticeUrgent)
        rig.advance(10)
        assertTrue("동승자가 자리에 없으면 돌아왔다는 말을 하지 않는다", rig.spoken("driver_back").isEmpty())
    }

    @Test
    fun `운전자와 동승자가 함께 내린 빈 차에는 되풀이해 말하지 않고 차가 대신 말한 횟수도 늘지 않는다`() {
        // 병원 앞에 세우고 운전자가 먼저 내린 뒤 어르신을 모시고 함께 들어간 경우
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(gear = Gear.PARK) }
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(13)
        assertEquals(1, rig.spoken("driver_away").size)
        rig.set { copy(seatDoorOpen = true) } // 운전자가 뒷문을 열어 드림(앱은 누가 열었는지 모른다)
        rig.advance(15)
        rig.set { copy(seatOccupied = false) }
        rig.advance(15)
        rig.set { copy(seatDoorOpen = false) }
        val before = rig.snapshot().counts
        val saidBefore = rig.spoken().size
        rig.advance(60 * 60) // 한 시간 동안 빈 차
        assertEquals("빈 차에는 아무 말도 하지 않는다", saidBefore, rig.spoken().size)
        assertEquals("차가 대신 말한 횟수가 늘지 않는다", before.spokenOnBehalf, rig.snapshot().counts.spokenOnBehalf)
        assertEquals(before, rig.snapshot().counts)
        assertEquals("돌아온 운전자에게 알릴 일을 남기려고 이탈 상태는 그대로 둔다", StopReason.DRIVER_AWAY, rig.snapshot().stopReason)

        // 어르신이 먼저 돌아와 앉으면(운전자는 아직 밖) 다시 안심시킨다
        rig.set { copy(seatOccupied = true) }
        rig.advance(3)
        assertEquals("driver_away_again", rig.spoken().last().situation)
        assertEquals(saidBefore + 1, rig.spoken().size)
    }

    @Test
    fun `P단에서 운전석과 동승 좌석이 모두 비면 그때부터의 시각을 알리고 누가 앉거나 주행 단이면 지운다`() {
        val rig = Rig()
        rig.startAndDepart()
        assertNull(rig.snapshot().vacantSinceMs)
        rig.set { copy(gear = Gear.PARK, driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(10)
        assertNull("동승자가 앉아 있으면 빈 차가 아니다", rig.snapshot().vacantSinceMs)
        rig.set { copy(seatOccupied = false) }
        rig.advance(2)
        assertNull("좌석 값은 2.5초 그대로일 때 받는다", rig.snapshot().vacantSinceMs)
        rig.advance(1)
        val since = rig.snapshot().vacantSinceMs
        assertEquals(rig.now, since)
        rig.advance(600)
        assertEquals("비어 있는 동안 그대로", since, rig.snapshot().vacantSinceMs)
        rig.set { copy(seatOccupied = true) }
        rig.advance(3)
        assertNull(rig.snapshot().vacantSinceMs)

        // 좌석 신호가 없는 차는 알 수 없다
        val none = Rig()
        none.signals = none.signals.copy(driverSeatOccupied = null, seatOccupied = null)
        none.startAndDepart()
        none.set { copy(gear = Gear.PARK) }
        none.advance(600)
        assertNull(none.snapshot().vacantSinceMs)
    }

    @Test
    fun `출발 전에도 운전자가 내리면 알린다`() {
        val rig = Rig()
        rig.start()
        rig.advance(15)
        rig.clear()
        rig.set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        rig.advance(3)
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
    fun `운전자가 차에 있는 P단에서는 문이 열려도 알리지 않는다`() {
        // 내리는 중일 수 있으므로. 운전자가 내린 동안 열리는 것은 따로 알린다(아래 시험).
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
    fun `운전자 알림의 상황 이름을 함께 내고 되풀이 알림은 글이 바뀌어도 같은 상황이다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(seatDoorOpen = true) }
        assertEquals("door_open_d", rig.snapshot().driverNoticeSituation)
        val first = rig.snapshot().driverNotice
        rig.advance(16) // 15초 뒤 되풀이(대본 변형을 돌려 써서 글이 바뀐다)
        assertEquals(2, rig.notices().count { it.situation == "door_open_d" })
        assertNotEquals(first, rig.snapshot().driverNotice)
        assertEquals("door_open_d", rig.snapshot().driverNoticeSituation)
        rig.set { copy(seatDoorOpen = false) }
        assertNull(rig.snapshot().driverNoticeSituation)
    }

    @Test
    fun `급한 알림이 겹치면 더 급한 것을 보이고 그것이 풀리면 남은 것을 다시 보인다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(seatBeltBuckled = false) }
        rig.advance(61) // 권유 두 번(0초, 30초) 뒤 60초에 운전자 알림
        val belt = rig.notices().single { it.situation == "belt_off_d" }
        assertEquals(belt.text, rig.snapshot().driverNotice)
        rig.set { copy(seatDoorOpen = true) }
        val door = rig.notices().last { it.situation == "door_open_d" }
        assertEquals("문이 더 급하다", door.text, rig.snapshot().driverNotice)
        rig.set { copy(seatWindowOpenPercent = 50) }
        assertEquals("덜 급한 창문은 그 줄을 차지하지 않는다", door.text, rig.snapshot().driverNotice)
        rig.set { copy(seatDoorOpen = false) }
        assertEquals("문이 닫혀도 안전띠는 아직 풀려 있다", belt.text, rig.snapshot().driverNotice)
        assertTrue(rig.snapshot().driverNoticeUrgent)
        rig.set { copy(seatBeltBuckled = true) }
        assertEquals("안전띠를 매면 남은 창문 알림이 보인다", "window_open_d", rig.notices().last().situation)
        assertEquals(rig.notices().last().text, rig.snapshot().driverNotice)
        assertFalse(rig.snapshot().driverNoticeUrgent)
        rig.advance(16)
        assertNull(rig.snapshot().driverNotice)
    }

    @Test
    fun `문이 계속 열려 있으면 세 번 알린 뒤로는 간격을 늘린다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(seatDoorOpen = true) }
        rig.advance(30) // 0초, 15초, 30초
        assertEquals(3, rig.notices().size)
        rig.advance(59)
        assertEquals("세 번 뒤로는 15초마다 되풀이하지 않는다", 3, rig.notices().size)
        rig.advance(1) // 30초 + 60초
        assertEquals(4, rig.notices().size)
        rig.advance(60)
        assertEquals(5, rig.notices().size)
        assertEquals(5, rig.spoken("door_open_p").size)
        assertEquals("한 번의 열림은 한 건", 1, rig.snapshot().counts.safetyNotices)
        rig.set { copy(seatDoorOpen = false) }
        rig.set { copy(seatDoorOpen = true) } // 닫았다가 새로 열면 처음부터 다시 센다
        rig.advance(15)
        assertEquals(7, rig.notices().size)
        assertEquals(2, rig.snapshot().counts.safetyNotices)
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

    @Test
    fun `주행 중에 신호가 모두 끊겨 null 이 되어도 멈춤·도착·문 안내를 지어내지 않고 돌아오면 이어 간다`() {
        val rig = Rig(TripConfig(place = "큰딸 집", interval = IntervalMode.FIVE_MIN))
        rig.startAndDepart()
        val before = rig.signals
        rig.clear()
        rig.signals = Signals() // 변속단·주행 규제·속도·브레이크·좌석·문·창문·안전띠·내비가 모두 끊김
        rig.set { this }
        rig.advance(6 * 60)
        assertEquals("출발한 여정은 그대로 이어 간다", Phase.DRIVING, rig.snapshot().phase)
        assertTrue(rig.spoken().none { it.kind == SpeechKind.STOP || it.kind == SpeechKind.SAFETY })
        assertTrue(rig.notices().isEmpty())
        assertTrue(rig.arrivals().isEmpty())
        assertEquals(StopReason.NONE, rig.snapshot().stopReason)
        assertFalse("P단인지 모르면 '도착했어요'를 받지 않는다", rig.snapshot().canConfirmArrival)
        assertTrue("시간으로 하는 먼저 말하기는 이어 간다", rig.spoken().any { it.kind == SpeechKind.PROACTIVE })
        assertTrue(rig.spoken().all { it.text.startsWith("어머니,") })

        // 신호가 돌아오면 출발 안내를 다시 하지 않고, 구간도 뒤로 가지 않는다
        val stage = rig.snapshot().stage
        rig.signals = before
        rig.set { this }
        rig.advance(30)
        assertTrue(rig.spoken("depart").isEmpty())
        assertEquals(Phase.DRIVING, rig.snapshot().phase)
        assertTrue(rig.snapshot().stage.ordinal >= stage.ordinal)

        // 신호가 몇 초 끊겼다 돌아온 사이에 브레이크를 밟고 있었어도 멈춤 시간은 처음부터 다시 잰다
        rig.set { copy(brakePressed = true) }
        rig.advance(10)
        rig.signals = Signals()
        rig.set { this }
        rig.advance(5)
        rig.signals = before.copy(brakePressed = true)
        rig.set { this }
        rig.advance(10)
        assertTrue(rig.spoken("stop_signal").isEmpty())
        rig.advance(24) // 돌아온 때부터 다시 잰 35초가 되기 전
        assertTrue(rig.spoken("stop_signal").isEmpty())
        rig.advance(1)
        assertEquals(1, rig.spoken("stop_signal").size)
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
        rig.advance(3) // 좌석 값이 2.5초 그대로여야 이탈로 본다. 그때 대답은 아직 하는 중이다
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
        a.advance(11)
        assertEquals(Priority.PROACTIVE, a.spoken("stage_half").single().priority)
        a.repeat()
        assertTrue("대답은 먼저 말하기를 끊는다", a.spoken().last().interrupt)
        assertEquals(Priority.ANSWER, a.spoken().last().priority)

        // 대답은 멈춤 안내를 끊지 못하고 기다린다. 안전 알림은 멈춤 안내를 끊는다.
        val b = Rig()
        b.startAndDepart()
        b.clear()
        b.set { copy(brakePressed = true) }
        b.advance(35)
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
        rig.advance(3)
        assertEquals(1, rig.spoken("driver_away").size)
    }

    @Test
    fun `조용히 중에 다시 말씀드리기를 누르면 조용히가 풀리고 그 사실을 운전자 화면에 보인다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.quiet()
        rig.clear()
        rig.repeat()
        assertEquals(1, rig.spoken().size)
        assertNull(rig.snapshot().quietUntilMs)
        val note = rig.notices().single()
        assertEquals("quiet_off_d", note.situation)
        assertFalse(note.urgent)
        assertEquals(note.text, rig.snapshot().driverNotice)
        rig.advance(16)
        assertNull("잠깐 보이고 사라진다", rig.snapshot().driverNotice)
        rig.clear()
        rig.repeat()
        assertTrue("조용히 중이 아니면 알리지 않는다", rig.notices().isEmpty())
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
    fun `잠시 조용히 중이거나 끝났으면 물음에 대답하지 않는다고 알려 준다`() {
        // 음성 인식 쪽은 이것으로 '대답한 물음'만 같은 물음 쿨다운에 센다(검토 NEW-8)
        val rig = Rig()
        rig.startAndDepart()
        assertTrue(rig.engine.acceptsQuestions(rig.now))
        rig.quiet()
        assertFalse(rig.engine.acceptsQuestions(rig.now))
        rig.clear()
        rig.ask(PassengerIntent.WHERE)
        assertTrue(rig.spoken().isEmpty())
        rig.engine.cancelQuiet()
        assertTrue(rig.engine.acceptsQuestions(rig.now))
        rig.engine.finish(rig.now)
        assertFalse(rig.engine.acceptsQuestions(rig.now))
    }

    @Test
    fun `내비 없는 길에서 P단으로 서 있을 때 언제 도착하느냐고 물어도 아직 가는 길이라고 하지 않는다`() {
        // 결함 2 에서 막은 '도착 부정'이 '언제 도착해?' 대답으로 남아 있었다(검토 NEW-6)
        val rig = Rig()
        rig.signals = rig.signals.copy(nav = NavState())
        rig.startAndDepart()
        rig.clear()
        rig.set { copy(gear = Gear.PARK) }
        rig.advance(2) // P단에 막 둔 때(정차 분류 5초 전)
        rig.ask(PassengerIntent.WHEN)
        assertEquals("stop_parked_neutral", rig.spoken().single().situation)
        rig.advance(12)
        assertEquals("방금 같은 말로 대답했으면 5초 뒤의 정차 문장을 되풀이하지 않는다", 1, rig.spoken().size)
        assertEquals(StopReason.PARKED_UNSURE, rig.snapshot().stopReason)
        rig.ask(PassengerIntent.WHEN) // 정차로 분류된 뒤
        rig.advance(12)
        rig.ask(PassengerIntent.ARRIVED_Q)
        rig.advance(12)
        assertTrue(rig.spoken().map { it.situation }.toString(), rig.spoken().all { it.situation == "stop_parked_neutral" })
        rig.spoken().forEach { assertFalse(it.text, it.text.contains("아직")) }

        // P단에 막 둔 때의 '다시 말씀드리기'와 '왜 안 가?'도 같다(내비가 넉넉히 남았으면 경유지 정차)
        val far = Rig()
        far.startAndDepart()
        far.clear()
        far.set { copy(gear = Gear.PARK) }
        far.repeat()
        far.advance(1)
        assertEquals("stop_waypoint", far.spoken().single().situation)
        val near = Rig()
        near.signals = near.signals.copy(nav = NavState())
        near.startAndDepart()
        near.clear()
        near.set { copy(gear = Gear.PARK) }
        near.ask(PassengerIntent.WHY_STOPPED)
        assertEquals("stop_parked_neutral", near.spoken().single().situation)

        // 달리는 중에는 그대로 구간 말로 대답한다
        val driving = Rig()
        driving.startAndDepart()
        driving.clear()
        driving.ask(PassengerIntent.WHEN)
        assertEquals("ask_when_onway", driving.spoken().single().situation)
    }

    @Test
    fun `서 있을 때 왜 안 가느냐고 물으면 서 있는 이유를 말한다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.set { copy(brakePressed = true) }
        rig.advance(45) // 35초에 신호 대기 안내, 그 말이 끝난 뒤
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
        rig.set { copy(brakePressed = true) }; rig.advance(36)
        rig.set { copy(brakePressed = false, seatWindowOpenPercent = 50) }; rig.advance(7)
        val record = rig.engine.finish(rig.now)
        assertEquals(75, record.durationSec)
        assertEquals(1, record.counts.proactive) // 출발 안내
        assertEquals(1, record.counts.driverRequested)
        assertEquals(1, record.counts.stopNotices)
        assertEquals(1, record.counts.safetyNotices)
        assertEquals(3, record.counts.spokenOnBehalf)
        assertEquals(0, record.counts.skipped)
        assertEquals(Phase.ENDED, rig.snapshot().phase)
        assertTrue(rig.engine.onTick(rig.now + 1000).isEmpty())
    }

    @Test
    fun `소리 내지 못한 말은 횟수에 넣지 않고 건너뜀으로 따로 센다`() {
        // 통화 중이라 오디오 포커스를 못 얻었거나 음성 엔진이 없는 경우
        val rig = Rig(speakResult = false)
        rig.startAndDepart()
        rig.repeat(); rig.advance(12)
        rig.set { copy(brakePressed = true) }; rig.advance(36)
        val said = rig.spoken()
        assertEquals(listOf("intro", "depart", "ask_where", "stop_signal"), said.map { it.situation })
        val counts = rig.engine.finish(rig.now).counts
        assertEquals(0, counts.proactive)
        assertEquals(0, counts.driverRequested)
        assertEquals(0, counts.stopNotices)
        assertEquals("차가 대신 말한 횟수는 실제로 소리 낸 것만", 0, counts.spokenOnBehalf)
        assertEquals(said.size, counts.skipped)
    }

    @Test
    fun `결과를 알려 주지 않은 말은 세지 않는다`() {
        val rig = Rig(speakResult = null)
        rig.startAndDepart()
        rig.repeat(); rig.advance(12)
        assertEquals(TripCounts(), rig.engine.finish(rig.now).counts)
    }

    @Test
    fun `말마다 다른 번호가 붙고 같은 결과를 두 번 알려도 한 번만 센다`() {
        val rig = Rig(speakResult = null)
        rig.startAndDepart()
        rig.repeat(); rig.advance(12)
        val ids = rig.spoken().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val answer = rig.spoken("ask_where").single()
        rig.engine.onUtteranceResult(answer.id, true)
        rig.engine.onUtteranceResult(answer.id, true)
        rig.engine.onUtteranceResult(9_999, true) // 모르는 번호는 버린다
        assertEquals(1, rig.snapshot().counts.driverRequested)
        assertEquals(1, rig.snapshot().counts.spokenOnBehalf)
    }

    @Test
    fun `처음 안내를 소리 내지 못해도 되풀이 안내를 소리 내면 그 일을 한 건으로 센다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.speakResult = false
        rig.set { copy(seatDoorOpen = true) }
        assertEquals(0, rig.snapshot().counts.safetyNotices)
        rig.speakResult = true
        rig.advance(16)
        rig.advance(16)
        assertEquals(3, rig.spoken("door_open_p").size)
        assertEquals(1, rig.snapshot().counts.safetyNotices)
        assertEquals(1, rig.snapshot().counts.skipped)
    }

    @Test
    fun `잠시 조용히 중 화면에만 알린 창문 안내는 횟수에 들지 않는다`() {
        val rig = Rig()
        rig.startAndDepart()
        rig.quiet()
        rig.set { copy(seatWindowOpenPercent = 50) }
        assertEquals("window_open_d", rig.notices().single().situation)
        assertTrue(rig.spoken("window_open_p").isEmpty())
        assertEquals(0, rig.snapshot().counts.safetyNotices)
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
        rig.set { copy(brakePressed = true) }; rig.advance(40)
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
        wait(10) // 내비 표본 하나로는 구간을 바로 바꾸지 않는다
        assertEquals("절반쯤 왔어요", cardLine())
        set { copy(brakePressed = true) }
        wait(36) // 속도 신호가 없어 브레이크로만 보면 35초
        assertEquals("잠깐 멈췄어요", cardLine())
        set { copy(brakePressed = false) }
        assertEquals("절반쯤 왔어요", cardLine())
        set { copy(nav = nav.copy(remainSec = 200)) } // 곧 도착: 따로 없으면 '거의 다 옴' 카드를 쓴다
        wait(10)
        assertEquals("거의 다 왔어요", cardLine())
        set { copy(gear = Gear.PARK, nav = nav.copy(remainSec = 900)) }
        set { copy(driverDoorOpen = true, driverSeatOccupied = false) }
        wait(3) // 좌석 값은 2.5초 그대로일 때 받는다
        assertEquals("따님이 곧 와요", cardLine())
        set { copy(driverSeatOccupied = true, driverDoorOpen = false) }
        wait(3)
        set { copy(gear = Gear.DRIVE, nav = nav.copy(remainSec = 30)) }
        wait(10) // 목적지 근처도 표본 하나로는 믿지 않는다(10초 이어짐)
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
