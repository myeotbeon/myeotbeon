package app.myeotbeonideun.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.R
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.ui.Badge
import app.myeotbeonideun.ui.BigButton
import app.myeotbeonideun.ui.ChoiceChip
import app.myeotbeonideun.ui.Panel
import app.myeotbeonideun.ui.SectionTitle
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

data class DevPanelUi(
    val state: VehicleState = VehicleState(),
    /** 동승자가 앉은 자리(동행 중이 아니면 뒷좌석 오른쪽). */
    val seat: SeatPosition = SeatPosition.REAR_RIGHT,
    /** 체험 모드일 때만 값을 바꿀 수 있다. 차량 모드에서는 읽기만 한다. */
    val editable: Boolean = false,
    val tripActive: Boolean = false,
    /** 재생 중인 시연 단계. */
    val scenarioStep: String? = null,
    /** 차량 연결 과정의 기록(차량 모드). */
    val diagnostics: List<String> = emptyList(),
    /** 좌석 값을 지금 어떻게 읽는지(차량 모드). 위의 좌석 칩은 이 해석을 거친 값이다. */
    val seatReadingLine: String? = null,
    /** SDK 가 넘긴 그대로의 좌석 점유·안전띠 값(차량 모드). */
    val rawValuesLine: String? = null,
)

/** 개발자 패널에서 하는 조작. */
interface DevPanelActions {
    fun change(change: (VehicleState) -> VehicleState)
    fun emitArrived()
    fun playScenario()
    fun stopScenario()
    fun reset()
}

/**
 * 개발자 패널. 체험 모드에서는 모의 신호를 손으로 바꾸고 시연 순서를 재생한다.
 * 차량 모드에서는 지금 읽히는 값과 연결 기록을 보여 준다(에뮬레이터 가상 콘솔과 맞는지 확인하는 용도).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevPanelScreen(ui: DevPanelUi, actions: DevPanelActions, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val s = ui.state
    val seat = ui.seat
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.dev_title), style = AppType.title, color = colors.text)
        Text(
            stringResource(if (ui.editable) R.string.dev_note_demo else R.string.dev_note_vehicle),
            style = AppType.small, color = colors.textSoft,
        )

        if (ui.editable) {
            Panel {
                SectionTitle(stringResource(R.string.dev_scenario))
                ui.scenarioStep?.let { Badge(it, strong = true) }
                Chips {
                    if (ui.scenarioStep == null) {
                        BigButton(
                            stringResource(R.string.dev_play), actions::playScenario,
                            enabled = ui.tripActive, minHeight = 72.dp, textStyle = AppType.body,
                            caption = if (ui.tripActive) null else stringResource(R.string.dev_play_need_trip),
                        )
                    } else {
                        BigButton(stringResource(R.string.dev_stop), actions::stopScenario, primary = false, minHeight = 72.dp, textStyle = AppType.body)
                    }
                    BigButton(stringResource(R.string.dev_reset), actions::reset, primary = false, minHeight = 72.dp, textStyle = AppType.body)
                }
            }
        }

        Panel {
            SectionTitle(stringResource(R.string.dev_motion))
            Chips {
                listOf(Gear.PARK to "P", Gear.REVERSE to "R", Gear.NEUTRAL to "N", Gear.DRIVE to "D").forEach { (gear, label) ->
                    Toggle(stringResource(R.string.dev_gear, label), s.gear == gear, ui.editable) { actions.change { it.copy(gear = gear) } }
                }
            }
            Chips {
                Toggle(stringResource(R.string.dev_brake_off), s.brakePressed == false, ui.editable) { actions.change { it.copy(brakePressed = false) } }
                Toggle(stringResource(R.string.dev_brake_on), s.brakePressed == true, ui.editable) { actions.change { it.copy(brakePressed = true) } }
            }
            Text(
                stringResource(
                    R.string.dev_motion_values,
                    s.speedKph?.let { "%.0f km/h".format(it) } ?: "-",
                    when (s.uxRestricted) { true -> "ON"; false -> "OFF"; null -> "-" },
                ),
                style = AppType.small, color = colors.textSoft,
            )
        }

        Panel {
            SectionTitle(stringResource(R.string.dev_driver))
            Chips {
                OnOff(R.string.dev_seat_on, R.string.dev_seat_off, s.seatOccupied[SeatPosition.DRIVER], ui.editable) { v ->
                    actions.change { it.copy(seatOccupied = it.seatOccupied + (SeatPosition.DRIVER to v)) }
                }
                OnOff(R.string.dev_door_open, R.string.dev_door_closed, s.doorOpen[SeatPosition.DRIVER], ui.editable) { v ->
                    actions.change { it.copy(doorOpen = it.doorOpen + (SeatPosition.DRIVER to v)) }
                }
            }
        }

        Panel {
            SectionTitle(stringResource(R.string.dev_passenger, seat.label))
            Chips {
                OnOff(R.string.dev_seat_on, R.string.dev_seat_off, s.seatOccupied[seat], ui.editable) { v ->
                    actions.change { it.copy(seatOccupied = it.seatOccupied + (seat to v)) }
                }
                OnOff(R.string.dev_door_open, R.string.dev_door_closed, s.doorOpen[seat], ui.editable) { v ->
                    actions.change { it.copy(doorOpen = it.doorOpen + (seat to v)) }
                }
                OnOff(R.string.dev_belt_on, R.string.dev_belt_off, s.beltBuckled[seat], ui.editable) { v ->
                    actions.change { it.copy(beltBuckled = it.beltBuckled + (seat to v)) }
                }
            }
            Chips {
                listOf(0, 20, 40, 80).forEach { p ->
                    Toggle(stringResource(R.string.dev_window, p), s.windowOpenPercent[seat] == p, ui.editable) {
                        actions.change { it.copy(windowOpenPercent = it.windowOpenPercent + (seat to p)) }
                    }
                }
            }
            Text(
                stringResource(R.string.dev_window_value, s.windowOpenPercent[seat]?.toString() ?: "-"),
                style = AppType.small, color = colors.textSoft,
            )
        }

        Panel {
            SectionTitle(stringResource(R.string.dev_nav))
            Chips {
                listOf(720, 400, 120, 20).forEach { sec ->
                    val label = if (sec >= 60) stringResource(R.string.dev_remain_min, sec / 60) else stringResource(R.string.dev_remain_sec, sec)
                    Toggle(label, s.nav.remainSec == sec, ui.editable) {
                        actions.change { it.copy(nav = it.nav.copy(remainSec = sec, remainMeters = sec * 8)) }
                    }
                }
            }
            Chips {
                Toggle(stringResource(R.string.dev_jam_off), !s.nav.congested, ui.editable) { actions.change { it.copy(nav = it.nav.copy(congested = false)) } }
                Toggle(stringResource(R.string.dev_jam_on), s.nav.congested, ui.editable) { actions.change { it.copy(nav = it.nav.copy(congested = true)) } }
                if (ui.editable) {
                    BigButton(stringResource(R.string.dev_arrived), actions::emitArrived, primary = false, minHeight = 64.dp, textStyle = AppType.body)
                }
            }
            Text(
                stringResource(
                    R.string.dev_nav_values,
                    s.nav.destinationName ?: "-",
                    s.nav.remainSec?.toString() ?: "-",
                    s.nav.totalSec?.toString() ?: "-",
                ),
                style = AppType.small, color = colors.textSoft,
            )
        }

        if (ui.seatReadingLine != null || ui.rawValuesLine != null) {
            Panel {
                SectionTitle(stringResource(R.string.dev_seat_values))
                ui.seatReadingLine?.let { Text(it, style = AppType.body, color = colors.text) }
                ui.rawValuesLine?.let { Text(it, style = AppType.small, color = colors.textSoft) }
            }
        }

        if (ui.diagnostics.isNotEmpty()) {
            Panel {
                SectionTitle(stringResource(R.string.dev_diagnostics))
                ui.diagnostics.forEach { Text(it, style = AppType.small, color = colors.textSoft) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Toggle(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ChoiceChip(text, selected, { if (enabled) onClick() })
}

/** 켜짐·꺼짐 두 칩. 값이 null(차가 주지 않는 신호)이면 둘 다 꺼져 보인다. */
@Composable
private fun OnOff(onLabel: Int, offLabel: Int, value: Boolean?, enabled: Boolean, set: (Boolean) -> Unit) {
    Toggle(stringResource(onLabel), value == true, enabled) { set(true) }
    Toggle(stringResource(offLabel), value == false, enabled) { set(false) }
}
