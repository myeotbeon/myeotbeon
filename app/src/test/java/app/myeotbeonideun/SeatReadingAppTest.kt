package app.myeotbeonideun

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.SeatPosition.DRIVER
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.signal.SeatInterpretation
import app.myeotbeonideun.signal.SeatPolarity
import app.myeotbeonideun.signal.SeatPolarityBasis
import app.myeotbeonideun.signal.SeatReading
import app.myeotbeonideun.ui.AppViewModel
import app.myeotbeonideun.ui.MainActivity
import app.myeotbeonideun.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 좌석 값 해석을 앱 흐름으로 본다(실차 신호는 가짜, 해석은 실제 SeatValueReader).
 * 에뮬레이터처럼 사람이 앉은 자리가 false 로 오는 차에서, 출발 전 설정(P단)이 운전석 기준으로 '반대로'를 정하고
 * 앉은 자리 자동 선택·운전자 이탈 판단이 해석한 값을 쓰는지.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomotiveTestApp::class)
class SeatReadingAppTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val fake: FakeVehicleSource get() = app.container.vehicleSource as FakeVehicleSource
    private fun idle(ms: Long = 100) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** 가상 콘솔 '운전석·뒷좌석 왼쪽 Occupied, 나머지 Empty' → SDK Boolean 은 반대로 온다. */
    private fun emulatorLikeSeats() {
        for (seat in SeatPosition.entries) fake.setRawSeat(seat, !(seat == DRIVER || seat == SeatPosition.REAR_LEFT))
    }

    @Test
    fun `출발 전 설정(P단)에서 운전석 기준으로 판별해 앉은 자리를 해석한 값으로 고른다`() {
        val activity = ActivityScenario.launch(MainActivity::class.java) // 화면이 보인다
        fake.state.value = VehicleState(gear = Gear.PARK, uxRestricted = false)
        emulatorLikeSeats()
        val vm = AppViewModel(app)
        idle()
        assertEquals(SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.DRIVER_SEAT), fake.seatInterpretation.value)
        assertTrue(fake.driverAtScreenNotes > 0)
        // 해석 전이면 빈자리(뒷좌석 오른쪽)를 골랐을 것이다
        assertEquals(listOf(SeatPosition.REAR_LEFT), vm.setup.value.occupiedSeats)
        assertEquals(SeatPosition.REAR_LEFT, vm.setup.value.form.seat)
        assertEquals("좌석 값: 반대로(운전석 기준 자동)", vm.settings.value.seatReadingLine)
        // 개발자 패널: 해석과 SDK 원시 값을 함께 보인다
        assertEquals("좌석 값: 반대로(운전석 기준 자동)", vm.dev.value.seatReadingLine)
        assertTrue(vm.dev.value.rawValuesLine!!, vm.dev.value.rawValuesLine!!.contains("운전석 false"))
        assertTrue(vm.dev.value.rawValuesLine!!.contains("안전띠"))
        activity.close()
    }

    @Test
    fun `주행 중이거나 화면이 뒤에 있으면 판별하지 않는다`() {
        val activity = ActivityScenario.launch(MainActivity::class.java)
        fake.state.value = VehicleState(gear = Gear.DRIVE, uxRestricted = true)
        emulatorLikeSeats()
        AppViewModel(app)
        idle()
        assertEquals(SeatPolarityBasis.NO_DRIVER_VALUE, fake.seatInterpretation.value.basis)

        activity.moveToState(Lifecycle.State.CREATED) // 화면이 뒤로 갔다
        idle(2_000)
        fake.state.value = VehicleState(gear = Gear.PARK, uxRestricted = false)
        emulatorLikeSeats()
        idle()
        assertEquals("화면이 보이지 않으면 운전자가 조작한다고 볼 수 없다", SeatPolarityBasis.NO_DRIVER_VALUE, fake.seatInterpretation.value.basis)
        activity.close()
    }

    @Test
    fun `동행을 시작하면 그 순간 정하고 동행 중에는 바꾸지 않는다`() {
        val activity = ActivityScenario.launch(MainActivity::class.java)
        fake.state.value = VehicleState(gear = Gear.PARK, uxRestricted = false)
        emulatorLikeSeats()
        val vm = AppViewModel(app)
        idle()
        vm.updateForm(vm.setup.value.form.copy(place = "큰딸 집"))
        vm.startTrip()
        idle()
        assertTrue(app.container.trip.inProgress)
        assertTrue(fake.seatInterpretation.value.heldForTrip)
        assertEquals("좌석 값: 반대로(운전석 기준 자동) · 동행 중 고정", vm.settings.value.seatReadingLine)
        assertEquals(SeatPosition.REAR_LEFT, app.container.trip.ui.value!!.config.seat)

        // 운전자가 내렸다(원시 값 true). 출발 전 설정 화면을 열어도 동행 중이라 판별을 바꾸지 않는다.
        vm.navigate(Screen.SETUP)
        fake.setRawSeat(DRIVER, true)
        idle()
        assertEquals(SeatPolarity.INVERTED, fake.seatInterpretation.value.polarity)
        assertEquals("엔진은 운전석을 빈자리로 본다", false, fake.state.value.seatOccupied[DRIVER])

        app.container.trip.end()
        idle()
        assertFalse(fake.seatInterpretation.value.heldForTrip)
        activity.close()
    }

    @Test
    fun `환경설정에서 고른 해석을 바로 쓰고 저장하며 주행 중에는 바꾸지 않는다`() {
        val c = app.container
        val vm = AppViewModel(app)
        emulatorLikeSeats()
        vm.setSeatReading(SeatReading.INVERTED)
        idle()
        assertEquals(SeatReading.INVERTED, c.store.settings.value.seatReading)
        assertEquals(SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.MANUAL), fake.seatInterpretation.value)
        assertEquals("좌석 값: 반대로(직접 고름)", vm.settings.value.seatReadingLine)
        assertEquals(listOf(SeatPosition.REAR_LEFT), fake.state.value.occupiedPassengerSeats())

        fake.state.value = fake.state.value.copy(gear = Gear.DRIVE, uxRestricted = true)
        idle()
        vm.setSeatReading(SeatReading.AS_IS)
        idle()
        assertEquals("주행 중에는 바꾸지 않는다", SeatReading.INVERTED, c.store.settings.value.seatReading)

        fake.state.value = fake.state.value.copy(gear = Gear.PARK, uxRestricted = false)
        idle()
        vm.setSeatReading(SeatReading.AS_IS)
        idle()
        assertEquals(SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.MANUAL), fake.seatInterpretation.value)
        assertEquals("좌석 값: 그대로(직접 고름)", vm.settings.value.seatReadingLine)
    }
}
