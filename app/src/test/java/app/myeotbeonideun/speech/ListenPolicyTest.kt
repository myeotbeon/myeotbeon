package app.myeotbeonideun.speech

import app.myeotbeonideun.engine.intent.PassengerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** '물으면 대답하기(실험 기능)'의 듣기 규칙(결함 9). 순수 규칙이라 JVM 에서 바로 돈다. */
class ListenPolicyTest {

    @Test
    fun `시간 초과(말 없음)는 오류로 세지 않아 몇 번이 나도 물러나지 않는다`() {
        val p = ListenPolicy()
        repeat(20) {
            p.onRecognitionStarted()
            assertFalse(p.onError(ListenError.NO_SPEECH, it * 5_000L))
            p.onEnded()
        }
        assertEquals(0, p.errorCount)
    }

    @Test
    fun `인식이 정상으로 끝날 때마다 오류 횟수를 0 으로 돌린다`() {
        val p = ListenPolicy()
        // 오류 두 번 뒤 정상 결과 → 0
        repeat(2) { assertFalse(p.onError(ListenError.OTHER, 0)) }
        assertEquals(2, p.errorCount)
        p.onResult(null, 0) // 물음이 아닌 말이어도 정상 결과다
        assertEquals(0, p.errorCount)
        // 오류 두 번 뒤 오류 없이 끝난 인식 → 0
        repeat(2) { p.onError(ListenError.OTHER, 0) }
        p.onRecognitionStarted()
        assertTrue(p.onEnded())
        assertEquals(0, p.errorCount)
    }

    @Test
    fun `오류가 난 인식이 끝나도 0 으로 돌리지 않고 연달아 세 번이면 물러난다`() {
        val p = ListenPolicy()
        p.onRecognitionStarted()
        assertFalse(p.onError(ListenError.OTHER, 0))
        assertFalse("오류 뒤에 끝남이 와도 정상 종료가 아니다(다시 요청은 오류 쪽이 늦춰 건다)", p.onEnded())
        assertEquals(1, p.errorCount)
        p.onRecognitionStarted()
        assertFalse(p.onError(ListenError.OTHER, 2_000))
        p.onEnded()
        p.onRecognitionStarted()
        assertTrue("연달아 세 번", p.onError(ListenError.OTHER, 4_000))
    }

    @Test
    fun `끝남이 오류보다 먼저 와도 1분 안에 오류가 여섯 번이면 물러나고 띄엄띄엄 난 오류로는 물러나지 않는다`() {
        val p = ListenPolicy()
        // 끝남(정상으로 보여 0) 다음에 오류가 오는 순서: 연달아 센 수는 늘 1 이다
        for (i in 0 until 5) {
            p.onRecognitionStarted()
            assertTrue(p.onEnded())
            assertFalse(p.onError(ListenError.OTHER, i * 2_000L))
        }
        p.onRecognitionStarted()
        p.onEnded()
        assertTrue("1분 안에 여섯 번", p.onError(ListenError.OTHER, 10_000))

        val q = ListenPolicy()
        for (i in 0 until 20) {
            q.onRecognitionStarted()
            q.onEnded()
            assertFalse("30초마다 한 번이면 버틴다", q.onError(ListenError.OTHER, i * 30_000L))
        }
    }

    @Test
    fun `권한 거부처럼 다시 해도 안 되는 오류는 바로 물러난다`() {
        assertTrue(ListenPolicy().onError(ListenError.FATAL, 0))
    }

    @Test
    fun `같은 물음 종류는 18초 안에 다시 대답하지 않고 다른 종류는 대답한다`() {
        val p = ListenPolicy()
        assertEquals(PassengerIntent.WHERE, p.onResult(PassengerIntent.WHERE, 0))
        assertNull("라디오·통화의 같은 말에 거듭 끼어들지 않는다", p.onResult(PassengerIntent.WHERE, 10_000))
        assertNull(p.onResult(PassengerIntent.WHERE, 17_999))
        assertEquals("다른 물음은 대답한다", PassengerIntent.WHEN, p.onResult(PassengerIntent.WHEN, 12_000))
        assertEquals(PassengerIntent.WHERE, p.onResult(PassengerIntent.WHERE, 18_000))
        assertTrue(ListenPolicy.COOLDOWN_MS in 15_000L..20_000L)
    }

    @Test
    fun `새 동행이면 쿨다운과 오류 횟수를 지운다`() {
        val p = ListenPolicy()
        p.onResult(PassengerIntent.WHERE, 0)
        repeat(2) { p.onError(ListenError.OTHER, 0) }
        p.reset()
        assertEquals(0, p.errorCount)
        assertEquals(PassengerIntent.WHERE, p.onResult(PassengerIntent.WHERE, 1_000))
    }

    @Test
    fun `SDK 오류 이름을 종류로 가른다`() {
        assertEquals(ListenError.NO_SPEECH, ListenError.of("TIMEOUT"))
        for (fatal in listOf("PERMISSION_DENIED", "NOT_SUPPORTED_API", "UNAVAILABLE_LANGUAGE", "UNAUTHENTICATED")) {
            assertEquals(fatal, ListenError.FATAL, ListenError.of(fatal))
        }
        for (other in listOf("AUDIO_ERROR", "MIC_ERROR", "ON_DEVICE_ENGINE_ERROR", "NETWORK_ERROR", "SERVER_INTERNAL_ERROR", "INVALID_REQUEST_ERROR", "UNKNOWN", "새 이름")) {
            assertEquals(other, ListenError.OTHER, ListenError.of(other))
        }
    }
}
