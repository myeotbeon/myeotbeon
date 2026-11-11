package app.myeotbeonideun.signal

import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 신호가 어디서 오는지. */
enum class SourceKind { VEHICLE, DEMO }

enum class SourceStatus {
    IDLE,
    /** 차량 서비스에 붙는 중. */
    CONNECTING,
    READY,
    /** 차량 신호를 하나도 얻지 못했다(Pleos 서비스가 없는 기기). */
    UNAVAILABLE,
}

/**
 * 지금 이 차에서 쓸 수 있는 신호. 시작할 때 check*Capabilities 로 확인하고,
 * 쓸 수 없는 신호는 그 기능만 끈다(명세 4).
 */
data class Capabilities(
    val seatOccupancy: Boolean = false,
    val seatBelt: Boolean = false,
    val door: Boolean = false,
    val window: Boolean = false,
    val brake: Boolean = false,
    val gear: Boolean = false,
    val speed: Boolean = false,
    val uxRestrictions: Boolean = false,
    val navigation: Boolean = false,
    val location: Boolean = false,
) {
    val any: Boolean
        get() = seatOccupancy || seatBelt || door || window || brake || gear || speed ||
            uxRestrictions || navigation || location
}

/** 내비게이션이 한 번 알리고 지나가는 일. */
sealed interface NavEvent {
    data object Arrived : NavEvent
    data object WaypointArrived : NavEvent
    data class RouteStarted(val destinationName: String) : NavEvent
}

/** 한 지점의 좌표. 기기 안에만 둔다. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 차량 신호의 출처. 구현은 둘이다.
 * - [PleosSignalSource]: Pleos Connect SDK 와 AAOS Car API
 * - [DemoSignalSource]: 체험 모드(모의 신호)
 * 엔진과 화면은 이 인터페이스만 본다.
 */
interface SignalSource {
    val kind: SourceKind
    val status: StateFlow<SourceStatus>
    val capabilities: StateFlow<Capabilities>
    val state: StateFlow<VehicleState>
    val navEvents: SharedFlow<NavEvent>

    /** 신호 수신을 시작한다. 여러 번 불러도 한 번만 시작한다. */
    fun start()

    /** 리스너를 모두 해제한다. */
    fun stop()

    /** 동행 중일 때만 내비 정보를 주기적으로 다시 묻는다(평소에는 폴링하지 않는다). */
    fun setTripActive(active: Boolean) {}

    /** 이 지점 300m 안에 들어오면 nav.nearDestination 을 켠다. 지원하지 않으면 아무 일도 하지 않는다. */
    fun watchArrivalPoint(point: GeoPoint?) {}

    /** 마지막으로 알려진 위치. 지원하지 않거나 권한이 없으면 null 을 돌려준다. */
    fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) = onResult(null)
}

/**
 * 실차 신호 출처([PleosSignalSource]). 체험 모드 출처에는 없는 것을 더한다.
 * 앱은 이 인터페이스만 보므로, 시험에서는 가짜를 바꿔 끼운다.
 */
interface VehicleSignalSource : SignalSource {
    /** 연결 과정에서 있었던 일(개발자 패널에 보인다). 개인정보는 넣지 않는다. */
    val diagnostics: StateFlow<List<String>>

    /** 속도 권한을 새로 받았다. 속도 신호만 다시 붙인다(다른 신호와 동행 중의 값은 그대로). */
    fun refreshSpeedPermission()

    /**
     * SDK 가 넘긴 그대로의 좌석 점유 값(해석 전). 개발자 패널에 보인다.
     * [state] 의 seatOccupied 는 [seatInterpretation] 으로 해석한 값이다(앉은 자리 자동 선택·운전자 이탈 판단에 쓴다).
     */
    val rawSeatOccupied: StateFlow<Map<SeatPosition, Boolean>>

    /** 좌석 점유 값을 지금 어떻게 읽는지(그대로·반대로, 무엇으로 정했는지). */
    val seatInterpretation: StateFlow<SeatInterpretation>

    /** 환경설정의 '좌석 값 읽기'(자동·그대로·반대로). */
    fun setSeatReading(reading: SeatReading)

    /**
     * 운전자가 화면을 조작하는 출발 전 설정(P단)이다. 자동이면 지금 운전석 원시 값으로 해석을 정한다.
     * 동행 중에는 바꾸지 않는다([setTripActive] 가 동행 시작 순간에 정하고 끝날 때까지 둔다).
     */
    fun noteDriverAtScreen()
}
