package app.myeotbeonideun.signal

import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.NavState
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 체험 모드의 모의 신호. Pleos 서비스가 없는 기기에서도 같은 엔진을 돌려 볼 수 있다.
 *
 * - 개발자 패널에서 값을 손으로 바꾼다([update]).
 * - [playScenario] 는 명세 6절의 시연 순서를 그대로 재생한다.
 * 화면에는 항상 '체험 모드'라고 표시한다(실제 차량 신호가 아님을 밝힌다).
 */
class DemoSignalSource(private val scope: CoroutineScope) : SignalSource {

    private val _state = MutableStateFlow(initialState())
    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    private val _status = MutableStateFlow(SourceStatus.IDLE)
    private val _scenarioStep = MutableStateFlow<String?>(null)
    private var scenario: Job? = null
    private var autoDrive: Job? = null

    override val kind = SourceKind.DEMO
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()
    override val capabilities: StateFlow<Capabilities> = MutableStateFlow(
        Capabilities(
            seatOccupancy = true, seatBelt = true, door = true, window = true, brake = true,
            gear = true, speed = false, uxRestrictions = true, navigation = true, location = false,
        ),
    )
    override val state: StateFlow<VehicleState> = _state.asStateFlow()
    override val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    /** 재생 중인 시연 단계의 설명. 재생 중이 아니면 null */
    val scenarioStep: StateFlow<String?> = _scenarioStep.asStateFlow()

    override fun start() {
        _status.value = SourceStatus.READY
    }

    override fun stop() {
        stopScenario()
        setTripActive(false)
        _status.value = SourceStatus.IDLE
    }

    /**
     * 동행 중에는 모의 주행을 한다: 주행 단이고 브레이크를 밟지 않았으면 남은 시간이 실제의 4배 빠르기로 줄어든다
     * (12분 길이 3분). 시연 순서를 재생하는 동안에는 재생 쪽이 값을 정하므로 쉬어 간다.
     */
    override fun setTripActive(active: Boolean) {
        autoDrive?.cancel()
        autoDrive = null
        if (!active) return
        autoDrive = scope.launch {
            while (true) {
                delay(1000)
                if (scenario != null) continue
                update { v ->
                    val remain = v.nav.remainSec
                    if (v.gear != Gear.DRIVE || v.brakePressed == true || remain == null || remain <= 0) v
                    else {
                        val next = (remain - AUTO_DRIVE_SPEEDUP).coerceAtLeast(0)
                        v.copy(nav = v.nav.copy(remainSec = next, remainMeters = next * 8))
                    }
                }
            }
        }
    }

    /** 값을 바꾼다. 주행 규제는 변속단을 따라간다(P단이 아니면 규제 중). */
    fun update(change: (VehicleState) -> VehicleState) {
        _state.update { old ->
            val next = change(old)
            next.copy(uxRestricted = next.gear != Gear.PARK)
        }
    }

    fun setSeat(seat: SeatPosition, occupied: Boolean) = update { it.copy(seatOccupied = it.seatOccupied + (seat to occupied)) }
    fun setDoor(seat: SeatPosition, open: Boolean) = update { it.copy(doorOpen = it.doorOpen + (seat to open)) }
    fun setWindow(seat: SeatPosition, percent: Int) = update { it.copy(windowOpenPercent = it.windowOpenPercent + (seat to percent)) }
    fun setBelt(seat: SeatPosition, buckled: Boolean) = update { it.copy(beltBuckled = it.beltBuckled + (seat to buckled)) }
    fun setNav(change: (NavState) -> NavState) = update { it.copy(nav = change(it.nav)) }
    fun emitArrived() { _navEvents.tryEmit(NavEvent.Arrived) }

    /** 처음 상태(P단, 운전석과 뒷좌석 오른쪽에 사람, 12분 길)로 되돌린다. */
    fun reset() {
        stopScenario()
        _state.value = initialState()
    }

    fun stopScenario() {
        scenario?.cancel()
        scenario = null
        _scenarioStep.value = null
    }

    /**
     * 명세 6절의 시연 순서를 재생한다(약 2분 30초). 동행을 시작한 뒤에 부른다.
     * @param seat 동승자가 앉은 자리
     */
    fun playScenario(seat: SeatPosition) {
        stopScenario()
        scenario = scope.launch {
            suspend fun step(caption: String, holdSec: Int, change: (VehicleState) -> VehicleState = { it }) {
                _scenarioStep.value = caption
                update(change)
                delay(holdSec * 1000L)
            }
            step("기어 D — 출발을 알립니다", 14) { it.copy(gear = Gear.DRIVE) }
            step("지금 ‘다시 말씀드리기’를 두 번 눌러 보세요", 18)
            step("브레이크를 밟고 20초 — 신호 대기를 알립니다", 27) { it.copy(brakePressed = true) }
            step("다시 출발", 3) { it.copy(brakePressed = false) }
            step("남은 시간이 절반으로 — 구간이 바뀝니다", 10) { it.copy(nav = it.nav.copy(remainSec = 400)) }
            step("동승 좌석 창문 40% — 손은 안에 두시라고 알립니다", 9) { it.copy(windowOpenPercent = it.windowOpenPercent + (seat to 40)) }
            step("창문 닫음", 2) { it.copy(windowOpenPercent = it.windowOpenPercent + (seat to 0)) }
            step("기어 P, 운전자가 내림 — 금방 돌아온다고 알립니다", 14) {
                it.copy(
                    gear = Gear.PARK,
                    doorOpen = it.doorOpen + (SeatPosition.DRIVER to true),
                    seatOccupied = it.seatOccupied + (SeatPosition.DRIVER to false),
                )
            }
            step("운전자가 돌아옴", 9) {
                it.copy(
                    doorOpen = it.doorOpen + (SeatPosition.DRIVER to false),
                    seatOccupied = it.seatOccupied + (SeatPosition.DRIVER to true),
                )
            }
            step("기어 D", 6) { it.copy(gear = Gear.DRIVE) }
            step("주행 중 동승 좌석 문 열림 — 운전자에게 바로 알립니다", 8) { it.copy(doorOpen = it.doorOpen + (seat to true)) }
            step("문 닫음", 4) { it.copy(doorOpen = it.doorOpen + (seat to false)) }
            step("남은 시간 2분 — 곧 도착을 알립니다", 10) { it.copy(nav = it.nav.copy(remainSec = 120, remainMeters = 600)) }
            step("목적지에서 기어 P — 도착을 알립니다", 10) {
                it.copy(gear = Gear.PARK, nav = it.nav.copy(remainSec = 20, remainMeters = 50))
            }
            _scenarioStep.value = null
            scenario = null
        }
    }

    companion object {
        const val DEMO_PLACE = "큰딸 집"
        const val DEMO_TOTAL_SEC = 12 * 60
        /** 모의 주행에서 1초에 줄어드는 남은 시간(초). */
        const val AUTO_DRIVE_SPEEDUP = 4

        fun initialState() = VehicleState(
            gear = Gear.PARK,
            speedKph = null,
            brakePressed = false,
            uxRestricted = false,
            seatOccupied = mapOf(
                SeatPosition.DRIVER to true,
                SeatPosition.FRONT_PASSENGER to false,
                SeatPosition.REAR_LEFT to false,
                SeatPosition.REAR_RIGHT to true,
            ),
            doorOpen = SeatPosition.entries.associateWith { false },
            windowOpenPercent = SeatPosition.PASSENGER_SEATS.associateWith { 0 },
            beltBuckled = SeatPosition.PASSENGER_SEATS.associateWith { true },
            nav = NavState(
                guiding = true,
                destinationName = DEMO_PLACE,
                totalSec = DEMO_TOTAL_SEC,
                remainSec = DEMO_TOTAL_SEC,
                remainMeters = 6_000,
            ),
        )
    }
}
