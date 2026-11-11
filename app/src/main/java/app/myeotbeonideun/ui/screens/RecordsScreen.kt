package app.myeotbeonideun.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.R
import app.myeotbeonideun.engine.record.DaySummary
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.ui.Panel
import app.myeotbeonideun.ui.SectionTitle
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

data class RecordsUi(
    val today: DaySummary = DaySummary(0, 0, TripCounts()),
    /** 최근 8번 이동에서 '차가 대신 말한 횟수'. 오래된 것부터. */
    val trend: List<Int> = emptyList(),
)

/**
 * 오늘의 기록(정차 중에만). 기록은 횟수와 시간뿐이고 기기 안에만 있다.
 *
 * 큰 숫자는 '차가 대신 말한 횟수'([TripCounts.spokenOnBehalf] = 먼저 말함 + 다시 말씀드리기 + 물음에 대답 + 멈춤 안내,
 * 끝까지 소리 낸 것만)다. 주장하는 것은 여기까지다 — 운전자가 대답하지 않아도 됐다거나 불안이 줄었다고 말하지 않는다.
 * 문·창문·안전띠 안내는 따로 세고, 끝까지 소리 내지 못한 말은 어디에도 넣지 않고 작게 따로 보인다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordsScreen(ui: RecordsUi, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val c = ui.today.counts
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 36.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.records_title), style = AppType.title, color = colors.text)

        Panel {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.records_count, c.spokenOnBehalf), style = AppType.hero, color = colors.accent)
                Text(stringResource(R.string.records_handled), style = AppType.line, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                stringResource(R.string.records_today_summary, ui.today.trips, ui.today.durationSec / 60),
                style = AppType.body, color = colors.textSoft,
            )
        }

        // 큰 숫자의 세부(더하면 큰 숫자가 된다)
        SectionTitle(stringResource(R.string.records_breakdown))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat(stringResource(R.string.records_proactive), c.proactive)
            Stat(stringResource(R.string.records_requested), c.driverRequested)
            Stat(stringResource(R.string.records_answered), c.answered)
            Stat(stringResource(R.string.records_stop), c.stopNotices)
        }
        Text(stringResource(R.string.records_safety, c.safetyNotices), style = AppType.body, color = colors.textSoft)
        Text(stringResource(R.string.records_skipped, c.skipped), style = AppType.small, color = colors.textSoft)

        Panel {
            SectionTitle(stringResource(R.string.records_trend))
            if (ui.trend.isEmpty()) {
                Text(stringResource(R.string.records_empty), style = AppType.body, color = colors.textSoft)
            } else {
                TrendBars(ui.trend)
            }
        }
        Text(stringResource(R.string.records_note), style = AppType.small, color = colors.textSoft)
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    val colors = LocalAppColors.current
    Panel(Modifier.widthIn(min = 200.dp, max = 260.dp)) {
        Text(stringResource(R.string.records_count, value), style = AppType.title, color = colors.text)
        Text(label, style = AppType.label, color = colors.textSoft)
    }
}

/** 최근 이동의 막대 그림. 움직이지 않는 그림이고, 값은 막대 아래 숫자로도 적는다. */
@Composable
private fun TrendBars(values: List<Int>) {
    val colors = LocalAppColors.current
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val description = stringResource(R.string.records_trend_description, values.joinToString(", "))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description },
        ) {
            val slots = 8
            val slot = size.width / slots
            val barWidth = slot * 0.56f
            values.takeLast(slots).forEachIndexed { i, v ->
                val h = (size.height - 8f) * v / max
                drawRoundRect(
                    color = if (i == values.takeLast(slots).lastIndex) colors.primary else colors.accent,
                    topLeft = Offset(slot * i + (slot - barWidth) / 2, size.height - h.coerceAtLeast(6f)),
                    size = Size(barWidth, h.coerceAtLeast(6f)),
                    cornerRadius = CornerRadius(10f, 10f),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val shown = values.takeLast(8)
            repeat(8) { i ->
                Text(
                    shown.getOrNull(i)?.toString() ?: "",
                    style = AppType.label, color = colors.textSoft, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
