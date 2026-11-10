package app.myeotbeonideun.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.myeotbeonideun.App
import app.myeotbeonideun.AppContainer
import app.myeotbeonideun.BuildConfig
import app.myeotbeonideun.R
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.engine.Phase
import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.engine.record.RecordAggregator
import app.myeotbeonideun.engine.script.ScriptEngine
import app.myeotbeonideun.engine.script.SlotValues
import app.myeotbeonideun.service.TripUi
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.PleosSignalSource
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.ui.screens.CompanionUi
import app.myeotbeonideun.ui.screens.DevPanelActions
import app.myeotbeonideun.ui.screens.DevPanelUi
import app.myeotbeonideun.ui.screens.RecordsUi
import app.myeotbeonideun.ui.screens.SettingsUi
import app.myeotbeonideun.ui.screens.SetupForm
import app.myeotbeonideun.ui.screens.SetupUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.ceil

/** 화면. 정차 전용 화면은 주행 규제 중에 잠긴다. */
enum class Screen(val parkedOnly: Boolean) {
    SETUP(true),
    COMPANION(false),
    CARD(false),
    RECORDS(true),
    SETTINGS(true),
    DEV(true),
}

/** 화면 틀(메뉴, 알림표)이 그리는 데 필요한 것. */
data class ShellUi(
    val screen: Screen = Screen.SETUP,
    val restricted: Boolean = false,
    val tripActive: Boolean = false,
    val demo: Boolean = false,
    val noticeAccepted: Boolean = true,
)

/**
 * 앱의 상태를 화면이 그리기 좋은 모양(…Ui)으로 바꿔 준다. 화면(Composable)은 상태를 갖지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app), DevPanelActions {

    private val c: AppContainer = (app as App).container
    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    private val vehicle = c.source.flatMapLatest { it.state }
    private val capabilities = c.source.flatMapLatest { it.capabilities }
    private val sourceKind = c.source.map { it.kind }

    private val _screen = MutableStateFlow(if (c.trip.inProgress) Screen.COMPANION else Screen.SETUP)
    private val _form = MutableStateFlow(c.store.routes.value.firstOrNull()?.let { SetupForm.from(it).copy(seatAuto = true) } ?: SetupForm())
    private var placeEdited = false
    private val _speedGranted = MutableStateFlow(checkSpeedPermission())

    init {
        // 좌석 신호와 내비 목적지로 빈칸을 미리 채운다(명세 3.1).
        viewModelScope.launch { vehicle.collect(::autoFill) }
        // 동행이 시작되면 동행 화면으로, 끝나면 기록 화면으로 간다.
        viewModelScope.launch {
            var wasActive = c.trip.inProgress
            c.trip.ui.collect { ui ->
                val active = ui != null
                if (active && !wasActive) _screen.value = Screen.COMPANION
                if (!active && wasActive && _screen.value == Screen.COMPANION) _screen.value = Screen.RECORDS
                wasActive = active
            }
        }
    }

    // ───────────────────────── 화면 틀 ─────────────────────────

    val shell: StateFlow<ShellUi> =
        combine(_screen, c.drivingRestricted, c.trip.ui, sourceKind, c.store.settings) { screen, restricted, trip, kind, settings ->
            ShellUi(screen, restricted, trip != null, kind == SourceKind.DEMO, settings.noticeAccepted)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellUi(_screen.value))

    fun navigate(screen: Screen) {
        _screen.value = screen
    }

    fun acceptNotice() = c.store.updateSettings { it.copy(noticeAccepted = true) }

    // ───────────────────────── 출발 전 설정 ─────────────────────────

    val setup: StateFlow<SetupUi> =
        combine(_form, c.store.routes, vehicle, capabilities) { form, routes, v, cap ->
            SetupUi(
                form = form,
                routes = routes,
                occupiedSeats = v.occupiedPassengerSeats(),
                seatSignalAvailable = cap.seatOccupancy,
                locationAvailable = cap.location,
                preview = if (form.canStart) preview(form) else "",
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SetupUi(_form.value))

    fun updateForm(form: SetupForm) {
        if (form.place != _form.value.place) placeEdited = true
        _form.value = form
    }

    fun startTrip() {
        val form = _form.value
        if (!form.canStart) return
        c.trip.start(form.toConfig(), form.rememberArrival)
    }

    fun startRoute(route: SavedRoute) {
        // 저장된 길은 한 번 눌러 시작한다. 자리는 지금 사람이 앉은 자리로 맞춘다.
        val occupied = c.source.value.state.value.occupiedPassengerSeats()
        val seat = if (occupied.isNotEmpty() && route.config.seat !in occupied) occupied.first() else route.config.seat
        _form.value = SetupForm.from(route).copy(seat = seat)
        c.trip.start(route.config.copy(seat = seat), route.rememberArrival)
    }

    fun deleteRoute(route: SavedRoute) = c.store.deleteRoute(route.id)

    private fun autoFill(v: VehicleState) {
        _form.update { form ->
            var next = form
            val occupied = v.occupiedPassengerSeats()
            if (form.seatAuto && occupied.isNotEmpty() && form.seat !in occupied) {
                next = next.copy(seat = occupied.first())
            }
            val navName = v.nav.destinationName
            if (!placeEdited && form.place.isBlank() && !navName.isNullOrBlank()) {
                next = next.copy(place = navName.take(16))
            }
            next
        }
    }

    private fun preview(form: SetupForm): String {
        val template = c.script.situation("depart")?.lines?.firstOrNull() ?: return ""
        return ScriptEngine.render(template, SlotValues(form.honorific.trim(), form.place.trim(), form.driver.trim()))
    }

    // ───────────────────────── 동행 중 ─────────────────────────

    val companion: StateFlow<CompanionUi> =
        combine(c.trip.ui, c.drivingRestricted, c.speech.lastSkipped, c.speech.engineMissing) { trip, restricted, skipped, noEngine ->
            if (trip == null) CompanionUi(active = false, restricted = restricted)
            else companionUi(trip, restricted, skipped != null, noEngine)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CompanionUi())

    /** 동승자 카드의 두 줄. 동행 중이 아니면 null */
    val card: StateFlow<Pair<String, String>?> =
        c.trip.ui.map { trip -> trip?.let { it.snapshot.cardTitle to it.snapshot.cardStage } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun repeat() { c.trip.repeat() }
    fun toggleQuiet() { c.trip.toggleQuiet() }
    fun endTrip() = c.trip.end()

    private fun companionUi(trip: TripUi, restricted: Boolean, speechSkipped: Boolean, noEngine: Boolean): CompanionUi {
        val s = trip.snapshot
        val stage = when {
            s.phase == Phase.READY -> text(R.string.stage_ready)
            else -> text(
                when (s.stage) {
                    Stage.JUST_DEPARTED -> R.string.stage_just_departed
                    Stage.ON_WAY -> R.string.stage_on_way
                    Stage.HALF -> R.string.stage_half
                    Stage.NEAR -> R.string.stage_near
                    Stage.SOON -> R.string.stage_soon
                    Stage.ARRIVED -> R.string.stage_arrived
                },
            )
        }
        return CompanionUi(
            active = true,
            who = text(R.string.companion_who, trip.config.honorific, trip.config.seat.label),
            place = trip.config.place,
            stage = stage,
            lastLine = s.lastLine,
            // 소리를 내지 못했으면(음성 엔진 없음, 통화 중 등) 그 사실을 운전자에게 한 줄로 알린다.
            notice = s.driverNotice ?: when {
                noEngine -> text(R.string.companion_no_engine)
                speechSkipped -> text(R.string.companion_speech_skipped)
                else -> null
            },
            noticeUrgent = s.driverNotice != null && s.driverNoticeUrgent,
            quietMinutesLeft = s.quietUntilMs?.let { ceil((it - trip.nowMs) / 60_000.0).toInt().coerceAtLeast(1) },
            canEnd = !restricted,
            restricted = restricted,
        )
    }

    // ───────────────────────── 오늘의 기록 ─────────────────────────

    val records: StateFlow<RecordsUi> =
        c.store.records.map { list ->
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            RecordsUi(
                today = RecordAggregator.summarize(list, start, start + DAY_MS),
                trend = RecordAggregator.recentTrend(list, 8),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordsUi())

    // ───────────────────────── 환경설정 ─────────────────────────

    val settings: StateFlow<SettingsUi> =
        combine(
            c.store.settings, sourceKind, capabilities, c.speech.engineName,
            combine(c.listener.state, _speedGranted) { listen, speed -> listen to speed },
        ) { settings, kind, cap, engine, (listen, speed) ->
            SettingsUi(
                settings = settings,
                isAutomotive = c.isAutomotive,
                sourceKind = kind,
                capabilities = cap,
                speechEngine = engine,
                listenState = listen,
                speedPermissionGranted = speed,
                versionName = BuildConfig.VERSION_NAME,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    fun setDemoMode(on: Boolean) {
        if (c.trip.inProgress) c.trip.end() // 신호 출처가 바뀌므로 하던 동행은 끝낸다
        c.store.updateSettings { it.copy(demoMode = on) }
    }

    fun setListen(on: Boolean) = c.store.updateSettings { it.copy(listenEnabled = on) }
    fun setWindowThreshold(percent: Int) = c.store.updateSettings { it.copy(windowThresholdPercent = percent) }
    fun setAudioZone(zone: Int?) {
        c.store.updateSettings { it.copy(audioZoneId = zone) }
        c.speech.setAudioZone(zone)
    }

    /** 자주 가는 길, 기록, 설정을 모두 지운다. */
    fun clearAll() {
        if (c.trip.inProgress) c.trip.end()
        c.store.clearAll()
        placeEdited = false
        _form.value = SetupForm()
        c.store.updateSettings { it.copy(noticeAccepted = true) }
    }

    fun onSpeedPermissionResult(granted: Boolean) {
        _speedGranted.value = granted
        if (granted) c.restartVehicleSource()
    }

    private fun checkSpeedPermission(): Boolean = runCatching {
        getApplication<Application>().checkSelfPermission(PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ───────────────────────── 개발자 패널 ─────────────────────────

    val dev: StateFlow<DevPanelUi> =
        combine(vehicle, sourceKind, c.trip.ui, c.demoSource.scenarioStep, c.vehicleSource.diagnostics) { v, kind, trip, step, notes ->
            DevPanelUi(
                state = v,
                seat = trip?.config?.seat ?: _form.value.seat,
                editable = kind == SourceKind.DEMO,
                tripActive = trip != null,
                scenarioStep = step,
                diagnostics = if (kind == SourceKind.VEHICLE) notes else emptyList(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DevPanelUi())

    private val demo: DemoSignalSource? get() = c.source.value as? DemoSignalSource

    override fun change(change: (VehicleState) -> VehicleState) { demo?.update(change) }
    override fun emitArrived() { demo?.emitArrived() }
    override fun playScenario() {
        val seat: SeatPosition = c.trip.ui.value?.config?.seat ?: return
        demo?.playScenario(seat)
    }
    override fun stopScenario() { demo?.stopScenario() }
    override fun reset() {
        if (c.trip.inProgress) c.trip.end()
        demo?.reset()
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 위치 권한 이름(Pleos FusedLocation). 화면에서 권한을 물을 때 쓴다. */
        const val PERMISSION_LOCATION = PleosSignalSource.PERMISSION_FUSED_LOCATION
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
    }
}
