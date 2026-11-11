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
import app.myeotbeonideun.engine.CompanionEngine
import app.myeotbeonideun.engine.Phase
import app.myeotbeonideun.engine.journey.Stage
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.engine.record.RecordAggregator
import app.myeotbeonideun.engine.script.ScriptEngine
import app.myeotbeonideun.engine.script.ScriptValidator
import app.myeotbeonideun.engine.script.SlotValues
import app.myeotbeonideun.service.TripUi
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.DrivingLock
import app.myeotbeonideun.signal.PleosSignalSource
import app.myeotbeonideun.signal.SeatInterpretation
import app.myeotbeonideun.signal.SeatPolarity
import app.myeotbeonideun.signal.SeatPolarityBasis
import app.myeotbeonideun.signal.SeatReading
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.signal.SourceStatus
import app.myeotbeonideun.speech.ListenState
import app.myeotbeonideun.speech.SpeechResult
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

/** 첫 실행 안내에서 속도 신호 허용을 권하는지(결함 7). */
enum class SpeedAsk {
    /** 권하지 않는다(차량용 기기가 아니거나 이미 허용했다). */
    HIDDEN,
    /** '속도 신호 허용하기' 버튼을 보인다. */
    SUGGEST,
    /** 한 번 물었고 허용하지 않았다. 환경설정에서 언제든 허용할 수 있다고만 적는다(다시 조르지 않는다). */
    DECLINED,
}

/**
 * 화면 틀(메뉴, 알림표)이 그리는 데 필요한 것.
 *
 * @property restricted 주행 중 화면 잠금(지금 신호 출처의 규제 OR 실차의 규제)
 * @property vehicleRestricted 실차가 주행 규제 중인지(체험 모드여도 실제 차의 값)
 * @property speedAsk 첫 실행 안내(정차 중에만 보인다)에서 속도 신호 허용을 권하는지
 */
data class ShellUi(
    val screen: Screen = Screen.SETUP,
    val restricted: Boolean = false,
    val tripActive: Boolean = false,
    val demo: Boolean = false,
    val noticeAccepted: Boolean = true,
    val vehicleRestricted: Boolean = false,
    val speedAsk: SpeedAsk = SpeedAsk.HIDDEN,
) {
    /**
     * 지금 화면 대신 잠금 안내를 보이는지. 정차 전용 화면은 주행 중에 잠근다.
     * 체험 모드의 개발자 패널은 모의 신호로 '주행'을 흉내 내는 도구라 모의 주행만으로는 잠그지 않지만,
     * 실차가 주행 중이면 잠근다.
     */
    val locked: Boolean
        get() = screen.parkedOnly && (vehicleRestricted || (restricted && !(screen == Screen.DEV && demo)))
}

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
    /** 이번 실행에서 속도 권한을 한 번 물었는지(첫 실행 안내에서 다시 조르지 않는다). */
    private val _speedAsked = MutableStateFlow(false)

    /** 이번 실행에서 알림 권한을 한 번 물었는지(동행을 시작할 때마다 조르지 않는다). */
    var notificationPermissionAsked = false

    init {
        // 좌석 값 자동 판별: 운전자가 화면을 조작하는 출발 전 설정(P단)에서는 운전석에 사람이 있다.
        // 그동안 운전석 원시 값으로 그대로·반대로를 정한다(동행 중에는 바꾸지 않는다). 빈칸 채우기보다 먼저 둔다.
        viewModelScope.launch {
            combine(_screen, c.foreground, c.trip.ui, c.vehicleSource.state) { screen, foreground, trip, v ->
                screen == Screen.SETUP && foreground && trip == null && DrivingLock.parkedForCalibration(v)
            }.collect { driverAtScreen -> if (driverAtScreen) c.vehicleSource.noteDriverAtScreen() }
        }
        // 좌석 신호와 내비 목적지로 빈칸을 미리 채운다(명세 3.1). 좌석 값은 해석한 값이다.
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
        combine(
            _screen, c.drivingRestricted, c.vehicleRestricted, c.trip.ui,
            combine(sourceKind, c.store.settings, _speedGranted, _speedAsked) { kind, settings, granted, asked ->
                Triple(kind, settings, speedAsk(granted, asked))
            },
        ) { screen, restricted, vehicleRestricted, trip, (kind, settings, speedAsk) ->
            ShellUi(
                screen = screen,
                restricted = restricted,
                tripActive = trip != null,
                demo = kind == SourceKind.DEMO,
                noticeAccepted = settings.noticeAccepted,
                vehicleRestricted = vehicleRestricted,
                speedAsk = speedAsk,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellUi(_screen.value))

    /**
     * 속도 권한(CAR_SPEED)이 없으면 첫 실행 안내에서 한 번 허용을 권한다. 허용하지 않으면 '정지'는
     * 주행 단에서 브레이크를 35초 넘게 밟고 있는 것으로만 본다(오토홀드로 발을 떼고 서 있으면 모른다).
     */
    private fun speedAsk(granted: Boolean, asked: Boolean): SpeedAsk = when {
        !c.isAutomotive || granted -> SpeedAsk.HIDDEN
        asked -> SpeedAsk.DECLINED
        else -> SpeedAsk.SUGGEST
    }

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
                customLineProblem = customLineProblem(form),
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
        // 운전자가 이 화면을 누르는 순간이므로 좌석 값 해석을 먼저 지금 운전석 값으로 맞춘다(자동일 때).
        c.vehicleSource.noteDriverAtScreen()
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

    /**
     * 직접 쓴 화제 전환 한마디를 엔진의 검사(CompanionEngine.checkCustomPurposeLine — 대본의 화제 전환 줄과 같은 규칙)로
     * 입력할 때 바로 본다(결함 16-6). 엔진은 이 검사를 통과한 한마디만 읽으므로, 통과하지 못하면 "이대로면 읽지 않아요"와
     * 고칠 것을 알려 준다. 동행 시작은 막지 않는다(한마디 없이 대답만 한다). 문제가 없으면 null.
     */
    private fun customLineProblem(form: SetupForm): String? {
        if (form.purpose != Purpose.CUSTOM || form.customPurposeLine.isBlank()) return null
        val honorific = form.honorific.trim()
        val findings = CompanionEngine.checkCustomPurposeLine(form.customPurposeLine, honorific)
        if (findings.isEmpty()) return null
        val line = CompanionEngine.customRedirectText(form.customPurposeLine, honorific).orEmpty()
        val reasons = findings.mapNotNull { f ->
            when (f.rule) {
                "R2" -> {
                    val sentences = ScriptValidator.splitSentences(line)
                    if (sentences.size > ScriptValidator.MAX_SENTENCES_REDIRECT) text(R.string.custom_line_sentences)
                    else text(
                        R.string.custom_line_too_long,
                        sentences.maxOfOrNull { ScriptValidator.countSyllables(it) } ?: 0,
                        ScriptValidator.MAX_SYLLABLES_PER_SENTENCE,
                    )
                }
                "R3" -> ScriptValidator.findForbidden(line)?.let { text(R.string.custom_line_forbidden, it) }
                "S" -> ScriptValidator.UNSAFE.firstOrNull { line.contains(it) }?.let { text(R.string.custom_line_unsafe, it) }
                "D" -> text(R.string.custom_line_dignity)
                "F-슬롯" -> text(R.string.custom_line_braces)
                else -> null
            }
        }.distinct()
        return text(R.string.custom_line_problem, reasons.joinToString(" ").ifEmpty { text(R.string.custom_line_other) })
    }

    // ───────────────────────── 동행 중 ─────────────────────────

    val companion: StateFlow<CompanionUi> =
        combine(
            c.trip.ui, c.drivingRestricted, c.speech.lastSkip, c.speech.engineMissing, c.vehicleSource.status,
        ) { trip, restricted, skip, noEngine, vehicleStatus ->
            if (trip == null) CompanionUi(active = false, restricted = restricted)
            else {
                // 차량 신호로 도는데 신호를 하나도 얻지 못했다(체험 모드로 넘어가지 않는다). 운전자에게 한 줄로 알린다.
                val noSignal = trip.sourceKind == SourceKind.VEHICLE && vehicleStatus == SourceStatus.UNAVAILABLE
                companionUi(trip, restricted, skip, noEngine, noSignal)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CompanionUi())

    /** 동승자 카드의 두 줄. 동행 중이 아니면 null */
    val card: StateFlow<Pair<String, String>?> =
        c.trip.ui.map { trip -> trip?.let { it.snapshot.cardTitle to it.snapshot.cardStage } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun repeat() { c.trip.repeat() }
    fun toggleQuiet() { c.trip.toggleQuiet() }
    fun endTrip() = c.trip.end()

    /** '도착했어요'(정차 중에만 보인다). 도착을 알리고, 안내가 끝나면 동행을 마친다. */
    fun confirmArrival() { c.trip.confirmArrival() }

    private fun companionUi(
        trip: TripUi,
        restricted: Boolean,
        skip: SpeechResult?,
        noEngine: Boolean,
        noSignal: Boolean,
    ): CompanionUi {
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
            // 소리를 내지 못한 말도 셋째 줄에 그대로 보인다(화면으로만 알림).
            lastLine = s.lastLine,
            notice = s.driverNotice,
            noticeUrgent = s.driverNotice != null && s.driverNoticeUrgent,
            // 소리를 내지 못했으면(음성 엔진 없음, 내비 안내·통화 중 등) 그 까닭을 첫째 줄 자리에 한 줄로 알린다.
            // '도착했어요'를 누른 뒤에는 곧 동행을 마친다는 것을 알린다.
            status = statusNoticeRes(noEngine, noSignal, skip, trip.endingAfterArrival)?.let { text(it) },
            quietMinutesLeft = s.quietUntilMs?.let { ceil((it - trip.nowMs) / 60_000.0).toInt().coerceAtLeast(1) },
            canEnd = !restricted,
            // '도착했어요'는 정차 중(화면 잠금이 아님)이고 엔진이 받을 수 있을 때(출발 뒤·도착 전·P단)만.
            canConfirmArrival = !restricted && s.canConfirmArrival && !trip.endingAfterArrival,
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

    private data class SettingsExtras(
        val listen: ListenState,
        val speedGranted: Boolean,
        val vehicleStatus: SourceStatus,
        val seat: SeatInterpretation,
    )

    val settings: StateFlow<SettingsUi> =
        combine(
            c.store.settings, sourceKind, capabilities, c.speech.engineName,
            combine(c.listener.state, _speedGranted, c.vehicleSource.status, c.vehicleSource.seatInterpretation, ::SettingsExtras),
        ) { settings, kind, cap, engine, extras ->
            SettingsUi(
                settings = settings,
                isAutomotive = c.isAutomotive,
                sourceKind = kind,
                capabilities = cap,
                speechEngine = engine,
                listenState = extras.listen,
                speedPermissionGranted = extras.speedGranted,
                versionName = BuildConfig.VERSION_NAME,
                vehicleStatus = extras.vehicleStatus,
                seatReadingLine = if (c.isAutomotive) seatReadingLine(extras.seat) else null,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    /** 좌석 값 읽기(자동·그대로·반대로). 정차 중에만 바꾼다. */
    fun setSeatReading(reading: SeatReading) {
        if (c.drivingRestricted.value) return
        c.store.updateSettings { it.copy(seatReading = reading) }
    }

    /** "좌석 값: 반대로(운전석 기준 자동)" */
    private fun seatReadingLine(i: SeatInterpretation): String {
        val polarity = text(if (i.polarity == SeatPolarity.INVERTED) R.string.seat_polarity_inverted else R.string.seat_polarity_as_is)
        val basis = text(
            when (i.basis) {
                SeatPolarityBasis.DRIVER_SEAT -> R.string.seat_basis_driver
                SeatPolarityBasis.NO_DRIVER_VALUE -> R.string.seat_basis_no_driver
                SeatPolarityBasis.MANUAL -> R.string.seat_basis_manual
            },
        )
        val line = text(R.string.seat_reading_line, polarity, basis)
        return if (i.heldForTrip) text(R.string.seat_reading_held, line) else line
    }

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
        _speedAsked.value = true
        _speedGranted.value = granted
        // 구독 전체를 놓았다 붙이지 않고 속도 신호만 다시 붙인다(동행 중의 좌석·문 값이 끊기지 않게).
        if (granted) c.vehicleSource.refreshSpeedPermission()
    }

    private fun checkSpeedPermission(): Boolean = runCatching {
        getApplication<Application>().checkSelfPermission(PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ───────────────────────── 개발자 패널 ─────────────────────────

    val dev: StateFlow<DevPanelUi> =
        combine(
            vehicle, sourceKind, c.trip.ui, c.demoSource.scenarioStep,
            combine(c.vehicleSource.diagnostics, c.vehicleSource.rawSeatOccupied, c.vehicleSource.seatInterpretation) { notes, raw, seat ->
                Triple(notes, raw, seat)
            },
        ) { v, kind, trip, step, (notes, raw, seat) ->
            val vehicleMode = kind == SourceKind.VEHICLE
            DevPanelUi(
                state = v,
                seat = trip?.config?.seat ?: _form.value.seat,
                editable = kind == SourceKind.DEMO,
                tripActive = trip != null,
                scenarioStep = step,
                diagnostics = if (vehicleMode) notes else emptyList(),
                // 차량 모드에서는 좌석 값의 해석과 SDK 원시 값(좌석 점유, 안전띠)을 함께 보인다.
                seatReadingLine = if (vehicleMode) seatReadingLine(seat) else null,
                rawValuesLine = if (vehicleMode) rawValuesLine(raw, v.beltBuckled) else null,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DevPanelUi())

    /** SDK 가 넘긴 그대로의 값. 좌석 점유는 해석 전, 안전띠는 해석하지 않는다(극성 미확인). */
    private fun rawValuesLine(seats: Map<SeatPosition, Boolean>, belts: Map<SeatPosition, Boolean>): String {
        fun list(values: Map<SeatPosition, Boolean>, positions: List<SeatPosition>) =
            positions.joinToString(" · ") { "${it.label} ${values[it]?.toString() ?: "-"}" }
        return text(
            R.string.dev_raw_values,
            list(seats, SeatPosition.entries),
            list(belts, SeatPosition.PASSENGER_SEATS),
        )
    }

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

        /**
         * 동행 화면 첫째 줄 자리에 보일 앱 상태 한 줄(문자열 id). 없으면 null.
         * 음성 엔진 없음 > 차량 신호 없음 > '도착했어요' 뒤 곧 마침 > 마지막 말을 소리 내지 못한 까닭 순.
         * 끊김·조용히는 알리지 않는다(운전자가 시켰거나 더 급한 말이 나갔다).
         */
        internal fun statusNoticeRes(
            noEngine: Boolean,
            noSignal: Boolean,
            skip: SpeechResult?,
            endingAfterArrival: Boolean = false,
        ): Int? = when {
            noEngine -> R.string.companion_no_engine
            noSignal -> R.string.companion_no_signal
            endingAfterArrival -> R.string.companion_ending_after_arrival
            else -> when (skip) {
                SpeechResult.NAVIGATION_BUSY -> R.string.companion_skip_navigation
                SpeechResult.CALL_BUSY -> R.string.companion_skip_call
                SpeechResult.FOCUS_DENIED -> R.string.companion_skip_focus
                SpeechResult.ENGINE_FAILED -> R.string.companion_skip_engine
                SpeechResult.NO_ENGINE -> R.string.companion_no_engine
                SpeechResult.SPOKEN, SpeechResult.INTERRUPTED, SpeechResult.SILENCED, null -> null
            }
        }

        /** 위치 권한 이름(Pleos FusedLocation). 화면에서 권한을 물을 때 쓴다. */
        const val PERMISSION_LOCATION = PleosSignalSource.PERMISSION_FUSED_LOCATION
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
    }
}
