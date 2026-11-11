package app.myeotbeonideun.service

import android.content.Context
import android.os.SystemClock
import app.myeotbeonideun.AppContainer
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.engine.ArrivalCancelled
import app.myeotbeonideun.engine.ArrivalSource
import app.myeotbeonideun.engine.Arrived
import app.myeotbeonideun.engine.CompanionEngine
import app.myeotbeonideun.engine.DriverNotice
import app.myeotbeonideun.engine.EngineAction
import app.myeotbeonideun.engine.EngineParams
import app.myeotbeonideun.engine.EngineSnapshot
import app.myeotbeonideun.engine.Phase
import app.myeotbeonideun.engine.Priority
import app.myeotbeonideun.engine.Silence
import app.myeotbeonideun.engine.Speak
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.GeoPoint
import app.myeotbeonideun.signal.NavEvent
import app.myeotbeonideun.signal.SignalSource
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.speech.AudioYield
import app.myeotbeonideun.speech.SpeechResult
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
    /** 운전자가 '도착했어요'로 도착을 알렸고, 도착 안내가 끝나면 동행을 마친다. */
    val endingAfterArrival: Boolean = false,
)

/** '도착했어요'(화면 버튼, Gleo)의 결과. */
enum class ArrivalConfirm {
    /** 도착을 알렸다. 도착 안내가 끝나면 동행을 마친다. */
    CONFIRMED,
    /** 이미 도착으로 봤다. 도착 안내를 한 번 더 하고 동행을 마친다. */
    ALREADY_ARRIVED,
    /** 동행 중이 아니다. */
    NO_TRIP,
    /** 주행 중이거나 P단인지 모른다. 받지 않았다(달리는 중에 "다 왔어요"라고 하지 않는다). */
    NOT_PARKED,
    /** 아직 출발하지 않았다. */
    NOT_DEPARTED,
}

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

    /** '도착했어요' 뒤 동행을 마치기까지 기다리는 것(도착 안내, 도착 지점 위치). */
    private class Finishing(val deadlineMs: Long) {
        /** 기다리는 도착 안내의 말 번호. 아직 엔진이 내보내지 않았으면 null */
        var arriveId: Long? = null
        var lineDone = false
        var locationPending = 0
    }

    private var finishing: Finishing? = null

    /** 이번 도착에서 기억한 도착 지점과 그 전 값(도착을 거두면 되돌린다). */
    private class SavedPoint(val routeId: String, val previous: Pair<Double, Double>?)

    private var savedPoint: SavedPoint? = null
    /** 도착마다 바뀌는 번호. 위치 조회가 늦게 돌아왔을 때 그사이 도착을 거뒀는지 본다. */
    private var arrivalSerial = 0

    /** 동행 중인지. */
    val inProgress: Boolean get() = engine != null

    /**
     * 지금 떠 있는 급한 운전자 알림 한 줄(주행 중 문 열림 등). 없으면 null.
     * 진행 중 알림의 글과, 앱이 화면 뒤에 있을 때의 한 줄 알림(헤드업)에 쓴다(결함 11, [TripNotifications]).
     */
    var urgentLine: String? = null
        private set
    private var urgentSituation: String? = null

    /** 알림에 지금 올려 둔 것. 같은 글로 다시 올리지 않는다(되풀이 알람 금지). */
    private var ongoingShown: String? = null
    private var headsUpShown: String? = null

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
        finishing = null
        savedPoint = null
        urgentLine = null
        urgentSituation = null
        ongoingShown = null
        headsUpShown = null

        src.setTripActive(true)
        if (saved.rememberArrival && saved.arrivalLat != null && saved.arrivalLon != null) {
            src.watchArrivalPoint(GeoPoint(saved.arrivalLat, saved.arrivalLon))
        }
        c.speech.clearSkipStatus()
        c.speech.setAudioZone(settings.audioZoneId)
        perform(e.start(now()))

        // 음성 인식(실험 기능)은 '말하는 중' 수집보다 먼저 시작한다(결함 9). 수집은 지금 값(소개말을 말하는 중)을 바로
        // 받아 pause 를 부르는데, 그때 듣기가 시작되지 않은 상태면 pause 가 무시되어 소개말 동안 듣게 된다.
        if (settings.listenEnabled) {
            c.listener.start { intent ->
                // 엔진이 받아 대답했을 때만 true(같은 물음 쿨다운은 그때만 센다). '잠시 조용히' 중이거나 끝났으면 false.
                val current = engine
                val t = now()
                if (current == null || !current.acceptsQuestions(t)) {
                    false
                } else {
                    perform(current.onPassengerAsked(intent, t))
                    true
                }
            }
        }

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
                    autoEndWhenVacant()
                    finishIfDone()
                }
            }
            launch {
                // 앱이 화면 뒤로 가면 떠 있는 급한 알림을 한 줄 알림으로, 앞으로 오면 그 알림을 지운다.
                c.foreground.collect { syncAlerts() }
            }
            launch {
                // 차가 말하는 동안에는 듣지 않는다.
                c.speech.speaking.collect { speaking ->
                    if (speaking) c.listener.pause() else c.listener.resume()
                }
            }
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

    /**
     * 운전자가 '도착했어요'를 눌렀다(또는 Gleo 로 시켰다). 내비 없이 가는 길은 차가 도착을 스스로 알 수 없다(결함 2).
     *
     * 정차(P단) 중에만 받는다. 엔진이 도착 안내(arrive)를 하고, '도착 지점 기억하기'를 켠 길이면 지금 위치를 그 길의
     * 도착 지점으로 저장한 뒤, 도착 안내가 끝나면(소리 내지 못했어도 결과가 오면) 동행을 마친다.
     * 이미 도착으로 봤으면 도착 안내를 한 번 더 하고 마친다. 안내가 [FINISH_DEADLINE_MS] 안에 끝나지 않아도 마친다.
     */
    fun confirmArrival(): ArrivalConfirm {
        val e = engine ?: return ArrivalConfirm.NO_TRIP
        val t = now()
        val snapshot = e.snapshot(t)
        val driving = c.isDrivingRestrictedNow()
        return when {
            snapshot.phase == Phase.ARRIVED && !driving -> {
                finishing = Finishing(t + FINISH_DEADLINE_MS)
                // 도착한 뒤의 '다시 말씀드리기'는 도착 안내다.
                perform(e.requestRepeat(t))
                ArrivalConfirm.ALREADY_ARRIVED
            }
            !driving && snapshot.canConfirmArrival -> {
                finishing = Finishing(t + FINISH_DEADLINE_MS)
                perform(e.driverConfirmedArrival(t))
                ArrivalConfirm.CONFIRMED
            }
            !driving && snapshot.phase == Phase.READY -> ArrivalConfirm.NOT_DEPARTED
            else -> ArrivalConfirm.NOT_PARKED
        }
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
        finishing = null
        urgentLine = null
        urgentSituation = null
        ongoingShown = null
        headsUpShown = null
        _ui.value = null
        CompanionService.stop(context)
        TripNotifications.cancelAll(context)
    }

    private fun perform(actions: List<EngineAction>) {
        for (action in actions) {
            when (action) {
                is Speak -> {
                    val e = engine
                    // 급한 안전 알림(주행 중 문 열림 등)은 내비 안내·통화가 끝나기를 기다리지 않는다.
                    val urgent = AudioYield.isUrgent(action.priority == Priority.SAFETY, action.situation)
                    // '도착했어요' 뒤라면 이 도착 안내가 끝나기를 기다렸다가 동행을 마친다.
                    val f = finishing
                    if (f != null && f.arriveId == null && action.situation == ARRIVE_SITUATION) f.arriveId = action.id
                    c.speech.speak(action.text, action.interrupt, urgent) { result ->
                        // 말마다 결과(말함, 또는 건너뜀과 그 까닭)를 엔진에 돌려준다. 기록은 끝까지 소리 낸 말만 센다.
                        // 그새 동행이 끝났거나 바뀌었으면 버린다.
                        if (e != null && engine === e) {
                            e.onUtteranceResult(action.id, result.spoken)
                            finishing?.let { if (it.arriveId == action.id) it.lineDone = true }
                            publish()
                        }
                    }
                }
                is DriverNotice -> if (action.urgent) c.speech.chime()
                // '잠시 조용히': 하던 말을 멈춘다. 멈춘 말은 '조용히'로 건너뜀이 된다.
                Silence -> c.speech.stop(SpeechResult.SILENCED)
                // 도착(내비·목적지 근처·운전자가 알림): 도착 지점을 기억하고 10분 뒤 스스로 끝낼 시각을 잡는다.
                is Arrived -> {
                    arrivedAtMs = now()
                    source?.let { rememberArrivalPoint(it, action.source) }
                }
                // 도착이 아니었다(다시 달림 등): 도착 뒤 자동 종료와 '도착했어요' 뒤의 마침을 풀고,
                // 이번 도착에서 기억한 도착 지점을 그 전 값으로 되돌린다.
                ArrivalCancelled -> {
                    arrivedAtMs = null
                    finishing = null
                    forgetArrivalPoint()
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
        _ui.value = TripUi(cfg, snapshot, src.kind, t, endingAfterArrival = finishing != null)
        // 같은 일인지는 상황 이름으로 가린다. 되풀이 알림(문이 열려 있는 동안 15초마다)은 대본 변형을 돌려 써서 글이
        // 바뀌지만 같은 일이다 — 처음 글을 그대로 두고 다시 알리지 않는다.
        val situation = snapshot.driverNoticeSituation?.takeIf { snapshot.driverNoticeUrgent && snapshot.driverNotice != null }
        if (situation != urgentSituation) {
            urgentSituation = situation
            urgentLine = situation?.let { snapshot.driverNotice }
        }
        syncAlerts()
    }

    /**
     * 급한 운전자 알림을 앱 밖에도 보인다(결함 11). 포그라운드 서비스를 둔 까닭이 '주행 중에는 내비가 앞에 있다'인데,
     * 그때 화면 안의 붉은 한 줄은 보이지 않는다.
     * - 진행 중 알림의 글을 그 한 줄로 바꾸고, 풀리면 평소 글로 되돌린다.
     * - 앱이 화면 뒤에 있으면 중요도 높음 채널의 한 줄 알림(헤드업)으로도 낸다. 앱이 앞에 있거나 풀리면 지운다.
     * 같은 글은 다시 올리지 않는다(문이 열려 있는 동안 15초마다 오는 되풀이 알림은 알림음만 낸다).
     * 다른 일로 바뀌면(안전띠 알림이 떠 있는 동안 주행 중 문 열림) 앞의 것을 지우고 새로 올려 헤드업이 다시 뜨게 한다(검토 NEW-5).
     */
    private fun syncAlerts() {
        if (engine == null) return
        val line = urgentLine
        if (line != ongoingShown) {
            ongoingShown = line
            TripNotifications.updateOngoing(context, line)
        }
        val wantHeadsUp = line != null && !c.foreground.value
        if (wantHeadsUp && line != headsUpShown) {
            if (headsUpShown != null) TripNotifications.cancelUrgent(context)
            headsUpShown = line
            TripNotifications.showUrgent(context, line!!)
        } else if (!wantHeadsUp && headsUpShown != null) {
            headsUpShown = null
            TripNotifications.cancelUrgent(context)
        }
    }

    /**
     * 도착 지점을 기억해 두면 다음에는 내비 없이도 300m 앞에서 '곧 도착'을 알 수 있다(사용자가 켠 길만).
     * 운전자가 알린 도착은 그 자리를 도착 지점으로 삼는다(이미 기억한 곳이 있어도 새로 — 운전자의 말이 가장 확실하다).
     * 차가 스스로 본 도착(내비 이벤트·목적지 근처)은 아직 기억한 곳이 없을 때만 저장한다.
     */
    private fun rememberArrivalPoint(src: SignalSource, arrival: ArrivalSource) {
        val r = route ?: return
        if (!r.rememberArrival) return
        if (arrival != ArrivalSource.DRIVER && (r.arrivalLat != null || savedPoint != null)) return
        val serial = ++arrivalSerial
        val f = finishing
        f?.let { it.locationPending++ }
        src.lastKnownLocation { point ->
            f?.let { it.locationPending-- }
            // 그사이 도착을 거뒀으면 저장하지 않는다.
            if (point == null || serial != arrivalSerial) return@lastKnownLocation
            val current = c.store.routes.value.firstOrNull { it.id == r.id } ?: return@lastKnownLocation
            val previous = savedPoint?.previous ?: current.arrivalLat?.let { lat -> current.arrivalLon?.let { lat to it } }
            c.store.setArrivalPoint(r.id, point.latitude, point.longitude)
            savedPoint = SavedPoint(r.id, previous)
        }
    }

    /** 도착을 거뒀다. 이번 도착에서 기억한 도착 지점을 그 전 값으로 되돌린다(약국 앞 같은 곳을 도착 지점으로 남기지 않게). */
    private fun forgetArrivalPoint() {
        arrivalSerial++
        val saved = savedPoint ?: return
        savedPoint = null
        c.store.setArrivalPoint(saved.routeId, saved.previous?.first, saved.previous?.second)
    }

    /** '도착했어요' 뒤: 도착 안내가 끝나고 위치 저장이 돌아왔으면(또는 시간이 다 됐으면) 동행을 마친다. */
    private fun finishIfDone() {
        val f = finishing ?: return
        if ((f.lineDone && f.locationPending <= 0) || now() >= f.deadlineMs) end()
    }

    /** 도착한 뒤에도 끝내지 않고 두면 스스로 끝낸다(백그라운드에 불필요하게 남지 않도록). */
    private fun autoEndAfterArrival() {
        val at = arrivedAtMs ?: return
        if (now() - at >= AUTO_END_AFTER_ARRIVAL_MS) end()
    }

    /**
     * 운전자와 동승자가 함께 내린 차(P단, 운전석·동승 좌석 모두 비어 있음)가 [AUTO_END_VACANT_MS] 동안 그대로면 스스로 끝낸다
     * (검토 NEW-2·NEW-7). 내비 없이 간 길이나 도착을 거둔 뒤에는 도착 뒤 자동 종료가 걸려 있지 않아, 그대로 두면 동행이
     * 끝나지 않고 남았다가 나중에 다른 곳으로 갈 때 옛 가는 곳을 말한다. 기록은 [end] 가 남긴다. 좌석 신호가 없는 차는
     * 차가 비었는지 알 수 없어 이것이 걸리지 않는다.
     */
    private fun autoEndWhenVacant() {
        val e = engine ?: return
        val since = e.snapshot(now()).vacantSinceMs ?: return
        if (now() - since >= AUTO_END_VACANT_MS) end()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_RECORD_SEC = 20
        const val AUTO_END_AFTER_ARRIVAL_MS = 10 * 60_000L
        /** 운전자와 동승자가 함께 내린 차가 이만큼 비어 있으면 동행을 마친다. */
        const val AUTO_END_VACANT_MS = 10 * 60_000L
        /** '도착했어요' 뒤 도착 안내를 기다리는 가장 긴 시간(내비 안내·통화를 기다리고 포커스를 다시 묻는 시간보다 길게). */
        const val FINISH_DEADLINE_MS = 30_000L
        const val ARRIVE_SITUATION = "arrive"
    }
}
