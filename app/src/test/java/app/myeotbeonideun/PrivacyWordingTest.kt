package app.myeotbeonideun

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.data.AppStore
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.record.TripRecord
import app.myeotbeonideun.signal.SeatReading
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 개인정보 안내가 실제로 저장하는 것과 맞는지(독립 검토 결함 13, 사실과 다른 주장 4·11·12).
 *
 * - 앱이 기기에 쓰는 항목을 모두 꺼내 아래 목록과 견준다. 새 항목을 저장하게 되면 이 시험이 깨진다 →
 *   앱 안 개인정보 안내(strings.xml `settings_privacy_body`), README '개인정보', docs/privacy.md 의 표에 그 항목을 적고
 *   이 목록에 더한다(문서 쪽은 [DocsFactsTest] 가 본다).
 * - 앱 안 안내는 사람이 알아야 할 저장 항목(도착 지점 위치, 직접 쓴 한마디, 시작 시각)을 빼지 않고, '…뿐'으로
 *   좁혀 말하지 않으며, 말하기 엔진을 '기기 안 엔진만'이라고 단정하지 않는다(Pleos TTS 를 못 쓰면 기기의 기본 TTS).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivacyWordingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val config = TripConfig(
        honorific = "어머니", place = "큰딸 집", purpose = Purpose.CUSTOM, customPurposeLine = "가서 손주 얼굴 봐요",
        driver = "따님", seat = SeatPosition.REAR_RIGHT, interval = IntervalMode.AUTO,
    )

    @Test
    fun `기기에 저장하는 항목은 개인정보 문서에 적은 목록과 같다`() {
        val store = AppStore(context)
        val route = store.saveRoute(config, rememberArrival = true, nowMs = 1_000)
        store.setArrivalPoint(route.id, 37.4, 127.1)
        store.addRecord(TripRecord(5_000, 600, TripCounts(3, 2, 1, 4, 1, skipped = 2)))
        store.updateSettings { it.copy(noticeAccepted = true, audioZoneId = 2, seatReading = SeatReading.INVERTED) }

        val prefs = context.getSharedPreferences("myeotbeon", Context.MODE_PRIVATE)
        assertEquals("앱 전용 저장소의 칸", setOf("routes", "records", "settings"), prefs.all.keys)
        assertEquals(
            "자주 가는 길에 저장하는 항목(새 항목이면 개인정보 문서 세 곳에 적을 것)",
            STORED_ROUTE, JSONArray(prefs.getString("routes", null)).getJSONObject(0).keys().asSequence().toSet(),
        )
        assertEquals(
            "이동 기록에 저장하는 항목",
            STORED_RECORD, JSONArray(prefs.getString("records", null)).getJSONObject(0).keys().asSequence().toSet(),
        )
        assertEquals(
            "환경설정에 저장하는 항목",
            STORED_SETTINGS, JSONObject(prefs.getString("settings", null)!!).keys().asSequence().toSet(),
        )
    }

    @Test
    fun `앱 안 개인정보 안내는 남는 것을 빠뜨리지 않고 음성 엔진을 단정하지 않는다`() {
        val body = context.getString(R.string.settings_privacy_body)
        for (item in listOf("부르는 이름", "가는 곳", "운전하는 사람", "직접 쓴 한마디", "도착 지점 위치", "시작 시각", "걸린 시간", "횟수", "환경설정")) {
            assertTrue("개인정보 안내에 '$item' 이 없다: $body", body.contains(item))
        }
        assertTrue("지우는 방법", body.contains("모두 지우기"))
        // 고친 옛 문장: 저장 항목을 좁혀 말하거나, 말하기 엔진을 기기 안 엔진만이라고 단정하던 것
        assertFalse(body.contains("기록뿐"))
        assertFalse(body.contains("횟수뿐"))
        assertFalse(body.contains("엔진만"))
        assertTrue("기본 음성 엔진으로 넘어간다는 사실", body.contains("기본 음성 엔진"))
    }

    @Test
    fun `도착 지점 기억하기 안내는 언제 위치를 기억하는지 말한다`() {
        val note = context.getString(R.string.setup_remember_note)
        // 도착 지점은 도착한 때(운전자가 '도착했어요'를 누르거나 차가 도착을 본 때)에만 저장된다. 그 조건을 빼면
        // '처음부터 내비 없이도 알린다'로 읽힌다(사실과 다른 주장 12).
        assertTrue(note, note.contains("도착했어요"))
        assertTrue(note, note.contains("300m"))
        assertTrue(note, note.contains("이 기기에만"))
    }

    companion object {
        /** docs/privacy.md 의 표와 같은 순서: 이름들, 가서 할 일·직접 쓴 한마디, 자리·간격, 도착 지점, 사용 횟수·마지막 사용 시각. */
        val STORED_ROUTE = setOf(
            "id", "honorific", "place", "purpose", "customPurposeLine", "driver", "seat", "interval",
            "rememberArrival", "arrivalLat", "arrivalLon", "useCount", "lastUsedAtMs",
        )

        /** 시작 시각, 걸린 시간, 여섯 가지 횟수. */
        val STORED_RECORD = setOf(
            "startedAtMs", "durationSec", "proactive", "driverRequested", "answered", "stopNotices", "safetyNotices", "skipped",
        )

        val STORED_SETTINGS = setOf(
            "demoMode", "listenEnabled", "windowThresholdPercent", "audioZoneId", "noticeAccepted", "seatReading",
        )
    }
}
