package app.myeotbeonideun

import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.signal.Capabilities
import app.myeotbeonideun.signal.GeoPoint
import app.myeotbeonideun.signal.NavEvent
import app.myeotbeonideun.signal.SeatReading
import app.myeotbeonideun.signal.SeatValueReader
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.signal.SourceStatus
import app.myeotbeonideun.signal.VehicleSignalSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 시험용 실차 신호. Pleos SDK 없이 값만 손으로 넣는다. 구독 시작·해제 횟수를 센다.
 * 좌석 점유는 PleosSignalSource 와 같은 [SeatValueReader] 로 해석한다([setRawSeat] 이 SDK 가 넘기는 원시 값).
 */
open class FakeVehicleSource : VehicleSignalSource {
    override val kind = SourceKind.VEHICLE
    override val status = MutableStateFlow(SourceStatus.IDLE)
    override val capabilities = MutableStateFlow(Capabilities())
    override val state = MutableStateFlow(VehicleState())
    override val navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    override val diagnostics = MutableStateFlow<List<String>>(emptyList())

    val seats = SeatValueReader()
    override val rawSeatOccupied = MutableStateFlow<Map<SeatPosition, Boolean>>(emptyMap())
    override val seatInterpretation = MutableStateFlow(seats.interpretation)

    /** 원시 값이 들어온 시각(좌석 값 판별의 유예 시간용). */
    var nowMs = 0L

    var started = false
        private set
    var starts = 0
        private set
    var stops = 0
        private set
    var tripActive = false
        private set
    var speedRefreshes = 0
        private set
    var driverAtScreenNotes = 0
        private set

    override fun start() {
        if (started) return
        started = true
        starts++
    }

    override fun stop() {
        if (!started) return
        started = false
        stops++
    }

    override fun setTripActive(active: Boolean) {
        tripActive = active
        if (active) seats.holdForTrip(nowMs) else seats.releaseTrip()
        publishSeats()
    }

    override fun refreshSpeedPermission() {
        speedRefreshes++
    }

    /** [lastKnownLocation] 이 돌려줄 위치(FusedLocation 대신). */
    var location: GeoPoint? = null
    var locationRequests = 0
        private set

    override fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) {
        locationRequests++
        onResult(location)
    }

    /** SDK 가 넘기는 것처럼 한 자리의 좌석 점유 원시 값을 넣는다. */
    fun setRawSeat(position: SeatPosition, value: Boolean?) {
        seats.setRaw(position, value, nowMs)
        publishSeats()
    }

    override fun setSeatReading(reading: SeatReading) {
        seats.setReading(reading)
        publishSeats()
    }

    override fun noteDriverAtScreen() {
        driverAtScreenNotes++
        seats.driverAtScreen()
        publishSeats()
    }

    private fun publishSeats() {
        rawSeatOccupied.value = seats.raw
        seatInterpretation.value = seats.interpretation
        state.update { it.copy(seatOccupied = seats.seatOccupied) }
    }
}

/** 차량용 기기처럼 도는 앱(시험용). 실차 신호는 [FakeVehicleSource]. */
class AutomotiveTestApp : App() {
    override fun createContainer(): AppContainer = AppContainer(this, isAutomotive = true, vehicleSource = FakeVehicleSource())
}
