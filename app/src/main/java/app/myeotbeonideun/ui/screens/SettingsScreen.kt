package app.myeotbeonideun.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.R
import app.myeotbeonideun.data.Settings
import app.myeotbeonideun.signal.Capabilities
import app.myeotbeonideun.signal.SeatReading
import app.myeotbeonideun.signal.SourceKind
import app.myeotbeonideun.signal.SourceStatus
import app.myeotbeonideun.speech.ListenState
import app.myeotbeonideun.ui.BigButton
import app.myeotbeonideun.ui.ChoiceChip
import app.myeotbeonideun.ui.Panel
import app.myeotbeonideun.ui.SectionTitle
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

data class SettingsUi(
    val settings: Settings = Settings(),
    val isAutomotive: Boolean = false,
    val sourceKind: SourceKind = SourceKind.DEMO,
    val capabilities: Capabilities = Capabilities(),
    val speechEngine: String? = null,
    val listenState: ListenState = ListenState.OFF,
    val speedPermissionGranted: Boolean = false,
    val versionName: String = "",
    /** 실차 신호의 연결 상태(차량 신호로 돌 때 '차량 신호 없음'을 보인다). */
    val vehicleStatus: SourceStatus = SourceStatus.IDLE,
    /** 좌석 값을 지금 어떻게 읽는지("좌석 값: 반대로(운전석 기준 자동)"). 차량용 기기가 아니면 null */
    val seatReadingLine: String? = null,
    /** 알림 권한이 없어(안드로이드 13 이상) '알림 허용하기'를 보이는지. */
    val notificationPermissionNeeded: Boolean = false,
)

/** 환경설정(정차 중에만): 체험 모드, 실험 기능, 개인정보 안내, 안전 안내, 지우기, 개발자 패널. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    onDemoMode: (Boolean) -> Unit,
    onListen: (Boolean) -> Unit,
    onWindowThreshold: (Int) -> Unit,
    onAudioZone: (Int?) -> Unit,
    onRequestSpeedPermission: () -> Unit,
    onClearAll: () -> Unit,
    onOpenDevPanel: () -> Unit,
    // Compose 규칙: modifier 는 첫째 선택 인자다(그 뒤에 기본값이 있는 콜백을 둔다).
    modifier: Modifier = Modifier,
    onSeatReading: (SeatReading) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 36.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = AppType.title, color = colors.text)

        // 안전 안내 — 이 앱은 안전장치가 아니다(명세 3.4)
        Panel {
            SectionTitle(stringResource(R.string.settings_safety))
            Text(stringResource(R.string.settings_safety_body), style = AppType.body, color = colors.text)
        }

        // 개인정보 안내(명세 5)
        Panel {
            SectionTitle(stringResource(R.string.settings_privacy))
            Text(stringResource(R.string.settings_privacy_body), style = AppType.body, color = colors.text)
        }

        Panel {
            SwitchRow(
                title = stringResource(R.string.settings_demo),
                note = stringResource(if (ui.isAutomotive) R.string.settings_demo_note else R.string.settings_demo_only),
                checked = ui.settings.demoMode || !ui.isAutomotive || ui.sourceKind == SourceKind.DEMO,
                enabled = ui.isAutomotive,
                onChange = onDemoMode,
            )
            SwitchRow(
                title = stringResource(R.string.settings_listen),
                note = stringResource(
                    when (ui.listenState) {
                        ListenState.UNAVAILABLE -> R.string.settings_listen_unavailable
                        ListenState.LISTENING, ListenState.STARTING -> R.string.settings_listen_on
                        ListenState.OFF -> R.string.settings_listen_note
                    },
                ),
                checked = ui.settings.listenEnabled,
                onChange = onListen,
            )
        }

        Panel {
            SectionTitle(stringResource(R.string.settings_window))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(20, 30, 40, 50).forEach { p ->
                    ChoiceChip(stringResource(R.string.settings_percent, p), ui.settings.windowThresholdPercent == p, { onWindowThreshold(p) })
                }
            }

            SectionTitle(stringResource(R.string.settings_zone))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceChip(stringResource(R.string.settings_zone_default), ui.settings.audioZoneId == null, { onAudioZone(null) })
                listOf(1, 2, 3).forEach { z ->
                    ChoiceChip(stringResource(R.string.settings_zone_n, z), ui.settings.audioZoneId == z, { onAudioZone(z) })
                }
            }
            Text(stringResource(R.string.settings_zone_note), style = AppType.small, color = colors.textSoft)
        }

        Panel {
            SectionTitle(stringResource(R.string.settings_signals))
            Text(
                stringResource(
                    when {
                        ui.sourceKind == SourceKind.DEMO -> R.string.settings_source_demo
                        // 차량용 기기에서는 신호를 못 얻어도 체험 모드로 넘어가지 않는다. 그 사실을 그대로 알린다.
                        ui.vehicleStatus == SourceStatus.UNAVAILABLE -> R.string.settings_source_vehicle_none
                        ui.vehicleStatus == SourceStatus.CONNECTING -> R.string.settings_source_vehicle_connecting
                        else -> R.string.settings_source_vehicle
                    },
                ),
                style = AppType.body, color = colors.text,
            )
            val cap = ui.capabilities
            val rows = listOf(
                R.string.cap_seat to cap.seatOccupancy,
                R.string.cap_door to cap.door,
                R.string.cap_window to cap.window,
                R.string.cap_belt to cap.seatBelt,
                R.string.cap_brake to cap.brake,
                R.string.cap_gear to cap.gear,
                R.string.cap_speed to cap.speed,
                R.string.cap_ux to cap.uxRestrictions,
                R.string.cap_navi to cap.navigation,
                R.string.cap_location to cap.location,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (label, on) ->
                    Text(
                        stringResource(if (on) R.string.cap_on else R.string.cap_off, stringResource(label)),
                        style = AppType.small,
                        color = if (on) colors.accent else colors.textSoft,
                    )
                }
            }
            Text(
                stringResource(R.string.settings_speech_engine, ui.speechEngine ?: stringResource(R.string.settings_speech_engine_none)),
                style = AppType.small, color = colors.textSoft,
            )
            // 좌석 값 읽기: 차에 따라 사람 있음·없음이 반대로 올 수 있다(에뮬레이터에서 그랬다). 정차 중에만 바꾼다
            // (이 화면은 주행 중에 잠긴다).
            if (ui.seatReadingLine != null) {
                Spacer(Modifier.height(4.dp))
                SectionTitle(stringResource(R.string.settings_seat_reading))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        SeatReading.AUTO to R.string.seat_reading_auto,
                        SeatReading.AS_IS to R.string.seat_reading_as_is,
                        SeatReading.INVERTED to R.string.seat_reading_inverted,
                    ).forEach { (reading, label) ->
                        ChoiceChip(stringResource(label), ui.settings.seatReading == reading, { onSeatReading(reading) })
                    }
                }
                Text(ui.seatReadingLine, style = AppType.body, color = colors.text)
                Text(stringResource(R.string.settings_seat_reading_note), style = AppType.small, color = colors.textSoft)
            }
            if (ui.isAutomotive && !ui.speedPermissionGranted) {
                BigButton(
                    text = stringResource(R.string.settings_speed_permission),
                    caption = stringResource(R.string.settings_speed_permission_note),
                    onClick = onRequestSpeedPermission,
                    primary = false, minHeight = 88.dp, textStyle = AppType.body,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 실기(에뮬레이터)에서 권한 창은 '위치 정보 액세스 허용'으로 뜬다. 놀라지 않게 미리 말해 둔다.
                Text(stringResource(R.string.settings_speed_permission_dialog), style = AppType.small, color = colors.textSoft)
            }
        }

        // 알림 권한(안드로이드 13 이상): 앱이 화면 뒤에 있을 때의 급한 한 줄 알림. 동행을 시작할 때는 묻지 않으므로 여기서 허용한다.
        if (ui.notificationPermissionNeeded) {
            Panel {
                SectionTitle(stringResource(R.string.settings_notify))
                Text(stringResource(R.string.settings_notify_note), style = AppType.body, color = colors.text)
                BigButton(
                    text = stringResource(R.string.settings_notify_permission),
                    onClick = onRequestNotificationPermission,
                    primary = false, minHeight = 88.dp, textStyle = AppType.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        var confirm by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton(
                text = stringResource(if (confirm) R.string.settings_clear_confirm else R.string.settings_clear),
                onClick = {
                    if (confirm) onClearAll()
                    confirm = !confirm
                },
                primary = false, minHeight = 88.dp, textStyle = AppType.body,
                modifier = Modifier.weight(1f),
            )
            BigButton(
                text = stringResource(R.string.settings_dev),
                onClick = onOpenDevPanel,
                primary = false, minHeight = 88.dp, textStyle = AppType.body,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.settings_version, ui.versionName),
            style = AppType.small, color = colors.textSoft, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Switch(checked, onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, color = colors.text)
            Text(note, style = AppType.small, color = colors.textSoft)
        }
    }
}

/** 주행 규제 중에 설정·기록 화면 대신 보이는 안내. 짧은 글 두 줄과 버튼 하나뿐이다. */
@Composable
fun LockedScreen(tripActive: Boolean, onGoCompanion: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.locked_title), style = AppType.hero, color = colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.locked_body), style = AppType.line, color = colors.textSoft, textAlign = TextAlign.Center)
        if (tripActive) {
            Spacer(Modifier.height(36.dp))
            BigButton(stringResource(R.string.locked_go), onGoCompanion, minHeight = 112.dp)
        }
    }
}
