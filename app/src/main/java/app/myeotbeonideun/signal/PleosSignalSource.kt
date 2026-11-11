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
import android.os.SystemClock
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
 * - Vehicle SDK 는 check*Capabilities 와 onFailed 를 거치고, 지원 영역 목록에 든 자리만 등록한다.
 *   실패한 확인(부팅 직후 서비스 미연결 등)은 지수 간격(2·4·8초)으로 세 번까지 다시 한다([RetryPolicy]).
 *   AAOS Car API 연결과 NaviHelper 연결 실패도 같은 간격으로 다시 한다. 끝내 실패한 신호는 값이 비어 있을 뿐이고,
 *   엔진은 그 신호를 쓰는 기능만 끈다.
 * - 구독은 앱이 정한 때(화면이 보이거나 동행 중)에만 둔다. [start] 마다 새 세대([SignalSession])를 만들고,
 *   옛 세대의 콜백은 무시한다. [stop] 은 리스너를 바로 풀고, SDK 객체는 기다리던 비동기 콜백이 모두 돌아온 뒤에
 *   놓는다(Pleos 문서: 비동기 콜백이 끝나기 전에 release 하지 말 것).
 * - 좌석 점유 Boolean 은 그대로 쓰지 않고 해석한다([SeatValueReader] — 2026-11-11 에뮬레이터에서 가상 콘솔과
 *   반대로 왔고, 실차의 극성은 모른다). 안전띠는 극성을 확인하지 못해 그대로 쓴다.
 * - 콜백은 어느 스레드에서 올지 문서에 없어 메인 스레드로 옮겨 처리한다.
 */
class PleosSignalSource(context: Context) : VehicleSignalSource {

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
    override val diagnostics: StateFlow<List<String>> = _diagnostics.asStateFlow()

    // 좌석 점유는 SDK 가 넘긴 값을 그대로 쓰지 않고 해석한다(에뮬레이터에서 콘솔과 반대로 왔다 — SeatValueReader).
    private val seats = SeatValueReader()
    private val _rawSeats = MutableStateFlow<Map<SeatPosition, Boolean>>(emptyMap())
    private val _seatInterpretation = MutableStateFlow(seats.interpretation)
    override val rawSeatOccupied: StateFlow<Map<SeatPosition, Boolean>> = _rawSeats.asStateFlow()
    override val seatInterpretation: StateFlow<SeatInterpretation> = _seatInterpretation.asStateFlow()

    /** 지금의 구독(세대). 멈추면 null. */
    private var session: Session? = null
    private var generation = 0

    private var tripActive = false
    private var routing = false

    /** 구독 한 번(세대)에 쓰는 SDK 객체와 그 장부. */
    private inner class Session(val ledger: SignalSession) {
        var carApi: CarApiBridge? = null
        var navi: NaviHelper? = null
        var location: FusedLocation? = null
        var proximityAlertId: String? = null

        /** 이 세대가 지금의 구독인지. 멈췄거나 다시 시작했으면 false 이고, 그 콜백은 아무것도 하지 않는다. */
        val alive: Boolean get() = session === this && !ledger.closing

        /** 메인 스레드에서, 이 세대가 살아 있을 때만 한다. */
        fun live(block: () -> Unit) = onMain { if (alive) block() }
    }

    // ───────────────────────── 시작과 끝 ─────────────────────────

    override fun start() {
        if (session != null) return
        val s = Session(SignalSession(++generation))
        session = s
        _status.value = SourceStatus.CONNECTING
        if (s.ledger.generation > 1) note("다시 연결(${s.ledger.generation}번째)")
        startVehicle(s, attempt = 0)
        startCarApi(s, attempt = 0)
        startNavi(s, attempt = 0)
        startLocation(s, attempt = 0)
        // 몇 초 안에 신호를 하나도 얻지 못하면 '차량 신호 없음'이다(체험 모드로 넘어가지 않는다).
        // 그 뒤에 다시 시도가 성공하면 READY 가 된다.
        main.postDelayed({
            if (s.alive && _status.value == SourceStatus.CONNECTING) {
                _status.value = if (_capabilities.value.any) SourceStatus.READY else SourceStatus.UNAVAILABLE
            }
        }, CONNECT_TIMEOUT_MS)
    }

    override fun stop() {
        val s = session ?: return
        session = null
        main.removeCallbacks(naviPoll)
        // 리스너는 바로 푼다. SDK 객체는 기다리던 비동기 콜백이 모두 돌아온 뒤에 놓는다.
        if (!s.ledger.close()) {
            note("콜백 ${s.ledger.pendingCalls}개가 돌아오면 놓습니다")
            main.postDelayed({
                if (!s.ledger.released) note("콜백 ${s.ledger.forceRelease()}개가 오지 않아 그대로 놓았습니다")
            }, RELEASE_TIMEOUT_MS)
        }
        routing = false
        _status.value = SourceStatus.IDLE
        _capabilities.value = Capabilities()
        // 놓은 뒤의 값은 모른다. 다시 붙이면 getCurrent* 로 새로 받는다(좌석 값의 해석은 같은 차의 성질이라 남긴다).
        seats.clearRaw()
        _state.value = VehicleState()
        publishSeats()
    }

    // ───────────────────────── 좌석 값 해석 ─────────────────────────

    override fun setSeatReading(reading: SeatReading) {
        seats.setReading(reading)
        publishSeats()
    }

    override fun noteDriverAtScreen() {
        seats.driverAtScreen()
        publishSeats()
    }

    private fun setSeatOccupied(position: SeatPosition, occupied: Boolean?) {
        seats.setRaw(position, occupied, SystemClock.elapsedRealtime())
        publishSeats()
    }

    /** 원시 값과 해석을 내보내고, 상태의 좌석 점유를 해석한 값으로 바꾼다. */
    private fun publishSeats() {
        _rawSeats.value = seats.raw
        _seatInterpretation.value = seats.interpretation
        val occupied = seats.seatOccupied
        _state.update { if (it.seatOccupied == occupied) it else it.copy(seatOccupied = occupied) }
    }

    /** 속도 권한을 새로 받았다. AAOS Car API 만 다시 붙인다(다른 신호와 동행 중의 값은 그대로 둔다). */
    override fun refreshSpeedPermission() {
        val s = session ?: return
        s.carApi?.let { old ->
            s.carApi = null
            runCatching { old.stop() }
        }
        startCarApi(s, attempt = 0)
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

    /**
     * 비동기 SDK 호출 하나를 장부에 올린다. [call] 안에서 SDK 를 부르고, 그 콜백에서 받은 reply 를 꼭 한 번 부른다.
     * reply 의 본문은 메인 스레드에서, 이 세대가 살아 있을 때만 돈다. 호출이 바로 예외를 던지면 장부에서 지우고 다시 던진다.
     */
    private fun async(s: Session, what: String, call: (reply: (body: () -> Unit) -> Unit) -> Unit) {
        val ticket = s.ledger.begin()
        val reply: (() -> Unit) -> Unit = { body -> onMain { if (ticket.finish()) guarded(what) { body() } } }
        try {
            call(reply)
        } catch (t: Throwable) {
            ticket.finish()
            throw t
        }
    }

    /** [what] 이 실패했다. 지수 간격(2·4·8초)으로 세 번까지 다시 한다. 그사이 멈췄거나 다시 시작했으면 하지 않는다. */
    private fun retry(s: Session, what: String, attempt: Int, again: (nextAttempt: Int) -> Unit) {
        val delay = RetryPolicy.delayMs(attempt)
        if (delay == null) {
            note("$what: 다시 시도하지 않습니다(${RetryPolicy.MAX_RETRIES}번 실패)")
            return
        }
        note("$what: ${delay / 1000}초 뒤 다시 시도(${attempt + 1}/${RetryPolicy.MAX_RETRIES})")
        main.postDelayed({ if (s.alive) guarded(what) { again(attempt + 1) } }, delay)
    }

    // ───────────────────────── Vehicle SDK ─────────────────────────

    private fun startVehicle(s: Session, attempt: Int) {
        val v = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            note("Vehicle SDK 사용 불가: ${t.javaClass.simpleName}")
            retry(s, "Vehicle SDK", attempt) { startVehicle(s, it) }
            return
        }
        s.ledger.onRelease { v.release() }
        watchSeatOccupancy(s, v, attempt = 0)
        watchSeatBelt(s, v, attempt = 0)
        watchDoors(s, v, attempt = 0)
        watchWindows(s, v, attempt = 0)
        watchBrake(s, v, attempt = 0)
    }

    private fun watchSeatOccupancy(s: Session, v: Vehicle, attempt: Int) {
        val failed: (String) -> Unit = { why ->
            note("좌석 점유 확인 실패: $why")
            retry(s, "좌석 점유 확인", attempt) { watchSeatOccupancy(s, v, it) }
        }
        try {
            val seat = v.getSeat()
            async(s, "좌석 점유") { reply ->
                seat.checkSeatOccupancyCapabilities(
                    onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                        reply body@{
                            if (!config.readable()) {
                                note("좌석 점유: 이 차는 지원하지 않습니다")
                                return@body
                            }
                            note("좌석 점유: 지원(영역 ${areas.size}곳)")
                            capable { copy(seatOccupancy = true) }
                            val picked = SupportedAreas.pickAll(areas, SEATS.map { it.second }, { a, x -> x in a.values }) { SeatArea(it) }
                            if (picked.fellBack) note("좌석 점유: 지원 영역 목록과 자리가 맞지 않아 네 자리를 모두 시도")
                            for ((i, seatValue) in SEATS.withIndex()) {
                                val position = seatValue.first
                                val area = picked.areas[i]
                                if (area == null) {
                                    note("좌석 점유 ${position.name}: 지원 영역이 아니어서 등록하지 않음")
                                    continue
                                }
                                guarded("좌석 점유 ${position.name}") {
                                    val listener = object : SeatOccupancyListener {
                                        override fun onSeatOccupancyUpdated(occupancy: Boolean?) =
                                            s.live { setSeatOccupied(position, occupancy) }

                                        override fun onFailed(e: Exception) = s.live {
                                            note("좌석 점유 ${position.name}: ${e.brief()}")
                                            setSeatOccupied(position, null)
                                        }
                                    }
                                    async(s, "좌석 점유 ${position.name}") { current ->
                                        seat.getCurrentSeatOccupancy(
                                            area = area,
                                            onComplete = { occupied: Boolean? -> current { setSeatOccupied(position, occupied) } },
                                            onFailed = { e: Exception -> current { note("좌석 점유 ${position.name}: ${e.brief()}") } },
                                        )
                                    }
                                    seat.registerSeatOccupancy(area, listener)
                                    s.ledger.onClose { seat.unregisterSeatOccupancy(area, listener) }
                                }
                            }
                        }
                    },
                    onFailed = { e: Exception -> reply { failed(e.brief()) } },
                )
            }
        } catch (t: Throwable) {
            failed(t.javaClass.simpleName)
        }
    }

    private fun watchSeatBelt(s: Session, v: Vehicle, attempt: Int) {
        val failed: (String) -> Unit = { why ->
            note("안전띠 확인 실패: $why")
            retry(s, "안전띠 확인", attempt) { watchSeatBelt(s, v, it) }
        }
        try {
            val seat = v.getSeat()
            async(s, "안전띠") { reply ->
                seat.checkSeatBeltBuckledCapabilities(
                    onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                        reply body@{
                            if (!config.readable()) {
                                note("안전띠: 이 차는 지원하지 않습니다")
                                return@body
                            }
                            note("안전띠: 지원(영역 ${areas.size}곳)")
                            capable { copy(seatBelt = true) }
                            val seats = SEATS.filter { it.first != SeatPosition.DRIVER }
                            val picked = SupportedAreas.pickAll(areas, seats.map { it.second }, { a, x -> x in a.values }) { SeatArea(it) }
                            if (picked.fellBack) note("안전띠: 지원 영역 목록과 자리가 맞지 않아 세 자리를 모두 시도")
                            for ((i, seatValue) in seats.withIndex()) {
                                val position = seatValue.first
                                val area = picked.areas[i]
                                if (area == null) {
                                    note("안전띠 ${position.name}: 지원 영역이 아니어서 등록하지 않음")
                                    continue
                                }
                                guarded("안전띠 ${position.name}") {
                                    val set: (Boolean?) -> Unit = { buckled ->
                                        _state.update { it.copy(beltBuckled = it.beltBuckled.with(position, buckled)) }
                                    }
                                    val listener = object : SeatBeltBuckledStateListener {
                                        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = s.live { set(state) }
                                        override fun onFailed(e: Exception) = s.live {
                                            note("안전띠 ${position.name}: ${e.brief()}")
                                            set(null)
                                        }
                                    }
                                    async(s, "안전띠 ${position.name}") { current ->
                                        seat.getCurrentSeatBeltBuckledState(
                                            area = area,
                                            onComplete = { buckled: Boolean? -> current { set(buckled) } },
                                            onFailed = { e: Exception -> current { note("안전띠 ${position.name}: ${e.brief()}") } },
                                        )
                                    }
                                    seat.registerSeatBeltBuckledState(area, listener)
                                    s.ledger.onClose { seat.unregisterSeatBeltBuckledState(area, listener) }
                                }
                            }
                        }
                    },
                    onFailed = { e: Exception -> reply { failed(e.brief()) } },
                )
            }
        } catch (t: Throwable) {
            failed(t.javaClass.simpleName)
        }
    }

    private fun watchDoors(s: Session, v: Vehicle, attempt: Int) {
        val failed: (String) -> Unit = { why ->
            note("문 확인 실패: $why")
            retry(s, "문 확인", attempt) { watchDoors(s, v, it) }
        }
        try {
            val door = v.getDoor()
            async(s, "문") { reply ->
                door.checkDoorPositionCapabilities(
                    onComplete = { config: CapabilityConfig, bounds: Map<DoorArea, AreaBounds<Int>> ->
                        reply body@{
                            if (!config.readable()) {
                                note("문: 이 차는 지원하지 않습니다")
                                return@body
                            }
                            note("문: 지원(영역 ${bounds.size}곳)")
                            capable { copy(door = true) }
                            val picked = SupportedAreas.pickAll(bounds.keys, DOORS.map { it.second }, { a, x -> x in a.values }) { DoorArea(it) }
                            if (picked.fellBack) note("문: 지원 영역 목록과 자리가 맞지 않아 네 자리를 모두 시도")
                            for ((i, doorValue) in DOORS.withIndex()) {
                                val position = doorValue.first
                                val area = picked.areas[i]
                                if (area == null) {
                                    note("문 ${position.name}: 지원 영역이 아니어서 등록하지 않음")
                                    continue
                                }
                                guarded("문 ${position.name}") {
                                    // 문 위치는 최솟값이 닫힘이다. 최솟값보다 크면 열린 것으로 본다.
                                    // (2026-11-11 에뮬레이터: 가상 콘솔 Open → DOOR_POS 1 로 왔고, 열림으로 읽혀 알림이 났다)
                                    val closedAt = bounds[area]?.minValue ?: 0
                                    val set: (Int?) -> Unit = { pos ->
                                        _state.update { it.copy(doorOpen = it.doorOpen.with(position, pos?.let { p -> p > closedAt })) }
                                    }
                                    val listener = object : DoorPositionListener {
                                        override fun onPositionUpdated(pos: Int?) = s.live { set(pos) }
                                        override fun onFailed(e: Exception) = s.live {
                                            note("문 ${position.name}: ${e.brief()}")
                                            set(null)
                                        }
                                    }
                                    async(s, "문 ${position.name}") { current ->
                                        door.getCurrentDoorPosition(
                                            area = area,
                                            onComplete = { pos: Int? -> current { set(pos) } },
                                            onFailed = { e: Exception -> current { note("문 ${position.name}: ${e.brief()}") } },
                                        )
                                    }
                                    door.registerDoorPosition(area, listener)
                                    s.ledger.onClose { door.unregisterDoorPosition(area, listener) }
                                }
                            }
                        }
                    },
                    onFailed = { e: Exception -> reply { failed(e.brief()) } },
                )
            }
        } catch (t: Throwable) {
            failed(t.javaClass.simpleName)
        }
    }

    private fun watchWindows(s: Session, v: Vehicle, attempt: Int) {
        val failed: (String) -> Unit = { why ->
            note("창문 확인 실패: $why")
            retry(s, "창문 확인", attempt) { watchWindows(s, v, it) }
        }
        try {
            val window = v.getWindow()
            async(s, "창문") { reply ->
                window.checkWindowPositionCapabilities(
                    onComplete = { config: CapabilityConfig, bounds: Map<WindowArea, AreaBounds<Int>> ->
                        reply body@{
                            if (!config.readable()) {
                                note("창문: 이 차는 지원하지 않습니다")
                                return@body
                            }
                            note("창문: 지원(영역 ${bounds.size}곳)")
                            capable { copy(window = true) }
                            val windows = WINDOWS.filter { it.first != SeatPosition.DRIVER }
                            val picked = SupportedAreas.pickAll(bounds.keys, windows.map { it.second }, { a, x -> x in a.values }) { WindowArea(it) }
                            if (picked.fellBack) note("창문: 지원 영역 목록과 자리가 맞지 않아 세 자리를 모두 시도")
                            for ((i, windowValue) in windows.withIndex()) {
                                val position = windowValue.first
                                val area = picked.areas[i]
                                if (area == null) {
                                    note("창문 ${position.name}: 지원 영역이 아니어서 등록하지 않음")
                                    continue
                                }
                                guarded("창문 ${position.name}") {
                                    val range = bounds[area]
                                    val min = range?.minValue ?: 0
                                    val max = range?.maxValue ?: 100
                                    val set: (Int?) -> Unit = { pos ->
                                        _state.update {
                                            it.copy(windowOpenPercent = it.windowOpenPercent.with(position, pos?.let { p -> openPercent(p, min, max) }))
                                        }
                                    }
                                    val listener = object : WindowPositionListener {
                                        override fun onWindowPositionUpdated(position: Int?) = s.live { set(position) }
                                        override fun onFailed(e: Exception) = s.live {
                                            note("창문 ${position.name}: ${e.brief()}")
                                            set(null)
                                        }
                                    }
                                    async(s, "창문 ${position.name}") { current ->
                                        window.getCurrentWindowPosition(
                                            area = area,
                                            onComplete = { pos: Int? -> current { set(pos) } },
                                            onFailed = { e: Exception -> current { note("창문 ${position.name}: ${e.brief()}") } },
                                        )
                                    }
                                    window.registerWindowPosition(area, listener)
                                    s.ledger.onClose { window.unregisterWindowPosition(area, listener) }
                                }
                            }
                        }
                    },
                    onFailed = { e: Exception -> reply { failed(e.brief()) } },
                )
            }
        } catch (t: Throwable) {
            failed(t.javaClass.simpleName)
        }
    }

    private fun watchBrake(s: Session, v: Vehicle, attempt: Int) {
        val failed: (String) -> Unit = { why ->
            note("브레이크 확인 실패: $why")
            retry(s, "브레이크 확인", attempt) { watchBrake(s, v, it) }
        }
        try {
            val brake = v.getBrake()
            async(s, "브레이크") { reply ->
                brake.checkBrakePedalStatusCapabilities(
                    onComplete = { config: CapabilityConfig ->
                        reply body@{
                            if (!config.readable()) {
                                note("브레이크: 이 차는 지원하지 않습니다")
                                return@body
                            }
                            note("브레이크: 지원")
                            capable { copy(brake = true) }
                            guarded("브레이크") {
                                val set: (Boolean?) -> Unit = { pressed -> _state.update { it.copy(brakePressed = pressed) } }
                                val listener = object : BrakePedalStatusListener {
                                    override fun onBrakePedalStatusUpdated(status: Boolean?) = s.live { set(status) }
                                    override fun onFailed(e: Exception) = s.live {
                                        note("브레이크: ${e.brief()}")
                                        set(null)
                                    }
                                }
                                async(s, "브레이크 값") { current ->
                                    brake.getCurrentBrakePedalStatus(
                                        onComplete = { pressed: Boolean? -> current { set(pressed) } },
                                        onFailed = { e: Exception -> current { note("브레이크: ${e.brief()}") } },
                                    )
                                }
                                brake.registerBrakePedalStatus(listener)
                                s.ledger.onClose { brake.unregisterBrakePedalStatus(listener) }
                            }
                        }
                    },
                    onFailed = { e: Exception -> reply { failed(e.brief()) } },
                )
            }
        } catch (t: Throwable) {
            failed(t.javaClass.simpleName)
        }
    }

    // ───────────────────────── AAOS Car API ─────────────────────────

    private fun startCarApi(s: Session, attempt: Int) {
        val bridge = try {
            CarApiBridge(
                context = appContext,
                onUxRestricted = { restricted -> s.live { _state.update { it.copy(uxRestricted = restricted) } } },
                onGear = { gear -> s.live { _state.update { it.copy(gear = gear) } } },
                onSpeedKph = { kph -> s.live { _state.update { it.copy(speedKph = kph) } } },
                note = ::note,
            )
        } catch (t: Throwable) {
            // android.car 가 없는 기기. 다시 해도 같다.
            note("AAOS Car API 사용 불가: ${t.javaClass.simpleName}")
            return
        }
        val connected = try {
            bridge.start()
        } catch (t: Throwable) {
            note("AAOS Car API 사용 불가: ${t.javaClass.simpleName}")
            false
        }
        if (!connected) {
            runCatching { bridge.stop() }
            retry(s, "AAOS Car API", attempt) { startCarApi(s, it) }
            return
        }
        s.carApi = bridge
        s.ledger.onClose { bridge.stop() }
        if (bridge.hasUx || bridge.hasGear || bridge.hasSpeed) {
            capable { copy(uxRestrictions = bridge.hasUx, gear = bridge.hasGear, speed = bridge.hasSpeed) }
        }
    }

    // ───────────────────────── NaviHelper ─────────────────────────

    /** 한 세대의 내비 리스너. 멈춘 뒤에 늦게 온 콜백은 아무것도 하지 않는다. */
    private fun naviListener(s: Session) = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = s.live {
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

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = s.live {
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

        override fun onDrivingInfo(drivingInfo: DrivingInfo) = s.live {
            val sec = drivingInfo.destination.duration
            if (routing && sec >= 0) setNav { copy(remainSec = sec) }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = s.live {
            routing = routeStateInfo.isRouting
            // 경로 전체의 정체 요약이다. 1분 넘는 정체가 있으면 '길이 막힌다'고 말할 근거로 쓴다.
            val jamSec = routeStateInfo.trafficStatus?.summary?.totalCongestionTime ?: 0L
            setNav { copy(guiding = routing, congested = routing && jamSec >= CONGESTION_SEC) }
        }

        override fun onNavigationStatus(status: NavigationStatus) = s.live {
            if (status != NavigationStatus.ROUTE_DRIVE && routing) {
                routing = false
                setNav { copy(guiding = false, congested = false) }
            }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = s.live {
            routing = false
            _navEvents.tryEmit(NavEvent.Arrived)
            setNav { NavState(destinationName = destinationName) }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = s.live {
            _navEvents.tryEmit(NavEvent.WaypointArrived)
        }

        override fun onRouteCancelled() = s.live {
            routing = false
            setNav { NavState(nearDestination = nearDestination) }
        }

        override fun onError(errorCode: NaviErrorCode) = s.live { note("내비 오류: ${errorCode.name}") }
    }

    private fun setNav(change: NavState.() -> NavState) = _state.update { it.copy(nav = it.nav.change()) }

    private fun startNavi(s: Session, attempt: Int) {
        val again = { reason: String ->
            note("내비 연결 실패: $reason")
            retry(s, "내비 연결", attempt) { startNavi(s, it) }
        }
        val n = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            again(t.javaClass.simpleName)
            return
        }
        s.navi = n
        s.ledger.onRelease { n.release() }
        val listener = naviListener(s)
        try {
            async(s, "NaviHelper") { reply ->
                n.initialize(
                    onConnected = {
                        reply {
                            n.addListener(listener)
                            s.ledger.onClose { n.removeListener(listener) }
                            n.getRouteStateInfo()
                            n.getDestinationInfo()
                            note("내비: 연결됨")
                            capable { copy(navigation = true) }
                            schedulePoll()
                        }
                    },
                    onFailed = { t: Throwable ->
                        reply {
                            // 그 객체의 콜백은 끝났으므로 놓고, 새 객체로 다시 한다(release 뒤에는 새로 만든다).
                            runCatching { n.release() }
                            again(t.javaClass.simpleName)
                        }
                    },
                )
            }
        } catch (t: Throwable) {
            again(t.javaClass.simpleName)
        }
    }

    private val naviPoll = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (!tripActive) return
            guarded("내비 조회") {
                s.navi?.getRouteStateInfo()
                s.navi?.getDestinationInfo()
            }
            main.postDelayed(this, NAVI_POLL_MS)
        }
    }

    /** 동행 중이고 내비가 연결돼 있으면 30초마다 다시 묻는다. 아니면 묻지 않는다. */
    private fun schedulePoll() {
        main.removeCallbacks(naviPoll)
        if (tripActive && session != null && _capabilities.value.navigation) main.post(naviPoll)
    }

    /**
     * 동행이 시작되거나 끝났다.
     * - 좌석 값 자동 판별: 동행 시작 순간에는 운전석에 사람이 있으므로 그때 정하고 끝날 때까지 바꾸지 않는다.
     * - 남은 시간은 콜백으로만 온다(NaviHelper 의 get*Info 는 값을 돌려주지 않는다). 동행 중에만 30초마다 다시 묻는다.
     */
    override fun setTripActive(active: Boolean) {
        tripActive = active
        if (active) seats.holdForTrip(SystemClock.elapsedRealtime()) else seats.releaseTrip()
        publishSeats()
        schedulePoll()
        if (!active) watchArrivalPoint(null)
    }

    // ───────────────────────── FusedLocation ─────────────────────────

    private fun startLocation(s: Session, attempt: Int) {
        try {
            val fl = FusedLocation(appContext)
            if (!fl.isSupported()) {
                note("위치: 이 차는 지원하지 않습니다")
                return
            }
            fl.initialize()
            s.location = fl
            s.ledger.onRelease { fl.release() }
            note("위치: 지원")
            capable { copy(location = true) }
        } catch (t: Throwable) {
            note("FusedLocation 사용 불가: ${t.javaClass.simpleName}")
            retry(s, "위치", attempt) { startLocation(s, it) }
        }
    }

    private fun locationGranted() =
        appContext.checkSelfPermission(PERMISSION_FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun watchArrivalPoint(point: GeoPoint?) {
        val s = session ?: return
        val fl = s.location ?: return
        guarded("근접 알림") {
            s.proximityAlertId?.let { fl.removeProximityAlert(it) }
            s.proximityAlertId = null
            setNav { copy(nearDestination = false) }
            if (point != null && locationGranted()) {
                async(s, "근접 알림") { reply ->
                    fl.addProximityAlert(
                        ProximityAlertRequest(
                            latitude = point.latitude,
                            longitude = point.longitude,
                            radiusMeters = ARRIVAL_RADIUS_M,
                            transitionTypes = setOf(ProximityTransition.ENTER),
                        ),
                        object : ProximityAlertListener {
                            override fun onRegistered(alertId: String) = reply {
                                s.proximityAlertId = alertId
                                s.ledger.onClose { fl.removeProximityAlert(alertId) }
                            }

                            override fun onTransition(event: ProximityAlertEvent) = s.live {
                                if (event.transition == ProximityTransition.ENTER) setNav { copy(nearDestination = true) }
                            }

                            override fun onError(exception: Exception) = reply { note("근접 알림: ${exception.brief()}") }
                        },
                    )
                }
            }
        }
    }

    override fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) {
        val s = session
        val fl = s?.location
        if (s == null || fl == null || !locationGranted()) {
            onResult(null)
            return
        }
        try {
            async(s, "위치 조회") { reply ->
                fl.getLastKnownLocation(object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) = reply {
                        onResult(location?.let { GeoPoint(it.latitude, it.longitude) })
                    }

                    override fun onError(exception: Exception) = reply { onResult(null) }
                })
            }
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
        /** 멈춘 뒤 비동기 콜백을 이만큼 기다려도 오지 않으면 그대로 SDK 를 놓는다(마지막 수단). */
        private const val RELEASE_TIMEOUT_MS = 10_000L
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

        /**
         * 창문 위치를 0(닫힘)~100(완전히 열림)으로 바꾼다. 최솟값이 닫힘이다(AAOS WINDOW_POS 와 같은 뜻).
         * 범위는 차마다 다르다: 2026-11-11 에뮬레이터에서는 가상 콘솔 40% 가 원시 값 4(0~10 눈금)로 왔고, 이 변환을 거쳐
         * 기준(30%)을 넘어 창문 안내가 났다.
         */
        fun openPercent(position: Int, min: Int, max: Int): Int {
            if (max <= min) return position.coerceIn(0, 100)
            return ((position - min) * 100 / (max - min)).coerceIn(0, 100)
        }
    }
}
