package app.myeotbeonideun.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

    // 이 액티비티의 Application 으로 만든다. 기본 공장(AndroidViewModelFactory)은 처음 본 Application 을 정적으로
    // 붙들고 있어, 시험마다 Application 이 새로 생기는 Robolectric 에서 두 번째 액티비티 시험이 옛 앱을 보게 된다.
    private val vm: AppViewModel by viewModels {
        viewModelFactory { initializer { AppViewModel(application) } }
    }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) vm.updateForm(vm.setup.value.form.copy(rememberArrival = false))
        }

    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.onSpeedPermissionResult(granted)
        }

    /** 알림 권한(안드로이드 13 이상). 결과와 상관없이 앱은 동작한다(허용하지 않으면 급한 한 줄 알림이 보이지 않을 뿐). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 음성 권한이 '사용자 허용'이 필요한 종류인 차에서만 묻는다. 결과와 상관없이 앱은 동작한다. */
    private val speechPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) (application as App).container.speech.warmUp()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as App).container.speech.warmUp()
        requestSpeechPermissionsIfNeeded()
        handleGleo(intent)
        setContent {
            MyeotbeonTheme {
                MyeotbeonApp(
                    vm = vm,
                    onNeedLocationPermission = ::requestLocationPermission,
                    onNeedSpeedPermission = { speedPermission.launch(AppViewModel.PERMISSION_SPEED) },
                    onTripStarting = ::requestNotificationPermissionIfNeeded,
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
        val result = Gleo.handleStart(this, container, GleoParams.parse(intent.getStringExtra(Gleo.EXTRA_PARAM)))
        // 저장된 길이 없으면 출발 전 설정으로. 주행 중이라 받지 않았으면 동행 화면(“차를 세운 뒤 출발 전 설정에서
        // 시작하세요”)으로 — 출발 전 설정은 주행 중에 잠겨 있다.
        vm.navigate(if (result == Gleo.StartResult.NEED_SETUP) Screen.SETUP else Screen.COMPANION)
        intent.action = null // 화면을 다시 만들 때 또 시작하지 않게
    }

    /**
     * Pleos TTS·STT 권한의 보호 수준은 문서에 없다. 설치할 때 주어지는 종류면 아무것도 묻지 않고,
     * 사용자 허용이 필요한 종류(dangerous)로 정의된 차에서만 한 번 묻는다.
     */
    private fun requestSpeechPermissionsIfNeeded() {
        val needed = listOf("pleos.car.permission.TTS_SERVICE", "pleos.car.permission.STT_SERVICE").filter { name ->
            val dangerous = runCatching {
                packageManager.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
            dangerous && checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) speechPermissions.launch(needed.toTypedArray())
    }

    /**
     * 급한 운전자 알림(주행 중 문 열림 등)을 앱이 화면 뒤에 있을 때 한 줄 알림으로 보이려면 안드로이드 13 이상에서는
     * 알림 권한이 있어야 한다(결함 11). 출발 전 설정에서 동행을 시작할 때(정차 중) 이번 실행에서 한 번만 묻는다.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || vm.notificationPermissionAsked) return
        if (checkSelfPermission(PERMISSION_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        vm.notificationPermissionAsked = true
        notificationPermission.launch(PERMISSION_NOTIFICATIONS)
    }

    /** '도착 지점 기억'을 켤 때만 위치 권한을 묻는다. */
    private fun requestLocationPermission() {
        val permission = AppViewModel.PERMISSION_LOCATION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) locationPermission.launch(permission)
    }

    private companion object {
        const val PERMISSION_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}

@Composable
fun MyeotbeonApp(
    vm: AppViewModel,
    onNeedLocationPermission: () -> Unit,
    onNeedSpeedPermission: () -> Unit,
    onTripStarting: () -> Unit = {},
) {
    val shell by vm.shell.collectAsStateWithLifecycle()
    val companion by vm.companion.collectAsStateWithLifecycle()

    AppShell(
        shell,
        onNavigate = vm::navigate,
        onAcceptNotice = vm::acceptNotice,
        onRequestSpeedPermission = onNeedSpeedPermission,
    ) { screen, wide ->
        when (screen) {
            Screen.COMPANION -> CompanionScreen(
                companion, vm::repeat, vm::toggleQuiet, vm::endTrip, { vm.navigate(Screen.SETUP) },
                onArrived = vm::confirmArrival,
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
                    onStart = {
                        onTripStarting()
                        vm.startTrip()
                    },
                    onStartRoute = { route ->
                        onTripStarting()
                        vm.startRoute(route)
                    },
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
                    onSeatReading = vm::setSeatReading,
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
                            onArrived = vm::confirmArrival,
                        )
                    }
                    DevPanelScreen(dev, vm, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}
