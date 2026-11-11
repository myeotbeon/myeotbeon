package app.myeotbeonideun

import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.signal.Capabilities
import app.myeotbeonideun.signal.NavEvent
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.signal.SourceStatus
import app.myeotbeonideun.signal.VehicleSignalSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 시험용 실차 신호. Pleos SDK 없이 값만 손으로 넣는다. 구독 시작·해제 횟수를 센다.
 */
open class FakeVehicleSource : VehicleSignalSource {
    override val kind = SourceKind.VEHICLE
    override val status = MutableStateFlow(SourceStatus.IDLE)
    override val capabilities = MutableStateFlow(Capabilities())
    override val state = MutableStateFlow(VehicleState())
    override val navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    override val diagnostics = MutableStateFlow<List<String>>(emptyList())

    var started = false
        private set
    var starts = 0
        private set
    var stops = 0
        private set
    var tripActive = false
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
    }
}

/** 차량용 기기처럼 도는 앱(시험용). 실차 신호는 [FakeVehicleSource]. */
class AutomotiveTestApp : App() {
    override fun createContainer(): AppContainer = AppContainer(this, isAutomotive = true, vehicleSource = FakeVehicleSource())
}
