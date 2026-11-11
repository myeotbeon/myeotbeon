package app.myeotbeonideun.engine.stop

import app.myeotbeonideun.engine.model.Motion

/** 차가 멈춘 이유. 어르신은 신호 대기·정체·잠깐 정차를 '도착'과 구별하기 어렵다(명세 3.3). */
enum class StopReason(val situation: String?) {
    NONE(null),
    /**
     * 주행 단인데 20초 넘게 서 있다. '서 있음'은 속도 신호가 살아 있으면 속도로, 아니면 브레이크를 밟고 있는 것으로 본다
     * (CompanionEngine — 속도 권한이 없으면 서행 중 브레이크도 '서 있음'이고, 오토홀드로 발을 떼고 서 있으면 모른다).
     */
    SIGNAL_WAIT("stop_signal"),
    /** 서 있고, 내비가 경로에 정체가 있다고 알려 줬다. */
    TRAFFIC("stop_traffic"),
    /** 도착 전에 P단으로 섰다. 내비가 아직 넉넉히 남았다고 알려 줄 때만 이것으로 본다("아직 다 온 건 아니에요"). */
    WAYPOINT_STOP("stop_waypoint"),
    /**
     * 출발한 뒤 P단으로 섰는데 도착인지 잠깐 정차인지 모른다(내비 남은 시간·근접 정보가 없거나 곧 도착 구간).
     * 내비 없이 가는 길의 목적지에서 "아직 다 온 건 아니에요"라고 하지 않도록, 도착을 부정하지 않는 말을 쓴다.
     */
    PARKED_UNSURE("stop_parked_neutral"),
    /** P단에서 운전자가 내리고 동승자만 남았다. 들어선 뒤로는 운전자가 돌아올 때까지 이어진다. */
    DRIVER_AWAY("driver_away"),
    /** 내렸던 운전자가 돌아왔다(한 번만 나오는 값). */
    DRIVER_BACK("driver_back"),
    ARRIVED("arrive"),
}

/**
 * 분류에 쓰는 값. null = 차가 그 신호를 주지 않음.
 *
 * @property stationaryForMs 주행 단에서 서 있은 시간. 서 있지 않으면 null
 * @property parkedForMs P단에 둔 시간. P단이 아니면 null
 * @property driverDoorOpenedSincePark P단에 둔 뒤 운전석 문이 한 번이라도 열렸는지. 문 신호가 없으면 null
 */
data class StopInput(
    val motion: Motion,
    val stationaryForMs: Long? = null,
    val parkedForMs: Long? = null,
    val congested: Boolean = false,
    val driverSeatOccupied: Boolean? = null,
    val driverDoorOpenedSincePark: Boolean? = null,
    val passengerSeatOccupied: Boolean? = null,
    val nearDestination: Boolean = false,
    val navArrived: Boolean = false,
    /** 내비가 남은 시간이 넉넉하다(곧 도착 구간보다 멀다)고 알려 준다. 이때만 P단 정차를 경유지 정차로 본다. */
    val farFromDestination: Boolean = false,
    /** 이미 출발한 여정인지. 출발 전의 P단은 '경유지 정차'가 아니다. */
    val departed: Boolean = true,
)

object StopClassifier {
    /** 주행 단에서 이만큼 서 있으면 신호 대기·정체로 본다. */
    const val STATIONARY_MS = 20_000L
    /** P단에 두고 이만큼 지나면 경유지 정차로 본다(운전자 이탈 판단이 먼저 나오게 하는 여유). */
    const val WAYPOINT_DWELL_MS = 5_000L

    /**
     * 판단 순서: 도착 → 운전자 복귀 → 운전자 이탈 → 경유지 정차(또는 도착인지 모르는 정차) → 정체 → 신호 대기.
     * 더 구체적인 사실이 앞선다.
     */
    fun classify(previous: StopReason, input: StopInput): StopReason {
        if (input.navArrived) return StopReason.ARRIVED
        val parked = input.motion == Motion.PARKED
        if (parked && input.nearDestination) return StopReason.ARRIVED

        if (previous == StopReason.DRIVER_AWAY && input.driverSeatOccupied == true) {
            return StopReason.DRIVER_BACK
        }
        // 이탈은 운전자가 돌아올 때까지 이어진다. 그사이 동승자가 자리를 떠도 풀지 않는다(그때가 가장 알아야 할 때다).
        if (previous == StopReason.DRIVER_AWAY && parked && input.driverSeatOccupied == false) {
            return StopReason.DRIVER_AWAY
        }
        if (parked &&
            input.driverSeatOccupied == false &&
            input.passengerSeatOccupied != false &&
            input.driverDoorOpenedSincePark != false
        ) {
            return StopReason.DRIVER_AWAY
        }
        if (parked && input.departed && (input.parkedForMs ?: 0L) >= WAYPOINT_DWELL_MS) {
            return if (input.farFromDestination) StopReason.WAYPOINT_STOP else StopReason.PARKED_UNSURE
        }
        if (input.motion == Motion.IN_DRIVE && (input.stationaryForMs ?: 0L) >= STATIONARY_MS) {
            return if (input.congested) StopReason.TRAFFIC else StopReason.SIGNAL_WAIT
        }
        return StopReason.NONE
    }
}
