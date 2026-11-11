package app.myeotbeonideun

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import app.myeotbeonideun.ui.screens.CompanionScreen
import app.myeotbeonideun.ui.screens.CompanionUi
import app.myeotbeonideun.ui.screens.PassengerCardScreen
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
 * 주행 중에도 쓰는 화면의 약속을 그려서 센다(명세 3.5):
 * 운전자 화면은 글 세 줄 이하와 큰 버튼 둘, 스크롤 없음. 동승자 카드는 큰 글자 두 줄.
 *
 * 줄 수는 실제로 그려진 글의 줄 수다. 나란히 놓인 글(가는 곳 · 구간)은 한 줄로 센다.
 * 여기서는 동행 화면만 그린다. 화면 틀(메뉴 5칸)까지 그려 메뉴를 따로 세는 시험은 [ShellLayoutTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = CompanionLayoutTest.LANDSCAPE)
class CompanionLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val driving = CompanionUi(
        active = true,
        who = "어머니 · 뒷좌석 오른쪽",
        place = "큰딸 집",
        stage = "절반쯤",
        lastLine = "어머니, 절반쯤 왔어요. 큰딸 집에 가고 있어요.",
        restricted = true,
    )

    private fun show(ui: CompanionUi) {
        compose.setContent { MyeotbeonTheme(dark = true) { CompanionScreen(ui, {}, {}, {}, {}) } }
        compose.waitForIdle()
    }

    private val hasText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    /** 버튼이 아닌 글. 버튼 안의 글은 버튼에 합쳐져 있어 여기 들지 않는다. */
    private fun plainTexts(): List<SemanticsNode> =
        compose.onAllNodes(hasText).fetchSemanticsNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) == null }

    private fun lineCount(node: SemanticsNode): Int {
        val results = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.lineCount ?: 1
    }

    /** 세로로 겹치는 글은 한 줄에 나란히 놓인 것으로 보고, 그 가운데 가장 많은 줄 수를 더한다. */
    private fun textLines(): Int {
        val nodes = plainTexts().sortedBy { it.boundsInRoot.top }
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

    private fun buttons(): Int = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
    private fun scrollables(): Int = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size

    @Test
    fun `주행 중 화면은 글 세 줄과 큰 버튼 둘이고 스크롤이 없다`() {
        show(driving)
        assertEquals(3, textLines())
        assertEquals(2, buttons())
        assertEquals(0, scrollables())
    }

    @Test
    fun `급한 알림이 두 줄을 써도 글은 세 줄을 넘지 않는다`() {
        show(driving.copy(notice = "같이 탄 분 쪽 문이 열렸어요. 차를 세울 수 있는 곳에서 확인해 주세요. 알림이 아주 길 때", noticeUrgent = true))
        assertTrue("글 ${textLines()}줄", textLines() <= 3)
        assertEquals(2, buttons())
    }

    @Test
    fun `알림 한 줄과 긴 이름과 긴 안내에서도 세 줄을 넘지 않는다`() {
        show(
            driving.copy(
                who = "할아버지할아버지 · 앞자리 옆 좌석",
                place = "분당서울대학교병원 본관 주차장",
                notice = "같이 탄 분 쪽 창문이 열렸어요.",
                lastLine = "할아버지, 주간보호센터에 가는 길이에요. 차가 잘 가고 있어요. 가서 의사 선생님 뵙고 와요.",
            ),
        )
        assertTrue("글 ${textLines()}줄", textLines() <= 3)
        assertEquals(2, buttons())
    }

    @Test
    fun `긴 안내 문장도 한 줄로 줄여 보인다`() {
        show(driving.copy(lastLine = "할아버지, 주간보호센터에 가는 길이에요. 차가 잘 가고 있어요. 가서 의사 선생님 뵙고 와요. ".repeat(3)))
        assertEquals(3, textLines())
    }

    @Test
    fun `정차 중에는 동행 끝내기가 더해져 버튼이 셋이다`() {
        show(driving.copy(restricted = false, canEnd = true))
        assertEquals(3, buttons())
        assertTrue(textLines() <= 3)
    }

    @Test
    fun `잠시 조용히 중에도 글 세 줄과 버튼 둘이다`() {
        show(driving.copy(quietMinutesLeft = 9))
        assertEquals(3, textLines())
        assertEquals(2, buttons())
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `세로 창에서도 글 세 줄과 큰 버튼 둘이다`() {
        show(driving)
        assertEquals(3, textLines())
        assertEquals(2, buttons())
        assertEquals(0, scrollables())
    }

    @Test
    fun `동승자 카드는 큰 글자 두 줄이고 누를 것이 없다`() {
        compose.setContent { MyeotbeonTheme(dark = true) { PassengerCardScreen("주간보호센터에 가는 길", "절반쯤 왔어요") } }
        compose.waitForIdle()
        assertEquals(2, textLines())
        assertEquals(0, buttons())
        assertEquals(0, scrollables())
    }

    companion object {
        const val LANDSCAPE = "w1700dp-h1212dp-land-mdpi"
        const val PORTRAIT = "w840dp-h1212dp-port-mdpi"
    }
}
