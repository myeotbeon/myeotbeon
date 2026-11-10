package app.myeotbeonideun.signal

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.myeotbeonideun.engine.model.NavState
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Connect 에서 차량 신호를 받는다.
 *
 * | 신호 | API |
 * |---|---|
 * | 좌석 점유(운전석 포함) | Vehicle.getSeat(): checkSeatOccupancyCapabilities / getCurrentSeatOccupancy / registerSeatOccupancy |
 * | 안전띠 | Vehicle.getSeat(): checkSeatBeltBuckledCapabilities / getCurrentSeatBeltBuckledState / registerSeatBeltBuckledState |
 * | 문 | Vehicle.getDoor(): checkDoorPositionCapabilities / getCurrentDoorPosition / registerDoorPosition |
 * | 창문 | Vehicle.getWindow(): checkWindowPositionCapabilities / getCurrentWindowPosition / registerWindowPosition |
 * | 브레이크 | Vehicle.getBrake(): checkBrakePedalStatusCapabilities / getCurrentBrakePedalStatus / registerBrakePedalStatus |
 * | 주행 규제·변속단·속도 | AAOS CarUxRestrictionsManager, CarPropertyManager ([CarApiBridge]) |
 * | 목적지·남은 시간·도착 | NaviHelper 리스너 |
 * | 도착 근접 | FusedLocation.addProximityAlert |
 *
 * 원칙
 * - 조회(get·register)만 한다. 차량 제어(set), 경고등(telltale), LLM 은 쓰지 않는다.
 * - 모든 호출은 check*Capabilities 와 onFailed 를 거친다. 실패한 신호는 값이 비어 있을 뿐이고,
 *   엔진은 그 신호를 쓰는 기능만 끈다.
 * - 콜백은 어느 스레드에서 올지 문서에 없어 메인 스레드로 옮겨 처리한다.
 */
class PleosSignalSource(context: Context) : SignalSource {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(SourceStatus.IDLE)
    private val _capabilities = MutableStateFlow(Capabilities())
    private val _state = MutableStateFlow(VehicleState())
    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())

    override val kind = SourceKind.VEHICLE
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()
    override val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()
    override val state: StateFlow<VehicleState> = _state.asStateFlow()
    override val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    /** 연결 과정에서 있었던 일(개발자 패널에 보인다). 개인정보는 넣지 않는다. */
    val diagnostics: StateFlow<List<String>> = _diagnostics.asStateFlow()

    private var vehicle: Vehicle? = null
    private var carApi: CarApiBridge? = null
    private var navi: NaviHelper? = null
    private var location: FusedLocation? = null
    private val releasers = ArrayList<() -> Unit>()
    private var started = false

    private var tripActive = false
    private var routing = false
    private var proximityAlertId: String? = null

    // ───────────────────────── 시작과 끝 ─────────────────────────

    override fun start() {
        if (started) return
        started = true
        _status.value = SourceStatus.CONNECTING
        guarded("Vehicle SDK") { startVehicle() }
        guarded("AAOS Car API") { startCarApi() }
        guarded("NaviHelper") { startNavi() }
        guarded("FusedLocation") { startLocation() }
        // 몇 초 안에 신호를 하나도 얻지 못하면 Pleos 서비스가 없는 기기로 본다.
        main.postDelayed({
            if (_status.value == SourceStatus.CONNECTING) {
                _status.value = if (_capabilities.value.any) SourceStatus.READY else SourceStatus.UNAVAILABLE
            }
        }, CONNECT_TIMEOUT_MS)
    }

    override fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        releasers.forEach { runCatching(it) }
        releasers.clear()
        runCatching { carApi?.stop() }
        runCatching { navi?.removeListener(naviListener) }
        runCatching { navi?.release() }
        runCatching { location?.release() }
        runCatching { vehicle?.release() }
        vehicle = null
        carApi = null
        navi = null
        location = null
        _status.value = SourceStatus.IDLE
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // 서비스가 없는 기기에서는 클래스나 연결이 없을 수 있다. 그 신호만 포기한다.
            note("$what 사용 불가: ${t.javaClass.simpleName}")
        }
    }

    private fun note(message: String) {
        _diagnostics.update { (it + message).takeLast(MAX_DIAGNOSTICS) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun capable(change: Capabilities.() -> Capabilities) {
        _capabilities.update(change)
        if (_status.value == SourceStatus.CONNECTING || _status.value == SourceStatus.UNAVAILABLE) {
            _status.value = SourceStatus.READY
        }
    }

    private fun CapabilityConfig.readable() =
        accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    // ───────────────────────── Vehicle SDK ─────────────────────────

    private fun startVehicle() {
        val v = Vehicle(appContext)
        v.initialize()
        vehicle = v
        guarded("좌석 점유") { watchSeatOccupancy(v) }
        guarded("안전띠") { watchSeatBelt(v) }
        guarded("문") { watchDoors(v) }
        guarded("창문") { watchWindows(v) }
        guarded("브레이크") { watchBrake(v) }
    }

    private fun watchSeatOccupancy(v: Vehicle) {
        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities(
            onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                onMain {
                    if (!config.readable()) {
                        note("좌석 점유: 이 차는 지원하지 않습니다")
                        return@onMain
                    }
                    note("좌석 점유: 지원(영역 ${areas.size}곳)")
                    capable { copy(seatOccupancy = true) }
                    for ((position, value) in SEATS) guarded("좌석 점유 ${position.name}") {
                        val area = SeatArea(value)
                        val set: (Boolean?) -> Unit = { occupied ->
                            onMain { _state.update { it.copy(seatOccupied = it.seatOccupied.with(position, occupied)) } }
                        }
                        val listener = object : SeatOccupancyListener {
                            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = set(occupancy)
                            override fun onFailed(e: Exception) {
                                note("좌석 점유 ${position.name}: ${e.brief()}")
                                set(null)
                            }
                        }
                        seat.getCurrentSeatOccupancy(
                            area = area,
                            onComplete = { occupied: Boolean? -> set(occupied) },
                            onFailed = { e: Exception -> note("좌석 점유 ${position.name}: ${e.brief()}") },
                        )
                        seat.registerSeatOccupancy(area, listener)
                        releasers += { seat.unregisterSeatOccupancy(area, listener) }
                    }
                }
            },
            onFailed = { e: Exception -> note("좌석 점유 확인 실패: ${e.brief()}") },
        )
    }

    private fun watchSeatBelt(v: Vehicle) {
        val seat = v.getSeat()
        seat.checkSeatBeltBuckledCapabilities(
            onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                onMain {
                    if (!config.readable()) {
                        note("안전띠: 이 차는 지원하지 않습니다")
                        return@onMain
                    }
                    note("안전띠: 지원(영역 ${areas.size}곳)")
                    capable { copy(seatBelt = true) }
                    for ((position, value) in SEATS) guarded("안전띠 ${position.name}") {
                        if (position == SeatPosition.DRIVER) return@guarded
                        val area = SeatArea(value)
                        val set: (Boolean?) -> Unit = { buckled ->
                            onMain { _state.update { it.copy(beltBuckled = it.beltBuckled.with(position, buckled)) } }
                        }
                        val listener = object : SeatBeltBuckledStateListener {
                            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = set(state)
                            override fun onFailed(e: Exception) {
                                note("안전띠 ${position.name}: ${e.brief()}")
                                set(null)
                            }
                        }
                        seat.getCurrentSeatBeltBuckledState(
                            area = area,
                            onComplete = { buckled: Boolean? -> set(buckled) },
                            onFailed = { e: Exception -> note("안전띠 ${position.name}: ${e.brief()}") },
                        )
                        seat.registerSeatBeltBuckledState(area, listener)
                        releasers += { seat.unregisterSeatBeltBuckledState(area, listener) }
                    }
                }
            },
            onFailed = { e: Exception -> note("안전띠 확인 실패: ${e.brief()}") },
        )
    }

    private fun watchDoors(v: Vehicle) {
        val door = v.getDoor()
        door.checkDoorPositionCapabilities(
            onComplete = { config: CapabilityConfig, bounds: Map<DoorArea, AreaBounds<Int>> ->
                onMain {
                    if (!config.readable()) {
                        note("문: 이 차는 지원하지 않습니다")
                        return@onMain
                    }
                    note("문: 지원(영역 ${bounds.size}곳)")
                    capable { copy(door = true) }
                    for ((position, value) in DOORS) guarded("문 ${position.name}") {
                        val area = DoorArea(value)
                        // 문 위치는 최솟값이 닫힘이다. 최솟값보다 크면 열린 것으로 본다.
                        val closedAt = bounds.entries.firstOrNull { value in it.key.values }?.value?.minValue ?: 0
                        val set: (Int?) -> Unit = { pos ->
                            onMain {
                                _state.update { it.copy(doorOpen = it.doorOpen.with(position, pos?.let { p -> p > closedAt })) }
                            }
                        }
                        val listener = object : DoorPositionListener {
                            override fun onPositionUpdated(pos: Int?) = set(pos)
                            override fun onFailed(e: Exception) {
                                note("문 ${position.name}: ${e.brief()}")
                                set(null)
                            }
                        }
                        door.getCurrentDoorPosition(
                            area = area,
                            onComplete = { pos: Int? -> set(pos) },
                            onFailed = { e: Exception -> note("문 ${position.name}: ${e.brief()}") },
                        )
                        door.registerDoorPosition(area, listener)
                        releasers += { door.unregisterDoorPosition(area, listener) }
                    }
                }
            },
            onFailed = { e: Exception -> note("문 확인 실패: ${e.brief()}") },
        )
    }

    private fun watchWindows(v: Vehicle) {
        val window = v.getWindow()
        window.checkWindowPositionCapabilities(
            onComplete = { config: CapabilityConfig, bounds: Map<WindowArea, AreaBounds<Int>> ->
                onMain {
                    if (!config.readable()) {
                        note("창문: 이 차는 지원하지 않습니다")
                        return@onMain
                    }
                    note("창문: 지원(영역 ${bounds.size}곳)")
                    capable { copy(window = true) }
                    for ((position, value) in WINDOWS) guarded("창문 ${position.name}") {
                        if (position == SeatPosition.DRIVER) return@guarded
                        val area = WindowArea(value)
                        val range = bounds.entries.firstOrNull { value in it.key.values }?.value
                        val min = range?.minValue ?: 0
                        val max = range?.maxValue ?: 100
                        val set: (Int?) -> Unit = { pos ->
                            onMain {
                                _state.update {
                                    it.copy(windowOpenPercent = it.windowOpenPercent.with(position, pos?.let { p -> openPercent(p, min, max) }))
                                }
                            }
                        }
                        val listener = object : WindowPositionListener {
                            override fun onWindowPositionUpdated(position: Int?) = set(position)
                            override fun onFailed(e: Exception) {
                                note("창문 ${position.name}: ${e.brief()}")
                                set(null)
                            }
                        }
                        window.getCurrentWindowPosition(
                            area = area,
                            onComplete = { pos: Int? -> set(pos) },
                            onFailed = { e: Exception -> note("창문 ${position.name}: ${e.brief()}") },
                        )
                        window.registerWindowPosition(area, listener)
                        releasers += { window.unregisterWindowPosition(area, listener) }
                    }
                }
            },
            onFailed = { e: Exception -> note("창문 확인 실패: ${e.brief()}") },
        )
    }

    private fun watchBrake(v: Vehicle) {
        val brake = v.getBrake()
        brake.checkBrakePedalStatusCapabilities(
            onComplete = { config: CapabilityConfig ->
                onMain {
                    if (!config.readable()) {
                        note("브레이크: 이 차는 지원하지 않습니다")
                        return@onMain
                    }
                    note("브레이크: 지원")
                    capable { copy(brake = true) }
                    guarded("브레이크") {
                        val set: (Boolean?) -> Unit = { pressed -> onMain { _state.update { it.copy(brakePressed = pressed) } } }
                        val listener = object : BrakePedalStatusListener {
                            override fun onBrakePedalStatusUpdated(status: Boolean?) = set(status)
                            override fun onFailed(e: Exception) {
                                note("브레이크: ${e.brief()}")
                                set(null)
                            }
                        }
                        brake.getCurrentBrakePedalStatus(
                            onComplete = { pressed: Boolean? -> set(pressed) },
                            onFailed = { e: Exception -> note("브레이크: ${e.brief()}") },
                        )
                        brake.registerBrakePedalStatus(listener)
                        releasers += { brake.unregisterBrakePedalStatus(listener) }
                    }
                }
            },
            onFailed = { e: Exception -> note("브레이크 확인 실패: ${e.brief()}") },
        )
    }

    // ───────────────────────── AAOS Car API ─────────────────────────

    private fun startCarApi() {
        val bridge = CarApiBridge(
            context = appContext,
            onUxRestricted = { restricted -> onMain { _state.update { it.copy(uxRestricted = restricted) } } },
            onGear = { gear -> onMain { _state.update { it.copy(gear = gear) } } },
            onSpeedKph = { kph -> onMain { _state.update { it.copy(speedKph = kph) } } },
            note = ::note,
        )
        bridge.start()
        carApi = bridge
        if (bridge.hasUx || bridge.hasGear || bridge.hasSpeed) {
            capable { copy(uxRestrictions = bridge.hasUx, gear = bridge.hasGear, speed = bridge.hasSpeed) }
        }
    }

    // ───────────────────────── NaviHelper ─────────────────────────

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onMain {
            routing = true
            setNav {
                copy(
                    guiding = true,
                    destinationName = routeStartInfo.name.ifBlank { destinationName },
                    totalSec = routeStartInfo.duration.takeIf { it > 0 },
                    remainSec = routeStartInfo.duration.takeIf { it > 0 },
                )
            }
            _navEvents.tryEmit(NavEvent.RouteStarted(routeStartInfo.name))
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onMain {
            // 목적지가 없을 때의 값은 문서에 없다. 이름과 남은 시간이 모두 비어 있으면 안내 중이 아닌 것으로 본다.
            val hasDestination = destinationInfo.name.isNotBlank() || destinationInfo.remainTime > 0
            setNav {
                if (!hasDestination) NavState(nearDestination = nearDestination)
                else copy(
                    guiding = routing || destinationInfo.remainTime > 0,
                    destinationName = destinationInfo.name.ifBlank { destinationName },
                    remainSec = destinationInfo.remainTime.takeIf { routing || it > 0 },
                    remainMeters = destinationInfo.remainDistance.takeIf { routing || it > 0 },
                )
            }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) = onMain {
            val sec = drivingInfo.destination.duration
            if (routing && sec >= 0) setNav { copy(remainSec = sec) }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onMain {
            routing = routeStateInfo.isRouting
            // 경로 전체의 정체 요약이다. 1분 넘는 정체가 있으면 '길이 막힌다'고 말할 근거로 쓴다.
            val jamSec = routeStateInfo.trafficStatus?.summary?.totalCongestionTime ?: 0L
            setNav { copy(guiding = routing, congested = routing && jamSec >= CONGESTION_SEC) }
        }

        override fun onNavigationStatus(status: NavigationStatus) = onMain {
            if (status != NavigationStatus.ROUTE_DRIVE && routing) {
                routing = false
                setNav { copy(guiding = false, congested = false) }
            }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = onMain {
            routing = false
            _navEvents.tryEmit(NavEvent.Arrived)
            setNav { NavState(destinationName = destinationName) }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = onMain {
            _navEvents.tryEmit(NavEvent.WaypointArrived)
        }

        override fun onRouteCancelled() = onMain {
            routing = false
            setNav { NavState(nearDestination = nearDestination) }
        }

        override fun onError(errorCode: NaviErrorCode) = note("내비 오류: ${errorCode.name}")
    }

    private fun setNav(change: NavState.() -> NavState) = _state.update { it.copy(nav = it.nav.change()) }

    private fun startNavi() {
        val n = NaviHelper(appContext)
        navi = n
        n.initialize(
            onConnected = {
                onMain {
                    guarded("NaviHelper 리스너") {
                        n.addListener(naviListener)
                        n.getRouteStateInfo()
                        n.getDestinationInfo()
                        note("내비: 연결됨")
                        capable { copy(navigation = true) }
                    }
                }
            },
            onFailed = { t: Throwable -> note("내비 연결 실패: ${t.javaClass.simpleName}") },
        )
    }

    private val naviPoll = object : Runnable {
        override fun run() {
            if (!tripActive || !started) return
            guarded("내비 조회") {
                navi?.getRouteStateInfo()
                navi?.getDestinationInfo()
            }
            main.postDelayed(this, NAVI_POLL_MS)
        }
    }

    /**
     * 남은 시간은 콜백으로만 온다(NaviHelper 의 get*Info 는 값을 돌려주지 않는다).
     * 동행 중에만 30초마다 다시 묻고, 동행이 끝나면 묻지 않는다.
     */
    override fun setTripActive(active: Boolean) {
        tripActive = active
        main.removeCallbacks(naviPoll)
        if (active && _capabilities.value.navigation) main.post(naviPoll)
        if (!active) watchArrivalPoint(null)
    }

    // ───────────────────────── FusedLocation ─────────────────────────

    private fun startLocation() {
        val fl = FusedLocation(appContext)
        if (!fl.isSupported()) {
            note("위치: 이 차는 지원하지 않습니다")
            return
        }
        fl.initialize()
        location = fl
        note("위치: 지원")
        capable { copy(location = true) }
    }

    private fun locationGranted() =
        appContext.checkSelfPermission(PERMISSION_FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val proximityListener = object : ProximityAlertListener {
        override fun onRegistered(alertId: String) = onMain { proximityAlertId = alertId }

        override fun onTransition(event: ProximityAlertEvent) = onMain {
            if (event.transition == ProximityTransition.ENTER) setNav { copy(nearDestination = true) }
        }

        override fun onError(exception: Exception) = note("근접 알림: ${exception.brief()}")
    }

    override fun watchArrivalPoint(point: GeoPoint?) {
        val fl = location ?: return
        guarded("근접 알림") {
            proximityAlertId?.let { fl.removeProximityAlert(it) }
            proximityAlertId = null
            setNav { copy(nearDestination = false) }
            if (point != null && locationGranted()) {
                fl.addProximityAlert(
                    ProximityAlertRequest(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        radiusMeters = ARRIVAL_RADIUS_M,
                        transitionTypes = setOf(ProximityTransition.ENTER),
                    ),
                    proximityListener,
                )
            }
        }
    }

    override fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) {
        val fl = location
        if (fl == null || !locationGranted()) {
            onResult(null)
            return
        }
        try {
            fl.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) = onMain {
                    onResult(location?.let { GeoPoint(it.latitude, it.longitude) })
                }

                override fun onError(exception: Exception) = onMain { onResult(null) }
            })
        } catch (t: Throwable) {
            onResult(null)
        }
    }

    // ───────────────────────── 도우미 ─────────────────────────

    private fun <K, V : Any> Map<K, V>.with(key: K, value: V?): Map<K, V> =
        if (value == null) this - key else this + (key to value)

    private fun Throwable.brief(): String = message?.take(80) ?: javaClass.simpleName

    companion object {
        const val PERMISSION_FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        private const val CONNECT_TIMEOUT_MS = 4_000L
        private const val NAVI_POLL_MS = 30_000L
        private const val CONGESTION_SEC = 60L
        private const val ARRIVAL_RADIUS_M = 300f
        private const val MAX_DIAGNOSTICS = 40

        /** 운전석은 왼쪽(국내 차량). */
        private val SEATS = listOf(
            SeatPosition.DRIVER to SeatValue.ROW_1_LEFT,
            SeatPosition.FRONT_PASSENGER to SeatValue.ROW_1_RIGHT,
            SeatPosition.REAR_LEFT to SeatValue.ROW_2_LEFT,
            SeatPosition.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
        )
        private val DOORS = listOf(
            SeatPosition.DRIVER to DoorValue.ROW_1_LEFT,
            SeatPosition.FRONT_PASSENGER to DoorValue.ROW_1_RIGHT,
            SeatPosition.REAR_LEFT to DoorValue.ROW_2_LEFT,
            SeatPosition.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
        )
        private val WINDOWS = listOf(
            SeatPosition.DRIVER to WindowValue.ROW_1_LEFT,
            SeatPosition.FRONT_PASSENGER to WindowValue.ROW_1_RIGHT,
            SeatPosition.REAR_LEFT to WindowValue.ROW_2_LEFT,
            SeatPosition.REAR_RIGHT to WindowValue.ROW_2_RIGHT,
        )

        /** 창문 위치를 0(닫힘)~100(완전히 열림)으로 바꾼다. 최솟값이 닫힘이다(AAOS WINDOW_POS 와 같은 뜻). */
        fun openPercent(position: Int, min: Int, max: Int): Int {
            if (max <= min) return position.coerceIn(0, 100)
            return ((position - min) * 100 / (max - min)).coerceIn(0, 100)
        }
    }
}
