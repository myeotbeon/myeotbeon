package app.myeotbeonideun

import android.media.AudioAttributes
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.speech.FakeSpeaker
import app.myeotbeonideun.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 가짜 음성 엔진으로 도는 앱(시험용). 말이 실제로 '소리 난' 것으로 돌아온다. 재생 중인 소리의 용도는 [playing]. */
class SpeakingTestApp : App() {
    val speaker = FakeSpeaker()
    var playing: List<Int> = emptyList()
    override fun createContainer(): AppContainer = AppContainer(this, speakers = listOf(speaker), activeUsages = { playing })
}

/**
 * 말마다의 결과가 엔진의 기록과 운전자 화면까지 가는지(결함 8·10). 체험 모드 신호 + 가짜 음성 엔진.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SpeakingTestApp::class)
class SpeechFlowTest {
    private val app: SpeakingTestApp get() = ApplicationProvider.getApplicationContext()
    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    private fun snapshot() = app.container.trip.ui.value!!.snapshot

    /** 내비 남은 시간 없이(구간이 스스로 바뀌지 않게) 동행을 시작하고 출발한다. */
    private fun startDriving() {
        val c = app.container
        c.demoSource.setNav { it.copy(remainSec = null, totalSec = null, remainMeters = null) }
        c.trip.start(TripConfig(place = "큰딸 집"))
        idle(12)
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(12)
    }

    @Test
    fun `소리 낸 말만 기록에 들고 내비 안내에 막힌 말은 건너뜀으로 세며 그 까닭과 말을 화면에 보인다`() {
        val c = app.container
        val vm = AppViewModel(app)
        startDriving()
        assertTrue("소개말을 소리 냈다", app.speaker.said.first().startsWith("어머니,"))
        assertEquals("출발 안내", 1, snapshot().counts.proactive)

        assertTrue(c.trip.repeat())
        idle(12)
        assertEquals(1, snapshot().counts.driverRequested)
        assertEquals(0, snapshot().counts.skipped)
        assertNull(vm.companion.value.status)

        // 내비 길 안내가 나는 동안에는 기다리고, 끝내 끝나지 않으면 소리 내지 않는다
        app.playing = listOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        val saidBefore = app.speaker.said.size
        assertTrue(c.trip.repeat())
        idle(12)
        assertEquals(saidBefore, app.speaker.said.size)
        assertEquals("건너뛴 말은 차가 대신 말한 횟수에 들지 않는다", 1, snapshot().counts.driverRequested)
        assertEquals(1, snapshot().counts.skipped)
        val ui = vm.companion.value
        assertEquals("내비 안내 중이라 소리 내지 않았어요", ui.status)
        assertEquals("하려던 말은 셋째 줄에 그대로 보인다(화면으로만 알림)", snapshot().lastLine, ui.lastLine)
        assertNull(ui.notice)

        // 급한 안전 알림(주행 중 문 열림)은 내비 안내를 기다리지 않는다
        c.demoSource.update { it.copy(doorOpen = it.doorOpen + (SeatPosition.REAR_RIGHT to true)) }
        idle(4)
        assertTrue(app.speaker.said.drop(saidBefore).any { it.contains("문은") })
        assertEquals(1, snapshot().counts.safetyNotices)
        assertNull("소리를 냈으니 까닭 표시는 지워진다", vm.companion.value.status)
        c.demoSource.update { it.copy(doorOpen = it.doorOpen + (SeatPosition.REAR_RIGHT to false)) }
        app.playing = emptyList()
        idle(2)

        c.trip.end()
        val counts = c.store.records.value.single().counts
        assertEquals(2, counts.spokenOnBehalf) // 출발 안내 + 다시 말씀드리기 한 번
        assertEquals(1, counts.skipped)
        assertEquals(1, counts.safetyNotices)
    }

    @Test
    fun `잠시 조용히로 멈춘 말은 대신 말한 횟수에 들지 않고 까닭 표시도 하지 않는다`() {
        val c = app.container
        val vm = AppViewModel(app)
        startDriving()
        app.speaker.sentenceMs = 4_000
        assertTrue(c.trip.repeat())
        idle(1)
        assertTrue(c.trip.toggleQuiet())
        idle(6)
        assertEquals(0, snapshot().counts.driverRequested)
        assertEquals(1, snapshot().counts.skipped)
        assertNull(vm.companion.value.status)
        assertTrue(vm.companion.value.quietMinutesLeft != null)
        c.trip.end()
    }

    @Test
    fun `통화 중이면 통화라고 알린다`() {
        val vm = AppViewModel(app)
        startDriving()
        app.playing = listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        app.container.trip.repeat()
        idle(12)
        assertEquals("통화 중이라 소리 내지 않았어요", vm.companion.value.status)
        app.container.trip.end()
    }

    @Test
    fun `까닭을 모르면서 통화·안내 탓이라고 쓰던 옛 문구는 없다`() {
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue(!strings.contains("통화·안내 중이라 화면으로만"))
    }
}
