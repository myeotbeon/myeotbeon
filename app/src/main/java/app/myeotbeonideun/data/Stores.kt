package app.myeotbeonideun.data

import android.content.Context
import android.content.SharedPreferences
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.record.RecordAggregator
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.record.TripRecord
import app.myeotbeonideun.signal.SeatReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 자주 가는 길 한 건. 다음에는 한 번 눌러 시작한다(명세 3.1).
 *
 * @property arrivalLat 내비 없이도 도착을 알기 위해 기억한 도착 지점(사용자가 켰을 때만). 기기 안에만 둔다.
 */
data class SavedRoute(
    val id: String = UUID.randomUUID().toString(),
    val config: TripConfig,
    val rememberArrival: Boolean = false,
    val arrivalLat: Double? = null,
    val arrivalLon: Double? = null,
    val useCount: Int = 0,
    val lastUsedAtMs: Long = 0,
)

/** 환경설정. */
data class Settings(
    /** 체험 모드를 항상 쓴다(차량 신호 대신 모의 신호). */
    val demoMode: Boolean = false,
    /** 물으면 대답하기(실험 기능). 기본은 꺼짐. */
    val listenEnabled: Boolean = false,
    /** 창문이 이만큼 열리면 안내한다. */
    val windowThresholdPercent: Int = 30,
    /** TextToSpeech.setAudioZoneId 에 넣을 값. null 이면 기본 존. 차종별 값은 문서에 없어 직접 넣게 했다. */
    val audioZoneId: Int? = null,
    /** 처음 실행할 때의 안내를 읽었는지. */
    val noticeAccepted: Boolean = false,
    /** 좌석 점유 값 읽기. 자동은 운전석 기준으로 그대로·반대로를 정한다(에뮬레이터에서 콘솔과 반대로 왔다). */
    val seatReading: SeatReading = SeatReading.AUTO,
)

/**
 * 모든 저장은 기기 안의 앱 전용 저장소(SharedPreferences)에만 한다. 서버로 보내지 않는다.
 * 저장하는 것: 자주 가는 길, 이동 기록(횟수와 시간), 환경설정. 음성과 인식 문장은 저장하지 않는다.
 */
class AppStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("myeotbeon", Context.MODE_PRIVATE)

    private val _routes = MutableStateFlow(loadRoutes())
    private val _records = MutableStateFlow(loadRecords())
    private val _settings = MutableStateFlow(loadSettings())

    val routes: StateFlow<List<SavedRoute>> = _routes.asStateFlow()
    val records: StateFlow<List<TripRecord>> = _records.asStateFlow()
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // ───────── 자주 가는 길 ─────────

    /** 같은 호칭·가는 곳이면 덮어쓰고, 아니면 새로 넣는다. 가장 최근에 쓴 것이 앞에 온다. */
    fun saveRoute(config: TripConfig, rememberArrival: Boolean, nowMs: Long): SavedRoute {
        var saved: SavedRoute? = null
        _routes.update { list ->
            val old = list.firstOrNull { it.config.place == config.place && it.config.honorific == config.honorific }
            val route = (old ?: SavedRoute(config = config)).copy(
                config = config,
                rememberArrival = rememberArrival,
                arrivalLat = if (rememberArrival) old?.arrivalLat else null,
                arrivalLon = if (rememberArrival) old?.arrivalLon else null,
                useCount = (old?.useCount ?: 0) + 1,
                lastUsedAtMs = nowMs,
            )
            saved = route
            (listOf(route) + list.filter { it.id != route.id }).take(MAX_ROUTES)
        }
        persistRoutes()
        return saved!!
    }

    fun setArrivalPoint(routeId: String, lat: Double, lon: Double) {
        _routes.update { list -> list.map { if (it.id == routeId) it.copy(arrivalLat = lat, arrivalLon = lon) else it } }
        persistRoutes()
    }

    fun deleteRoute(routeId: String) {
        _routes.update { list -> list.filter { it.id != routeId } }
        persistRoutes()
    }

    // ───────── 이동 기록 ─────────

    fun addRecord(record: TripRecord) {
        _records.update { RecordAggregator.trim(it + record, MAX_RECORDS) }
        prefs.edit().putString(KEY_RECORDS, JSONArray().apply { _records.value.forEach { put(it.toJson()) } }.toString()).apply()
    }

    // ───────── 환경설정 ─────────

    fun updateSettings(change: (Settings) -> Settings) {
        _settings.update(change)
        val s = _settings.value
        prefs.edit().putString(
            KEY_SETTINGS,
            JSONObject()
                .put("demoMode", s.demoMode)
                .put("listenEnabled", s.listenEnabled)
                .put("windowThresholdPercent", s.windowThresholdPercent)
                .put("audioZoneId", s.audioZoneId ?: JSONObject.NULL)
                .put("noticeAccepted", s.noticeAccepted)
                .put("seatReading", s.seatReading.name)
                .toString(),
        ).apply()
    }

    /** 자주 가는 길과 기록, 설정을 모두 지운다(명세 5: 지우기 버튼). */
    fun clearAll() {
        prefs.edit().clear().apply()
        _routes.value = emptyList()
        _records.value = emptyList()
        _settings.value = Settings()
    }

    /** 기록만 지운다. */
    fun clearRecords() {
        prefs.edit().remove(KEY_RECORDS).apply()
        _records.value = emptyList()
    }

    // ───────── 읽고 쓰기 ─────────

    private fun persistRoutes() {
        prefs.edit().putString(KEY_ROUTES, JSONArray().apply { _routes.value.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private fun loadRoutes(): List<SavedRoute> = readArray(KEY_ROUTES) { it.toRoute() }
    private fun loadRecords(): List<TripRecord> = readArray(KEY_RECORDS) { it.toRecord() }

    private fun loadSettings(): Settings = runCatching {
        val o = JSONObject(prefs.getString(KEY_SETTINGS, null) ?: return Settings())
        Settings(
            demoMode = o.optBoolean("demoMode", false),
            listenEnabled = o.optBoolean("listenEnabled", false),
            windowThresholdPercent = o.optInt("windowThresholdPercent", 30).coerceIn(10, 90),
            audioZoneId = if (o.isNull("audioZoneId")) null else o.optInt("audioZoneId"),
            noticeAccepted = o.optBoolean("noticeAccepted", false),
            seatReading = enumOr(o.optString("seatReading"), SeatReading.AUTO),
        )
    }.getOrDefault(Settings())

    private fun <T> readArray(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(prefs.getString(key, null) ?: return emptyList())
        (0 until arr.length()).mapNotNull { i -> runCatching { parse(arr.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY_ROUTES = "routes"
        private const val KEY_RECORDS = "records"
        private const val KEY_SETTINGS = "settings"
        const val MAX_ROUTES = 8
        const val MAX_RECORDS = 60

        internal fun SavedRoute.toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("honorific", config.honorific)
            .put("place", config.place)
            .put("purpose", config.purpose.name)
            .put("customPurposeLine", config.customPurposeLine)
            .put("driver", config.driver)
            .put("seat", config.seat.name)
            .put("interval", config.interval.name)
            .put("rememberArrival", rememberArrival)
            .put("arrivalLat", arrivalLat ?: JSONObject.NULL)
            .put("arrivalLon", arrivalLon ?: JSONObject.NULL)
            .put("useCount", useCount)
            .put("lastUsedAtMs", lastUsedAtMs)

        internal fun JSONObject.toRoute(): SavedRoute = SavedRoute(
            id = getString("id"),
            config = TripConfig(
                honorific = getString("honorific"),
                place = getString("place"),
                purpose = enumOr(optString("purpose"), Purpose.NONE),
                customPurposeLine = optString("customPurposeLine"),
                driver = getString("driver"),
                seat = enumOr(optString("seat"), SeatPosition.REAR_RIGHT),
                interval = enumOr(optString("interval"), IntervalMode.AUTO),
            ),
            rememberArrival = optBoolean("rememberArrival", false),
            arrivalLat = if (isNull("arrivalLat")) null else optDouble("arrivalLat"),
            arrivalLon = if (isNull("arrivalLon")) null else optDouble("arrivalLon"),
            useCount = optInt("useCount", 0),
            lastUsedAtMs = optLong("lastUsedAtMs", 0),
        )

        internal fun TripRecord.toJson(): JSONObject = JSONObject()
            .put("startedAtMs", startedAtMs)
            .put("durationSec", durationSec)
            .put("proactive", counts.proactive)
            .put("driverRequested", counts.driverRequested)
            .put("answered", counts.answered)
            .put("stopNotices", counts.stopNotices)
            .put("safetyNotices", counts.safetyNotices)
            .put("skipped", counts.skipped)

        internal fun JSONObject.toRecord(): TripRecord = TripRecord(
            startedAtMs = getLong("startedAtMs"),
            durationSec = optInt("durationSec", 0),
            counts = TripCounts(
                proactive = optInt("proactive", 0),
                driverRequested = optInt("driverRequested", 0),
                answered = optInt("answered", 0),
                stopNotices = optInt("stopNotices", 0),
                safetyNotices = optInt("safetyNotices", 0),
                skipped = optInt("skipped", 0),
            ),
        )

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
