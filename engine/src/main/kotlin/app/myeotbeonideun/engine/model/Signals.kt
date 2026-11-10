package app.myeotbeonideun.engine.model

/** 변속단. 차가 알려 주지 않으면 UNKNOWN. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 차가 서 있는 상태인지(P단), 주행 단인지. */
enum class Motion { PARKED, IN_DRIVE, UNKNOWN }

/** 좌석 위치. 운전석은 왼쪽(국내 차량) 기준. */
enum class SeatPosition(val label: String) {
    DRIVER("운전석"),
    FRONT_PASSENGER("앞자리 옆 좌석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽");

    companion object {
        /** 동승자가 앉을 수 있는 자리. 자동 선택은 이 순서로 한다(뒷좌석 오른쪽 우선). */
        val PASSENGER_SEATS = listOf(REAR_RIGHT, REAR_LEFT, FRONT_PASSENGER)
    }
}

/** 내비게이션이 알려 주는 길 정보. 모르는 값은 null. */
data class NavState(
    val guiding: Boolean = false,
    val destinationName: String? = null,
    /** 출발할 때의 전체 예상 시간(초). */
    val totalSec: Int? = null,
    /** 남은 시간(초). */
    val remainSec: Int? = null,
    /** 남은 거리(m). */
    val remainMeters: Int? = null,
    /** 경로에 정체 구간이 있다고 내비가 알려 준 상태. */
    val congested: Boolean = false,
    /** 목적지 근처(근접 알림 300m 등)에 들어왔다. */
    val nearDestination: Boolean = false,
)

/**
 * 차량에서 읽은 값 그대로의 묶음. 좌석별 값은 지원하는 자리만 들어 있다.
 * (지원하지 않는 신호는 맵에 없거나 null 이고, 엔진은 그 기능만 끈다.)
 */
data class VehicleState(
    val gear: Gear = Gear.UNKNOWN,
    val speedKph: Float? = null,
    val brakePressed: Boolean? = null,
    /** CarUxRestrictions 가 주행 규제를 요구하는 상태인지. */
    val uxRestricted: Boolean? = null,
    val seatOccupied: Map<SeatPosition, Boolean> = emptyMap(),
    val doorOpen: Map<SeatPosition, Boolean> = emptyMap(),
    /** 0 = 닫힘, 100 = 완전히 열림. */
    val windowOpenPercent: Map<SeatPosition, Int> = emptyMap(),
    val beltBuckled: Map<SeatPosition, Boolean> = emptyMap(),
    val nav: NavState = NavState(),
) {
    /** 운전석을 뺀, 사람이 앉아 있는 자리. */
    fun occupiedPassengerSeats(): List<SeatPosition> =
        SeatPosition.PASSENGER_SEATS.filter { seatOccupied[it] == true }

    /** 동승자가 앉은 자리를 기준으로 엔진이 쓰는 신호만 뽑는다. */
    fun forSeat(seat: SeatPosition): Signals = Signals(
        gear = gear,
        speedKph = speedKph,
        brakePressed = brakePressed,
        uxRestricted = uxRestricted,
        driverSeatOccupied = seatOccupied[SeatPosition.DRIVER],
        driverDoorOpen = doorOpen[SeatPosition.DRIVER],
        seatOccupied = seatOccupied[seat],
        seatDoorOpen = doorOpen[seat],
        seatWindowOpenPercent = windowOpenPercent[seat],
        seatBeltBuckled = beltBuckled[seat],
        nav = nav,
    )
}

/** 엔진이 보는 신호. 좌석 값은 동승자가 앉은 자리의 것이다. null = 차가 알려 주지 않음. */
data class Signals(
    val gear: Gear = Gear.UNKNOWN,
    val speedKph: Float? = null,
    val brakePressed: Boolean? = null,
    val uxRestricted: Boolean? = null,
    val driverSeatOccupied: Boolean? = null,
    val driverDoorOpen: Boolean? = null,
    val seatOccupied: Boolean? = null,
    val seatDoorOpen: Boolean? = null,
    val seatWindowOpenPercent: Int? = null,
    val seatBeltBuckled: Boolean? = null,
    val nav: NavState = NavState(),
) {
    /**
     * 변속단을 알면 그것으로, 모르면 주행 규제 상태로 판단한다.
     * (Pleos 문서: 주행/정차 판정의 공식 경로는 CarUxRestrictions)
     */
    val motion: Motion
        get() = when (gear) {
            Gear.PARK -> Motion.PARKED
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> Motion.IN_DRIVE
            Gear.UNKNOWN -> when (uxRestricted) {
                true -> Motion.IN_DRIVE
                false -> Motion.PARKED
                null -> Motion.UNKNOWN
            }
        }
}
