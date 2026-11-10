package app.myeotbeonideun

import android.app.Application
import android.content.pm.PackageManager
import app.myeotbeonideun.data.AppStore
import app.myeotbeonideun.engine.script.ScriptBook
import app.myeotbeonideun.service.TripController
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.PleosSignalSource
import app.myeotbeonideun.signal.SignalSource
import app.myeotbeonideun.signal.SourceStatus
import app.myeotbeonideun.speech.PassengerListener
import app.myeotbeonideun.speech.PleosPassengerListener
import app.myeotbeonideun.speech.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 앱 전체가 함께 쓰는 것들. 의존성 주입 라이브러리 없이 손으로 묶었다.
 * 모든 흐름은 메인 스레드에서 돈다(엔진은 한 스레드에서만 부른다).
 */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 차량용 기기(AAOS)인지. 아니면 차량 서비스에 붙으려 하지 않고 체험 모드로만 돈다. */
    val isAutomotive: Boolean = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    val store = AppStore(app)
    val demoSource = DemoSignalSource(scope)
    val vehicleSource = PleosSignalSource(app)
    val speech = SpeechController(app, scope)
    val listener: PassengerListener = PleosPassengerListener(app)

    /** 미리 써 둔 고정 대본. 실행 중에 문장을 만들지 않는다. */
    val script: ScriptBook by lazy {
        ScriptBook.parse(app.assets.open(SCRIPT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /**
     * 지금 쓰는 신호 출처.
     * - 환경설정에서 체험 모드를 켰으면 모의 신호.
     * - 아니면 차량 신호. 다만 차량 신호를 하나도 얻지 못하면(Pleos 서비스가 없는 기기) 체험 모드로 넘어간다.
     */
    val source: StateFlow<SignalSource> =
        combine(store.settings, vehicleSource.status) { settings, status ->
            if (!isAutomotive || settings.demoMode || status == SourceStatus.UNAVAILABLE) demoSource else vehicleSource
        }.stateIn(scope, SharingStarted.Eagerly, if (isAutomotive) vehicleSource else demoSource)

    /** 주행 규제 중인지. 규제 중에는 설정·기록 화면을 잠근다. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val drivingRestricted: StateFlow<Boolean> =
        source.flatMapLatest { it.state }
            .map { it.uxRestricted ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val trip = TripController(app, this)

    init {
        demoSource.start()
        if (isAutomotive) vehicleSource.start()
    }

    /** 속도 권한을 새로 허용받았을 때 등, 차량 신호를 다시 붙인다. */
    fun restartVehicleSource() {
        if (!isAutomotive) return
        vehicleSource.stop()
        vehicleSource.start()
    }

    companion object {
        const val SCRIPT_ASSET = "scripts_ko.json"
    }
}
