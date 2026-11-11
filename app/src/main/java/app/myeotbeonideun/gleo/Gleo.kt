package app.myeotbeonideun.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.myeotbeonideun.App
import app.myeotbeonideun.AppContainer
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.service.ArrivalConfirm
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo AI 연동(Gleo Interface).
 *
 * 문서(app-integration-guide: app-capability, deliver-schema, examples, action_callback)에서 확인한 것
 * - 앱의 assets/schema.json 을 Gleo AI 가 부팅·설치 때 읽는다. 함수는 최대 3개.
 * - Gleo 는 함수 이름을 Intent action 으로, 파라미터를 "ACTION_PARAM" 문자열(JSON)로 보낸다.
 *   component 가 "activity" 면 startActivity, "service" 면 sendBroadcast.
 * - 앱은 "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" 브로드캐스트로 결과를 돌려준다
 *   (extra: packageName, response = Callback Schema JSON). Gleo 는 7초 뒤 Action 을 취소한다.
 *
 * 확인하지 못한 것(에뮬레이터 실측 필요)
 * - schema.json 은 "검토·승인 후 활성화"라고 되어 있어, 승인 전 에뮬레이터에서 호출이 오는지는 모른다.
 *   2026-11-11 에뮬레이터: 같은 꼴의 방송(REPEAT_TRIP_ANSWER)을 넣으면 앱이 실행하고, 앱의 응답
 *   APP_ACTION_RESPONSE 를 Gleo 프로세스(GAA)가 받는 것은 확인. Gleo 음성 → 앱 함수 경로는 미확인.
 * - TODO(실측): ACTION_PARAM 의 따옴표 처리. 문서 예시의 보내는 쪽 문자열이 한 겹 더 감싸여 있어
 *   [GleoParams.parse] 가 두 형태를 모두 받게 했다.
 *
 * 받는 쪽 보호의 지금 상태와 한계(결함 18)
 * - 리시버([GleoActionReceiver])와 "동행 시작" 액티비티 필터는 권한 없이 exported 다. Gleo AI 가 어떤 권한·패키지로
 *   보내는지 문서에 없어 보낸 쪽을 검사하지 못한다(에뮬레이터에서도 보내는 쪽 패키지·권한은 확인하지 못함).
 * - 그래서 기기 안의 다른 앱도 동행 중 '다시 말하기'·'다 왔다고 말하기'·'잠시 조용히'와 정차 중 '동행 시작'을 부를 수
 *   있다. 줄인 것:
 *   - 동행이 없으면 앞의 셋은 아무 일도 하지 않는다. '다 왔다고 말하기'와 '동행 시작'은 정차(P단) 중에만 받는다.
 *   - '동행 시작'은 저장된 '자주 가는 길'로만 시작한다(바깥에서 가는 곳·호칭을 정하지 못한다).
 *   - 연속 호출 제한([GleoLimits]): 말하기 4초에 한 번·1분에 네 번, 조용히 같은 수, 동행 시작 10초에 한 번·10분에 세 번.
 *     차가 쉬지 않고 말하게 하거나 동행을 거듭 시작하지 못한다.
 *   - '잠시 조용히'는 1~60분으로 자르고, 조용히 중에도 주행 중 문 열림·도착·운전자가 없는 동안의 안내는 한다. 화면에
 *     '조용히 N분 남음'이 보여 운전자가 바로 풀 수 있다.
 *   - 응답 방송(APP_ACTION_RESPONSE)은 받는 쪽을 정하지 못해(Gleo 패키지가 문서에 없다) 기기 안의 다른 앱도 받을 수
 *     있으므로, 가는 곳 이름 같은 값을 싣지 않는다(성공·실패와 영어 문장, 조용히 몇 분만).
 * - 줄이지 못한 것: 보낸 쪽 확인(권한·서명), 응답 방송의 받는 쪽 지정, 정차 중 바깥에서의 '동행 시작' 자체.
 *   Gleo 의 송신 권한이나 패키지가 문서에 나오거나 실측되면 매니페스트에 permission 을 걸거나 보낸 쪽을 검사한다.
 */
object Gleo {
    const val ACTION_REPEAT = "REPEAT_TRIP_ANSWER"
    const val ACTION_PAUSE = "PAUSE_VOICE_GUIDE"
    const val ACTION_START = "START_COMPANION_TRIP"

    const val EXTRA_PARAM = "ACTION_PARAM"

    /** REPEAT_TRIP_ANSWER 의 선택 파라미터(boolean): 운전자가 도착했다고 말했다. */
    const val PARAM_ARRIVED = "arrived"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    const val STATUS_SUCCESS = "success"
    const val STATUS_ASKING = "asking"
    const val STATUS_FAIL = "fail"

    /** 앱이 직접 말할 때는 Gleo 의 음성을 끈다. */
    const val MODE_SILENT = "silent"
    const val MODE_VOICE = "voice"

    private const val MESSAGE_NO_TRIP = "No companion trip is in progress."
    private const val MESSAGE_TOO_MANY = "Too many requests came in a short time, so this one was ignored."

    private fun now() = SystemClock.elapsedRealtime()

    /** Callback Schema 에 맞춰 결과를 Gleo AI 에 돌려준다. message 는 영어 문장이어야 한다. */
    fun respond(
        context: Context,
        functionName: String,
        status: String,
        message: String,
        mode: String = MODE_VOICE,
        data: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            val response = buildResponse(functionName, status, message, mode, data)
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", response.toString()),
            )
        }
    }

    internal fun buildResponse(
        functionName: String,
        status: String,
        message: String,
        mode: String,
        data: Map<String, String>,
    ): JSONObject = JSONObject().apply {
        put("functionName", functionName)
        put("status", status)
        put("message", message)
        if (mode != MODE_VOICE) put("mode", mode)
        if (data.isNotEmpty()) {
            // data 는 "JSON 객체 배열을 담은 문자열"이다.
            put("data", JSONArray().put(JSONObject(data)).toString())
        }
    }

    /** "다시 말씀드리기" */
    fun handleRepeat(context: Context, container: AppContainer) {
        if (!container.trip.inProgress) {
            respond(context, ACTION_REPEAT, STATUS_FAIL, MESSAGE_NO_TRIP)
            return
        }
        if (!container.gleoLimits.speak.tryAcquire(now())) {
            respond(context, ACTION_REPEAT, STATUS_FAIL, MESSAGE_TOO_MANY)
            return
        }
        container.trip.repeat()
        respond(context, ACTION_REPEAT, STATUS_SUCCESS, "The passenger was told again by voice.", MODE_SILENT)
    }

    /**
     * "다 왔다고 말씀드려 줘"(REPEAT_TRIP_ANSWER 에 arrived = true, 결함 2). 화면의 '도착했어요'와 같다.
     * 함수는 최대 3개라(App Schema 가이드) 새 함수를 두지 않고 '다시 말씀드리기'의 선택 파라미터로 받는다.
     * 정차(P단) 중에만 받는다. 달리는 중에 "다 왔어요"라고 하지 않는다.
     */
    fun handleArrived(context: Context, container: AppContainer) {
        if (!container.trip.inProgress) {
            respond(context, ACTION_REPEAT, STATUS_FAIL, MESSAGE_NO_TRIP)
            return
        }
        val limiter = container.gleoLimits.speak
        val at = now()
        if (!limiter.canAcquire(at)) {
            respond(context, ACTION_REPEAT, STATUS_FAIL, MESSAGE_TOO_MANY)
            return
        }
        when (container.trip.confirmArrival()) {
            ArrivalConfirm.CONFIRMED, ArrivalConfirm.ALREADY_ARRIVED -> {
                // 실제로 말했을 때만 센다. 주행 중·출발 전이라 거절한 호출은 세지 않는다(CallLimiter 의 약속, 검토 NEW-9).
                limiter.record(at)
                respond(
                    context, ACTION_REPEAT, STATUS_SUCCESS,
                    "The passenger was told by voice that the car has arrived, and the ride companion is ending.", MODE_SILENT,
                )
            }
            ArrivalConfirm.NO_TRIP -> respond(context, ACTION_REPEAT, STATUS_FAIL, MESSAGE_NO_TRIP)
            ArrivalConfirm.NOT_PARKED -> respond(
                context, ACTION_REPEAT, STATUS_FAIL, "Arrival can only be told to the passenger after the car is parked.",
            )
            ArrivalConfirm.NOT_DEPARTED -> respond(
                context, ACTION_REPEAT, STATUS_FAIL, "The car has not departed yet, so it has not arrived.",
            )
        }
    }

    /** "잠시 조용히" */
    fun handlePause(context: Context, container: AppContainer, params: JSONObject) {
        val minutes = params.optDouble("minutes", 10.0).let { if (it.isNaN()) 10.0 else it }.toInt().coerceIn(1, 60)
        if (!container.trip.inProgress) {
            respond(context, ACTION_PAUSE, STATUS_FAIL, MESSAGE_NO_TRIP)
            return
        }
        if (!container.gleoLimits.pause.tryAcquire(now())) {
            respond(context, ACTION_PAUSE, STATUS_FAIL, MESSAGE_TOO_MANY)
            return
        }
        container.trip.quiet(minutes)
        respond(
            context, ACTION_PAUSE, STATUS_SUCCESS, "Voice guidance is paused.",
            data = mapOf("minutes" to minutes.toString()),
        )
    }

    /** "동행 시작"의 결과. 화면은 이것으로 어디로 갈지 정한다. */
    enum class StartResult {
        STARTED,
        ALREADY_RUNNING,
        /** 저장된 길이 없어 화면에서 먼저 정해야 한다. */
        NEED_SETUP,
        /** 주행 중이라 받지 않았다(설정은 P단에서만). */
        DRIVING,
        /** 짧은 사이에 거듭 불러 받지 않았다(연속 호출 제한). */
        TOO_MANY,
    }

    /**
     * "동행 시작". 저장된 '자주 가는 길'로 시작한다. 저장된 길이 없으면 화면에서 먼저 정해야 한다.
     * 주행 중(주행 규제 on — 체험 모드여도 실차 값)이면 시작하지 않고 그 이유를 돌려준다. 설정은 P단에서만이다.
     */
    fun handleStart(context: Context, container: AppContainer, params: JSONObject): StartResult {
        if (container.trip.inProgress) {
            respond(context, ACTION_START, STATUS_SUCCESS, "The companion trip is already in progress.")
            return StartResult.ALREADY_RUNNING
        }
        // 화면 밖에서 오는 호출이라 차량 신호가 아직 없을 수 있다. 먼저 붙이고, 흐름을 거치지 않은 지금 값으로 본다
        // (주행 규제는 붙이는 순간 읽는다).
        container.ensureVehicleSignals()
        if (container.isDrivingRestrictedNow()) {
            respond(context, ACTION_START, STATUS_FAIL, "A companion trip can only be started while the car is parked.")
            return StartResult.DRIVING
        }
        val route = pickRoute(container.store.routes.value, params.optString("destinationName"))
        if (route == null) {
            respond(
                context, ACTION_START, STATUS_ASKING,
                "The passenger and the destination must be set on the screen first.",
            )
            return StartResult.NEED_SETUP
        }
        if (!container.gleoLimits.start.tryAcquire(now())) {
            respond(context, ACTION_START, STATUS_FAIL, MESSAGE_TOO_MANY)
            return StartResult.TOO_MANY
        }
        container.trip.start(route.config, route.rememberArrival)
        // 응답 방송은 기기 안의 다른 앱도 받을 수 있어 가는 곳 이름을 싣지 않는다(앱이 직접 말하므로 Gleo 도 쓸 일이 없다).
        respond(context, ACTION_START, STATUS_SUCCESS, "The companion trip has started.", MODE_SILENT)
        return StartResult.STARTED
    }

    /** 이름이 맞는 길을 먼저, 없으면 가장 최근에 쓴 길. */
    internal fun pickRoute(routes: List<SavedRoute>, name: String?): SavedRoute? {
        val wanted = name?.filterNot { it.isWhitespace() }.orEmpty()
        if (wanted.isNotEmpty()) {
            routes.firstOrNull { r ->
                val place = r.config.place.filterNot { it.isWhitespace() }
                place.contains(wanted) || wanted.contains(place)
            }?.let { return it }
        }
        return routes.maxByOrNull { it.lastUsedAtMs }
    }
}

object GleoParams {
    /** ACTION_PARAM 을 읽는다. 값이 없거나 형식이 달라도 빈 객체를 돌려주고, 예외를 내지 않는다. */
    fun parse(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        val text = raw.trim()
        runCatching { return JSONObject(text) }
        // 한 겹 더 따옴표로 감싸여 온 경우
        val inner = text.removeSurrounding("\"").replace("\\\"", "\"")
        return runCatching { JSONObject(inner) }.getOrDefault(JSONObject())
    }
}

/** Gleo 가 화면 없이 처리하는 함수(component = "service")를 브로드캐스트로 받는다. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? App)?.container ?: return
        val params = GleoParams.parse(intent.getStringExtra(Gleo.EXTRA_PARAM))
        when (intent.action) {
            Gleo.ACTION_REPEAT ->
                if (params.optBoolean(Gleo.PARAM_ARRIVED, false)) Gleo.handleArrived(context, container)
                else Gleo.handleRepeat(context, container)
            Gleo.ACTION_PAUSE -> Gleo.handlePause(context, container, params)
        }
    }
}
