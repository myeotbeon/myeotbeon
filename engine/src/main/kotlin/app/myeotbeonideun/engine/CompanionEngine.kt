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
 * 쓰는 순서: [start] → ([onSignals] | [onTick] | [requestRepeat] | [quiet] | [onPassengerAsked])* → [finish]
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

    // 신호 반응(문·창문·안전띠)
    private var doorAlertAt: Long? = null
    private var windowAlerted = false
    private var windowAlertAt = 0L
    private var beltSince: Long? = null
    private var beltStep = 0

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
    private var notice: String? = null
    private var noticeSituation: String? = null
    private var noticeUrgent = false
    private var noticeAt = 0L
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
    )

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
        quietUntil = null // 운전자가 직접 시킨 말이므로 '잠시 조용히'를 푼다
        val out = ArrayList<EngineAction>()
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

    /** 동행을 끝내고 기록(횟수와 시간)을 돌려준다. */
    fun finish(nowMs: Long): TripRecord {
        phase = Phase.ENDED
        pending.clear()
        return TripRecord(
            startedAtMs = startedAt,
            durationSec = ((nowMs - startedAt) / 1000).toInt().coerceAtLeast(0),
            counts = counts,
        )
    }

    fun snapshot(nowMs: Long): EngineSnapshot = EngineSnapshot(
        phase = phase,
        stage = stage,
        stopReason = stopReason,
        quietUntilMs = quietUntil?.takeIf { nowMs < it },
        lastLine = lastLine,
        lastLineAtMs = lastLineAt,
        driverNotice = notice,
        driverNoticeUrgent = noticeUrgent,
        cardTitle = script.at("card_title", 0) ?: config.place,
        cardStage = cardStageLine(),
        counts = counts,
    )

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
        expireNotice(now)
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
            if (last == null || now - last >= params.doorRepeatMs) {
                if (last == null) counts = counts.copy(safetyNotices = counts.safetyNotices + 1)
                doorAlertAt = now
                notify("door_open_d", urgent = true, now, out)
                offer(Candidate("door_open_p", Priority.SAFETY, SpeechKind.SAFETY, now, ignoreQuiet = true), now, out)
            }
        } else {
            doorAlertAt = null
            if (noticeSituation == "door_open_d") clearNotice()
        }

        // 창문: 동승자에게 손은 안에 두라고, 운전자 화면에 한 줄.
        val pct = s.seatWindowOpenPercent
        if (pct != null) {
            if (windowAlerted && pct < params.windowThresholdPercent - params.windowHysteresisPercent) {
                windowAlerted = false
                if (noticeSituation == "window_open_d") clearNotice()
            }
            if (driving && pct >= params.windowThresholdPercent &&
                (!windowAlerted || now - windowAlertAt >= params.windowRepeatMs)
            ) {
                if (!windowAlerted) counts = counts.copy(safetyNotices = counts.safetyNotices + 1)
                windowAlerted = true
                windowAlertAt = now
                notify("window_open_d", urgent = false, now, out)
                offer(Candidate("window_open_p", Priority.SAFETY, SpeechKind.SAFETY, now), now, out)
            }
        }

        // 안전띠: 부드러운 권유 두 번 뒤 운전자에게 알린다.
        if (driving && s.seatBeltBuckled == false && s.seatOccupied != false) {
            val since = beltSince ?: now.also {
                beltSince = it
                beltStep = 0
                counts = counts.copy(safetyNotices = counts.safetyNotices + 1)
            }
            if (beltStep <= params.beltPrompts && now - since >= beltStep * params.beltPromptGapMs) {
                if (beltStep < params.beltPrompts) {
                    offer(Candidate("belt_off_p", Priority.SAFETY, SpeechKind.SAFETY, now), now, out)
                } else {
                    notify("belt_off_d", urgent = true, now, out)
                }
                beltStep++
            }
        } else {
            beltSince = null
            beltStep = 0
            if (noticeSituation == "belt_off_d") clearNotice()
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
        out += Speak(text, c.situation, c.priority, c.kind, interrupt)
        busyUntil = now + estimateSpeechMs(text)
        busyPriority = c.priority
        lastPassengerSpeechAt = now
        lastLine = text
        lastLineAt = now
        counts = when (c.kind) {
            SpeechKind.PROACTIVE -> counts.copy(proactive = counts.proactive + 1)
            SpeechKind.DRIVER_REQUEST -> counts.copy(driverRequested = counts.driverRequested + 1)
            SpeechKind.ANSWER -> counts.copy(answered = counts.answered + 1)
            SpeechKind.STOP -> counts.copy(stopNotices = counts.stopNotices + 1)
            SpeechKind.INTRO, SpeechKind.SAFETY -> counts
        }
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

    private fun notify(situation: String, urgent: Boolean, now: Long, out: MutableList<EngineAction>) {
        val text = script.next(situation) ?: return
        out += DriverNotice(text, situation, urgent)
        // 급한 알림(주행 중 문 열림 등)이 떠 있는 동안에는 덜 급한 알림이 그 한 줄을 차지하지 않는다.
        if (notice != null && noticeUrgent && !urgent) return
        notice = text
        noticeSituation = situation
        noticeUrgent = urgent
        noticeAt = now
    }

    private fun clearNotice() {
        notice = null
        noticeSituation = null
        noticeUrgent = false
    }

    private fun expireNotice(now: Long) {
        if (notice != null && !noticeUrgent && now - noticeAt >= params.noticeHoldMs) clearNotice()
    }

    companion object {
        const val MOVING_KPH = 5f
        const val STATIONARY_KPH = 1f
        /** 남은 시간·거리가 이 안이고 P단이면 도착으로 본다. */
        const val ARRIVE_REMAIN_SEC = 60
        const val ARRIVE_REMAIN_METERS = 150
        /** 남은 시간이 이보다 많고 5분 이하일 때만 "5분쯤 남았어요"를 쓴다. */
        const val FIVE_MIN_LINE_MIN_SEC = 3 * 60
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
