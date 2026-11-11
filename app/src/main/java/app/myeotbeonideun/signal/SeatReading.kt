package app.myeotbeonideun.signal

import app.myeotbeonideun.engine.model.SeatPosition

// 이 파일은 안드로이드에 의존하지 않는다(순수 Kotlin). 판단 규칙은 SeatReadingTest 가 확인한다.

/** 환경설정의 '좌석 값 읽기'. 기본은 자동. */
enum class SeatReading { AUTO, AS_IS, INVERTED }

/** 좌석 점유 Boolean 을 그대로 쓸지, 뒤집어 쓸지. */
enum class SeatPolarity { AS_IS, INVERTED }

/** 지금의 해석을 무엇으로 정했는지(화면에 보인다). */
enum class SeatPolarityBasis {
    /** 운전자가 화면을 조작하는 때(출발 전 설정 P단, 동행 시작 순간)의 운전석 값으로 정했다. */
    DRIVER_SEAT,
    /** 자동인데 운전석 값을 아직 받지 못해 그대로 둔다. */
    NO_DRIVER_VALUE,
    /** 환경설정에서 직접 골랐다. */
    MANUAL,
}

/**
 * 좌석 점유 값을 지금 어떻게 읽는지.
 * @property heldForTrip 동행 중이라 자동 판별을 바꾸지 않는 중인지
 */
data class SeatInterpretation(
    val polarity: SeatPolarity = SeatPolarity.AS_IS,
    val basis: SeatPolarityBasis = SeatPolarityBasis.NO_DRIVER_VALUE,
    val heldForTrip: Boolean = false,
)

/** 운전석 기준 판별. */
object SeatPolarityRule {
    /**
     * 운전석에 사람이 있다고 알 수 있는 때의 운전석 원시 값으로 해석을 정한다.
     * 원시 값이 false(빈자리라고 옴)면 반대로, true 면 그대로. 값이 없으면 정하지 않는다(null).
     */
    fun fromDriverSeat(rawDriverOccupied: Boolean?): SeatPolarity? = when (rawDriverOccupied) {
        false -> SeatPolarity.INVERTED
        true -> SeatPolarity.AS_IS
        null -> null
    }

    /** 원시 값에 해석을 입힌다. 값이 없는 자리는 그대로 없다. */
    fun apply(polarity: SeatPolarity, raw: Map<SeatPosition, Boolean>): Map<SeatPosition, Boolean> =
        if (polarity == SeatPolarity.AS_IS) raw else raw.mapValues { !it.value }
}

/**
 * 좌석 점유 원시 값(SDK 가 넘기는 Boolean)을 받아 해석한 값을 낸다. 앉은 자리 자동 선택과 운전자 이탈 판단은
 * 모두 [seatOccupied](해석한 값)를 쓴다.
 *
 * 2026-11-11 Pleos Connect 에뮬레이터에서 SeatOccupancyListener·getCurrentSeatOccupancy 가 넘기는 Boolean 이
 * 가상 콘솔의 Empty/Occupied 와 반대였다(원시 값은 AAOS 표준대로 1=빈자리·2=점유로 오는데 Boolean 이 뒤집혀 있음).
 * 실차의 극성은 모른다. 그래서
 * - 자동(기본): 운전자가 화면을 조작하는 출발 전 설정(P단)과 동행 시작 순간에는 운전석에 사람이 있다.
 *   그때 운전석 원시 값이 false 면 '반대로', true 면 '그대로'로 정하고, 동행 중에는 바꾸지 않는다.
 *   운전석 값이 없으면 '그대로'.
 * - 그대로 / 반대로: 환경설정에서 직접 고른다(바로 적용).
 *
 * 안전띠는 극성을 확인하지 못해(가상 콘솔에 좌석별 안전띠가 없음) 해석하지 않고 그대로 쓴다.
 * 한 스레드(메인)에서만 부른다.
 *
 * @param tripStartGraceMs 동행을 시작할 때 운전석 값이 아직 없으면, 이 시간 안에 처음 들어오는 값으로 정한다
 *   (화면 밖 Gleo 로 시작해 신호를 막 붙인 때 등)
 */
class SeatValueReader(
    reading: SeatReading = SeatReading.AUTO,
    private val tripStartGraceMs: Long = TRIP_START_GRACE_MS,
) {
    var reading: SeatReading = reading
        private set

    /** SDK 가 넘긴 그대로의 값. 값이 없는 자리는 맵에 없다. */
    var raw: Map<SeatPosition, Boolean> = emptyMap()
        private set

    /** 운전석 기준으로 정한 해석. 아직 정하지 못했으면 null(그대로). 직접 고른 동안에도 계속 정해 둔다. */
    private var autoPolarity: SeatPolarity? = null
    private var heldForTrip = false
    private var awaitDriverUntil: Long? = null

    val interpretation: SeatInterpretation
        get() = when (reading) {
            SeatReading.AS_IS -> SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.MANUAL, heldForTrip)
            SeatReading.INVERTED -> SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.MANUAL, heldForTrip)
            SeatReading.AUTO -> autoPolarity
                ?.let { SeatInterpretation(it, SeatPolarityBasis.DRIVER_SEAT, heldForTrip) }
                ?: SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.NO_DRIVER_VALUE, heldForTrip)
        }

    /** 해석한 좌석 점유 값. */
    val seatOccupied: Map<SeatPosition, Boolean>
        get() = SeatPolarityRule.apply(interpretation.polarity, raw)

    /** 한 자리의 원시 값이 들어왔다(null = 값을 잃음). */
    fun setRaw(seat: SeatPosition, value: Boolean?, nowMs: Long) {
        raw = if (value == null) raw - seat else raw + (seat to value)
        if (seat == SeatPosition.DRIVER && value != null) {
            val until = awaitDriverUntil
            if (until != null) {
                awaitDriverUntil = null
                if (nowMs <= until) autoPolarity = SeatPolarityRule.fromDriverSeat(value)
            }
        }
    }

    /** 신호 구독을 놓았다. 원시 값만 비운다(정한 해석은 같은 차의 성질이라 남긴다). */
    fun clearRaw() {
        raw = emptyMap()
    }

    fun setReading(reading: SeatReading) {
        this.reading = reading
    }

    /** 운전자가 화면을 조작하는 출발 전 설정(P단)이다. 동행 중에는 바꾸지 않는다. 운전석 값이 없으면 앞의 판별을 둔다. */
    fun driverAtScreen() {
        if (heldForTrip) return
        SeatPolarityRule.fromDriverSeat(raw[SeatPosition.DRIVER])?.let { autoPolarity = it }
    }

    /**
     * 동행을 시작한다: 지금 운전석 값으로 정하고 동행이 끝날 때까지 바꾸지 않는다.
     * 운전석 값이 아직 없으면 [tripStartGraceMs] 안에 처음 들어오는 값으로 정한다.
     */
    fun holdForTrip(nowMs: Long) {
        heldForTrip = true
        val decided = SeatPolarityRule.fromDriverSeat(raw[SeatPosition.DRIVER])
        if (decided != null) {
            autoPolarity = decided
            awaitDriverUntil = null
        } else {
            awaitDriverUntil = nowMs + tripStartGraceMs
        }
    }

    /** 동행이 끝났다. 다음 출발 전 설정에서 다시 판별한다. */
    fun releaseTrip() {
        heldForTrip = false
        awaitDriverUntil = null
    }

    companion object {
        const val TRIP_START_GRACE_MS = 10_000L
    }
}
