package app.myeotbeonideun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.myeotbeonideun.R
import app.myeotbeonideun.ui.screens.LockedScreen
import app.myeotbeonideun.ui.theme.AppType
import app.myeotbeonideun.ui.theme.LocalAppColors

private data class NavItem(val screen: Screen, val label: Int, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(Screen.SETUP, R.string.nav_setup, Icons.Filled.Place),
    NavItem(Screen.COMPANION, R.string.nav_companion, Icons.Filled.Face),
    NavItem(Screen.CARD, R.string.nav_card, Icons.Filled.Person),
    NavItem(Screen.RECORDS, R.string.nav_records, Icons.Filled.DateRange),
    NavItem(Screen.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/**
 * 화면 틀. 넓은 창(가로)에서는 왼쪽에 메뉴를, 좁은 창(세로)에서는 아래에 메뉴를 둔다.
 * 주행 규제 중에는 정차 전용 화면(설정·기록·환경설정) 대신 잠금 안내를 보인다.
 * 체험 모드의 개발자 패널은 모의 신호로 '주행'을 흉내 내는 도구라 잠그지 않는다.
 *
 * @param content 지금 화면을 그린다. 둘째 인자는 넓은 창인지
 */
@Composable
fun AppShell(
    shell: ShellUi,
    onNavigate: (Screen) -> Unit,
    onAcceptNotice: () -> Unit,
    content: @Composable (Screen, Boolean) -> Unit,
) {
    val colors = LocalAppColors.current
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.text) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 900.dp
            if (!shell.noticeAccepted && !shell.restricted) {
                FirstNotice(onAcceptNotice)
                return@BoxWithConstraints
            }
            val locked = shell.restricted && shell.screen.parkedOnly && !(shell.screen == Screen.DEV && shell.demo)
            val body: @Composable (Modifier) -> Unit = { m ->
                Box(m) {
                    if (locked) LockedScreen(shell.tripActive, { onNavigate(Screen.COMPANION) })
                    else content(shell.screen, wide)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(148.dp).fillMaxHeight().background(colors.surface).padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NAV_ITEMS.forEach { item ->
                            NavButton(item, shell, onNavigate, Modifier.fillMaxWidth().weight(1f))
                        }
                        if (shell.demo) Badge(stringResource(R.string.badge_demo), strong = true)
                    }
                    body(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (shell.demo) {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                            Badge(stringResource(R.string.badge_demo), strong = true)
                        }
                    }
                    body(Modifier.weight(1f).fillMaxWidth())
                    Row(Modifier.fillMaxWidth().height(104.dp).background(colors.surface)) {
                        NAV_ITEMS.forEach { item ->
                            NavButton(item, shell, onNavigate, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(item: NavItem, shell: ShellUi, onNavigate: (Screen) -> Unit, modifier: Modifier) {
    val colors = LocalAppColors.current
    val selected = shell.screen == item.screen || (shell.screen == Screen.DEV && item.screen == Screen.SETTINGS)
    val dimmed = shell.restricted && item.screen.parkedOnly
    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) colors.surfaceHigh else colors.surface)
            .clickable(role = Role.Tab) { onNavigate(item.screen) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.alpha(if (dimmed) 0.45f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(item.icon, contentDescription = null, tint = if (selected) colors.accent else colors.textSoft, modifier = Modifier.size(40.dp))
            Text(stringResource(item.label), style = AppType.label, color = if (selected) colors.text else colors.textSoft, maxLines = 1)
        }
    }
}

/** 처음 실행할 때 한 번 보이는 안내. '확인했어요'를 눌러야만 넘어간다. */
@Composable
private fun FirstNotice(onAccept: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.notice_title), style = AppType.hero, color = colors.text)
        Text(stringResource(R.string.notice_intro), style = AppType.line, color = colors.accent)
        Panel(Modifier.widthIn(max = 1100.dp)) {
            SectionTitle(stringResource(R.string.settings_safety))
            Text(stringResource(R.string.settings_safety_body), style = AppType.body, color = colors.text)
            Spacer(Modifier.height(4.dp))
            SectionTitle(stringResource(R.string.settings_privacy))
            Text(stringResource(R.string.settings_privacy_body), style = AppType.body, color = colors.text)
        }
        BigButton(stringResource(R.string.notice_accept), onAccept, Modifier.widthIn(min = 420.dp), minHeight = 112.dp)
    }
}
