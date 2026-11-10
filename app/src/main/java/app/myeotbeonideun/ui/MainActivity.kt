package app.myeotbeonideun.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.myeotbeonideun.App
import app.myeotbeonideun.gleo.Gleo
import app.myeotbeonideun.gleo.GleoParams
import app.myeotbeonideun.ui.screens.CompanionScreen
import app.myeotbeonideun.ui.screens.DevPanelScreen
import app.myeotbeonideun.ui.screens.LockedScreen
import app.myeotbeonideun.ui.screens.PassengerCardScreen
import app.myeotbeonideun.ui.screens.RecordsScreen
import app.myeotbeonideun.ui.screens.SettingsScreen
import app.myeotbeonideun.ui.screens.SetupScreen
import app.myeotbeonideun.ui.theme.MyeotbeonTheme

/**
 * 화면은 이 액티비티 하나다. 주행 중에도 쓰는 화면(동행 중·동승자 카드)이 있어 매니페스트에
 * distractionOptimized 를 선언했고, 주행 규제(CarUxRestrictions) 중에는 정차 전용 화면을 잠근다.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) vm.updateForm(vm.setup.value.form.copy(rememberArrival = false))
        }

    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.onSpeedPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as App).container.speech.warmUp()
        handleGleo(intent)
        setContent {
            MyeotbeonTheme {
                MyeotbeonApp(
                    vm = vm,
                    onNeedLocationPermission = ::requestLocationPermission,
                    onNeedSpeedPermission = { speedPermission.launch(AppViewModel.PERMISSION_SPEED) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGleo(intent)
    }

    /** Gleo 의 "동행 시작"(component = activity). */
    private fun handleGleo(intent: Intent?) {
        if (intent?.action != Gleo.ACTION_START) return
        val container = (application as App).container
        val started = Gleo.handleStart(this, container, GleoParams.parse(intent.getStringExtra(Gleo.EXTRA_PARAM)))
        vm.navigate(if (started) Screen.COMPANION else Screen.SETUP)
        intent.action = null // 화면을 다시 만들 때 또 시작하지 않게
    }

    /** '도착 지점 기억'을 켤 때만 위치 권한을 묻는다. */
    private fun requestLocationPermission() {
        val permission = AppViewModel.PERMISSION_LOCATION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) locationPermission.launch(permission)
    }
}

@Composable
fun MyeotbeonApp(
    vm: AppViewModel,
    onNeedLocationPermission: () -> Unit,
    onNeedSpeedPermission: () -> Unit,
) {
    val shell by vm.shell.collectAsStateWithLifecycle()
    val companion by vm.companion.collectAsStateWithLifecycle()

    AppShell(shell, onNavigate = vm::navigate, onAcceptNotice = vm::acceptNotice) { screen, wide ->
        when (screen) {
            Screen.COMPANION -> CompanionScreen(
                companion, vm::repeat, vm::toggleQuiet, vm::endTrip, { vm.navigate(Screen.SETUP) },
            )

            Screen.CARD -> {
                val card by vm.card.collectAsStateWithLifecycle()
                PassengerCardScreen(card?.first, card?.second)
            }

            Screen.SETUP -> {
                val setup by vm.setup.collectAsStateWithLifecycle()
                SetupScreen(
                    setup,
                    onForm = { form ->
                        if (form.rememberArrival && !setup.form.rememberArrival) onNeedLocationPermission()
                        vm.updateForm(form)
                    },
                    onStart = vm::startTrip,
                    onStartRoute = vm::startRoute,
                    onDeleteRoute = vm::deleteRoute,
                )
            }

            Screen.RECORDS -> {
                val records by vm.records.collectAsStateWithLifecycle()
                RecordsScreen(records)
            }

            Screen.SETTINGS -> {
                val settings by vm.settings.collectAsStateWithLifecycle()
                SettingsScreen(
                    settings,
                    onDemoMode = vm::setDemoMode,
                    onListen = vm::setListen,
                    onWindowThreshold = vm::setWindowThreshold,
                    onAudioZone = vm::setAudioZone,
                    onRequestSpeedPermission = onNeedSpeedPermission,
                    onClearAll = vm::clearAll,
                    onOpenDevPanel = { vm.navigate(Screen.DEV) },
                )
            }

            Screen.DEV -> {
                val dev by vm.dev.collectAsStateWithLifecycle()
                // 넓은 화면에서는 동행 화면과 나란히 두어, 신호를 바꾸면서 반응을 함께 본다.
                Row {
                    if (wide) {
                        CompanionScreen(
                            companion, vm::repeat, vm::toggleQuiet, vm::endTrip, { vm.navigate(Screen.SETUP) },
                            modifier = Modifier.weight(1.25f).fillMaxHeight(),
                        )
                    }
                    DevPanelScreen(dev, vm, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}
