package app.myeotbeonideun

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.Phase
import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.stop.StopReason
import app.myeotbeonideun.gleo.Gleo
import app.myeotbeonideun.gleo.GleoActionReceiver
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.ui.AppViewModel
import app.myeotbeonideun.ui.MainActivity
import app.myeotbeonideun.ui.Screen
import app.myeotbeonideun.ui.SpeedAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 앱 전체를 실제 Application 으로 띄워 보는 시험(Robolectric).
 * 차량 서비스가 없는 환경이므로 체험 모드(모의 신호)로 돈다 — 신호 출처만 다르고 엔진·진행·저장은 같은 코드다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppFlowTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    private fun snapshot() = app.container.trip.ui.value!!.snapshot

    @Test
    fun `차량 서비스가 없는 기기에서는 체험 모드로 돈다`() {
        assertFalse(app.container.isAutomotive)
        assertEquals(SourceKind.DEMO, app.container.source.value.kind)
        assertFalse(app.container.drivingRestricted.value)
        // 차량용 기기가 아니면 속도 권한을 권하지 않는다
        assertEquals(SpeedAsk.HIDDEN, AppViewModel(app).shell.value.speedAsk)
    }

    @Test
    fun `대본 파일을 읽을 수 있다`() {
        assertNotNull(app.container.script.situation("intro"))
    }

    @Test
    fun `출발부터 도착까지 신호가 엔진과 화면 상태와 기록으로 이어진다`() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집", purpose = Purpose.MEAL))
        idle(1)
        assertEquals(Phase.READY, snapshot().phase)
        assertTrue(snapshot().lastLine!!.startsWith("어머니,"))
        assertEquals("큰딸 집", c.store.routes.value.single().config.place) // '자주 가는 길'로 저장

        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(25)
        assertEquals(Phase.DRIVING, snapshot().phase)
        assertTrue("주행 단이면 주행 규제 중", c.drivingRestricted.value)
        assertTrue(snapshot().lastLine!!.contains("큰딸 집"))

        assertTrue(c.trip.repeat())
        idle(12)
        // 시험 환경(Robolectric)에는 음성 엔진이 없어 말이 모두 '소리 내지 못함'으로 돌아온다.
        // 기록은 실제로 소리 낸 말만 세므로 '다시 말씀드리기'는 횟수에 들지 않고 건너뜀으로 센다.
        assertTrue(c.speech.engineMissing.value)
        assertEquals(0, snapshot().counts.driverRequested)
        assertTrue(snapshot().counts.skipped >= 3) // 소개, 출발, 다시 말씀드리기

        // 모의 주행: 남은 시간이 스스로 줄어 구간이 바뀐다
        idle(70)
        assertTrue(c.demoSource.state.value.nav.remainSec!! < 720)
        assertTrue(snapshot().stage.ordinal >= Stage.ON_WAY.ordinal)

        c.demoSource.update { it.copy(brakePressed = true) }
        idle(22)
        assertTrue(snapshot().stopReason == StopReason.SIGNAL_WAIT)
        c.demoSource.update { it.copy(brakePressed = false) }

        assertTrue(c.trip.toggleQuiet())
        assertNotNull(snapshot().quietUntilMs)
        assertTrue(c.trip.toggleQuiet())
        assertNull(snapshot().quietUntilMs)

        c.demoSource.setNav { it.copy(remainSec = 20, remainMeters = 60) }
        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(2)
        assertEquals(Phase.ARRIVED, snapshot().phase)
        assertFalse(c.drivingRestricted.value)

        c.trip.end()
        idle(1)
        assertNull(c.trip.ui.value)
        val record = c.store.records.value.single()
        assertEquals("소리 내지 못한 말은 '차가 대신 말한 횟수'에 넣지 않는다", 0, record.counts.spokenOnBehalf)
        assertTrue(record.counts.skipped >= 4)
        assertTrue(record.durationSec >= 120)
    }

    @Test
    fun `도착한 뒤 10분이 지나면 동행을 스스로 끝낸다`() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(30)
        c.demoSource.emitArrived()
        idle(2)
        assertEquals(Phase.ARRIVED, snapshot().phase)
        idle(11 * 60)
        assertFalse(c.trip.inProgress)
    }

    @Test
    fun `도착 뒤 다시 달려 도착을 거두면 도착 뒤 자동 종료도 풀린다`() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(30)
        c.demoSource.setNav { it.copy(remainSec = 40, remainMeters = 100) }
        c.demoSource.update { it.copy(gear = Gear.PARK) } // 목적지 앞 약국에 잠깐 섰다
        idle(2)
        assertEquals(Phase.ARRIVED, snapshot().phase)
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(20)
        assertEquals(Phase.DRIVING, snapshot().phase)
        idle(11 * 60)
        assertTrue("다시 달리는 동안에는 스스로 끝내지 않는다", c.trip.inProgress)
        c.trip.end()
    }

    @Test
    fun `Gleo 의 다시 말씀드리기와 잠시 조용히를 브로드캐스트로 받는다`() {
        val c = app.container
        val receiver = GleoActionReceiver()
        // 동행 중이 아니면 아무 일도 하지 않고 실패를 돌려준다
        receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT))
        assertEquals(Gleo.STATUS_FAIL, lastGleoResponse().getString("status"))

        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(25)
        val skippedBefore = snapshot().counts.skipped
        receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT))
        idle(1)
        // 다시 말하기가 실행됐다. 시험 환경에는 음성 엔진이 없어 횟수가 아니라 건너뜀으로 센다.
        assertEquals(skippedBefore + 1, snapshot().counts.skipped)
        assertEquals(0, snapshot().counts.driverRequested)
        assertEquals("silent", lastGleoResponse().getString("mode")) // 앱이 직접 말하므로 Gleo 는 말하지 않는다

        receiver.onReceive(app, Intent(Gleo.ACTION_PAUSE).putExtra(Gleo.EXTRA_PARAM, """{"minutes":5}"""))
        val quietUntil = snapshot().quietUntilMs!!
        assertTrue(quietUntil - c.trip.ui.value!!.nowMs in 4 * 60_000L..5 * 60_000L)
        assertEquals(Gleo.STATUS_SUCCESS, lastGleoResponse().getString("status"))
        c.trip.end()
    }

    @Test
    fun `Gleo 의 동행 시작은 주행 중이면 받지 않고 이유를 돌려준다`() {
        val c = app.container
        c.store.saveRoute(TripConfig(place = "큰딸 집"), rememberArrival = false, nowMs = 1_000)
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(1)
        assertEquals(Gleo.StartResult.DRIVING, Gleo.handleStart(app, c, org.json.JSONObject()))
        assertFalse(c.trip.inProgress)
        val refused = lastGleoResponse()
        assertEquals(Gleo.STATUS_FAIL, refused.getString("status"))
        assertTrue(refused.getString("message").contains("parked"))

        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(1)
        assertEquals(Gleo.StartResult.STARTED, Gleo.handleStart(app, c, org.json.JSONObject()))
        assertTrue(c.trip.inProgress)
        assertEquals(Gleo.StartResult.ALREADY_RUNNING, Gleo.handleStart(app, c, org.json.JSONObject()))
        c.trip.end()

        c.store.clearAll()
        assertEquals(Gleo.StartResult.NEED_SETUP, Gleo.handleStart(app, c, org.json.JSONObject()))
        assertEquals(Gleo.STATUS_ASKING, lastGleoResponse().getString("status"))
    }

    @Test
    fun `주행 규제가 켜지면 화면 틀이 정차 전용 화면을 잠근다`() {
        val c = app.container
        val vm = AppViewModel(app)
        idle(1)
        assertEquals(Screen.SETUP, vm.shell.value.screen)
        assertFalse(vm.shell.value.restricted)
        assertEquals("큰딸 집", vm.setup.value.form.place) // 내비 목적지로 미리 채움
        assertEquals(SeatPosition.REAR_RIGHT, vm.setup.value.form.seat) // 좌석 신호로 자리 선택

        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(1)
        assertTrue(vm.shell.value.restricted)
        assertTrue(vm.shell.value.screen.parkedOnly)
        assertTrue(vm.companion.value.restricted)

        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(1)
        assertFalse(vm.shell.value.restricted)

        vm.startTrip()
        idle(1)
        assertEquals(Screen.COMPANION, vm.shell.value.screen)
        assertTrue(vm.companion.value.active)
        assertEquals("어머니 · 뒷좌석 오른쪽", vm.companion.value.who)
        assertEquals("출발 전", vm.companion.value.stage)
        assertTrue(vm.companion.value.canEnd)
        vm.endTrip()
        idle(1)
        assertEquals(Screen.RECORDS, vm.shell.value.screen)
    }

    private fun lastGleoResponse(): org.json.JSONObject {
        val sent = shadowOf(app).broadcastIntents.last { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
        assertEquals(app.packageName, sent.getStringExtra("packageName"))
        return org.json.JSONObject(sent.getStringExtra("response")!!)
    }
}

/** 화면을 실제 액티비티로 띄워 눌러 본다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1700dp-h1212dp-land-mdpi")
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** 처음 실행 안내는 한 번 확인하면 다시 나오지 않는다. */
    private fun acceptNoticeIfShown() {
        if (compose.onAllNodesWithText("확인했어요").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("확인했어요").performClick()
        }
    }

    /** 신호가 화면까지 전해지게 한다: 시험 환경에서는 메인 루퍼와 화면 그리기를 손으로 돌려야 한다. */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
        }
    }

    @Test
    fun `처음 안내를 확인하고 동행을 시작하면 동행 화면이 나온다`() {
        acceptNoticeIfShown()
        compose.onNodeWithText("출발 전 10초 설정").assertIsDisplayed()
        // 체험 모드의 내비 목적지(큰딸 집)와 좌석 신호로 빈칸이 채워져 있어 바로 시작할 수 있다
        compose.onNodeWithText("동행 시작").assertIsDisplayed().performClick()
        compose.onNodeWithText("다시 말씀드리기").assertIsDisplayed()
        compose.onNodeWithText("큰딸 집").assertIsDisplayed()
        compose.onNodeWithText("체험 모드").assertIsDisplayed()

        val app = compose.activity.application as App
        assertTrue(app.container.trip.inProgress)
        compose.onNodeWithText("다시 말씀드리기").performClick()
        compose.onNodeWithText("동행 끝내기").performClick()
        assertFalse(app.container.trip.inProgress)
        compose.onNodeWithText("오늘의 기록").assertIsDisplayed()
    }
}
