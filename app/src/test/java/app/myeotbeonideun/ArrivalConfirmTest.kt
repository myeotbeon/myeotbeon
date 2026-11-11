package app.myeotbeonideun

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.Phase
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.stop.StopReason
import app.myeotbeonideun.gleo.Gleo
import app.myeotbeonideun.gleo.GleoActionReceiver
import app.myeotbeonideun.service.ArrivalConfirm
import app.myeotbeonideun.signal.GeoPoint
import app.myeotbeonideun.signal.NavEvent
import app.myeotbeonideun.ui.AppViewModel
import app.myeotbeonideun.ui.Screen
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

/**
 * 결함 2(앱 쪽): 내비 없이 간 길에서 운전자가 '도착했어요'로 도착을 알린다.
 * 가짜 음성 엔진으로 도는 앱이라 도착 안내가 실제로 '소리 난' 것으로 돌아온다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SpeakingTestApp::class)
class ArrivalConfirmTest {
    private val app: SpeakingTestApp get() = ApplicationProvider.getApplicationContext()
    private fun snapshot() = app.container.trip.ui.value!!.snapshot

    /** 내비 남은 시간이 없는 길: 차는 도착을 스스로 알지 못한다. */
    private fun driveWithoutNavi(place: String = "주간보호센터") {
        val c = app.container
        c.demoSource.setNav { it.copy(remainSec = null, totalSec = null, remainMeters = null) }
        c.trip.start(TripConfig(place = place))
        idle(12)
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(30)
    }

    @Test
    fun `정차 중 도착했어요를 누르면 도착을 말하고 그 안내가 끝나면 동행을 마친다`() {
        val c = app.container
        val vm = AppViewModel(app)
        driveWithoutNavi()
        assertFalse("주행 중에는 보이지 않는다", vm.companion.value.canConfirmArrival)
        assertFalse(vm.companion.value.canEnd)
        assertEquals(ArrivalConfirm.NOT_PARKED, c.trip.confirmArrival())
        assertEquals(Phase.DRIVING, snapshot().phase)

        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(8)
        assertEquals("도착을 부정하지 않는 정차", StopReason.PARKED_UNSURE, snapshot().stopReason)
        assertTrue(vm.companion.value.canConfirmArrival)
        assertTrue(vm.companion.value.canEnd)

        val saidBefore = app.speaker.said.size
        vm.confirmArrival()
        idle(1)
        assertEquals(Phase.ARRIVED, snapshot().phase)
        assertEquals("도착 안내가 끝나면 동행을 마쳐요", vm.companion.value.status)
        assertFalse("한 번 누르면 사라진다", vm.companion.value.canConfirmArrival)
        idle(8)
        assertFalse("도착 안내가 끝나서 동행을 마쳤다", c.trip.inProgress)
        assertTrue(app.speaker.said.drop(saidBefore).first().contains("주간보호센터"))
        assertEquals(Screen.RECORDS, vm.shell.value.screen)
        val counts = c.store.records.value.single().counts
        assertEquals("정차 안내와 도착 안내", 2, counts.stopNotices)
    }

    @Test
    fun `출발 전과 동행이 없을 때는 받지 않는다`() {
        val c = app.container
        assertEquals(ArrivalConfirm.NO_TRIP, c.trip.confirmArrival())
        c.trip.start(TripConfig(place = "큰딸 집"))
        idle(3)
        assertEquals(ArrivalConfirm.NOT_DEPARTED, c.trip.confirmArrival())
        assertEquals(Phase.READY, snapshot().phase)
        c.trip.end()
    }

    @Test
    fun `이미 도착으로 봤으면 도착 안내를 한 번 더 하고 마친다`() {
        val c = app.container
        driveWithoutNavi()
        c.demoSource.emitArrived() // 내비 도착 이벤트
        idle(1)
        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(10)
        assertEquals(Phase.ARRIVED, snapshot().phase)
        val saidBefore = app.speaker.said.size
        assertEquals(ArrivalConfirm.ALREADY_ARRIVED, c.trip.confirmArrival())
        idle(10)
        assertFalse(c.trip.inProgress)
        assertTrue(app.speaker.said.drop(saidBefore).first().contains("주간보호센터"))
    }

    @Test
    fun `Gleo 로 다 왔다고 말씀드려 달라면 같은 일을 하고 주행 중에는 거절한다`() {
        val c = app.container
        val receiver = GleoActionReceiver()
        val arrived = Intent(Gleo.ACTION_REPEAT).putExtra(Gleo.EXTRA_PARAM, """{"arrived":true}""")

        receiver.onReceive(app, arrived)
        assertEquals(Gleo.STATUS_FAIL, lastGleoResponse().getString("status"))

        driveWithoutNavi()
        receiver.onReceive(app, arrived)
        val refused = lastGleoResponse()
        assertEquals(Gleo.STATUS_FAIL, refused.getString("status"))
        assertTrue(refused.getString("message").contains("parked"))
        assertEquals(Phase.DRIVING, snapshot().phase)

        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(8)
        receiver.onReceive(app, arrived)
        val ok = lastGleoResponse()
        assertEquals(Gleo.STATUS_SUCCESS, ok.getString("status"))
        assertEquals("앱이 직접 말하므로 Gleo 는 말하지 않는다", Gleo.MODE_SILENT, ok.getString("mode"))
        assertEquals(Phase.ARRIVED, snapshot().phase)
        idle(10)
        assertFalse(c.trip.inProgress)
    }

    @Test
    fun `운전자와 동승자가 함께 내린 차가 10분 비어 있으면 동행을 스스로 마치고 기록을 남긴다`() {
        // 내비 없이 간 길이라 차는 도착을 모르고, 운전자는 '도착했어요'를 누르지 않고 어르신을 모시고 들어갔다(검토 NEW-2)
        val c = app.container
        driveWithoutNavi()
        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(8)
        c.demoSource.update {
            it.copy(
                doorOpen = it.doorOpen + (SeatPosition.DRIVER to true),
                seatOccupied = it.seatOccupied + (SeatPosition.DRIVER to false),
            )
        }
        idle(20)
        c.demoSource.update { it.copy(seatOccupied = it.seatOccupied + (SeatPosition.REAR_RIGHT to false)) }
        idle(5) // 좌석 값 완충 2.5초 뒤 자리가 빈 때의 안심 말 한 번이 끝날 때까지
        assertTrue("차가 비었다", snapshot().vacantSinceMs != null)
        val saidWhileVacant = app.speaker.said.size
        idle(9 * 60)
        assertTrue("10분이 되기 전에는 그대로", c.trip.inProgress)
        idle(60)
        assertFalse("빈 차가 10분이면 동행을 마친다", c.trip.inProgress)
        assertEquals("빈 차에는 말하지 않았다", saidWhileVacant, app.speaker.said.size)
        assertEquals("기록은 남긴다", 1, c.store.records.value.size)
    }

    @Test
    fun `arrived 가 없거나 false 면 그냥 다시 말씀드리기다`() {
        val c = app.container
        driveWithoutNavi()
        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(8)
        val receiver = GleoActionReceiver()
        receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT).putExtra(Gleo.EXTRA_PARAM, """{"arrived":false}"""))
        receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT))
        idle(12)
        assertEquals(Phase.DRIVING, snapshot().phase)
        assertTrue(c.trip.inProgress)
        c.trip.end()
    }

    private fun lastGleoResponse(): JSONObject {
        val sent = shadowOf(app).broadcastIntents.last { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
        return JSONObject(sent.getStringExtra("response")!!)
    }
}

/** 음성 엔진이 없어도(시험 환경 그대로) '도착했어요' 뒤에 동행을 마친다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArrivalWithoutEngineTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `도착 안내를 소리 내지 못해도 결과가 오면 동행을 마친다`() {
        val c = app.container
        c.demoSource.setNav { it.copy(remainSec = null, totalSec = null, remainMeters = null) }
        c.trip.start(TripConfig(place = "주간보호센터"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(30)
        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(8)
        assertEquals(ArrivalConfirm.CONFIRMED, c.trip.confirmArrival())
        idle(15)
        assertFalse(c.trip.inProgress)
        assertTrue(c.store.records.value.single().counts.skipped >= 1)
    }
}

/**
 * '도착 지점 기억하기'를 켠 길: 운전자가 알린 도착은 그 자리를 저장(이미 있어도 새로), 차가 스스로 본 도착은
 * 처음 한 번만, 도착을 거두면 그 도착에서 저장한 지점을 되돌린다. 위치는 가짜 실차 신호가 준다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomotiveTestApp::class)
class ArrivalPointTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val fake: FakeVehicleSource get() = app.container.vehicleSource as FakeVehicleSource
    private fun route() = app.container.store.routes.value.single()
    private fun snapshot() = app.container.trip.ui.value!!.snapshot

    private fun gear(g: Gear) = fake.state.update { it.copy(gear = g, uxRestricted = g != Gear.PARK) }

    private fun startAndDrive() {
        gear(Gear.PARK)
        app.container.trip.start(TripConfig(place = "서울내과"), rememberArrival = true)
        gear(Gear.DRIVE)
        idle(30)
    }

    @Test
    fun `운전자가 알린 도착은 그 자리를 도착 지점으로 저장하고 다음에도 새로 저장한다`() {
        val c = app.container
        startAndDrive()
        gear(Gear.PARK)
        idle(8)
        fake.location = GeoPoint(37.402, 127.108)
        assertEquals(ArrivalConfirm.CONFIRMED, c.trip.confirmArrival())
        assertEquals(37.402, route().arrivalLat!!, 1e-9)
        assertEquals(127.108, route().arrivalLon!!, 1e-9)
        idle(15)
        assertFalse(c.trip.inProgress)

        // 다음에 다른 자리에서 알리면 그 자리로(운전자의 말이 가장 확실하다)
        startAndDrive()
        gear(Gear.PARK)
        idle(8)
        fake.location = GeoPoint(37.410, 127.120)
        c.trip.confirmArrival()
        assertEquals(37.410, route().arrivalLat!!, 1e-9)
        idle(15)
    }

    @Test
    fun `차가 스스로 본 도착은 처음 한 번만 저장하고 도착을 거두면 되돌린다`() {
        val c = app.container
        startAndDrive()
        fake.location = GeoPoint(37.300, 127.000) // 목적지 앞 약국
        fake.navEvents.tryEmit(NavEvent.Arrived)
        idle(1)
        assertEquals(Phase.ARRIVED, snapshot().phase)
        assertEquals(37.300, route().arrivalLat!!, 1e-9)

        // 약국이었다: P단을 거쳐 다시 달리면 도착을 거두고, 저장한 지점도 되돌린다
        gear(Gear.PARK)
        idle(3)
        gear(Gear.DRIVE)
        idle(20)
        assertEquals(Phase.DRIVING, snapshot().phase)
        assertNull(route().arrivalLat)
        assertNull(route().arrivalLon)
        c.trip.end()

        // 이미 기억한 지점이 있으면 차가 스스로 본 도착으로는 덮어쓰지 않는다
        c.store.setArrivalPoint(route().id, 37.402, 127.108)
        startAndDrive()
        fake.location = GeoPoint(37.500, 127.500)
        fake.navEvents.tryEmit(NavEvent.Arrived)
        idle(1)
        assertEquals(37.402, route().arrivalLat!!, 1e-9)
        c.trip.end()
    }

    @Test
    fun `기억하기를 켜지 않은 길은 위치를 묻지 않는다`() {
        val c = app.container
        gear(Gear.PARK)
        c.trip.start(TripConfig(place = "서울내과"), rememberArrival = false)
        gear(Gear.DRIVE)
        idle(30)
        gear(Gear.PARK)
        idle(8)
        fake.location = GeoPoint(37.402, 127.108)
        c.trip.confirmArrival()
        assertEquals(0, fake.locationRequests)
        assertNull(route().arrivalLat)
        idle(15)
    }
}
