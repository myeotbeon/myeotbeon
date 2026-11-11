package app.myeotbeonideun

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.gleo.Gleo
import app.myeotbeonideun.gleo.GleoActionReceiver
import org.json.JSONObject
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
 * 권한 없이 열린 Gleo 리시버·'동행 시작'의 위험 줄이기(결함 18): 연속 호출 제한, 응답 방송에 가는 곳 이름을 싣지 않기.
 * 가짜 음성 엔진이라 말한 횟수가 기록에 든다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SpeakingTestApp::class)
class GleoGuardTest {
    private val app: SpeakingTestApp get() = ApplicationProvider.getApplicationContext()
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun responses() = shadowOf(app).broadcastIntents
        .filter { it.action == "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" }
        .map { JSONObject(it.getStringExtra("response")!!) }

    @Test
    fun `다른 앱이 다시 말하기를 쉬지 않고 불러도 4초에 한 번만 말한다`() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(20_000)
        val before = responses().size
        val receiver = GleoActionReceiver()
        repeat(10) {
            receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT))
            idle(200)
        }
        val sent = responses().drop(before)
        assertEquals(10, sent.size)
        assertEquals("2초 안의 열 번 가운데 하나만 받는다", 1, sent.count { it.getString("status") == Gleo.STATUS_SUCCESS })
        assertTrue(sent.filter { it.getString("status") == Gleo.STATUS_FAIL }.all { it.getString("message").contains("Too many") })

        idle(4_000)
        receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT))
        assertEquals(Gleo.STATUS_SUCCESS, responses().last().getString("status"))
        idle(8_000)
        assertEquals("말한 것은 받아들인 두 번뿐", 2, c.trip.ui.value!!.snapshot.counts.driverRequested)
        c.trip.end()
    }

    @Test
    fun `동행이 없을 때의 거절은 제한에 세지 않는다`() {
        val receiver = GleoActionReceiver()
        repeat(5) { receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT)) }
        assertTrue(responses().all { it.getString("message") == "No companion trip is in progress." })
        app.container.trip.start(TripConfig(place = "큰딸 집"))
        idle(1_000)
        receiver.onReceive(app, Intent(Gleo.ACTION_REPEAT))
        assertEquals(Gleo.STATUS_SUCCESS, responses().last().getString("status"))
        app.container.trip.end()
    }

    @Test
    fun `주행 중이라 거절한 다 왔다고 말하기는 제한에 세지 않는다`() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(20_000)
        val receiver = GleoActionReceiver()
        val arrived = Intent(Gleo.ACTION_REPEAT).putExtra(Gleo.EXTRA_PARAM, """{"arrived":true}""")
        repeat(4) { // 4초 넘는 간격으로 네 번: 모두 주행 중이라 거절
            receiver.onReceive(app, arrived)
            assertTrue(responses().last().getString("message").contains("parked"))
            idle(4_100)
        }
        c.demoSource.update { it.copy(gear = Gear.PARK) }
        idle(8_000)
        receiver.onReceive(app, arrived)
        assertEquals("거절된 네 번은 1분에 네 번 제한을 쓰지 않았다", Gleo.STATUS_SUCCESS, responses().last().getString("status"))
        idle(10_000)
        assertFalse("도착 안내 뒤 동행을 마쳤다", c.trip.inProgress)
    }

    @Test
    fun `동행 시작은 짧은 사이에 거듭 받지 않고 응답에 가는 곳 이름을 싣지 않는다`() {
        val c = app.container
        c.store.saveRoute(TripConfig(place = "서울내과"), rememberArrival = false, nowMs = 1_000)
        assertEquals(Gleo.StartResult.STARTED, Gleo.handleStart(app, c, JSONObject()))
        val started = responses().last()
        assertEquals(Gleo.STATUS_SUCCESS, started.getString("status"))
        assertFalse("기기 안의 다른 앱도 받을 수 있는 방송이라 가는 곳을 싣지 않는다", started.has("data"))
        assertFalse(started.toString().contains("서울내과"))
        c.trip.end()

        idle(2_000)
        assertEquals(Gleo.StartResult.TOO_MANY, Gleo.handleStart(app, c, JSONObject()))
        assertFalse(c.trip.inProgress)
        idle(10_000)
        assertEquals(Gleo.StartResult.STARTED, Gleo.handleStart(app, c, JSONObject()))
        c.trip.end()
    }

    @Test
    fun `잠시 조용히도 거듭 부르면 받지 않는다`() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집"))
        idle(1_000)
        val receiver = GleoActionReceiver()
        receiver.onReceive(app, Intent(Gleo.ACTION_PAUSE).putExtra(Gleo.EXTRA_PARAM, """{"minutes":5}"""))
        receiver.onReceive(app, Intent(Gleo.ACTION_PAUSE).putExtra(Gleo.EXTRA_PARAM, """{"minutes":60}"""))
        val last = responses().last()
        assertEquals(Gleo.STATUS_FAIL, last.getString("status"))
        val quietLeft = c.trip.ui.value!!.snapshot.quietUntilMs!! - c.trip.ui.value!!.nowMs
        assertTrue("처음 받은 5분 그대로", quietLeft <= 5 * 60_000L)
        c.trip.end()
    }
}
