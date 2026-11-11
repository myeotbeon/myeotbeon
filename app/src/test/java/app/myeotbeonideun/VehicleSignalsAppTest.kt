package app.myeotbeonideun

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.gleo.Gleo
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.signal.Capabilities
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.signal.SourceStatus
import app.myeotbeonideun.ui.AppViewModel
import app.myeotbeonideun.ui.MainActivity
import app.myeotbeonideun.ui.Screen
import app.myeotbeonideun.ui.SpeedAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 차량용 기기처럼 띄운 앱에서 실차 신호(가짜)와 화면 잠금·신호 출처를 본다(결함 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomotiveTestApp::class)
class VehicleSourceChoiceTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val fake: FakeVehicleSource get() = app.container.vehicleSource as FakeVehicleSource
    private fun idle(seconds: Long = 1) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test
    fun `차량용 기기에서는 차량 신호를 하나도 못 얻어도 체험 모드로 넘어가지 않는다`() {
        val c = app.container
        fake.status.value = SourceStatus.UNAVAILABLE
        idle()
        assertSame(fake, c.source.value)
        assertEquals(SourceKind.VEHICLE, c.source.value.kind)

        val vm = AppViewModel(app)
        idle()
        assertEquals(SourceStatus.UNAVAILABLE, vm.settings.value.vehicleStatus)
        assertFalse(vm.shell.value.demo)

        // 그 상태로 동행을 시작하면 차량 신호로 돌고, 운전자에게 '차량 신호 없음'을 한 줄로 알린다
        // (시험 환경에는 음성 엔진이 없어 그 알림이 앞서면 그것이 보인다)
        c.trip.start(TripConfig(place = "큰딸 집"))
        idle()
        assertEquals(SourceKind.VEHICLE, c.trip.ui.value!!.sourceKind)
        val expected = if (c.speech.engineMissing.value) "음성 엔진이 없어 화면으로만 알려요" else "차량 신호가 없어 먼저 말하지 않아요"
        assertEquals(expected, vm.companion.value.notice)
        assertEquals(R.string.companion_no_signal, AppViewModel.statusNoticeRes(noEngine = false, noSignal = true, speechSkipped = true))
        assertEquals(R.string.companion_no_engine, AppViewModel.statusNoticeRes(noEngine = true, noSignal = true, speechSkipped = false))
        assertEquals(null, AppViewModel.statusNoticeRes(noEngine = false, noSignal = false, speechSkipped = false))
        c.trip.end()

        // 신호가 돌아오면 그대로 차량 신호로 돈다
        fake.capabilities.value = Capabilities(gear = true, uxRestrictions = true)
        fake.status.value = SourceStatus.READY
        idle()
        assertEquals(SourceStatus.READY, vm.settings.value.vehicleStatus)
    }

    @Test
    fun `체험 모드는 환경설정에서 켰을 때만이고 그때도 실차 주행 규제를 지킨다`() {
        val c = app.container
        c.store.updateSettings { it.copy(demoMode = true) }
        idle()
        assertEquals(SourceKind.DEMO, c.source.value.kind)
        assertFalse(c.drivingRestricted.value)

        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = true)
        idle()
        assertTrue("모의 신호는 P단이어도 실차가 달리면 잠근다", c.drivingRestricted.value)
        assertTrue(c.vehicleRestricted.value)
        assertTrue(c.isDrivingRestrictedNow())

        // CarUxRestrictions 를 못 받는 차는 변속단으로 본다
        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = null)
        idle()
        assertTrue(c.drivingRestricted.value)

        fake.state.value = VehicleState(gear = Gear.PARK, uxRestricted = false)
        idle()
        assertFalse(c.drivingRestricted.value)
        assertFalse(c.isDrivingRestrictedNow())
    }

    @Test
    fun `속도 권한이 없으면 첫 실행 안내에서 한 번 권하고 거절하면 다시 조르지 않는다`() {
        val vm = AppViewModel(app)
        idle()
        assertEquals(SpeedAsk.SUGGEST, vm.shell.value.speedAsk)
        vm.onSpeedPermissionResult(false)
        idle()
        assertEquals(SpeedAsk.DECLINED, vm.shell.value.speedAsk)
        assertEquals(0, fake.speedRefreshes)
        vm.onSpeedPermissionResult(true) // 환경설정에서 나중에 허용
        idle()
        assertEquals(SpeedAsk.HIDDEN, vm.shell.value.speedAsk)
        assertEquals(1, fake.speedRefreshes)
    }

    @Test
    fun `Gleo 의 동행 시작은 실차가 주행 중이면 체험 모드여도 받지 않는다`() {
        val c = app.container
        c.store.updateSettings { it.copy(demoMode = true) }
        c.store.saveRoute(TripConfig(place = "큰딸 집"), rememberArrival = false, nowMs = 1_000)
        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = true)
        idle()
        assertFalse("화면도 동행도 없어 아직 구독 전", fake.started)
        assertEquals(Gleo.StartResult.DRIVING, Gleo.handleStart(app, c, JSONObject()))
        assertTrue("화면 밖 호출이라 차량 신호를 먼저 붙여 지금 값을 본다", fake.started)
        assertFalse(c.trip.inProgress)
    }

    @Test
    fun `실차 주행 중 Gleo 로 동행 시작을 부르면 시작하지 않고 동행 화면에서 기다린다`() {
        c().store.saveRoute(TripConfig(place = "큰딸 집"), rememberArrival = false, nowMs = 1_000)
        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent(Gleo.ACTION_START)).setup()
        idle()
        assertFalse(c().trip.inProgress)
        val vm = ViewModelProvider(activity.get())[AppViewModel::class.java]
        assertEquals(Screen.COMPANION, vm.shell.value.screen)
        assertEquals("차를 세운 뒤 출발 전 설정에서 시작하세요 안내가 보이는 화면", false, vm.companion.value.active)
        assertTrue(vm.companion.value.restricted)
        activity.pause().stop().destroy()
    }

    private fun c() = app.container

    @Test
    fun `실차 주행 중에는 체험 모드의 개발자 패널도 잠긴다`() {
        val c = app.container
        c.store.updateSettings { it.copy(demoMode = true) }
        val vm = AppViewModel(app)
        vm.navigate(Screen.DEV)
        // 모의 주행(체험 모드 D)만으로는 개발자 패널을 잠그지 않는다
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle()
        assertTrue(vm.shell.value.restricted)
        assertFalse(vm.shell.value.locked)
        // 실차가 달리면 잠근다
        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = true)
        idle()
        assertTrue(vm.shell.value.locked)
    }
}

/** 실제 액티비티로 그려, 체험 모드 + 실차 규제에서 설정 화면 대신 잠금 안내가 그려지는지 본다(결함 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomotiveTestApp::class, qualifiers = "w1700dp-h1212dp-land-mdpi")
class DemoModeVehicleLockTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: App get() = compose.activity.application as App
    private val fake: FakeVehicleSource get() = app.container.vehicleSource as FakeVehicleSource

    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
        }
    }

    @Test
    fun `체험 모드 + 실차 규제 on 이면 설정 화면이 잠긴다`() {
        app.container.store.updateSettings { it.copy(demoMode = true, noticeAccepted = true) }
        fake.state.value = VehicleState(gear = Gear.PARK, uxRestricted = false)
        settle()
        compose.onNodeWithText("설정").performClick()
        settle()
        compose.onNodeWithText("환경설정").assertIsDisplayed()

        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = true)
        settle()
        compose.onNodeWithText("주행 중에는 쓸 수 없어요").assertIsDisplayed()
        compose.onAllNodesWithText("환경설정").assertCountEquals(0)
        compose.onAllNodesWithText("체험 모드").assertCountEquals(1) // 체험 모드 표시는 그대로

        fake.state.value = VehicleState(gear = Gear.PARK, uxRestricted = false)
        settle()
        compose.onNodeWithText("환경설정").assertIsDisplayed()
    }
}
