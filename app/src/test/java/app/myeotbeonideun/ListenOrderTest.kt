package app.myeotbeonideun

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.intent.PassengerIntent
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.speech.FakeSpeaker
import app.myeotbeonideun.speech.ListenState
import app.myeotbeonideun.speech.PassengerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 부른 순서를 적어 두는 가짜 음성 인식. */
class FakeListener : PassengerListener {
    override val state: StateFlow<ListenState> = MutableStateFlow(ListenState.OFF)
    val calls = ArrayList<String>()
    var onIntent: ((PassengerIntent) -> Unit)? = null
    private var running = false
    private var paused = false

    /** 지금 듣고 있는지(시작했고 멈추지 않았다). */
    val listening: Boolean get() = running && !paused

    override fun start(onIntent: (PassengerIntent) -> Unit) {
        calls += "start"
        this.onIntent = onIntent
        running = true
        paused = false
    }

    override fun pause() {
        calls += "pause"
        // 실제 구현처럼 시작하지 않았으면 멈춤을 무시한다(이 순서 결함이 그래서 생겼다).
        if (running) paused = true
    }

    override fun resume() {
        calls += "resume"
        if (running) paused = false
    }

    override fun stop() {
        calls += "stop"
        running = false
    }
}

/** 가짜 음성 엔진과 가짜 음성 인식으로 도는 앱(시험용). */
class ListeningTestApp : App() {
    val speaker = FakeSpeaker()
    val listener = FakeListener()
    override fun createContainer(): AppContainer =
        AppContainer(this, speakers = listOf(speaker), activeUsages = { emptyList() }, listener = listener)
}

/** 음성 인식은 '말하는 중' 수집보다 먼저 시작해, 소개말 동안 듣지 않는다(결함 9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ListeningTestApp::class)
class ListenOrderTest {
    private val app: ListeningTestApp get() = ApplicationProvider.getApplicationContext()
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `소개말을 하는 동안에는 듣지 않고 끝나면 듣는다`() {
        val c = app.container
        c.store.updateSettings { it.copy(listenEnabled = true) }
        app.speaker.sentenceMs = 2_000
        c.trip.start(TripConfig(place = "큰딸 집"))
        assertEquals("듣기를 먼저 시작하고, 소개말을 말하는 중이라 바로 멈춘다", listOf("start", "pause"), app.listener.calls.take(2))
        assertTrue("소개말 동안은 듣지 않는다", !app.listener.listening)
        idle(8_000)
        assertTrue("소개말이 끝나면 듣는다", app.listener.listening)
        c.trip.end()
        assertEquals("stop", app.listener.calls.last())
    }

    @Test
    fun `알아들은 물음에 대답한다`() {
        val c = app.container
        c.store.updateSettings { it.copy(listenEnabled = true) }
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(15_000)
        app.listener.onIntent!!.invoke(PassengerIntent.WHERE)
        idle(6_000)
        assertEquals(1, c.trip.ui.value!!.snapshot.counts.answered)
        c.trip.end()
    }

    @Test
    fun `꺼져 있으면 듣지 않는다`() {
        app.container.trip.start(TripConfig(place = "큰딸 집"))
        idle(3_000)
        assertTrue(app.listener.calls.none { it == "start" })
        app.container.trip.end()
    }
}
