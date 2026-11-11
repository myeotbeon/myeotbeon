package app.myeotbeonideun

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.myeotbeonideun.engine.record.DaySummary
import app.myeotbeonideun.engine.record.TripCounts
import app.myeotbeonideun.ui.screens.RecordsScreen
import app.myeotbeonideun.ui.screens.RecordsUi
import app.myeotbeonideun.ui.theme.MyeotbeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 기록 화면은 사실만 말한다(결함 8): '차가 대신 말한 횟수'와 그 세부, 따로 센 것, 소리 내지 못한 것. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1700dp-h1212dp-land-mdpi")
class RecordsWordingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `큰 숫자는 차가 대신 말한 횟수이고 세부를 더하면 그 수다`() {
        val counts = TripCounts(proactive = 11, driverRequested = 6, answered = 2, stopNotices = 9, safetyNotices = 4, skipped = 3)
        compose.setContent {
            MyeotbeonTheme(dark = true) {
                RecordsScreen(RecordsUi(today = DaySummary(trips = 2, durationSec = 71 * 60, counts = counts), trend = listOf(20, 28)))
            }
        }
        compose.waitForIdle()
        assertEquals(28, counts.spokenOnBehalf)
        compose.onNodeWithText("28번").assertIsDisplayed()
        compose.onNodeWithText("차가 대신 말했어요").assertIsDisplayed()
        compose.onNodeWithText("그중").assertIsDisplayed()
        for ((label, n) in listOf("먼저 말함" to 11, "다시 말씀드리기" to 6, "물음에 대답" to 2, "멈춤 안내" to 9)) {
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("${n}번").assertIsDisplayed()
        }
        compose.onNodeWithText("문·창문·안전띠 안내 4번은 위 횟수와 따로 셌어요.").assertIsDisplayed()
        compose.onNodeWithText("끝까지 소리 내지 못한 말 3번", substring = true).assertIsDisplayed()
        compose.onNodeWithText("최근 8번 이동 — 차가 대신 말한 횟수").assertIsDisplayed()
    }

    @Test
    fun `화면 글에 부풀린 효과 문구가 없다`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        // 차가 말했다는 사실까지만 쓴다. 운전자가 대답하지 않아도 됐다는 것은 앱이 알 수 없다.
        assertFalse(strings.contains("직접 대답하지 않아도"))
        assertTrue(strings.contains("차가 대신 말했어요"))
    }
}
