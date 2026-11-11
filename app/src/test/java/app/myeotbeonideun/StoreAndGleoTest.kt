package app.myeotbeonideun

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.data.AppStore
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.record.TripRecord
import app.myeotbeonideun.gleo.Gleo
import app.myeotbeonideun.gleo.GleoParams
import app.myeotbeonideun.signal.PleosSignalSource
import app.myeotbeonideun.signal.SeatReading
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val config = TripConfig(
        honorific = "아버지", place = "주간보호센터", purpose = Purpose.CUSTOM, customPurposeLine = "가서 친구들 만나요",
        driver = "사위", seat = SeatPosition.REAR_LEFT, interval = IntervalMode.TEN_MIN,
    )

    @Test
    fun `자주 가는 길을 저장하고 다시 읽는다`() {
        AppStore(context).saveRoute(config, rememberArrival = true, nowMs = 1_000)
        val reloaded = AppStore(context).routes.value.single()
        assertEquals(config, reloaded.config)
        assertTrue(reloaded.rememberArrival)
        assertNull(reloaded.arrivalLat)
        assertEquals(1, reloaded.useCount)
    }

    @Test
    fun `같은 길을 또 쓰면 덮어쓰고 가장 최근 길이 앞에 온다`() {
        val store = AppStore(context)
        store.saveRoute(config, false, 1_000)
        store.saveRoute(config.copy(place = "큰딸 집", honorific = "어머니"), false, 2_000)
        store.saveRoute(config.copy(interval = IntervalMode.OFF), false, 3_000)
        val routes = store.routes.value
        assertEquals(listOf("주간보호센터", "큰딸 집"), routes.map { it.config.place })
        assertEquals(2, routes.first().useCount)
        assertEquals(IntervalMode.OFF, routes.first().config.interval)
    }

    @Test
    fun `기록은 횟수와 시간만 저장한다`() {
        val store = AppStore(context)
        store.addRecord(TripRecord(5_000, 600, TripCounts(3, 2, 1, 4, 1, skipped = 2)))
        val stored = context.getSharedPreferences("myeotbeon", Context.MODE_PRIVATE).getString("records", "")!!
        val keys = JSONArray(stored).getJSONObject(0).keys().asSequence().toSet()
        assertEquals(
            setOf("startedAtMs", "durationSec", "proactive", "driverRequested", "answered", "stopNotices", "safetyNotices", "skipped"),
            keys,
        )
        assertEquals(10, AppStore(context).records.value.single().counts.spokenOnBehalf)
        assertEquals(2, AppStore(context).records.value.single().counts.skipped)
    }

    @Test
    fun `모두 지우면 아무것도 남지 않는다`() {
        val store = AppStore(context)
        store.saveRoute(config, false, 1_000)
        store.addRecord(TripRecord(5_000, 600, TripCounts(1)))
        store.updateSettings { it.copy(listenEnabled = true, audioZoneId = 2, seatReading = SeatReading.AS_IS) }
        store.clearAll()
        val fresh = AppStore(context)
        assertTrue(fresh.routes.value.isEmpty())
        assertTrue(fresh.records.value.isEmpty())
        assertFalse(fresh.settings.value.listenEnabled)
        assertNull(fresh.settings.value.audioZoneId)
        assertEquals(SeatReading.AUTO, fresh.settings.value.seatReading)
        assertTrue(context.getSharedPreferences("myeotbeon", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun `환경설정을 저장하고 다시 읽는다`() {
        AppStore(context).updateSettings {
            it.copy(demoMode = true, windowThresholdPercent = 40, audioZoneId = 1, seatReading = SeatReading.INVERTED)
        }
        val s = AppStore(context).settings.value
        assertTrue(s.demoMode)
        assertEquals(40, s.windowThresholdPercent)
        assertEquals(1, s.audioZoneId)
        assertEquals(SeatReading.INVERTED, s.seatReading)
        // 처음에는 자동(운전석 기준)
        assertEquals(SeatReading.AUTO, app.myeotbeonideun.data.Settings().seatReading)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GleoTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `App Schema 는 함수가 셋 이하이고 이름은 대문자와 밑줄이며 설명은 영어다`() {
        val schema = JSONObject(context.assets.open("schema.json").bufferedReader().use { it.readText() })
        assertEquals(context.packageName, schema.getString("packageName"))
        val calls = schema.getJSONArray("actionCalls")
        assertTrue(calls.length() in 1..3)
        val names = (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            assertEquals("function", call.getString("type"))
            val f = call.getJSONObject("function")
            assertTrue(f.getString("name").matches(Regex("[A-Z]+(_[A-Z]+)*")))
            assertTrue(f.getString("description").all { it.code < 128 })
            assertTrue(f.getString("component") in setOf("activity", "service"))
            assertEquals("object", f.getJSONObject("parameters").getString("type"))
            f.getString("name")
        }
        assertEquals(setOf(Gleo.ACTION_REPEAT, Gleo.ACTION_PAUSE, Gleo.ACTION_START), names.toSet())
        assertTrue(schema.getString("appDescription").all { it.code < 128 })
    }

    @Test
    fun `매니페스트가 스키마의 세 함수를 받는다`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        for (action in listOf(Gleo.ACTION_REPEAT, Gleo.ACTION_PAUSE, Gleo.ACTION_START)) {
            assertTrue(action, manifest.contains("<action android:name=\"$action\""))
        }
    }

    @Test
    fun `ACTION_PARAM 은 JSON 그대로 와도 한 겹 더 감싸여 와도 읽는다`() {
        assertEquals(15, GleoParams.parse("""{"minutes":15}""").getInt("minutes"))
        assertEquals("큰딸 집", GleoParams.parse("\"{\\\"destinationName\\\":\\\"큰딸 집\\\"}\"").getString("destinationName"))
        assertEquals(0, GleoParams.parse(null).length())
        assertEquals(0, GleoParams.parse("이건 JSON 이 아님").length())
    }

    @Test
    fun `응답은 Callback Schema 의 모양이다`() {
        val silent = Gleo.buildResponse(Gleo.ACTION_REPEAT, Gleo.STATUS_SUCCESS, "The passenger was told again by voice.", Gleo.MODE_SILENT, emptyMap())
        assertEquals("REPEAT_TRIP_ANSWER", silent.getString("functionName"))
        assertEquals("success", silent.getString("status"))
        assertEquals("silent", silent.getString("mode"))
        assertFalse(silent.has("data"))

        val withData = Gleo.buildResponse(Gleo.ACTION_PAUSE, Gleo.STATUS_SUCCESS, "Voice guidance is paused.", Gleo.MODE_VOICE, mapOf("minutes" to "10"))
        assertFalse("기본값 voice 는 적지 않는다", withData.has("mode"))
        val data = JSONArray(withData.getString("data")) // data 는 JSON 객체 배열을 담은 문자열
        assertEquals("10", data.getJSONObject(0).getString("minutes"))
    }

    @Test
    fun `동행 시작은 이름이 맞는 길을 먼저 고르고 없으면 가장 최근 길을 고른다`() {
        fun route(place: String, at: Long) = SavedRoute(config = TripConfig(place = place), lastUsedAtMs = at)
        val routes = listOf(route("큰딸 집", 10), route("주간보호센터", 30), route("병원", 20))
        assertEquals("큰딸 집", Gleo.pickRoute(routes, "큰딸집")!!.config.place)
        assertEquals("병원", Gleo.pickRoute(routes, "서울 병원")!!.config.place)
        assertEquals("주간보호센터", Gleo.pickRoute(routes, null)!!.config.place)
        assertEquals("주간보호센터", Gleo.pickRoute(routes, "모르는 곳")!!.config.place)
        assertNull(Gleo.pickRoute(emptyList(), "큰딸 집"))
    }

    @Test
    fun `창문 위치를 열림 퍼센트로 바꾼다`() {
        assertEquals(40, PleosSignalSource.openPercent(40, 0, 100))
        assertEquals(50, PleosSignalSource.openPercent(5, 0, 10))
        assertEquals(100, PleosSignalSource.openPercent(300, 0, 255))
        assertEquals(0, PleosSignalSource.openPercent(-3, 0, 100))
    }
}
