package app.myeotbeonideun.speech

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
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

/**
 * 말하기의 결과와 다른 소리에 양보하기(결함 8·10). 음성 엔진은 가짜, 재생 중인 소리의 용도는 손으로 넣는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechControllerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speaker = FakeSpeaker()
    /** 지금 재생 중인 소리의 용도(가짜 AudioManager.getActivePlaybackConfigurations). */
    private var playing: List<Int> = emptyList()
    private val speech by lazy { SpeechController(context, scope, listOf(speaker)) { playing } }
    private val results = ArrayList<Pair<String, SpeechResult>>()
    private val startMs = SystemClock.uptimeMillis()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun say(text: String, interrupt: Boolean = false, urgent: Boolean = false) =
        speech.speak(text, interrupt, urgent) { results += text to it }
    private fun result(text: String) = results.single { it.first == text }.second

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `끝까지 읽은 말은 말함으로 돌려준다`() {
        say("어머니, 큰딸 집에 가요.")
        idle(5_000)
        assertEquals(SpeechResult.SPOKEN, result("어머니, 큰딸 집에 가요."))
        assertEquals(listOf("어머니, 큰딸 집에 가요."), speaker.said)
        assertNull(speech.lastSkip.value)
        assertFalse(speech.speaking.value)
    }

    @Test
    fun `기다리는 사이 맞지 않게 된 말은 소리 내지 않고 STALE 로 돌려준다`() {
        // 앞의 말이 끝나기를 기다리는 사이 차가 섰다: "잘 가고 있어요"는 버린다. 운전자 화면에는 까닭을 띄우지 않는다.
        var stillTrue = true
        say("어머니, 안전띠가 풀려 있어요.")
        speech.speak("어머니, 잘 가고 있어요.", false, false, { stillTrue }) { results += "어머니, 잘 가고 있어요." to it }
        stillTrue = false
        idle(8_000)
        assertEquals(SpeechResult.SPOKEN, result("어머니, 안전띠가 풀려 있어요."))
        assertEquals(SpeechResult.STALE, result("어머니, 잘 가고 있어요."))
        assertEquals(listOf("어머니, 안전띠가 풀려 있어요."), speaker.said)
        assertNull(speech.lastSkip.value)
    }

    @Test
    fun `음성 엔진이 없으면 엔진 없음으로 건너뛴다`() {
        speaker.available = false
        say("어머니, 큰딸 집에 가요.")
        idle(5_000)
        assertEquals(SpeechResult.NO_ENGINE, result("어머니, 큰딸 집에 가요."))
        assertTrue(speech.engineMissing.value)
        assertEquals(SpeechResult.NO_ENGINE, speech.lastSkip.value)
    }

    @Test
    fun `내비 길 안내가 나는 동안 기다렸다가 끝나면 말한다`() {
        playing = listOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        say("어머니, 절반쯤 왔어요.")
        idle(3_000)
        assertTrue("안내가 나는 동안에는 말하지 않는다", speaker.said.isEmpty())
        assertTrue("기다리는 동안은 말하는 중으로 본다(엔진·음성 인식이 끼어들지 않게)", speech.speaking.value)
        playing = emptyList()
        idle(4_000)
        assertEquals(listOf("어머니, 절반쯤 왔어요."), speaker.said)
        assertEquals(SpeechResult.SPOKEN, result("어머니, 절반쯤 왔어요."))
        assertTrue("내비 안내가 끝난 뒤에 말했다", speaker.firstSpokeAtMs - startMs >= 3_000)
    }

    @Test
    fun `내비 안내가 최대 대기보다 길면 소리 내지 않고 건너뜀으로 센다`() {
        playing = listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        say("어머니, 절반쯤 왔어요.")
        idle(SpeechController.MAX_YIELD_MS - 500)
        assertTrue(results.isEmpty())
        idle(1_500)
        assertEquals(SpeechResult.NAVIGATION_BUSY, result("어머니, 절반쯤 왔어요."))
        assertTrue(speaker.said.isEmpty())
        assertEquals(SpeechResult.NAVIGATION_BUSY, speech.lastSkip.value)
        assertFalse(SpeechResult.NAVIGATION_BUSY.spoken)
    }

    @Test
    fun `통화 중이면 기다리다가 통화로 건너뛴다`() {
        playing = listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        say("어머니, 절반쯤 왔어요.")
        idle(SpeechController.MAX_YIELD_MS + 1_000)
        assertEquals(SpeechResult.CALL_BUSY, result("어머니, 절반쯤 왔어요."))
        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `급한 안전 알림은 내비 안내를 기다리지 않는다`() {
        playing = listOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        say("어머니, 문은 닫아 주세요.", urgent = true)
        idle(2_000)
        assertEquals(listOf("어머니, 문은 닫아 주세요."), speaker.said)
        assertEquals(SpeechResult.SPOKEN, result("어머니, 문은 닫아 주세요."))
    }

    @Test
    fun `음악과 우리 음성 용도(음성 비서)는 기다리지 않는다`() {
        playing = listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANT)
        say("어머니, 큰딸 집에 가요.")
        idle(2_000)
        assertEquals(SpeechResult.SPOKEN, result("어머니, 큰딸 집에 가요."))
    }

    @Test
    fun `오디오 포커스를 끝내 얻지 못하면 포커스 거절로 건너뛴다`() {
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        say("어머니, 큰딸 집에 가요.")
        idle(8_000)
        assertEquals(SpeechResult.FOCUS_DENIED, result("어머니, 큰딸 집에 가요."))
        assertTrue(speaker.said.isEmpty())
        assertEquals(SpeechResult.FOCUS_DENIED, speech.lastSkip.value)
    }

    @Test
    fun `잠시 조용히로 멈춘 말과 기다리던 말은 조용히로 돌려준다`() {
        speaker.sentenceMs = 3_000
        say("어머니, 첫째 말이에요.")
        say("어머니, 둘째 말이에요.")
        idle(1_000)
        speech.stop(SpeechResult.SILENCED)
        idle(1_000)
        assertEquals(SpeechResult.SILENCED, result("어머니, 첫째 말이에요."))
        assertEquals(SpeechResult.SILENCED, result("어머니, 둘째 말이에요."))
        assertNull("조용히는 소리 내지 못한 까닭으로 알리지 않는다", speech.lastSkip.value)
    }

    @Test
    fun `더 급한 말이 끊으면 하던 말은 끊김이다`() {
        speaker.sentenceMs = 3_000
        say("어머니, 절반쯤 왔어요.")
        idle(1_000)
        say("어머니, 문은 닫아 주세요.", interrupt = true, urgent = true)
        idle(6_000)
        assertEquals(SpeechResult.INTERRUPTED, result("어머니, 절반쯤 왔어요."))
        assertEquals(SpeechResult.SPOKEN, result("어머니, 문은 닫아 주세요."))
    }

    @Test
    fun `내비 안내를 기다리는 사이에 조용히를 누르면 그 말은 조용히다`() {
        playing = listOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        say("어머니, 절반쯤 왔어요.")
        idle(2_000)
        speech.stop(SpeechResult.SILENCED)
        idle(1_000)
        assertEquals(SpeechResult.SILENCED, result("어머니, 절반쯤 왔어요."))
        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `소리 내지 못한 까닭은 다음 말을 소리 내면 지워진다`() {
        playing = listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        say("어머니, 첫째 말이에요.")
        idle(SpeechController.MAX_YIELD_MS + 1_000)
        assertEquals(SpeechResult.CALL_BUSY, speech.lastSkip.value)
        playing = emptyList()
        say("어머니, 둘째 말이에요.")
        idle(3_000)
        assertEquals(SpeechResult.SPOKEN, result("어머니, 둘째 말이에요."))
        assertNull(speech.lastSkip.value)
        // 동행을 새로 시작할 때도 지운다
        playing = listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        say("어머니, 셋째 말이에요.")
        idle(SpeechController.MAX_YIELD_MS + 1_000)
        speech.clearSkipStatus()
        assertNull(speech.lastSkip.value)
    }

    @Test
    fun `말마다 결과를 꼭 한 번 돌려준다`() {
        speaker.sentenceMs = 1_000
        repeat(3) { say("어머니, ${it}번째 말이에요.") }
        idle(500)
        say("어머니, 끊는 말이에요.", interrupt = true)
        idle(5_000)
        assertEquals(4, results.size)
        assertEquals(4, results.map { it.first }.toSet().size)
    }
}

/** 다른 소리에 양보하는 규칙과 급한 알림의 기준(순수 규칙). */
class AudioYieldTest {
    @Test
    fun `통화가 내비 안내보다 앞서고 음악과 음성 비서는 기다리지 않는다`() {
        assertEquals(AudioYield.Busy.CALL, AudioYield.busy(listOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_VOICE_COMMUNICATION)))
        assertEquals(AudioYield.Busy.CALL, AudioYield.busy(listOf(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)))
        assertEquals(AudioYield.Busy.CALL, AudioYield.busy(listOf(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)))
        assertEquals(AudioYield.Busy.NAVIGATION, AudioYield.busy(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)))
        assertNull(AudioYield.busy(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_NOTIFICATION)))
        assertNull(AudioYield.busy(emptyList()))
    }

    @Test
    fun `안전 알림 가운데 창문과 안전띠 권유만 기다리고 나머지는 급하다`() {
        assertTrue(AudioYield.isUrgent(safetyPriority = true, situation = "door_open_p"))
        assertTrue(AudioYield.isUrgent(safetyPriority = true, situation = "driver_away_again"))
        assertFalse(AudioYield.isUrgent(safetyPriority = true, situation = "window_open_p"))
        assertFalse(AudioYield.isUrgent(safetyPriority = true, situation = "belt_off_p"))
        assertFalse("안전 알림이 아니면 급하지 않다", AudioYield.isUrgent(safetyPriority = false, situation = "driver_away_again"))
        assertFalse(AudioYield.isUrgent(safetyPriority = false, situation = "arrive"))
    }

    @Test
    fun `소리를 아예 내지 못한 까닭만 운전자에게 알린다`() {
        val told = SpeechResult.entries.filter { it.notVoiced }.toSet()
        assertEquals(
            setOf(SpeechResult.NO_ENGINE, SpeechResult.NAVIGATION_BUSY, SpeechResult.CALL_BUSY, SpeechResult.FOCUS_DENIED, SpeechResult.ENGINE_FAILED),
            told,
        )
        assertEquals(listOf(SpeechResult.SPOKEN), SpeechResult.entries.filter { it.spoken })
    }
}

/** 가짜 음성 엔진. 한 문장을 [sentenceMs] 동안 읽고, 멈추면 false 를 돌려준다. */
class FakeSpeaker : Speaker {
    override val name = "가짜 음성 엔진"
    var available = true
    var sentenceMs = 500L
    val said = ArrayList<String>()
    var firstSpokeAtMs = -1L
    private var current: CompletableDeferred<Boolean>? = null

    override suspend fun prepare(attributes: AudioAttributes): Boolean = available

    override suspend fun speak(text: String): Boolean {
        if (firstSpokeAtMs < 0) firstSpokeAtMs = SystemClock.uptimeMillis()
        val stopped = CompletableDeferred<Boolean>()
        current = stopped
        // 다 읽기 전에 멈추면 false, 끝까지 읽으면 true
        val done = withTimeoutOrNull(sentenceMs) { stopped.await() } ?: true
        if (current === stopped) current = null
        if (done) said += text
        return done
    }

    override fun stop() {
        current?.complete(false)
    }

    override fun release() {}
}
