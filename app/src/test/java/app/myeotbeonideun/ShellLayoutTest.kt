package app.myeotbeonideun

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import app.myeotbeonideun.ui.AppShell
import app.myeotbeonideun.ui.Screen
import app.myeotbeonideun.ui.ShellUi
import app.myeotbeonideun.ui.screens.CompanionScreen
import app.myeotbeonideun.ui.screens.CompanionUi
import app.myeotbeonideun.ui.theme.MyeotbeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 화면 틀(AppShell)까지 그려서 센다(결함 17-1, 17-2).
 *
 * 주행 중 화면의 약속 '글 세 줄·큰 버튼 둘·스크롤 없음'은 화면 틀 안의 본문(동행 화면)에 대한 것이다.
 * 화면 틀의 메뉴 5칸(출발·동행·카드·기록·설정)은 주행 중에도 함께 보이므로 **따로 센다**: 메뉴는 탭(Role.Tab)
 * 다섯 개이고 칸마다 짧은 글 한 줄이다. 본문의 큰 버튼은 Role.Button 으로 센다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = ShellLayoutTest.LANDSCAPE)
class ShellLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val counter get() = LayoutCounter(compose)

    private val driving = CompanionUi(
        active = true,
        who = "어머니 · 뒷좌석 오른쪽",
        place = "큰딸 집",
        stage = "절반쯤",
        lastLine = "어머니, 절반쯤 왔어요. 큰딸 집에 가고 있어요.",
        restricted = true,
    )

    private val drivingShell = ShellUi(Screen.COMPANION, restricted = true, tripActive = true, vehicleRestricted = true)

    private fun showCompanion(shell: ShellUi = drivingShell, ui: CompanionUi = driving) {
        compose.setContent {
            MyeotbeonTheme(dark = true) {
                AppShell(shell, onNavigate = {}, onAcceptNotice = {}) { _, _ -> CompanionScreen(ui, {}, {}, {}, {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `화면 틀까지 그려도 본문은 글 세 줄과 큰 버튼 둘이고 메뉴는 따로 다섯 칸이다`() {
        showCompanion()
        val c = counter
        assertEquals("본문 글", 3, c.textLines())
        assertEquals("본문 큰 버튼", 2, c.withRole(Role.Button).size)
        val tabs = c.withRole(Role.Tab)
        assertEquals("메뉴(따로 셈)", 5, tabs.size)
        assertEquals(listOf("출발", "동행", "카드", "기록", "설정"), tabs.map { c.label(it) })
        assertTrue("메뉴 칸은 짧은 글 한 줄", tabs.all { c.label(it).length <= 2 })
        assertEquals("누를 수 있는 것은 메뉴 5칸 + 큰 버튼 2개뿐", 7, c.clickables())
        assertEquals("화면 틀 어디에도 스크롤이 없다", 0, c.scrollables())
    }

    @Test
    fun `급한 알림이 떠도 화면 틀 안의 본문 글은 세 줄을 넘지 않는다`() {
        showCompanion(ui = driving.copy(notice = "같이 탄 분 쪽 문이 열렸어요. 차를 세울 수 있는 곳에서 확인해 주세요.", noticeUrgent = true))
        val c = counter
        assertTrue("글 ${c.textLines()}줄", c.textLines() <= 3)
        assertEquals(2, c.withRole(Role.Button).size)
        assertEquals(5, c.withRole(Role.Tab).size)
        assertEquals(0, c.scrollables())
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `세로 창에서도 아래 메뉴 다섯 칸을 따로 세고 본문은 세 줄과 버튼 둘이다`() {
        showCompanion()
        val c = counter
        assertEquals(3, c.textLines())
        assertEquals(2, c.withRole(Role.Button).size)
        assertEquals(5, c.withRole(Role.Tab).size)
        assertEquals(0, c.scrollables())
    }

    @Test
    fun `체험 모드 표시는 메뉴 쪽의 한 줄이고 본문 글에 더해지지 않는다`() {
        showCompanion(shell = drivingShell.copy(demo = true, vehicleRestricted = false))
        val c = counter
        val badge = c.plainTexts().filter { c.label(it) == "체험 모드" }
        assertEquals(1, badge.size)
        assertTrue("넓은 창에서는 왼쪽 메뉴 칸 안", badge.single().boundsInRoot.right <= c.menuWidthPx())
        assertEquals("표시를 뺀 본문 글", 3, c.textLines(excluding = badge))
        assertEquals(2, c.withRole(Role.Button).size)
    }

    // ───────── 잠금 화면이 실제로 그려지는지(결함 17-2) ─────────

    /** 한 시험에서 화면 틀의 상태만 바꿔 가며 다시 그린다(setContent 는 시험마다 한 번뿐이다). */
    private var shellState: MutableState<ShellUi>? = null
    private var navigated: (Screen) -> Unit = {}

    private fun showLocked(shell: ShellUi, onNavigate: (Screen) -> Unit = {}) {
        navigated = onNavigate
        val state = shellState
        if (state == null) {
            val created = mutableStateOf(shell)
            shellState = created
            compose.setContent {
                MyeotbeonTheme(dark = true) {
                    AppShell(created.value, onNavigate = { navigated(it) }, onAcceptNotice = {}) { screen, _ -> Text("본문: ${screen.name}") }
                }
            }
        } else {
            state.value = shell
        }
        compose.waitForIdle()
    }

    @Test
    fun `주행 중에는 정차 전용 화면 대신 잠금 안내가 실제로 그려진다`() {
        for (screen in Screen.entries.filter { it.parkedOnly }) {
            var went: Screen? = null
            showLocked(ShellUi(screen, restricted = true, tripActive = true, vehicleRestricted = true)) { went = it }
            compose.onNodeWithText("주행 중에는 쓸 수 없어요").assertIsDisplayed()
            compose.onNodeWithText("차를 세우고 P단에 두면 열려요").assertIsDisplayed()
            compose.onAllNodesWithText("본문: ${screen.name}").assertCountEquals(0)
            compose.onNodeWithText("동행 화면으로").assertIsDisplayed().performClick()
            assertEquals(screen.name, Screen.COMPANION, went)
        }
    }

    @Test
    fun `잠금 안내는 글 두 줄과 버튼 하나이고 스크롤이 없다`() {
        showLocked(ShellUi(Screen.SETUP, restricted = true, tripActive = true, vehicleRestricted = true))
        val c = counter
        assertEquals(2, c.textLines())
        assertEquals(1, c.withRole(Role.Button).size)
        assertEquals(5, c.withRole(Role.Tab).size)
        assertEquals(0, c.scrollables())
    }

    @Test
    fun `주행 중에도 동행 화면과 동승자 카드는 그대로 그린다`() {
        for (screen in listOf(Screen.COMPANION, Screen.CARD)) {
            showLocked(ShellUi(screen, restricted = true, tripActive = true, vehicleRestricted = true))
            compose.onNodeWithText("본문: ${screen.name}").assertIsDisplayed()
            compose.onAllNodesWithText("주행 중에는 쓸 수 없어요").assertCountEquals(0)
        }
    }

    @Test
    fun `체험 모드의 개발자 패널은 모의 주행으로는 열리고 실차 주행이면 잠긴다`() {
        showLocked(ShellUi(Screen.DEV, restricted = true, demo = true, vehicleRestricted = false))
        compose.onNodeWithText("본문: DEV").assertIsDisplayed()

        showLocked(ShellUi(Screen.DEV, restricted = true, demo = true, vehicleRestricted = true))
        compose.onNodeWithText("주행 중에는 쓸 수 없어요").assertIsDisplayed()
        compose.onAllNodesWithText("본문: DEV").assertCountEquals(0)

        // 체험 모드여도 실차가 달리면 환경설정이 잠긴다(결함 6)
        showLocked(ShellUi(Screen.SETTINGS, restricted = true, demo = true, vehicleRestricted = true))
        compose.onNodeWithText("주행 중에는 쓸 수 없어요").assertIsDisplayed()
    }

    companion object {
        const val LANDSCAPE = "w1700dp-h1212dp-land-mdpi"
        const val PORTRAIT = "w840dp-h1212dp-port-mdpi"
    }
}

/** 그려진 화면의 글 줄 수·버튼·스크롤을 센다(CompanionLayoutTest 와 같은 방법). */
private class LayoutCounter(private val compose: ComposeContentTestRule) {
    private val hasText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    /** 버튼·메뉴가 아닌 글. 누를 수 있는 것 안의 글은 그것에 합쳐져 있어 여기 들지 않는다. */
    fun plainTexts(): List<SemanticsNode> =
        compose.onAllNodes(hasText).fetchSemanticsNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) == null }

    fun label(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun lineCount(node: SemanticsNode): Int {
        val results = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.lineCount ?: 1
    }

    /** 세로로 겹치는 글은 한 줄에 나란히 놓인 것으로 보고, 그 가운데 가장 많은 줄 수를 더한다. */
    fun textLines(excluding: List<SemanticsNode> = emptyList()): Int {
        val skip = excluding.map { it.id }.toSet()
        val nodes = plainTexts().filter { it.id !in skip }.sortedBy { it.boundsInRoot.top }
        var total = 0
        var rowBottom = Float.NEGATIVE_INFINITY
        var rowLines = 0
        for (node in nodes) {
            val b = node.boundsInRoot
            if (b.top >= rowBottom) {
                total += rowLines
                rowLines = 0
                rowBottom = b.bottom
            } else {
                rowBottom = maxOf(rowBottom, b.bottom)
            }
            rowLines = maxOf(rowLines, lineCount(node))
        }
        return total + rowLines
    }

    fun withRole(role: Role): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.config.getOrNull(SemanticsProperties.Role) == role }

    fun clickables(): Int = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
    fun scrollables(): Int = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size

    /** 넓은 창의 왼쪽 메뉴 칸 너비(148dp, mdpi 라 1dp = 1px). */
    fun menuWidthPx(): Float = 148f
}
