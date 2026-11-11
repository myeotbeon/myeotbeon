package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.intent.PassengerIntent
import app.myeotbeonideun.engine.journey.IntervalPolicy
import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.journey.StageCalculator
import app.myeotbeonideun.engine.model.Motion
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.Signals
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.record.TripRecord
import app.myeotbeonideun.engine.script.ScriptBook
import app.myeotbeonideun.engine.script.ScriptEngine
import app.myeotbeonideun.engine.script.ScriptValidator
import app.myeotbeonideun.engine.script.SlotValues
import app.myeotbeonideun.engine.stop.StopClassifier
import app.myeotbeonideun.engine.stop.StopInput
import app.myeotbeonideun.engine.stop.StopReason

/**
 * 「몇 번이든」의 두뇌. 차량 신호와 시각을 받아 "지금 누구에게 무슨 말을 할지"를 정한다.
 *
 * - 안드로이드에 의존하지 않는다. 시각은 모두 바깥에서 넣어 주므로(nowMs) 시험에서 그대로 재현된다.
 * - 문장은 대본(ScriptBook)의 고정 문장만 쓴다. 실행 중에 문장을 만들지 않는다.
 * - 한 번의 이동에 인스턴스 하나를 쓴다. 한 스레드에서만 부른다.
 *
 * 쓰는 순서: [start] → ([onSignals] | [onTick] | [requestRepeat] | [quiet] | [onPassengerAsked] | [onUtteranceResult])* → [finish]
 *
 * 기록의 횟수는 바깥(음성)이 [onUtteranceResult] 로 '실제로 소리 냈다'고 알려 준 말만 센다.
 */
class CompanionEngine(
    private val book: ScriptBook,
    val config: TripConfig,
    val params: EngineParams = EngineParams(),
) {
    private val script = ScriptEngine(book, SlotValues(config.honorific, config.place, config.driver))

    private var phase = Phase.READY
    private var stage = Stage.JUST_DEPARTED
    private var stopReason = StopReason.NONE
    private var signals = Signals()

    private var startedAt = 0L
    private var departedAt: Long? = null
    private var totalSec: Int? = null
    private var navArrived = false

    // 멈춤 판단에 쓰는 시간 기록
    private var parkedSince: Long? = null
    private var driverDoorOpenedSincePark: Boolean? = null
    private var stationarySince: Long? = null
    private var speedSeenMoving = false
    private var waypointAnnounced = false
    private var awayNoticeAt = 0L
    private var stopNoticeAt: Long? = null

    // 신호 반응(문·창문·안전띠). 한 번의 일(문 한 번 열림 등)마다 번호를 붙여, 되풀이 안내를 한 건으로 센다.
    private var doorAlertAt: Long? = null
    private var doorAlertCount = 0
    private var doorEvent = 0L
    private var windowAlerted = false
    private var windowAlertAt = 0L
    private var windowEvent = 0L
    private var beltSince: Long? = null
    private var beltStep = 0
    private var beltEvent = 0L
    private var nextEvent = 0L

    // 말 번호와, 실제로 소리 냈는지 기다리는 말
    private var nextSpeakId = 0L
    private val awaitingResult = HashMap<Long, Utterance>()
    private val countedEvents = HashSet<Long>()

    // 말하기 통로
    private var busyUntil = Long.MIN_VALUE
    private var busyPriority = Priority.PROACTIVE
    private var lastPassengerSpeechAt: Long? = null
    private val pending = ArrayList<Candidate>()
    private var stageToAnnounce: Stage? = null
    private var answerCount = 0
    private var quietUntil: Long? = null

    // 화면에 보일 것
    private var lastLine: String? = null
    private var lastLineAt: Long? = null
    /** 운전자 화면의 알림. 상황마다 따로 두고 가장 급한 것을 보인다. 하나가 풀리면 남은 것이 다시 보인다. */
    private val notices = LinkedHashMap<String, Notice>()
    private var counts = TripCounts()

    private data class Candidate(
        val situation: String,
        val priority: Priority,
        val kind: SpeechKind,
        val createdAt: Long,
        /** 이 멈춤 이유가 이어지는 동안에만 말한다. null 이면 따지지 않는다. */
        val validWhile: StopReason? = null,
        /** 화제 전환 한마디를 붙일 수 있는 말인지. */
        val redirectEligible: Boolean = false,
        /** '잠시 조용히' 중에도 하는 말인지. */
        val ignoreQuiet: Boolean = false,
        /** 안전 안내가 어느 일(문 한 번 열림 등)에 대한 것인지. 같은 일의 되풀이 안내는 한 건으로 센다. */
        val eventKey: Long? = null,
    )

    /** 내보낸 말이 무엇이었는지. 실제로 소리 냈다는 결과가 오면 그때 센다. */
    private data class Utterance(val kind: SpeechKind, val eventKey: Long?)

    /**
     * 운전자 화면의 알림 한 줄.
     * @property untilMs 이때 지운다. null 이면 급한 알림은 그 일이 풀릴 때까지, 급하지 않은 알림은 [EngineParams.noticeHoldMs] 동안
     */
    private data class Notice(val situation: String, val text: String, val urgent: Boolean, val atMs: Long, val untilMs: Long?)

    // ───────────────────────── 바깥에서 부르는 것 ─────────────────────────

    /** 동행을 시작한다. 차가 먼저 자기를 소개한다(명세 3.1). */
    fun start(nowMs: Long): List<EngineAction> {
        startedAt = nowMs
        phase = Phase.READY
        val out = ArrayList<EngineAction>()
        emit(Candidate("intro", Priority.ANSWER, SpeechKind.INTRO, nowMs, ignoreQuiet = true), nowMs, out, false)
        return out
    }

    /** 차량 신호가 바뀌었다. */
    fun onSignals(s: Signals, nowMs: Long): List<EngineAction> {
        signals = s
        return evaluate(nowMs)
    }

    /** 내비가 목적지 도착을 알렸다. */
    fun onNavArrived(nowMs: Long): List<EngineAction> {
        if (phase == Phase.DRIVING) navArrived = true
        return evaluate(nowMs)
    }

    /** 시간이 흘렀다. 1초쯤마다 부른다. */
    fun onTick(nowMs: Long): List<EngineAction> = evaluate(nowMs)

    /** 운전자가 '다시 말씀드리기'를 눌렀다(또는 Gleo 로 시켰다). */
    fun requestRepeat(nowMs: Long): List<EngineAction> {
        if (phase == Phase.ENDED) return emptyList()
        val out = ArrayList<EngineAction>()
        if (isQuiet(nowMs)) {
            // 운전자가 직접 시킨 말이므로 '잠시 조용히'를 푼다. 말없이 풀리지 않게 운전자 화면에 한 줄로 알린다.
            notify("quiet_off_d", urgent = false, nowMs, out)
        }
        quietUntil = null
        val situation = currentFactSituation()
        offer(
            Candidate(
                situation, Priority.ANSWER, SpeechKind.DRIVER_REQUEST, nowMs,
                redirectEligible = isRedirectEligible(situation),
            ),
            nowMs, out,
        )
        out += evaluate(nowMs)
        return out
    }

    /** 동승자의 물음을 알아들었다(실험 기능). */
    fun onPassengerAsked(intent: PassengerIntent, nowMs: Long): List<EngineAction> {
        if (phase == Phase.ENDED || isQuiet(nowMs)) return emptyList()
        val near = stage == Stage.NEAR || stage == Stage.SOON
        val whenSituation = if (near) "ask_when_near" else "ask_when_onway"
        val stopSituation = activeStopSituation()
        val situation = when (intent) {
            PassengerIntent.WHERE -> "ask_where"
            PassengerIntent.WHEN -> if (phase == Phase.ARRIVED) "arrive" else whenSituation
            PassengerIntent.ARRIVED_Q -> when {
                phase == Phase.ARRIVED -> "arrive"
                stopSituation != null -> stopSituation
                else -> whenSituation
            }
            PassengerIntent.WHY_STOPPED -> stopSituation ?: currentFactSituation()
            // 집으로 가는 길이면 "…에 들렀다가 집에 가요"가 맞지 않는다. 어디 가는지를 그대로 말한다.
            PassengerIntent.GO_HOME -> if (headingHome()) "ask_where" else "ask_gohome"
        }
        val out = ArrayList<EngineAction>()
        offer(
            Candidate(
                situation, Priority.ANSWER, SpeechKind.ANSWER, nowMs,
                redirectEligible = isRedirectEligible(situation),
            ),
            nowMs, out,
        )
        out += evaluate(nowMs)
        return out
    }

    /**
     * '잠시 조용히'. 먼저 말하기·대답·멈춤 안내·창문과 안전띠 권유를 쉰다.
     * 주행 중 문 열림, 도착, 운전자가 자리를 비운 동안의 안내는 그대로 한다(운전자 화면의 알림 한 줄도 그대로).
     */
    fun quiet(nowMs: Long, durationMs: Long = params.quietMs): List<EngineAction> {
        if (phase == Phase.ENDED) return emptyList()
        quietUntil = nowMs + durationMs
        pending.removeAll { !it.ignoreQuiet }
        stageToAnnounce = null
        val out = ArrayList<EngineAction>()
        if (nowMs < busyUntil && busyPriority < Priority.SAFETY) {
            busyUntil = nowMs
            out += Silence
        }
        return out
    }

    /** '잠시 조용히'를 푼다. */
    fun cancelQuiet() {
        quietUntil = null
    }

    /**
     * 실제 음성 재생 상태를 알려 준다(선택). 엔진은 문장 길이로 말하는 시간을 어림하는데,
     * 실제 재생이 더 길거나 짧으면 이것으로 맞춘다.
     */
    fun onSpeechState(speaking: Boolean, nowMs: Long) {
        busyUntil = if (speaking) maxOf(busyUntil, nowMs + 1_000) else minOf(busyUntil, nowMs)
    }

    /**
     * 엔진이 시킨 말([Speak])을 실제로 소리 내 읽었는지 알려 준다. 기록의 횟수는 이것으로만 올라간다.
     *
     * @param id [Speak.id]
     * @param spoken true = 끝까지 소리 내 읽음. false = 소리 내지 못함(오디오 포커스 거절, 음성 엔진 없음,
     *   다른 말에 끊김) — 그 말은 [TripCounts.skipped] 로 따로 센다
     */
    fun onUtteranceResult(id: Long, spoken: Boolean) {
        if (phase == Phase.ENDED) return
        val u = awaitingResult.remove(id) ?: return
        if (!spoken) {
            counts = counts.copy(skipped = counts.skipped + 1)
            return
        }
        counts = when (u.kind) {
            SpeechKind.PROACTIVE -> counts.copy(proactive = counts.proactive + 1)
            SpeechKind.DRIVER_REQUEST -> counts.copy(driverRequested = counts.driverRequested + 1)
            SpeechKind.ANSWER -> counts.copy(answered = counts.answered + 1)
            SpeechKind.STOP -> counts.copy(stopNotices = counts.stopNotices + 1)
            // 같은 일(문 한 번 열림 등)의 되풀이 안내는 처음 소리 낸 것 하나만 센다.
            SpeechKind.SAFETY ->
                if (u.eventKey == null || countedEvents.add(u.eventKey)) counts.copy(safetyNotices = counts.safetyNotices + 1)
                else counts
            SpeechKind.INTRO -> counts
        }
    }

    /** 동행을 끝내고 기록(횟수와 시간)을 돌려준다. 결과를 아직 모르는 말은 세지 않는다. */
    fun finish(nowMs: Long): TripRecord {
        phase = Phase.ENDED
        pending.clear()
        awaitingResult.clear()
        return TripRecord(
            startedAtMs = startedAt,
            durationSec = ((nowMs - startedAt) / 1000).toInt().coerceAtLeast(0),
            counts = counts,
        )
    }

    fun snapshot(nowMs: Long): EngineSnapshot {
        val top = topNotice()
        return EngineSnapshot(
            phase = phase,
            stage = stage,
            stopReason = stopReason,
            quietUntilMs = quietUntil?.takeIf { nowMs < it },
            lastLine = lastLine,
            lastLineAtMs = lastLineAt,
            driverNotice = top?.text,
            driverNoticeUrgent = top?.urgent ?: false,
            cardTitle = script.at("card_title", 0) ?: config.place,
            cardStage = cardStageLine(),
            counts = counts,
        )
    }

    // ───────────────────────── 판단 ─────────────────────────

    private fun evaluate(now: Long): List<EngineAction> {
        val out = ArrayList<EngineAction>()
        if (phase == Phase.ENDED) return out
        updateTrackers(now)
        evaluateSafety(now, out)
        evaluateDeparture(now)
        evaluateStop(now, out)
        evaluateStage(now, out)
        flush(now, out)
        evaluateProactive(now, out)
        expireNotices(now)
        return out
    }

    private fun updateTrackers(now: Long) {
        val s = signals
        if (s.motion == Motion.PARKED) {
            if (parkedSince == null) {
                parkedSince = now
                driverDoorOpenedSincePark = if (s.driverDoorOpen == null) null else false
            }
            if (s.driverDoorOpen == true) driverDoorOpenedSincePark = true
        } else {
            parkedSince = null
            driverDoorOpenedSincePark = null
            waypointAnnounced = false
        }

        // 속도 신호가 살아 있다고 확인되면(한 번이라도 움직임이 보이면) 속도로, 아니면 브레이크로 정지를 본다.
        // 에뮬레이터는 속도를 바꿀 수 없어 0 에 머무르므로, 속도만 믿으면 계속 서 있는 것으로 잘못 본다.
        val speed = s.speedKph
        if (speed != null && speed >= MOVING_KPH) speedSeenMoving = true
        val stationary = if (speedSeenMoving && speed != null) speed < STATIONARY_KPH else s.brakePressed == true
        if (s.motion == Motion.IN_DRIVE && stationary) {
            if (stationarySince == null) stationarySince = now
        } else {
            stationarySince = null
        }
    }

    /** 문·창문·안전띠(명세 3.4). 주행 단일 때만 본다. */
    private fun evaluateSafety(now: Long, out: MutableList<EngineAction>) {
        val s = signals
        val driving = s.motion == Motion.IN_DRIVE

        // 문: 운전자에게 즉시 소리와 화면으로, 동승자에게는 닫아 달라고.
        if (driving && s.seatDoorOpen == true) {
            val last = doorAlertAt
            // 열려 있는 동안 되풀이하되, 몇 번 알린 뒤로는 간격을 늘린다(덜 닫힌 문으로 끝없이 울리지 않게).
            val gap = if (doorAlertCount < params.doorFastRepeats) params.doorRepeatMs else params.doorSlowRepeatMs
            if (last == null || now - last >= gap) {
                if (last == null) doorEvent = ++nextEvent
                doorAlertAt = now
                doorAlertCount++
                notify("door_open_d", urgent = true, now, out)
                offer(
                    Candidate("door_open_p", Priority.SAFETY, SpeechKind.SAFETY, now, ignoreQuiet = true, eventKey = doorEvent),
                    now, out,
                )
            }
        } else {
            doorAlertAt = null
            doorAlertCount = 0
            clearNotice("door_open_d")
        }

        // 창문: 동승자에게 손은 안에 두라고, 운전자 화면에 한 줄.
        val pct = s.seatWindowOpenPercent
        if (pct != null) {
            if (windowAlerted && pct < params.windowThresholdPercent - params.windowHysteresisPercent) {
                windowAlerted = false
                clearNotice("window_open_d")
            }
            if (driving && pct >= params.windowThresholdPercent &&
                (!windowAlerted || now - windowAlertAt >= params.windowRepeatMs)
            ) {
                // 횟수는 말을 실제로 소리 냈을 때 센다('잠시 조용히' 중이라 화면에만 알린 것은 세지 않는다).
                if (!windowAlerted) windowEvent = ++nextEvent
                windowAlerted = true
                windowAlertAt = now
                notify("window_open_d", urgent = false, now, out)
                offer(Candidate("window_open_p", Priority.SAFETY, SpeechKind.SAFETY, now, eventKey = windowEvent), now, out)
            }
        }

        // 안전띠: 부드러운 권유 두 번 뒤 운전자에게 알린다.
        if (driving && s.seatBeltBuckled == false && s.seatOccupied != false) {
            val since = beltSince ?: now.also {
                beltSince = it
                beltStep = 0
                beltEvent = ++nextEvent
            }
            if (beltStep <= params.beltPrompts && now - since >= beltStep * params.beltPromptGapMs) {
                if (beltStep < params.beltPrompts) {
                    offer(Candidate("belt_off_p", Priority.SAFETY, SpeechKind.SAFETY, now, eventKey = beltEvent), now, out)
                } else {
                    notify("belt_off_d", urgent = true, now, out)
                }
                beltStep++
            }
        } else {
            beltSince = null
            beltStep = 0
            clearNotice("belt_off_d")
        }
    }

    private fun evaluateDeparture(now: Long) {
        if (phase != Phase.READY) return
        val departed = when (signals.motion) {
            Motion.IN_DRIVE -> true
            Motion.PARKED -> false
            Motion.UNKNOWN -> now - startedAt >= params.unknownMotionDepartMs
        }
        if (!departed) return
        phase = Phase.DRIVING
        departedAt = now
        // 짧은 길이라 이미 '곧 도착' 구간이어도 첫마디는 출발 안내다. 구간은 화면과 다음 말에만 반영한다.
        stage = computeStage(now)
        stageToAnnounce = Stage.JUST_DEPARTED
    }

    /** 멈춤의 순간(명세 3.3). */
    private fun evaluateStop(now: Long, out: MutableList<EngineAction>) {
        if (phase == Phase.ARRIVED) return
        val s = signals
        val driving = phase == Phase.DRIVING
        val prev = stopReason
        val reason = StopClassifier.classify(
            prev,
            StopInput(
                motion = s.motion,
                stationaryForMs = stationarySince?.let { now - it },
                parkedForMs = parkedSince?.let { now - it },
                congested = s.nav.congested,
                driverSeatOccupied = s.driverSeatOccupied,
                driverDoorOpenedSincePark = driverDoorOpenedSincePark,
                passengerSeatOccupied = s.seatOccupied,
                nearDestination = driving && isNearDestination(),
                navArrived = driving && navArrived,
                departed = driving,
            ),
        )
        stopReason = reason
        when (reason) {
            StopReason.ARRIVED -> {
                phase = Phase.ARRIVED
                stage = Stage.ARRIVED
                stageToAnnounce = null
                pending.clear()
                offer(Candidate("arrive", Priority.STOP, SpeechKind.STOP, now, ignoreQuiet = true), now, out)
            }
            StopReason.DRIVER_AWAY -> {
                // 운전자가 차에 없으므로 '잠시 조용히'와 상관없이 말한다. 혼자 남은 때가 가장 불안하다.
                waypointAnnounced = true
                if (prev != StopReason.DRIVER_AWAY) {
                    awayNoticeAt = now
                    offer(stopCandidate("driver_away", now, StopReason.DRIVER_AWAY, ignoreQuiet = true), now, out)
                } else if (now - awayNoticeAt >= params.driverAwayRepeatMs) {
                    awayNoticeAt = now
                    offer(stopCandidate("driver_away_again", now, StopReason.DRIVER_AWAY, ignoreQuiet = true), now, out)
                }
            }
            StopReason.DRIVER_BACK ->
                offer(stopCandidate("driver_back", now, validWhile = null, ignoreQuiet = true), now, out)
            StopReason.WAYPOINT_STOP ->
                if (!waypointAnnounced) {
                    waypointAnnounced = true
                    offer(stopCandidate("stop_waypoint", now, StopReason.WAYPOINT_STOP), now, out)
                }
            StopReason.SIGNAL_WAIT, StopReason.TRAFFIC -> {
                val entered = prev != StopReason.SIGNAL_WAIT && prev != StopReason.TRAFFIC
                val last = stopNoticeAt
                val due = if (entered) last == null || now - last >= params.stopCooldownMs
                else last != null && now - last >= params.stopRepeatMs
                if (due) {
                    stopNoticeAt = now
                    offer(stopCandidate(reason.situation!!, now, reason), now, out)
                }
            }
            StopReason.NONE -> Unit
        }
    }

    private fun stopCandidate(
        situation: String,
        now: Long,
        validWhile: StopReason?,
        ignoreQuiet: Boolean = false,
    ) = Candidate(situation, Priority.STOP, SpeechKind.STOP, now, validWhile = validWhile, ignoreQuiet = ignoreQuiet)

    /** 구간이 바뀌면 먼저 말한다. 구간은 앞으로만 간다(어르신께 "절반" 뒤에 "가는 중"이라고 말하지 않기 위해). */
    private fun evaluateStage(now: Long, out: MutableList<EngineAction>) {
        if (phase != Phase.DRIVING) return
        val computed = computeStage(now)
        if (computed.ordinal > stage.ordinal) {
            stage = computed
            stageToAnnounce = computed
        }
        val s = stageToAnnounce ?: return
        if (isQuiet(now)) {
            stageToAnnounce = null
            return
        }
        if (stopReason != StopReason.NONE || !channelFree(now)) return
        stageToAnnounce = null
        emit(
            Candidate(
                stageSituation(s), Priority.PROACTIVE, SpeechKind.PROACTIVE, now,
                redirectEligible = s != Stage.JUST_DEPARTED,
            ),
            now, out, false,
        )
    }

    /** 일정 간격마다 먼저 말한다. 어떤 말이든 한 뒤로 간격을 다시 센다. */
    private fun evaluateProactive(now: Long, out: MutableList<EngineAction>) {
        if (phase != Phase.DRIVING || stopReason != StopReason.NONE) return
        if (isQuiet(now) || stageToAnnounce != null || !channelFree(now)) return
        val intervalSec = IntervalPolicy.intervalSec(config.interval, signals.nav.remainSec) ?: return
        val last = lastPassengerSpeechAt ?: departedAt ?: return
        if (now - last < intervalSec * 1000L) return
        val situation = stageSituationForRepeat()
        emit(
            Candidate(situation, Priority.PROACTIVE, SpeechKind.PROACTIVE, now, redirectEligible = true),
            now, out, false,
        )
    }

    private fun computeStage(now: Long): Stage {
        val nav = signals.nav
        nav.remainSec?.let { r -> totalSec = maxOf(totalSec ?: 0, nav.totalSec ?: 0, r) }
        val elapsed = (((now - (departedAt ?: now)) / 1000).toInt()).coerceAtLeast(0)
        return StageCalculator.stageOf(totalSec, nav.remainSec, elapsed, nav.nearDestination)
    }

    private fun isNearDestination(): Boolean {
        val nav = signals.nav
        return nav.nearDestination ||
            (nav.remainSec != null && nav.remainSec <= ARRIVE_REMAIN_SEC) ||
            (nav.remainMeters != null && nav.remainMeters <= ARRIVE_REMAIN_METERS)
    }

    /** 지금 이 순간의 사실을 말하는 상황 이름. '다시 말씀드리기'가 쓰는 것. */
    private fun currentFactSituation(): String = when {
        phase == Phase.ARRIVED -> "arrive"
        phase == Phase.READY -> "ask_where"
        else -> activeStopSituation() ?: stageSituationForRepeat()
    }

    /** 지금 서 있는 이유를 말하는 상황 이름. 서 있지 않으면 null */
    private fun activeStopSituation(): String? = when (stopReason) {
        StopReason.SIGNAL_WAIT, StopReason.TRAFFIC, StopReason.WAYPOINT_STOP -> stopReason.situation
        StopReason.DRIVER_AWAY -> "driver_away_again"
        else -> null
    }

    /** 출발 직후에 다시 말할 때는 "이제 출발해요" 대신 어디 가는지를 말한다. */
    private fun stageSituationForRepeat(): String =
        if (stage == Stage.JUST_DEPARTED) "ask_where" else stageSituation(stage)

    /**
     * 그 구간에서 하는 말의 상황 이름. 분 숫자는 "5분쯤" 하나뿐이고(명세 3.2),
     * 그 말은 남은 시간이 3분 넘고 5분 이하일 때만 사실이다. 그보다 가까우면 "거의 다 왔어요"로 말한다.
     */
    private fun stageSituation(s: Stage): String =
        if (s == Stage.SOON && !fiveMinuteLineFits()) Stage.NEAR.situation else s.situation

    private fun fiveMinuteLineFits(): Boolean {
        val remain = signals.nav.remainSec ?: return false
        return remain > FIVE_MIN_LINE_MIN_SEC && remain <= StageCalculator.SOON_SEC
    }

    /** 가는 곳이 집인지. '집에서 쉬기'를 골랐거나 가는 곳 이름이 집이면 그렇게 본다. */
    private fun headingHome(): Boolean =
        config.purpose == Purpose.REST || config.place.filterNot { it.isWhitespace() } in HOME_NAMES

    private fun isRedirectEligible(situation: String): Boolean =
        situation.startsWith("ask_") || situation.startsWith("stage_")

    /**
     * 동승자 카드 둘째 줄. 대본의 카드 문구는 두 가지 꼴을 모두 받는다.
     * - 구간별 상황: card_stage_depart · onway · half · near · (5min) · arrive, 그리고 서 있을 때의 card_stop,
     *   운전자가 내려 있을 때의 card_driver_away
     * - 한 상황에 구간 순서대로 6줄: card_stage
     * 변형이 여럿이어도 첫째 것만 쓴다(한 번의 이동에서 카드의 말투가 바뀌지 않게).
     */
    private fun cardStageLine(): String {
        if (phase != Phase.ARRIVED) {
            val stopCard = when (stopReason) {
                StopReason.DRIVER_AWAY -> "card_driver_away"
                StopReason.SIGNAL_WAIT, StopReason.TRAFFIC, StopReason.WAYPOINT_STOP -> "card_stop"
                else -> null
            }
            stopCard?.let { script.at(it, 0) }?.let { return it }
        }
        stage.cardSituations.firstNotNullOfOrNull { script.at(it, 0) }?.let { return it }
        if (script.count("card_stage") == Stage.entries.size) {
            script.at("card_stage", stage.ordinal)?.let { return it }
        }
        return stage.cardLabel
    }

    // ───────────────────────── 말하기 통로와 우선순위 ─────────────────────────

    private fun isQuiet(now: Long): Boolean = quietUntil?.let { now < it } ?: false

    private fun channelFree(now: Long): Boolean =
        busyUntil == Long.MIN_VALUE || now >= busyUntil + params.minGapMs

    /**
     * 말할 거리를 내놓는다. 통로가 비어 있으면 바로 말하고, 누가 말하는 중이면
     * 우선순위가 높을 때만 끊고 말한다. 그렇지 않으면 기다리게 한다.
     */
    private fun offer(c: Candidate, now: Long, out: MutableList<EngineAction>) {
        if (isQuiet(now) && !c.ignoreQuiet) return
        val speaking = now < busyUntil
        when {
            channelFree(now) -> emit(c, now, out, interrupt = false)
            // 안전 알림은 다른 말을 끊는다. 안전 알림끼리는 끊지 않고 차례로 말한다(문 안내가 창문 안내에 잘리지 않게).
            c.priority == Priority.SAFETY && !(speaking && busyPriority == Priority.SAFETY) ->
                emit(c, now, out, interrupt = speaking)
            speaking && c.priority > busyPriority -> emit(c, now, out, interrupt = true)
            else -> {
                pending.removeAll { it.situation == c.situation }
                pending += c
            }
        }
    }

    /** 기다리던 말 가운데 우선순위가 가장 높은 것 하나를 말한다. */
    private fun flush(now: Long, out: MutableList<EngineAction>) {
        pending.removeAll {
            now - it.createdAt > params.pendingTtlMs ||
                (it.validWhile != null && it.validWhile != stopReason) ||
                (isQuiet(now) && !it.ignoreQuiet)
        }
        if (pending.isEmpty() || !channelFree(now)) return
        val next = pending.maxWithOrNull(compareBy<Candidate> { it.priority }.thenByDescending { it.createdAt })
            ?: return
        // 같은 우선순위에서는 먼저 온 것부터
        val first = pending.filter { it.priority == next.priority }.minByOrNull { it.createdAt } ?: next
        pending.remove(first)
        emit(first, now, out, interrupt = false)
    }

    private fun emit(c: Candidate, now: Long, out: MutableList<EngineAction>, interrupt: Boolean) {
        var text = script.next(c.situation) ?: return
        if (c.redirectEligible) {
            answerCount++
            if (answerCount % params.redirectEvery == 0) redirectLine()?.let { text = "$text $it" }
        }
        val id = ++nextSpeakId
        out += Speak(text, c.situation, c.priority, c.kind, interrupt, id)
        // 여기서는 세지 않는다. 실제로 소리 냈다는 결과(onUtteranceResult)가 오면 센다.
        awaitingResult[id] = Utterance(c.kind, c.eventKey)
        busyUntil = now + estimateSpeechMs(text)
        busyPriority = c.priority
        lastPassengerSpeechAt = now
        lastLine = text
        lastLineAt = now
    }

    /** 화제 전환 한마디. 목적이 없으면 붙이지 않는다. 직접 쓴 한마디는 그대로 읽는다. */
    private fun redirectLine(): String? = when (config.purpose) {
        Purpose.NONE -> null
        Purpose.CUSTOM -> config.customPurposeLine.trim().takeIf { it.isNotEmpty() }?.let {
            if (it.last() in ".!?") it else "$it."
        }
        else -> config.purpose.situation?.let { script.next(it) }?.let(::withoutCall)
    }

    /**
     * 대본의 화제 전환 줄도 호칭으로 시작한다(명세 10절). 대답 뒤에 이어 붙일 때는 그 호칭 부름을 떼어,
     * 한 번의 말에 호칭이 두 번 들리지 않게 한다("어머니, … 가서 같이 식사하세요.").
     */
    private fun withoutCall(line: String): String {
        val call = config.honorific.trim()
        if (call.isEmpty() || !line.startsWith(call)) return line
        val rest = line.substring(call.length).trimStart()
        return if (rest.startsWith(",")) rest.substring(1).trimStart() else line
    }

    /**
     * 운전자 화면에 알림을 올린다. 상황마다 따로 보관하고 화면에는 가장 급한 것 하나를 보인다([topNotice]).
     * @param holdMs 이만큼 지나면 지운다(이미 지난 일을 알리는 급한 알림). null 이면 [Notice.untilMs] 설명대로
     */
    private fun notify(situation: String, urgent: Boolean, now: Long, out: MutableList<EngineAction>, holdMs: Long? = null) {
        val text = script.next(situation) ?: return
        out += DriverNotice(text, situation, urgent)
        notices[situation] = Notice(situation, text, urgent, now, holdMs?.let { now + it })
    }

    private fun clearNotice(situation: String) {
        notices.remove(situation)
    }

    /** 화면에 보일 알림: 급한 것 > 급한 정도(문 > 안전띠 > 창문) > 나중 것. */
    private fun topNotice(): Notice? =
        notices.values.maxWithOrNull(
            compareBy<Notice>({ it.urgent }, { NOTICE_RANK[it.situation] ?: 0 }, { it.atMs }),
        )

    private fun expireNotices(now: Long) {
        notices.values.removeAll { n ->
            val until = n.untilMs
            if (until != null) now >= until else !n.urgent && now - n.atMs >= params.noticeHoldMs
        }
    }

    companion object {
        const val MOVING_KPH = 5f
        const val STATIONARY_KPH = 1f
        /** 남은 시간·거리가 이 안이고 P단이면 도착으로 본다. */
        const val ARRIVE_REMAIN_SEC = 60
        const val ARRIVE_REMAIN_METERS = 150
        /** 남은 시간이 이보다 많고 5분 이하일 때만 "5분쯤 남았어요"를 쓴다. */
        const val FIVE_MIN_LINE_MIN_SEC = 3 * 60
        /** 운전자 알림의 급한 정도. 큰 것이 화면 한 줄을 먼저 차지한다. */
        private val NOTICE_RANK = mapOf(
            "door_open_d" to 5,
            "belt_off_d" to 2,
            "window_open_d" to 1,
            "quiet_off_d" to 0,
        )

        /** 가는 곳 이름이 이것이면 집으로 가는 길로 본다(공백은 빼고 견준다). */
        val HOME_NAMES = setOf("집", "우리집", "댁", "자택")

        /** 느리게 말할 때 걸리는 시간 어림: 음절당 0.24초 + 문장 사이 쉼. */
        fun estimateSpeechMs(text: String): Long {
            val syllables = ScriptValidator.countSyllables(text)
            val sentences = ScriptValidator.splitSentences(text).size.coerceAtLeast(1)
            return 700L + syllables * 240L + sentences * 350L
        }
    }
}
