package app.myeotbeonideun.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.R
import app.myeotbeonideun.ui.BigButton
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

/**
 * 동행 중 화면에 보일 것. 화면은 이 값만 보고 그린다.
 *
 * @property who 누구·어느 자리 ("어머니 · 뒷좌석 오른쪽")
 * @property place 어디 ("큰딸 집")
 * @property stage 어느 구간 ("절반쯤")
 * @property lastLine 마지막으로 한 안내(소리 내지 못했어도 엔진이 하려던 말)
 * @property notice 운전자에게 알릴 한 줄(문·창문·안전띠·조용히 해제). 있으면 마지막 안내 자리에 보인다
 * @property status 앱 상태 한 줄(음성 엔진 없음, 차량 신호 없음, 마지막 말을 소리 내지 못한 까닭).
 *   첫째 줄(누구·어느 자리) 자리에 보여, 셋째 줄의 마지막 안내는 그대로 읽을 수 있다(그래서 '화면으로만 알린다'가 맞다)
 * @property quietMinutesLeft '잠시 조용히'가 몇 분 남았는지. 조용히 중이 아니면 null
 * @property canEnd 동행을 끝낼 수 있는지(정차 중에만)
 * @property canConfirmArrival '도착했어요'를 보일지(정차 중이고 출발한 뒤 아직 도착 전일 때만 — 주행 중에는 보이지 않는다)
 */
data class CompanionUi(
    val active: Boolean = false,
    val who: String = "",
    val place: String = "",
    val stage: String = "",
    val lastLine: String? = null,
    val notice: String? = null,
    val noticeUrgent: Boolean = false,
    val status: String? = null,
    val quietMinutesLeft: Int? = null,
    val canEnd: Boolean = false,
    val canConfirmArrival: Boolean = false,
    val restricted: Boolean = false,
)

/**
 * 운전자 화면(주행 중에도 쓰는 화면).
 *
 * 글은 세 줄 이하(누구·어느 자리 / 어디·어느 구간 / 마지막 안내), 큰 버튼은 둘뿐이다.
 * 스크롤, 움직이는 그림, 긴 글, 글자 입력이 없다. 1~2초 안에 읽고 한 손으로 누른다.
 * 정차 중에만 아래 줄에 '도착했어요'(주 버튼, 도착 전일 때)와 '동행 끝내기'(보조)가 더해진다.
 *
 * @param onArrived '도착했어요': 도착을 알리고, 안내가 끝나면 동행을 마친다
 */
@Composable
fun CompanionScreen(
    ui: CompanionUi,
    onRepeat: () -> Unit,
    onQuiet: () -> Unit,
    onEnd: () -> Unit,
    onGoSetup: () -> Unit,
    modifier: Modifier = Modifier,
    onArrived: () -> Unit = {},
) {
    if (!ui.active) {
        IdleCompanion(ui.restricted, onGoSetup, modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        val wide = maxWidth >= 840.dp
        // 큰 창(가로 1700dp 급)에서는 글자를 더 키운다.
        val scale = if (maxWidth >= 1300.dp) 1.3f else 1f
        if (wide) {
            // 버튼은 왼쪽에 둔다. 운전석이 왼쪽인 차에서 가운데 화면의 왼쪽이 운전자 손에 가장 가깝다.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                ) {
                    TwoButtons(ui, onRepeat, onQuiet, Modifier.weight(1f))
                    ParkedButtons(ui, onArrived, onEnd)
                }
                ThreeLines(ui, scale, Modifier.weight(1.2f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ThreeLines(ui, scale, Modifier.weight(1f).fillMaxWidth())
                TwoButtons(ui, onRepeat, onQuiet, Modifier.weight(1.1f))
                ParkedButtons(ui, onArrived, onEnd)
            }
        }
    }
}

@Composable
private fun ThreeLines(ui: CompanionUi, scale: Float, modifier: Modifier) {
    val colors = LocalAppColors.current
    val line = AppType.line.scaled(scale)
    val hero = AppType.hero.scaled(scale)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)) {
        // ① 누구·어느 자리. 앱 상태 한 줄(소리 내지 못한 까닭 등)이 있으면 그 자리에 대신 보인다.
        //    급한 알림이 두 줄을 쓸 때는 이 줄을 내려, 글이 세 줄을 넘지 않게 한다.
        val urgent = ui.notice != null && ui.noticeUrgent
        if (!urgent) {
            val status = ui.status
            Text(
                status ?: ui.who, style = line, color = if (status != null) colors.accent else colors.textSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // ② 어디·어느 구간 — 가는 곳 이름이 길어도 구간은 잘리지 않게 따로 둔다
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.place, style = hero, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(stringResource(R.string.companion_stage, ui.stage), style = hero, color = colors.accent, maxLines = 1)
        }
        // ③ 마지막 안내. 운전자에게 알릴 것이 있으면 그 자리에 대신 보인다.
        if (ui.notice != null) {
            val bg = if (ui.noticeUrgent) colors.alert else colors.surfaceHigh
            val fg = if (ui.noticeUrgent) colors.onAlert else colors.text
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(ui.notice, style = line, color = fg, maxLines = if (urgent) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(
                ui.lastLine?.let { stringResource(R.string.companion_last_line, it) }
                    ?: stringResource(R.string.companion_no_line),
                style = line,
                color = colors.textSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TwoButtons(ui: CompanionUi, onRepeat: () -> Unit, onQuiet: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        BigButton(
            text = stringResource(R.string.companion_repeat),
            onClick = onRepeat,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        val quiet = ui.quietMinutesLeft
        BigButton(
            text = stringResource(if (quiet == null) R.string.companion_quiet else R.string.companion_quiet_cancel),
            caption = if (quiet == null) stringResource(R.string.companion_quiet_caption)
            else stringResource(R.string.companion_quiet_left, quiet),
            onClick = onQuiet,
            primary = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

private fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

/**
 * 정차 중에만 보이는 아래 줄: '도착했어요'(주 버튼, 출발한 뒤 도착 전일 때)와 '동행 끝내기'(보조).
 * 주행 중에는 큰 버튼 둘만 남는다.
 */
@Composable
private fun ParkedButtons(ui: CompanionUi, onArrived: () -> Unit, onEnd: () -> Unit) {
    if (!ui.canEnd) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (ui.canConfirmArrival) {
            BigButton(
                text = stringResource(R.string.companion_arrived),
                onClick = onArrived,
                minHeight = 88.dp,
                textStyle = AppType.title,
                modifier = Modifier.weight(1.3f),
            )
        }
        BigButton(
            text = stringResource(R.string.companion_end),
            onClick = onEnd,
            primary = false,
            minHeight = if (ui.canConfirmArrival) 88.dp else 72.dp,
            textStyle = AppType.body,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IdleCompanion(restricted: Boolean, onGoSetup: () -> Unit, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.companion_idle_title), style = AppType.hero, color = colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (restricted) R.string.companion_idle_driving else R.string.companion_idle_parked),
            style = AppType.line, color = colors.textSoft, textAlign = TextAlign.Center,
        )
        if (!restricted) {
            Spacer(Modifier.height(36.dp))
            BigButton(stringResource(R.string.companion_go_setup), onGoSetup, Modifier.width(520.dp), minHeight = 112.dp)
        }
    }
}
