package app.myeotbeonideun

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.myeotbeonideun.ui.AppShell
import app.myeotbeonideun.ui.NotifyAsk
import app.myeotbeonideun.ui.ShellUi
import app.myeotbeonideun.ui.SpeedAsk
import app.myeotbeonideun.ui.theme.MyeotbeonTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 첫 실행 안내(정차 중에만)에서 속도 신호 허용을 한 번 권한다(결함 7).
 * 실기에서 권한 창은 '위치 정보 액세스 허용'으로 뜨므로, 안내가 그 점을 미리 말해 준다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1700dp-h1212dp-land-mdpi")
class FirstNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(shell: ShellUi, onSpeed: () -> Unit = {}, onNotify: () -> Unit = {}) {
        compose.setContent {
            MyeotbeonTheme(dark = true) {
                AppShell(
                    shell, onNavigate = {}, onAcceptNotice = {}, onRequestSpeedPermission = onSpeed,
                    onRequestNotificationPermission = onNotify,
                ) { _, _ -> }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `속도 권한이 없으면 첫 실행 안내에서 허용을 권하고 위치 정보 창이 뜬다고 미리 말한다`() {
        var asked = 0
        show(ShellUi(noticeAccepted = false, speedAsk = SpeedAsk.SUGGEST), onSpeed = { asked++ })
        compose.onNodeWithText("속도 신호 허용(권장)").assertExists()
        compose.onNodeWithText("허용 창에는 ‘위치 정보’에 액세스한다고 나와요", substring = true).assertExists()
        compose.onNodeWithText("속도 신호 허용하기").performScrollTo().performClick()
        assertEquals(1, asked)
        compose.onNodeWithText("확인했어요").assertExists()
    }

    @Test
    fun `한 번 거절했으면 다시 조르지 않고 환경설정에서 할 수 있다고만 적는다`() {
        show(ShellUi(noticeAccepted = false, speedAsk = SpeedAsk.DECLINED))
        compose.onNodeWithText("환경설정 › 차량 신호에서 언제든", substring = true).assertExists()
        compose.onAllNodesWithText("속도 신호 허용하기").assertCountEquals(0)
    }

    @Test
    fun `허용했거나 차량용 기기가 아니면 권하지 않는다`() {
        show(ShellUi(noticeAccepted = false, speedAsk = SpeedAsk.HIDDEN))
        compose.onNodeWithText("시작하기 전에").assertExists()
        compose.onAllNodesWithText("속도 신호 허용(권장)").assertCountEquals(0)
    }

    @Test
    fun `알림 권한이 없으면 첫 실행 안내에서 알림 허용도 권하고 거절하면 환경설정에서 할 수 있다고만 적는다`() {
        var asked = 0
        show(ShellUi(noticeAccepted = false, speedAsk = SpeedAsk.SUGGEST, notifyAsk = NotifyAsk.SUGGEST), onNotify = { asked++ })
        compose.onNodeWithText("급한 알림 허용(권장)").assertExists()
        compose.onNodeWithText("앱이 화면 뒤에 있을 때", substring = true).assertExists()
        compose.onNodeWithText("알림 허용하기").performScrollTo().performClick()
        assertEquals(1, asked)
        compose.onNodeWithText("확인했어요").assertIsDisplayed() // 권하는 칸이 둘이어도 넘기지 않고 보인다
    }

    @Test
    fun `알림을 거절했거나 이미 허용했으면 다시 조르지 않는다`() {
        show(ShellUi(noticeAccepted = false, notifyAsk = NotifyAsk.DECLINED))
        compose.onNodeWithText("알림을 허용하지 않았어요", substring = true).assertExists()
        compose.onAllNodesWithText("알림 허용하기").assertCountEquals(0)
    }

    @Test
    fun `알림 권한이 있으면 권하지 않는다`() {
        show(ShellUi(noticeAccepted = false, notifyAsk = NotifyAsk.HIDDEN))
        compose.onAllNodesWithText("급한 알림 허용(권장)").assertCountEquals(0)
    }

    @Test
    fun `주행 중에는 첫 실행 안내를 보이지 않는다`() {
        show(ShellUi(noticeAccepted = false, restricted = true, speedAsk = SpeedAsk.SUGGEST, notifyAsk = NotifyAsk.SUGGEST))
        compose.onAllNodesWithText("시작하기 전에").assertCountEquals(0)
        compose.onAllNodesWithText("속도 신호 허용하기").assertCountEquals(0)
        compose.onAllNodesWithText("알림 허용하기").assertCountEquals(0)
    }
}
