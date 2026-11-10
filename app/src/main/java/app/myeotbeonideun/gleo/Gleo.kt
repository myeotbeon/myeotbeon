package app.myeotbeonideun.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.myeotbeonideun.App
import app.myeotbeonideun.AppContainer
import app.myeotbeonideun.data.SavedRoute
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
 * - TODO(실측): ACTION_PARAM 의 따옴표 처리. 문서 예시의 보내는 쪽 문자열이 한 겹 더 감싸여 있어
 *   [GleoParams.parse] 가 두 형태를 모두 받게 했다.
 */
object Gleo {
    const val ACTION_REPEAT = "REPEAT_TRIP_ANSWER"
    const val ACTION_PAUSE = "PAUSE_VOICE_GUIDE"
    const val ACTION_START = "START_COMPANION_TRIP"

    const val EXTRA_PARAM = "ACTION_PARAM"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    const val STATUS_SUCCESS = "success"
    const val STATUS_ASKING = "asking"
    const val STATUS_FAIL = "fail"

    /** 앱이 직접 말할 때는 Gleo 의 음성을 끈다. */
    const val MODE_SILENT = "silent"
    const val MODE_VOICE = "voice"

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
        if (container.trip.repeat()) {
            respond(context, ACTION_REPEAT, STATUS_SUCCESS, "The passenger was told again by voice.", MODE_SILENT)
        } else {
            respond(context, ACTION_REPEAT, STATUS_FAIL, "No companion trip is in progress.")
        }
    }

    /** "잠시 조용히" */
    fun handlePause(context: Context, container: AppContainer, params: JSONObject) {
        val minutes = params.optDouble("minutes", 10.0).let { if (it.isNaN()) 10.0 else it }.toInt().coerceIn(1, 60)
        if (container.trip.quiet(minutes)) {
            respond(
                context, ACTION_PAUSE, STATUS_SUCCESS, "Voice guidance is paused.",
                data = mapOf("minutes" to minutes.toString()),
            )
        } else {
            respond(context, ACTION_PAUSE, STATUS_FAIL, "No companion trip is in progress.")
        }
    }

    /**
     * "동행 시작". 저장된 '자주 가는 길'로 시작한다. 저장된 길이 없으면 화면에서 먼저 정해야 한다.
     * @return 시작했으면 true
     */
    fun handleStart(context: Context, container: AppContainer, params: JSONObject): Boolean {
        if (container.trip.inProgress) {
            respond(context, ACTION_START, STATUS_SUCCESS, "The companion trip is already in progress.")
            return true
        }
        val route = pickRoute(container.store.routes.value, params.optString("destinationName"))
        if (route == null) {
            respond(
                context, ACTION_START, STATUS_ASKING,
                "The passenger and the destination must be set on the screen first.",
            )
            return false
        }
        container.trip.start(route.config, route.rememberArrival)
        respond(
            context, ACTION_START, STATUS_SUCCESS, "The companion trip has started.", MODE_SILENT,
            data = mapOf("destinationName" to route.config.place),
        )
        return true
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
            Gleo.ACTION_REPEAT -> Gleo.handleRepeat(context, container)
            Gleo.ACTION_PAUSE -> Gleo.handlePause(context, container, params)
        }
    }
}
