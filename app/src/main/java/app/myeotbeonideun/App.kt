package app.myeotbeonideun

import android.app.Application
import android.content.pm.PackageManager
import app.myeotbeonideun.data.AppStore
import app.myeotbeonideun.engine.script.ScriptBook
import app.myeotbeonideun.gleo.GleoLimits
import app.myeotbeonideun.service.TripController
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.DrivingLock
import app.myeotbeonideun.signal.PleosSignalSource
import app.myeotbeonideun.signal.SignalSource
import app.myeotbeonideun.signal.VehicleSignalSource
import app.myeotbeonideun.speech.PassengerListener
import app.myeotbeonideun.speech.PleosPassengerListener
import app.myeotbeonideun.speech.Speaker
import app.myeotbeonideun.speech.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

open class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
    }

    /** 앱 전체가 함께 쓰는 것들을 만든다. 시험은 이것을 바꿔 차량 신호를 가짜로 끼운다. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}

/**
 * 앱 전체가 함께 쓰는 것들. 의존성 주입 라이브러리 없이 손으로 묶었다.
 * 모든 흐름은 메인 스레드에서 돈다(엔진은 한 스레드에서만 부른다).
 *
 * @param isAutomotive 차량용 기기(AAOS)인지. 아니면 차량 서비스에 붙으려 하지 않고 체험 모드로만 돈다.
 * @param vehicleSource 실차 신호(시험에서는 가짜)
 * @param speakers 음성 엔진(시험에서는 가짜). null 이면 Pleos TTS → 안드로이드 TTS
 * @param activeUsages 지금 재생 중인 소리의 용도(시험에서는 가짜). null 이면 AudioManager 에서 읽는다
 * @param listener 음성 인식(실험 기능, 시험에서는 가짜)
 */
class AppContainer(
    private val app: Application,
    val isAutomotive: Boolean = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
    val vehicleSource: VehicleSignalSource = PleosSignalSource(app),
    speakers: List<Speaker>? = null,
    activeUsages: (() -> List<Int>)? = null,
    val listener: PassengerListener = PleosPassengerListener(app),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store = AppStore(app)
    val demoSource = DemoSignalSource(scope)
    val speech =
        if (speakers != null) SpeechController(app, scope, speakers, activeUsages)
        else SpeechController(app, scope, activeUsages = activeUsages)

    /** 미리 써 둔 고정 대본. 실행 중에 문장을 만들지 않는다. */
    val script: ScriptBook by lazy {
        ScriptBook.parse(app.assets.open(SCRIPT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /**
     * 지금 쓰는 신호 출처.
     * - 차량용 기기가 아니면 체험 모드(모의 신호).
     * - 차량용 기기에서는 환경설정에서 체험 모드를 켰을 때만 모의 신호다. 차량 신호를 하나도 얻지 못해도 체험 모드로
     *   넘어가지 않는다(모의 P단 때문에 실제 주행 중 화면 잠금이 풀리지 않게). 그때는 '차량 신호 없음'으로 돌며
     *   다시 연결해 본다.
     */
    val source: StateFlow<SignalSource> =
        store.settings.map { settings -> pick(settings.demoMode) }
            .stateIn(scope, SharingStarted.Eagerly, pick(store.settings.value.demoMode))

    private fun pick(demoMode: Boolean): SignalSource =
        if (!isAutomotive || demoMode) demoSource else vehicleSource

    /** 실차가 주행 규제 중인지(체험 모드여도 실제 차의 값). 차량용 기기가 아니면 늘 false. */
    val vehicleRestricted: StateFlow<Boolean> =
        vehicleSource.state.map { isAutomotive && DrivingLock.restricted(it) }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * 주행 중 화면 잠금. 규제 중에는 설정·기록 화면을 잠근다.
     * 지금 쓰는 신호 출처의 규제와 실차의 규제(CarUxRestrictions, 없으면 변속단 != P)를 OR 로 묶는다.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val drivingRestricted: StateFlow<Boolean> =
        combine(source.flatMapLatest { it.state }, vehicleRestricted) { current, real -> DrivingLock.restricted(current) || real }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** 지금 이 순간의 화면 잠금 값. 흐름을 거치지 않고 바로 센다(Gleo 처럼 화면 밖에서 판단할 때). */
    fun isDrivingRestrictedNow(): Boolean =
        DrivingLock.screenRestricted(source.value.state.value, vehicleSource.state.value.takeIf { isAutomotive })

    private val foregroundTracker = ForegroundTracker().also { app.registerActivityLifecycleCallbacks(it) }

    /** 앱 화면이 보이는지(ProcessLifecycle 의 onStart/onStop 과 같은 뜻). */
    val foreground: StateFlow<Boolean> = foregroundTracker.foreground

    val trip = TripController(app, this)

    /** Gleo(와 같은 꼴로 부르는 바깥 앱)의 연속 호출 제한(결함 18). */
    val gleoLimits = GleoLimits()

    init {
        demoSource.start()
        // 환경설정의 '좌석 값 읽기'(자동·그대로·반대로)를 실차 신호에 알린다.
        scope.launch { store.settings.map { it.seatReading }.distinctUntilChanged().collect { vehicleSource.setSeatReading(it) } }
        // 차량 신호 구독은 '화면이 보이거나 동행 중'일 때만 둔다. 백그라운드에서 동행이 없으면 놓는다
        // (Pleos 가이드: 백그라운드의 불필요한 센서 수집 정리).
        scope.launch { foreground.collect { syncVehicleSubscription() } }
        scope.launch { trip.ui.map { it != null }.distinctUntilChanged().collect { syncVehicleSubscription() } }
    }

    /** 지금 차량 신호 구독이 필요한지 보고 붙이거나 놓는다. 여러 번 불러도 된다. */
    fun syncVehicleSubscription() {
        if (DrivingLock.shouldSubscribe(isAutomotive, foreground.value, trip.inProgress)) vehicleSource.start()
        else vehicleSource.stop()
    }

    /**
     * 화면 밖(Gleo 의 "동행 시작" 등)에서 주행 여부를 봐야 할 때, 차량 신호를 먼저 붙인다.
     * 주행 규제(CarUxRestrictions)는 붙이는 순간 지금 값을 읽는다. 쓸 일이 없으면 다음 [syncVehicleSubscription] 이 놓는다.
     */
    fun ensureVehicleSignals() {
        if (isAutomotive) vehicleSource.start()
    }

    companion object {
        const val SCRIPT_ASSET = "scripts_ko.json"
    }
}
