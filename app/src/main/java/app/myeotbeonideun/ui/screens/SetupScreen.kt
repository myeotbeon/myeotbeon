package app.myeotbeonideun.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.R
import app.myeotbeonideun.data.SavedRoute
import app.myeotbeonideun.engine.model.IntervalMode
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.ui.BigButton
import app.myeotbeonideun.ui.ChoiceChip
import app.myeotbeonideun.ui.Panel
import app.myeotbeonideun.ui.SectionTitle
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

/** 출발 전 설정에 적는 값. */
data class SetupForm(
    val honorific: String = "어머니",
    val honorificCustom: Boolean = false,
    val place: String = "",
    val purpose: Purpose = Purpose.NONE,
    val customPurposeLine: String = "",
    val driver: String = "따님",
    val driverCustom: Boolean = false,
    val seat: SeatPosition = SeatPosition.REAR_RIGHT,
    /** 좌석 신호로 자리를 자동으로 고른 상태인지. 손으로 고르면 false */
    val seatAuto: Boolean = true,
    val interval: IntervalMode = IntervalMode.AUTO,
    val rememberArrival: Boolean = false,
) {
    val canStart: Boolean
        get() = honorific.isNotBlank() && place.isNotBlank() && driver.isNotBlank() &&
            (purpose != Purpose.CUSTOM || customPurposeLine.isNotBlank())

    fun toConfig() = TripConfig(
        honorific = honorific.trim(),
        place = place.trim(),
        purpose = purpose,
        customPurposeLine = customPurposeLine.trim(),
        driver = driver.trim(),
        seat = seat,
        interval = interval,
    )

    companion object {
        val HONORIFICS = listOf("어머니", "아버지", "할머니", "할아버지")
        val DRIVERS = listOf("따님", "아드님", "사위", "며느리", "선생님")

        fun from(route: SavedRoute) = SetupForm(
            honorific = route.config.honorific,
            honorificCustom = route.config.honorific !in HONORIFICS,
            place = route.config.place,
            purpose = route.config.purpose,
            customPurposeLine = route.config.customPurposeLine,
            driver = route.config.driver,
            driverCustom = route.config.driver !in DRIVERS,
            seat = route.config.seat,
            seatAuto = false,
            interval = route.config.interval,
            rememberArrival = route.rememberArrival,
        )
    }
}

data class SetupUi(
    val form: SetupForm = SetupForm(),
    val routes: List<SavedRoute> = emptyList(),
    /** 좌석 신호로 본, 사람이 앉아 있는 자리. */
    val occupiedSeats: List<SeatPosition> = emptyList(),
    val seatSignalAvailable: Boolean = false,
    val locationAvailable: Boolean = false,
    /** 이 설정으로 차가 처음 할 말(미리 보기). */
    val preview: String = "",
)

/**
 * 출발 전 10초 설정(명세 3.1). 정차 중에만 열린다 — 주행 규제 중에는 이 화면 대신 잠금 안내가 보인다.
 * 고르는 것 위주이고, 글자 입력은 '가는 곳'과 '직접 입력'뿐이다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    ui: SetupUi,
    onForm: (SetupForm) -> Unit,
    onStart: () -> Unit,
    onStartRoute: (SavedRoute) -> Unit,
    onDeleteRoute: (SavedRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val form = ui.form
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 36.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = AppType.title, color = colors.text)

        if (ui.routes.isNotEmpty()) {
            Panel {
                SectionTitle(stringResource(R.string.setup_routes))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.routes.forEach { route ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BigButton(
                                text = route.config.place,
                                caption = stringResource(R.string.setup_route_caption, route.config.honorific),
                                onClick = { onStartRoute(route) },
                                minHeight = 96.dp,
                                textStyle = AppType.title,
                                modifier = Modifier.widthIn(min = 260.dp),
                            )
                            Row {
                                TextButton(onClick = { onForm(SetupForm.from(route)) }) {
                                    Text(stringResource(R.string.setup_route_edit), style = AppType.small, color = colors.textSoft)
                                }
                                TextButton(onClick = { onDeleteRoute(route) }) {
                                    Text(stringResource(R.string.setup_route_delete), style = AppType.small, color = colors.textSoft)
                                }
                            }
                        }
                    }
                }
            }
        }

        Panel {
            SectionTitle(stringResource(R.string.setup_honorific))
            ChipRow {
                SetupForm.HONORIFICS.forEach { h ->
                    ChoiceChip(h, !form.honorificCustom && form.honorific == h, { onForm(form.copy(honorific = h, honorificCustom = false)) })
                }
                ChoiceChip(stringResource(R.string.setup_custom), form.honorificCustom, { onForm(form.copy(honorific = "", honorificCustom = true)) })
            }
            if (form.honorificCustom) {
                Field(form.honorific, { onForm(form.copy(honorific = it.take(8))) }, stringResource(R.string.setup_honorific_hint))
            }

            SectionTitle(stringResource(R.string.setup_place))
            Field(form.place, { onForm(form.copy(place = it.take(16))) }, stringResource(R.string.setup_place_hint))

            SectionTitle(stringResource(R.string.setup_purpose))
            ChipRow {
                PURPOSES.forEach { (purpose, label) ->
                    ChoiceChip(stringResource(label), form.purpose == purpose, { onForm(form.copy(purpose = purpose)) })
                }
            }
            if (form.purpose == Purpose.CUSTOM) {
                Field(form.customPurposeLine, { onForm(form.copy(customPurposeLine = it.take(24))) }, stringResource(R.string.setup_purpose_hint))
            }
        }

        Panel {
            SectionTitle(stringResource(R.string.setup_driver))
            ChipRow {
                SetupForm.DRIVERS.forEach { d ->
                    ChoiceChip(d, !form.driverCustom && form.driver == d, { onForm(form.copy(driver = d, driverCustom = false)) })
                }
                ChoiceChip(stringResource(R.string.setup_custom), form.driverCustom, { onForm(form.copy(driver = "", driverCustom = true)) })
            }
            if (form.driverCustom) {
                Field(form.driver, { onForm(form.copy(driver = it.take(8))) }, stringResource(R.string.setup_driver_hint))
            }

            SectionTitle(stringResource(R.string.setup_seat))
            ChipRow {
                SeatPosition.PASSENGER_SEATS.forEach { seat ->
                    val occupied = seat in ui.occupiedSeats
                    val label = if (occupied) stringResource(R.string.setup_seat_occupied, seat.label) else seat.label
                    ChoiceChip(label, form.seat == seat, { onForm(form.copy(seat = seat, seatAuto = false)) })
                }
            }
            Text(
                stringResource(
                    when {
                        !ui.seatSignalAvailable -> R.string.setup_seat_manual
                        form.seatAuto && form.seat in ui.occupiedSeats -> R.string.setup_seat_auto
                        else -> R.string.setup_seat_signal
                    },
                ),
                style = AppType.small, color = colors.textSoft,
            )

            SectionTitle(stringResource(R.string.setup_interval))
            ChipRow {
                INTERVALS.forEach { (mode, label) ->
                    ChoiceChip(stringResource(label), form.interval == mode, { onForm(form.copy(interval = mode)) })
                }
            }

            if (ui.locationAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Switch(form.rememberArrival, { onForm(form.copy(rememberArrival = it)) })
                    Column {
                        Text(stringResource(R.string.setup_remember), style = AppType.body, color = colors.text)
                        Text(stringResource(R.string.setup_remember_note), style = AppType.small, color = colors.textSoft)
                    }
                }
            }
        }

        if (ui.preview.isNotEmpty()) {
            Text(stringResource(R.string.setup_preview, ui.preview), style = AppType.line, color = colors.accent)
        }
        BigButton(
            text = stringResource(R.string.setup_start),
            caption = if (form.canStart) null else stringResource(R.string.setup_start_need),
            onClick = onStart,
            enabled = form.canStart,
            minHeight = 120.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = AppType.body,
        placeholder = { Text(hint, style = AppType.body, color = LocalAppColors.current.textSoft) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
    )
}

private val PURPOSES = listOf(
    Purpose.CLINIC to R.string.purpose_clinic,
    Purpose.MEAL to R.string.purpose_meal,
    Purpose.FAMILY to R.string.purpose_family,
    Purpose.REST to R.string.purpose_rest,
    Purpose.CUSTOM to R.string.setup_custom,
    Purpose.NONE to R.string.purpose_none,
)

private val INTERVALS = listOf(
    IntervalMode.AUTO to R.string.interval_auto,
    IntervalMode.FIVE_MIN to R.string.interval_5,
    IntervalMode.TEN_MIN to R.string.interval_10,
    IntervalMode.OFF to R.string.interval_off,
)
