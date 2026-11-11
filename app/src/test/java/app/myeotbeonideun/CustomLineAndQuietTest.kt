package app.myeotbeonideun

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.CompanionEngine
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.Purpose
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.ui.AppViewModel
import app.myeotbeonideun.ui.screens.SetupForm
import app.myeotbeonideun.ui.screens.SetupScreen
import app.myeotbeonideun.ui.screens.SetupUi
import app.myeotbeonideun.ui.theme.MyeotbeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 결함 16(화면 쪽): 직접 쓴 화제 전환 한마디를 입력할 때 엔진 검사로 알려 주기, '조용히 해제'를 화면에 반영하기. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomLineAndQuietTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun problemFor(line: String, purpose: Purpose = Purpose.CUSTOM): String? {
        val vm = AppViewModel(app)
        vm.updateForm(SetupForm(place = "큰딸 집", purpose = purpose, customPurposeLine = line))
        idle(1)
        return vm.setup.value.customLineProblem
    }

    @Test
    fun `원칙에 맞는 한마디는 아무 말도 하지 않는다`() {
        assertNull(problemFor("가서 손주 얼굴 봐요"))
        assertNull("호칭으로 시작해도 엔진이 떼고 읽는다", problemFor("어머니, 가서 손주 얼굴 봐요"))
        assertNull("직접 입력이 아니면 보지 않는다", problemFor("아까 말했잖아요", purpose = Purpose.MEAL))
        assertNull("비어 있으면 아직 묻지 않는다", problemFor("   "))
    }

    @Test
    fun `금지어가 들면 그 말을 짚어 알려 준다`() {
        val p = problemFor("아까 말했잖아요 손주 봐요")!!
        assertTrue(p, p.startsWith("이대로면 이 한마디는 읽지 않아요."))
        assertTrue(p, p.contains("‘아까’ 같은 말은 빼 주세요"))
    }

    @Test
    fun `한 문장이 18음절을 넘으면 음절 수와 함께 알려 준다`() {
        val p = problemFor("가서손주들이랑맛있는저녁같이먹고오세요")!!
        assertTrue(p, p.contains("한 문장이 19음절이에요. 18음절 안으로 줄여 주세요."))
    }

    @Test
    fun `두 문장이면 한 문장으로 적으라고 한다`() {
        assertTrue(problemFor("가서 손주 봐요. 밥도 먹어요.")!!.contains("한 문장으로 적어 주세요."))
    }

    @Test
    fun `몸을 내밀게 할 수 있는 말과 빈칸 기호도 알려 준다`() {
        assertTrue(problemFor("창문 밖으로 손 흔들어요")!!.contains("‘밖으로’처럼 몸을 밖으로 내밀게 할 수 있는 말은 빼 주세요."))
        assertTrue(problemFor("{곳} 가서 쉬어요")!!.contains("{ } 기호는 쓸 수 없어요."))
    }

    @Test
    fun `엔진도 그 한마디를 읽지 않는다(앱의 안내와 엔진의 판단이 같다)`() {
        val line = "아까 말했잖아요 손주 봐요"
        assertNotNull(problemFor(line))
        assertTrue(CompanionEngine.checkCustomPurposeLine(line, "어머니").isNotEmpty())
    }

    @Test
    fun `다시 말씀드리기가 잠시 조용히를 풀면 화면에 한 줄로 보이고 조용히 표시도 풀린다`() {
        val c = app.container
        val vm = AppViewModel(app)
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(20)
        assertTrue(c.trip.toggleQuiet())
        idle(1)
        assertEquals(10, vm.companion.value.quietMinutesLeft)

        assertTrue(c.trip.repeat())
        idle(1)
        val ui = vm.companion.value
        assertNull("조용히 표시가 풀렸다(버튼은 다시 '잠시 조용히')", ui.quietMinutesLeft)
        assertEquals("잠시 조용히를 풀었어요.", ui.notice)
        assertTrue(!ui.noticeUrgent)
        idle(16)
        assertNull("15초 보인 뒤 지운다", vm.companion.value.notice)
        c.trip.end()
    }

    @Test
    fun `조용히가 시간이 다 되어 풀리면 화면의 조용히 표시도 풀린다`() {
        val c = app.container
        val vm = AppViewModel(app)
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(20)
        assertTrue(c.trip.quiet(1))
        idle(1)
        assertEquals(1, vm.companion.value.quietMinutesLeft)
        idle(61)
        assertNull(vm.companion.value.quietMinutesLeft)
        c.trip.end()
    }
}

/** 출발 전 설정 화면이 한마디의 문제를 입력 칸 아래에 그린다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1700dp-h1212dp-land-mdpi")
class CustomLineScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `직접 입력 칸 아래에 읽지 않는 까닭이 보인다`() {
        val problem = "이대로면 이 한마디는 읽지 않아요. ‘아까’ 같은 말은 빼 주세요. 고치거나 따지는 말로 들릴 수 있어요."
        compose.setContent {
            MyeotbeonTheme(dark = true) {
                SetupScreen(
                    SetupUi(
                        form = SetupForm(place = "큰딸 집", purpose = Purpose.CUSTOM, customPurposeLine = "아까 말했잖아요"),
                        customLineProblem = problem,
                    ),
                    {}, {}, {}, {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(problem).assertIsDisplayed()
    }
}
