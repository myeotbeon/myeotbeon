package app.myeotbeonideun.signal

import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.VehicleState

// 이 파일은 안드로이드에 의존하지 않는다(순수 Kotlin). DrivingLockTest 가 확인한다.

/** 주행 중 화면 잠금과 차량 신호 구독을 정하는 규칙. */
object DrivingLock {
    /**
     * 이 신호로 본 주행 규제: CarUxRestrictions 값이 있으면 그것, 없으면 변속단이 P 가 아닌지.
     * 둘 다 모르면 false 다(알 수 없다. 이때 잠그면 출발 전 설정도 열 수 없다).
     */
    fun restricted(state: VehicleState): Boolean = state.uxRestricted ?: when (state.gear) {
        Gear.PARK, Gear.UNKNOWN -> false
        Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> true
    }

    /**
     * 화면 잠금. 지금 쓰는 신호 출처(체험 모드면 모의 신호)의 규제와 실차의 규제를 OR 로 묶는다.
     * 체험 모드를 켜고 실제로 달려도 실차 값으로 잠긴다.
     * @param vehicle 실차 신호. 차량용 기기가 아니면 null
     */
    fun screenRestricted(current: VehicleState, vehicle: VehicleState?): Boolean =
        restricted(current) || (vehicle != null && restricted(vehicle))

    /**
     * 운전자가 차에 앉아 화면을 조작하는 정차로 볼 수 있는지(좌석 값 자동 판별의 근거).
     * P단이면 그렇고, 변속단을 모르면 주행 규제가 아닐 때만.
     */
    fun parkedForCalibration(state: VehicleState): Boolean = when (state.gear) {
        Gear.PARK -> true
        Gear.UNKNOWN -> state.uxRestricted != true
        Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> false
    }

    /** 차량 신호를 구독할지: 차량용 기기에서, 화면이 보이거나 동행 중일 때만(백그라운드에서 동행이 없으면 놓는다). */
    fun shouldSubscribe(isAutomotive: Boolean, foreground: Boolean, tripActive: Boolean): Boolean =
        isAutomotive && (foreground || tripActive)
}
