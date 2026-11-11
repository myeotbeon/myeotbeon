package app.myeotbeonideun

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.signal.DrivingLock
import app.myeotbeonideun.ui.AppViewModel
import app.myeotbeonideun.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 차량 신호 구독을 '화면이 보일 때 또는 동행 중'으로 묶는다(결함 14). 실차 신호는 가짜. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomotiveTestApp::class)
class VehicleLifecycleTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val fake: FakeVehicleSource get() = app.container.vehicleSource as FakeVehicleSource
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `구독 규칙은 차량용 기기에서 화면이 보이거나 동행 중일 때만이다`() {
        assertTrue(DrivingLock.shouldSubscribe(isAutomotive = true, foreground = true, tripActive = false))
        assertTrue(DrivingLock.shouldSubscribe(isAutomotive = true, foreground = false, tripActive = true))
        assertFalse(DrivingLock.shouldSubscribe(isAutomotive = true, foreground = false, tripActive = false))
        assertFalse(DrivingLock.shouldSubscribe(isAutomotive = false, foreground = true, tripActive = true))
    }

    @Test
    fun `화면이 보일 때만 구독하고 백그라운드에서 동행이 없으면 놓는다`() {
        idle(100)
        assertFalse("화면도 동행도 없으면 붙이지 않는다", fake.started)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(100)
        assertTrue(fake.started)

        activity.pause().stop()
        idle(500)
        assertTrue("잠깐 가려진 것은 기다린다(화면을 다시 만들 때 놓았다 붙이지 않게)", fake.started)
        idle(1_000)
        assertFalse(fake.started)
        assertEquals(1, fake.stops)

        activity.start().resume()
        idle(100)
        assertTrue(fake.started)
        assertEquals(2, fake.starts)
        activity.pause().stop().destroy()
    }

    @Test
    fun `동행 중에는 화면이 뒤로 가도 구독을 두고 동행이 끝나면 놓는다`() {
        val c = app.container
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        c.trip.start(TripConfig(place = "큰딸 집"))
        idle(100)
        assertTrue(fake.tripActive)

        activity.pause().stop()
        idle(3_000)
        assertTrue("동행 중에는 놓지 않는다(주행 중 내비가 앞에 있어도 안내를 이어 간다)", fake.started)

        c.trip.end()
        idle(100)
        assertFalse(fake.tripActive)
        assertFalse("동행이 끝났고 화면도 없으면 놓는다", fake.started)
        activity.destroy()
    }

    @Test
    fun `화면 없이 시작한 동행에서도 구독을 붙인다`() {
        val c = app.container
        idle(100)
        assertFalse(fake.started)
        c.trip.start(TripConfig(place = "큰딸 집")) // Gleo 의 "동행 시작"처럼 화면이 뜨기 전에 시작
        idle(100)
        assertTrue(fake.started)
        c.trip.end()
        idle(100)
        assertFalse(fake.started)
    }

    @Test
    fun `속도 권한을 받으면 구독 전체를 놓지 않고 속도만 다시 붙인다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = AppViewModel(app)
        idle(100)
        val starts = fake.starts
        vm.onSpeedPermissionResult(true)
        assertEquals(1, fake.speedRefreshes)
        assertEquals(starts, fake.starts)
        assertEquals(0, fake.stops)
        vm.onSpeedPermissionResult(false)
        assertEquals(1, fake.speedRefreshes)
        activity.pause().stop().destroy()
    }
}
