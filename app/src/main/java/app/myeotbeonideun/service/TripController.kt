package app.myeotbeonideun.service

import android.content.Context
import android.os.SystemClock
import app.myeotbeonideun.AppContainer
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.engine.Arrived
import app.myeotbeonideun.engine.CompanionEngine
import app.myeotbeonideun.engine.DriverNotice
import app.myeotbeonideun.engine.EngineAction
import app.myeotbeonideun.engine.EngineParams
import app.myeotbeonideun.engine.EngineSnapshot
import app.myeotbeonideun.engine.Silence
import app.myeotbeonideun.engine.Speak
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.GeoPoint
import app.myeotbeonideun.signal.NavEvent
import app.myeotbeonideun.signal.SignalSource
import app.myeotbeonideun.signal.SourceKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 화면이 그리는 동행 상태. 동행 중이 아니면 null 이다. */
data class TripUi(
    val config: TripConfig,
    val snapshot: EngineSnapshot,
    val sourceKind: SourceKind,
    /** 엔진 시계(부팅 뒤 경과 시간) 기준의 지금. '잠시 조용히' 남은 시간을 셈하는 데 쓴다. */
    val nowMs: Long,
)

/**
 * 한 번의 동행을 이끈다: 신호 출처 → 엔진 → 음성·화면.
 * 엔진은 한 스레드(메인)에서만 부르고, 엔진의 시각은 SystemClock.elapsedRealtime() 으로 준다.
 */
class TripController(private val context: Context, private val c: AppContainer) {

    private val _ui = MutableStateFlow<TripUi?>(null)
    val ui: StateFlow<TripUi?> = _ui.asStateFlow()

    private var engine: CompanionEngine? = null
    private var config: TripConfig? = null
    private var source: SignalSource? = null
    private var route: SavedRoute? = null
    private var job: Job? = null
    private var startedWallMs = 0L
    private var arrivedAtMs: Long? = null

    /** 동행 중인지. */
    val inProgress: Boolean get() = engine != null

    private fun now() = SystemClock.elapsedRealtime()

    /** 동행을 시작한다. 설정은 '자주 가는 길'로 저장된다. */
    fun start(config: TripConfig, rememberArrival: Boolean = false) {
        if (engine != null) end()
        val src = c.source.value
        val settings = c.store.settings.value
        val saved = c.store.saveRoute(config, rememberArrival, System.currentTimeMillis())
        val e = CompanionEngine(
            c.script, config,
            EngineParams(windowThresholdPercent = settings.windowThresholdPercent),
        )
        engine = e
        this.config = config
        source = src
        route = saved
        startedWallMs = System.currentTimeMillis()
        arrivedAtMs = null

        src.setTripActive(true)
        if (saved.rememberArrival && saved.arrivalLat != null && saved.arrivalLon != null) {
            src.watchArrivalPoint(GeoPoint(saved.arrivalLat, saved.arrivalLon))
        }
        c.speech.setAudioZone(settings.audioZoneId)
        perform(e.start(now()))

        job = c.scope.launch {
            launch {
                src.state.collect { vehicle -> perform(e.onSignals(vehicle.forSeat(config.seat), now())) }
            }
            launch {
                src.navEvents.collect { event ->
                    if (event is NavEvent.Arrived) perform(e.onNavArrived(now()))
                }
            }
            launch {
                while (true) {
                    delay(TICK_MS)
                    e.onSpeechState(c.speech.speaking.value, now())
                    perform(e.onTick(now()))
                    autoEndAfterArrival()
                }
            }
            launch {
                // 차가 말하는 동안에는 듣지 않는다.
                c.speech.speaking.collect { speaking ->
                    if (speaking) c.listener.pause() else c.listener.resume()
                }
            }
        }
        if (settings.listenEnabled) {
            c.listener.start { intent -> engine?.let { perform(it.onPassengerAsked(intent, now())) } }
        }
        CompanionService.start(context)
    }

    /** 다시 말씀드리기(화면 버튼, Gleo). 동행 중이 아니면 false */
    fun repeat(): Boolean {
        val e = engine ?: return false
        perform(e.requestRepeat(now()))
        return true
    }

    /** 잠시 조용히. 이미 조용히 중이면 푼다. */
    fun toggleQuiet(): Boolean {
        val e = engine ?: return false
        if (e.snapshot(now()).quietUntilMs != null) {
            e.cancelQuiet()
            publish()
        } else {
            perform(e.quiet(now()))
        }
        return true
    }

    /** 정한 시간만큼 조용히(Gleo). */
    fun quiet(minutes: Int): Boolean {
        val e = engine ?: return false
        perform(e.quiet(now(), minutes.coerceIn(1, 60) * 60_000L))
        return true
    }

    /** 동행을 끝내고 횟수 기록을 남긴다. */
    fun end() {
        val e = engine ?: return
        job?.cancel()
        job = null
        c.listener.stop()
        c.speech.stop()
        source?.setTripActive(false)
        (source as? DemoSignalSource)?.stopScenario()
        val record = e.finish(now()).copy(startedAtMs = startedWallMs)
        if (record.durationSec >= MIN_RECORD_SEC) c.store.addRecord(record)
        engine = null
        config = null
        source = null
        route = null
        _ui.value = null
        CompanionService.stop(context)
    }

    private fun perform(actions: List<EngineAction>) {
        for (action in actions) {
            when (action) {
                is Speak -> {
                    val e = engine
                    c.speech.speak(action.text, action.interrupt) { spoken ->
                        // 기록은 실제로 소리 낸 말만 센다. 그새 동행이 끝났거나 바뀌었으면 버린다.
                        if (e != null && engine === e) {
                            e.onUtteranceResult(action.id, spoken)
                            publish()
                        }
                    }
                }
                is DriverNotice -> if (action.urgent) c.speech.chime()
                Silence -> c.speech.stop()
                // 도착(내비·목적지 근처·운전자가 알림): 도착 지점을 기억하고 10분 뒤 스스로 끝낼 시각을 잡는다.
                is Arrived -> {
                    arrivedAtMs = now()
                    source?.let { rememberArrivalPoint(it) }
                }
            }
        }
        publish()
    }

    private fun publish() {
        val e = engine ?: return
        val cfg = config ?: return
        val src = source ?: return
        val t = now()
        val snapshot = e.snapshot(t)
        _ui.value = TripUi(cfg, snapshot, src.kind, t)
    }

    /** 도착 지점을 기억해 두면 다음에는 내비 없이도 300m 앞에서 '곧 도착'을 알 수 있다(사용자가 켠 길만). */
    private fun rememberArrivalPoint(src: SignalSource) {
        val r = route ?: return
        if (!r.rememberArrival || r.arrivalLat != null) return
        src.lastKnownLocation { point ->
            if (point != null) c.store.setArrivalPoint(r.id, point.latitude, point.longitude)
        }
    }

    /** 도착한 뒤에도 끝내지 않고 두면 스스로 끝낸다(백그라운드에 불필요하게 남지 않도록). */
    private fun autoEndAfterArrival() {
        val at = arrivedAtMs ?: return
        if (now() - at >= AUTO_END_AFTER_ARRIVAL_MS) end()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_RECORD_SEC = 20
        const val AUTO_END_AFTER_ARRIVAL_MS = 10 * 60_000L
    }
}
