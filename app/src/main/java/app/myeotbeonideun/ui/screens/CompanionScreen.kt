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
 * @property where 어디·어느 구간 ("큰딸 집 · 절반쯤")
 * @property lastLine 마지막으로 한 안내
 * @property notice 운전자에게 알릴 한 줄(문·창문·안전띠). 있으면 마지막 안내 자리에 보인다
 * @property quietMinutesLeft '잠시 조용히'가 몇 분 남았는지. 조용히 중이 아니면 null
 * @property canEnd 동행을 끝낼 수 있는지(정차 중에만)
 */
data class CompanionUi(
    val active: Boolean = false,
    val who: String = "",
    val where: String = "",
    val lastLine: String? = null,
    val notice: String? = null,
    val noticeUrgent: Boolean = false,
    val quietMinutesLeft: Int? = null,
    val canEnd: Boolean = false,
    val restricted: Boolean = false,
)

/**
 * 운전자 화면(주행 중에도 쓰는 화면).
 *
 * 글은 세 줄 이하(누구·어느 자리 / 어디·어느 구간 / 마지막 안내), 큰 버튼은 둘뿐이다.
 * 스크롤, 움직이는 그림, 긴 글, 글자 입력이 없다. 1~2초 안에 읽고 한 손으로 누른다.
 */
@Composable
fun CompanionScreen(
    ui: CompanionUi,
    onRepeat: () -> Unit,
    onQuiet: () -> Unit,
    onEnd: () -> Unit,
    onGoSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ui.active) {
        IdleCompanion(ui.restricted, onGoSetup, modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                ThreeLines(ui, Modifier.weight(1.2f).fillMaxHeight())
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                ) {
                    TwoButtons(ui, onRepeat, onQuiet, Modifier.weight(1f))
                    EndButton(ui, onEnd)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ThreeLines(ui, Modifier.weight(1f).fillMaxWidth())
                TwoButtons(ui, onRepeat, onQuiet, Modifier.weight(1.1f))
                EndButton(ui, onEnd)
            }
        }
    }
}

@Composable
private fun ThreeLines(ui: CompanionUi, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        // ① 누구·어느 자리
        Text(ui.who, style = AppType.line, color = colors.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // ② 어디·어느 구간
        Text(ui.where, style = AppType.hero, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text(ui.notice, style = AppType.line, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(
                ui.lastLine?.let { stringResource(R.string.companion_last_line, it) }
                    ?: stringResource(R.string.companion_no_line),
                style = AppType.line,
                color = colors.accent,
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

/** 동행 끝내기는 정차 중에만 보인다. 주행 중에는 큰 버튼 둘만 남는다. */
@Composable
private fun EndButton(ui: CompanionUi, onEnd: () -> Unit) {
    if (!ui.canEnd) return
    BigButton(
        text = stringResource(R.string.companion_end),
        onClick = onEnd,
        primary = false,
        minHeight = 72.dp,
        textStyle = AppType.body,
        modifier = Modifier.fillMaxWidth(),
    )
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
