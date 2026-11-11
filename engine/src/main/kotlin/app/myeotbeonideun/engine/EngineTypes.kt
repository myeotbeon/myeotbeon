package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.stop.StopReason

/** 여정의 큰 단계. */
enum class Phase { READY, DRIVING, ARRIVED, ENDED }

/**
 * 말의 우선순위. 뒤에 있을수록 높다: 안전 알림 > 멈춤 > 대답 > 먼저 말하기.
 * 높은 것이 낮은 것을 끊고 말할 수 있고, 낮은 것은 기다리거나 건너뛴다.
 */
enum class Priority { PROACTIVE, ANSWER, STOP, SAFETY }

/** 무엇 때문에 한 말인지. 기록 집계에 쓴다. */
enum class SpeechKind { INTRO, PROACTIVE, DRIVER_REQUEST, ANSWER, STOP, SAFETY }

/** 도착을 무엇으로 알았는지. */
enum class ArrivalSource {
    /** 내비의 도착 이벤트. */
    NAV_EVENT,
    /** 목적지 근처(남은 60초·150m, 근접 알림) + P단. */
    NEAR_DESTINATION,
    /** 운전자가 알렸다('도착했어요'). 내비 없이 가는 길에서는 이것뿐이다. */
    DRIVER,
}

/** 엔진이 바깥(음성·화면)에 시키는 일. */
sealed interface EngineAction

/**
 * 동승자에게 말한다.
 * @property interrupt true 면 지금 하던 말을 끊고 말한다
 * @property id 말마다 붙는 번호. 실제로 소리 냈는지를 [CompanionEngine.onUtteranceResult] 로 알려 줄 때 쓴다.
 *   기록의 횟수는 그 결과로만 올라간다(엔진이 말하기로 한 것만으로는 세지 않는다)
 */
data class Speak(
    val text: String,
    val situation: String,
    val priority: Priority,
    val kind: SpeechKind,
    val interrupt: Boolean,
    val id: Long = 0,
) : EngineAction

/**
 * 운전자에게 화면 한 줄로 알린다.
 * @property urgent true 면 소리도 함께 낸다(주행 중 문 열림 등)
 */
data class DriverNotice(val text: String, val situation: String, val urgent: Boolean) : EngineAction

/** 하던 말을 멈춘다('잠시 조용히'). */
data object Silence : EngineAction

/** 도착했다(phase 가 ARRIVED 가 됨). 도착 지점을 기억하는 계기와 도착 뒤 자동 종료의 시작점으로 쓴다. */
data class Arrived(val source: ArrivalSource) : EngineAction

/**
 * 도착 판정을 거뒀다: 도착 뒤 P단을 거쳐 주행 단으로 15초 넘게 달렸거나, 남은 시간으로 도착을 본 같은 경로의
 * 남은 시간이 다시 늘었다. phase 는 DRIVING 으로 돌아간다. 도착 뒤의 자동 종료 예약을 푼다.
 */
data object ArrivalCancelled : EngineAction

/** 시간 기준값. 시험과 시연에서 바꿀 수 있게 한곳에 모았다. */
data class EngineParams(
    /** 한 말이 끝나고 다음 말을 하기까지의 최소 간격. */
    val minGapMs: Long = 1_500,
    /** 신호 대기·정체 안내를 한 뒤 다음 멈춤에서 다시 안내하기까지의 쿨다운. */
    val stopCooldownMs: Long = 60_000,
    /** 같은 멈춤이 이어질 때 다시 안내하는 간격. */
    val stopRepeatMs: Long = 120_000,
    /** 운전자가 내려 있는 동안 다시 안심시키는 간격(명세: 60~90초). */
    val driverAwayRepeatMs: Long = 75_000,
    /** 운전자 이탈에 다시 들어왔을 때 "내렸어요"를 다시 하기까지의 최소 간격(좌석 값 흔들림 대비). */
    val awayReentryGapMs: Long = 30_000,
    /** 운전자가 없는 동안 문 열림·자리 비움으로 다시 안심시키는 말 사이의 최소 간격. */
    val awayEventGapMs: Long = 10_000,
    /** 운전자가 없는 동안 생긴 일(문 열림·자리 비움)을 돌아온 운전자 화면에 보여 두는 시간. */
    val awayEventNoticeMs: Long = 60_000,
    /** 좌석 점유 값이 바뀌면 이만큼 그대로일 때만 받는다(흔들림 완충). */
    val seatDebounceMs: Long = 2_500,
    /** 주행 중 문이 계속 열려 있을 때 운전자에게 다시 알리는 간격. */
    val doorRepeatMs: Long = 15_000,
    /** 이 횟수만큼 알린 뒤로는 [doorSlowRepeatMs] 간격으로 늘린다(덜 닫힌 문으로 끝없이 울리지 않게). */
    val doorFastRepeats: Int = 3,
    val doorSlowRepeatMs: Long = 60_000,
    /** 창문이 이만큼(%) 열리면 안내한다. */
    val windowThresholdPercent: Int = 30,
    /** 이만큼 더 닫혀야 다음 열림을 새 일로 본다. */
    val windowHysteresisPercent: Int = 10,
    val windowRepeatMs: Long = 120_000,
    /** 안전띠 권유 횟수와 간격. 권유가 끝나면 운전자에게 알린다. */
    val beltPrompts: Int = 2,
    val beltPromptGapMs: Long = 30_000,
    /** '잠시 조용히' 시간. */
    val quietMs: Long = 10 * 60_000L,
    /** 대답 몇 번에 한 번 화제 전환 한마디를 붙일지. */
    val redirectEvery: Int = 2,
    /** 기다리는 말이 이 시간을 넘기면 버린다(이미 지난 사실을 말하지 않게). */
    val pendingTtlMs: Long = 20_000,
    /** 운전자 화면의 알림 한 줄을 지우는 시간(긴급하지 않은 것). */
    val noticeHoldMs: Long = 15_000,
    /** 내비 남은 시간으로 정한 구간은 같은 구간이 이만큼 이어지거나 … */
    val stageConfirmMs: Long = 10_000,
    /** … 내비 표본이 이만큼 연달아 같은 구간을 가리킬 때만 앞으로 간다(표본 하나로 굳히지 않는다). */
    val stageConfirmSamples: Int = 2,
    /** 도착 뒤 P단을 거쳐 주행 단으로 이만큼 이어지면 도착이 아니었던 것으로 보고 거둔다. */
    val rearmDriveMs: Long = 15_000,
)

/** 화면이 그리는 데 필요한 엔진 상태. */
data class EngineSnapshot(
    val phase: Phase = Phase.READY,
    val stage: Stage = Stage.JUST_DEPARTED,
    val stopReason: StopReason = StopReason.NONE,
    /** '잠시 조용히'가 끝나는 시각. 조용히 중이 아니면 null */
    val quietUntilMs: Long? = null,
    /** 마지막으로 동승자에게 한 말. */
    val lastLine: String? = null,
    val lastLineAtMs: Long? = null,
    /** 운전자에게 보이는 알림 한 줄. 여러 개가 떠 있으면 가장 급한 것(풀리면 남은 것이 다시 보인다). */
    val driverNotice: String? = null,
    val driverNoticeUrgent: Boolean = false,
    /** 동승자 카드 두 줄. */
    val cardTitle: String = "",
    val cardStage: String = "",
    val counts: TripCounts = TripCounts(),
    /**
     * 운전자가 '도착했어요'로 도착을 알릴 수 있는 때(출발한 뒤 아직 도착 전이고 P단일 때. 변속단·주행 규제를 모르면 false).
     * 내비 없이 가는 길은 차가 도착을 스스로 알 수 없어 이것이 유일한 길이다([CompanionEngine.driverConfirmedArrival]).
     */
    val canConfirmArrival: Boolean = false,
)
