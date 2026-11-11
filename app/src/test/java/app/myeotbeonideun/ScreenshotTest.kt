package app.myeotbeonideun

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.data.Settings
import app.myeotbeonideun.engine.CompanionEngine
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.NavState
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.Signals
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.record.DaySummary
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.engine.script.ScriptBook
import app.myeotbeonideun.signal.Capabilities
import app.myeotbeonideun.signal.DemoSignalSource
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.ui.AppShell
import app.myeotbeonideun.ui.Screen
import app.myeotbeonideun.ui.ShellUi
import app.myeotbeonideun.ui.screens.CompanionScreen
import app.myeotbeonideun.ui.screens.CompanionUi
import app.myeotbeonideun.ui.screens.DevPanelActions
import app.myeotbeonideun.ui.screens.DevPanelScreen
import app.myeotbeonideun.ui.screens.DevPanelUi
import app.myeotbeonideun.ui.screens.PassengerCardScreen
import app.myeotbeonideun.ui.screens.RecordsScreen
import app.myeotbeonideun.ui.screens.RecordsUi
import app.myeotbeonideun.ui.screens.SettingsScreen
import app.myeotbeonideun.ui.screens.SettingsUi
import app.myeotbeonideun.ui.screens.SetupForm
import app.myeotbeonideun.ui.screens.SetupScreen
import app.myeotbeonideun.ui.screens.SetupUi
import app.myeotbeonideun.ui.theme.MyeotbeonTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 화면 그림을 PNG 로 뽑는다(Roborazzi). 에뮬레이터 없이 JVM 에서 실제 Compose 화면을 그린다.
 *
 *   ./gradlew :app:recordRoborazziDebug     → docs/screens/ 에 저장
 *
 * 화면에 보이는 안내 문장은 실제 엔진에 신호를 넣어 얻은 것이다(대본 파일 그대로).
 * 크기: Pleos Connect 의 가로 창(1700x1212, 160dpi)과 세로 창(840x1212).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = ScreenshotTest.LANDSCAPE)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val config = TripConfig(
        honorific = "어머니", place = "큰딸 집", purpose = Purpose.MEAL, driver = "따님",
        seat = SeatPosition.REAR_RIGHT, interval = IntervalMode.AUTO,
    )

    private fun shot(name: String, shell: ShellUi, dark: Boolean = true, content: @Composable (Boolean) -> Unit) {
        compose.setContent {
            MyeotbeonTheme(dark = dark) {
                AppShell(shell, onNavigate = {}, onAcceptNotice = {}) { _, wide -> content(wide) }
            }
        }
        compose.onRoot().captureRoboImage("$OUT/$name.png")
    }

    /** 실제 엔진을 돌려 그 순간의 화면 상태를 얻는다. */
    private class Drive(context: Context, private val config: TripConfig) {
        val engine = CompanionEngine(
            ScriptBook.parse(context.assets.open("scripts_ko.json").bufferedReader().use { it.readText() }),
            config,
        )
        var now = 0L
        var signals: Signals = DemoSignalSource.initialState().forSeat(config.seat)

        init {
            engine.start(now)
            engine.onSignals(signals, now)
        }

        fun set(change: Signals.() -> Signals) = apply {
            signals = signals.change()
            engine.onSignals(signals, now)
        }

        fun nav(change: NavState.() -> NavState) = set { copy(nav = nav.change()) }

        fun wait(seconds: Int) = apply {
            repeat(seconds) {
                now += 1000
                engine.onTick(now)
            }
        }

        fun repeatOnce() = apply { engine.requestRepeat(now) }
        fun quiet() = apply { engine.quiet(now) }

        fun ui(stage: String, restricted: Boolean = true): CompanionUi {
            val s = engine.snapshot(now)
            return CompanionUi(
                active = true,
                who = "${config.honorific} · ${config.seat.label}",
                place = config.place,
                stage = stage,
                lastLine = s.lastLine,
                notice = s.driverNotice,
                noticeUrgent = s.driverNoticeUrgent,
                quietMinutesLeft = s.quietUntilMs?.let { ((it - now + 59_999) / 60_000).toInt() },
                canEnd = !restricted,
                restricted = restricted,
            )
        }
    }

    private fun driving() = Drive(context, config).set { copy(gear = Gear.DRIVE) }.wait(20)
    private fun halfway() = driving().nav { copy(remainSec = 400) }.wait(10)

    @Composable
    private fun Companion(ui: CompanionUi) = CompanionScreen(ui, {}, {}, {}, {})

    private val drivingShell = ShellUi(Screen.COMPANION, restricted = true, tripActive = true, demo = false)

    // ───────── ① 출발 전 설정 ─────────

    @Test
    fun `01 출발 전 설정`() = shot("01_setup", ShellUi(Screen.SETUP)) {
        SetupScreen(
            SetupUi(
                form = SetupForm(place = "큰딸 집", purpose = Purpose.MEAL),
                routes = listOf(
                    SavedRoute(config = config, useCount = 7),
                    SavedRoute(config = config.copy(place = "서울내과", purpose = Purpose.CLINIC)),
                    SavedRoute(config = config.copy(honorific = "아버지", place = "주간보호센터", driver = "사위")),
                ),
                occupiedSeats = listOf(SeatPosition.REAR_RIGHT),
                seatSignalAvailable = true,
                locationAvailable = true,
                preview = "어머니, 이제 출발해요. 큰딸 집으로 가요.",
            ),
            {}, {}, {}, {},
        )
    }

    // ───────── ② 동행 중: 상태별 ─────────

    @Test
    fun `02 동행 중 - 출발`() = shot("02_companion_depart", drivingShell) {
        Companion(driving().ui("방금 출발"))
    }

    @Test
    fun `03 동행 중 - 절반쯤`() = shot("03_companion_half", drivingShell) {
        Companion(halfway().ui("절반쯤"))
    }

    @Test
    fun `04 동행 중 - 신호 대기`() = shot("04_companion_signal_wait", drivingShell) {
        Companion(halfway().set { copy(brakePressed = true) }.wait(21).ui("절반쯤"))
    }

    @Test
    fun `05 동행 중 - 주행 중 문 열림`() = shot("05_companion_door_open", drivingShell) {
        Companion(halfway().set { copy(seatDoorOpen = true) }.ui("절반쯤"))
    }

    @Test
    fun `06 동행 중 - 창문 열림`() = shot("06_companion_window_open", drivingShell) {
        Companion(halfway().set { copy(seatWindowOpenPercent = 40) }.ui("절반쯤"))
    }

    @Test
    fun `07 동행 중 - 운전자가 내림`() = shot("07_companion_driver_away", drivingShell.copy(restricted = false)) {
        Companion(
            // 좌석 값은 2.5초 그대로일 때 받으므로 3초 기다렸다가 그린다
            halfway().set { copy(gear = Gear.PARK, driverDoorOpen = true, driverSeatOccupied = false) }.wait(3)
                .ui("절반쯤", restricted = false),
        )
    }

    @Test
    fun `08 동행 중 - 잠시 조용히`() = shot("08_companion_quiet", drivingShell) {
        Companion(halfway().quiet().wait(95).ui("절반쯤"))
    }

    @Test
    fun `09 동행 중 - 도착`() = shot("09_companion_arrived", drivingShell.copy(restricted = false)) {
        Companion(
            // 앞의 말이 끝나고 잠깐 쉰 뒤에 도착 안내가 나오므로 몇 초 기다렸다가 그린다
            halfway().nav { copy(remainSec = 30, remainMeters = 80) }.wait(8).set { copy(gear = Gear.PARK) }.wait(3)
                .ui("도착", restricted = false),
        )
    }

    @Test
    fun `10 동행 중 - 낮 색`() = shot("10_companion_day", drivingShell, dark = false) {
        Companion(halfway().repeatOnce().ui("절반쯤"))
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `11 동행 중 - 세로 창`() = shot("11_companion_portrait", drivingShell) {
        Companion(halfway().ui("절반쯤"))
    }

    // ───────── ③ 동승자 카드 ─────────

    @Test
    fun `12 동승자 카드`() = shot("12_passenger_card", drivingShell.copy(screen = Screen.CARD)) {
        val s = halfway().engine.snapshot(0)
        PassengerCardScreen(s.cardTitle, s.cardStage)
    }

    // ───────── ④ 오늘의 기록 ─────────

    @Test
    fun `13 오늘의 기록`() = shot("13_records", ShellUi(Screen.RECORDS)) {
        RecordsScreen(
            RecordsUi(
                today = DaySummary(trips = 2, durationSec = 71 * 60, counts = TripCounts(11, 6, 2, 9, 1)),
                trend = listOf(9, 14, 11, 17, 12, 15, 13, 15),
            ),
        )
    }

    // ───────── ⑤ 환경설정, 개발자 패널, 잠금, 처음 안내 ─────────

    @Test
    fun `14 환경설정`() = shot("14_settings", ShellUi(Screen.SETTINGS)) {
        SettingsScreen(
            SettingsUi(
                settings = Settings(),
                isAutomotive = true,
                sourceKind = SourceKind.VEHICLE,
                capabilities = Capabilities(
                    seatOccupancy = true, door = true, window = true, brake = true, gear = true,
                    uxRestrictions = true, navigation = true,
                ),
                speechEngine = "Pleos TextToSpeech (ON_DEVICE)",
                versionName = "1.0.0",
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun `15 체험 모드 - 개발자 패널과 동행 화면`() =
        shot("15_dev_panel", ShellUi(Screen.DEV, restricted = true, tripActive = true, demo = true)) { wide ->
            val drive = halfway().set { copy(brakePressed = true) }.wait(21)
            Row {
                if (wide) Companion2(drive.ui("절반쯤"), Modifier.weight(1.25f).fillMaxHeight())
                DevPanelScreen(
                    DevPanelUi(
                        state = DemoSignalSource.initialState().copy(
                            gear = Gear.DRIVE, brakePressed = true, uxRestricted = true,
                            nav = DemoSignalSource.initialState().nav.copy(remainSec = 400),
                        ),
                        editable = true,
                        tripActive = true,
                        scenarioStep = "브레이크를 밟고 20초 — 신호 대기를 알립니다",
                    ),
                    NoActions,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

    @Composable
    private fun Companion2(ui: CompanionUi, modifier: Modifier) = CompanionScreen(ui, {}, {}, {}, {}, modifier)

    @Test
    fun `16 주행 중 잠금 안내`() = shot("16_locked_while_driving", ShellUi(Screen.SETUP, restricted = true, tripActive = true)) {}

    @Test
    fun `17 처음 실행 안내`() = shot("17_first_notice", ShellUi(Screen.SETUP, noticeAccepted = false)) {}

    private object NoActions : DevPanelActions {
        override fun change(change: (app.myeotbeonideun.engine.model.VehicleState) -> app.myeotbeonideun.engine.model.VehicleState) {}
        override fun emitArrived() {}
        override fun playScenario() {}
        override fun stopScenario() {}
        override fun reset() {}
    }

    companion object {
        /** Pleos Connect 가로 창(앱 2칸 영역)과 세로 창(1칸). 160dpi 라 dp 와 px 가 같다. */
        const val LANDSCAPE = "w1700dp-h1212dp-land-mdpi"
        const val PORTRAIT = "w840dp-h1212dp-port-mdpi"
        const val OUT = "../docs/screens"
    }
}
