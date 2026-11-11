package app.myeotbeonideun

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.service.TripNotifications
import app.myeotbeonideun.ui.MainActivity
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
import java.io.File
import java.time.Duration

/**
 * 급한 운전자 알림을 앱 밖에도(결함 11): 앱이 화면 뒤에 있으면 중요도 높음 채널의 한 줄 알림, 진행 중 알림의 글도
 * 그 한 줄. 풀리면 지운다. 체험 모드 신호로 주행 중 문을 연다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UrgentAlertTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun posted(id: Int): Notification? = shadowOf(nm).getNotification(id)
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private val doorLine = "같이 탄 분 쪽 문이 열렸어요."

    private fun drive() {
        val c = app.container
        c.trip.start(TripConfig(place = "큰딸 집"))
        c.demoSource.update { it.copy(gear = Gear.DRIVE) }
        idle(20_000)
    }

    private fun door(open: Boolean) =
        app.container.demoSource.update { it.copy(doorOpen = it.doorOpen + (SeatPosition.REAR_RIGHT to open)) }

    @Test
    fun `앱이 뒤에 있을 때 주행 중 문이 열리면 한 줄 알림과 진행 중 알림 글로 알리고 닫히면 지운다`() {
        val c = app.container
        drive()
        assertTrue("시험에는 화면이 없다(앱이 뒤에 있다)", !c.foreground.value)
        assertNull(posted(TripNotifications.URGENT_ID))

        door(true)
        idle(500)
        assertEquals(doorLine, c.trip.urgentLine)
        val headsUp = posted(TripNotifications.URGENT_ID)!!
        assertEquals(doorLine, headsUp.title())
        assertEquals(TripNotifications.CHANNEL_URGENT, headsUp.channelId)
        val channel = nm.getNotificationChannel(TripNotifications.CHANNEL_URGENT)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull("채널 소리는 끈다(소리는 앱의 짧은 알림음 하나)", channel.sound)
        assertEquals(doorLine, posted(TripNotifications.ONGOING_ID)!!.text())

        // 문이 열려 있는 동안 15초마다 오는 되풀이 알림에도 한 줄 알림을 다시 올리지 않는다(되풀이 알람 금지)
        val firstWhen = headsUp.`when`
        idle(40_000)
        assertEquals(firstWhen, posted(TripNotifications.URGENT_ID)!!.`when`)

        door(false)
        idle(500)
        assertNull(c.trip.urgentLine)
        assertNull("풀리면 지운다", posted(TripNotifications.URGENT_ID))
        assertEquals("가는 길을 대신 말해 드리고 있어요", posted(TripNotifications.ONGOING_ID)!!.text())

        c.trip.end()
        assertNull(posted(TripNotifications.ONGOING_ID))
        assertNull(posted(TripNotifications.URGENT_ID))
    }

    @Test
    fun `안전띠 알림이 떠 있는 동안 주행 중 문이 열리면 앞의 것을 지우고 새 한 줄 알림으로 다시 띄운다`() {
        // 같은 번호를 '한 번만 알림'으로 고쳐 올리면 시스템 UI 가 헤드업을 다시 띄우지 않는다(검토 NEW-5)
        val c = app.container
        drive()
        c.demoSource.setBelt(SeatPosition.REAR_RIGHT, false)
        idle(61_000) // 권유 두 번(0초, 30초) 뒤 60초에 운전자에게
        val beltLine = "같이 탄 분 안전띠가 풀렸어요."
        val belt = posted(TripNotifications.URGENT_ID)!!
        assertEquals(beltLine, belt.title())
        assertEquals("한 번만 알림 표시를 쓰지 않는다", 0, belt.flags and Notification.FLAG_ONLY_ALERT_ONCE)

        idle(1_000)
        door(true)
        idle(500)
        assertEquals(doorLine, c.trip.urgentLine)
        val door = posted(TripNotifications.URGENT_ID)!!
        assertEquals("더 급한 문 열림으로 바뀐다", doorLine, door.title())
        assertTrue("새로 올린 알림이다", door.`when` > belt.`when`)
        assertEquals(0, door.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals("한 줄 알림은 하나만 남는다", 1, nm.activeNotifications.count { it.id == TripNotifications.URGENT_ID })

        // 문이 닫히면 남은 안전띠 알림으로 돌아간다(다시 새로 올림)
        door(false)
        idle(500)
        assertEquals(beltLine, posted(TripNotifications.URGENT_ID)!!.title())
        c.trip.end()
    }

    @Test
    fun `급하지 않은 알림(창문)은 한 줄 알림으로 올리지 않는다`() {
        val c = app.container
        drive()
        c.demoSource.update { it.copy(windowOpenPercent = it.windowOpenPercent + (SeatPosition.REAR_RIGHT to 40)) }
        idle(500)
        assertNotNull(c.trip.ui.value!!.snapshot.driverNotice)
        assertNull(c.trip.urgentLine)
        assertNull(posted(TripNotifications.URGENT_ID))
        c.trip.end()
    }

    @Test
    fun `알림을 끈 기기에서는 한 줄 알림을 올리지 않는다`() {
        shadowOf(nm).setNotificationsEnabled(false)
        drive()
        door(true)
        idle(500)
        assertEquals(doorLine, app.container.trip.urgentLine)
        assertNull(posted(TripNotifications.URGENT_ID))
        app.container.trip.end()
    }

    @Test
    fun `앱이 앞에 있으면 한 줄 알림을 띄우지 않고 뒤로 가면 띄우며 다시 앞으로 오면 지운다`() {
        val activity = ActivityScenario.launch(MainActivity::class.java)
        idle(200)
        assertTrue(app.container.foreground.value)
        drive()
        door(true)
        idle(500)
        assertNull("앱이 앞에 있으면 화면의 붉은 한 줄이 알린다", posted(TripNotifications.URGENT_ID))
        assertEquals(doorLine, posted(TripNotifications.ONGOING_ID)!!.text())

        activity.moveToState(Lifecycle.State.CREATED) // 내비가 앞으로 왔다
        idle(1_500)
        assertEquals(doorLine, posted(TripNotifications.URGENT_ID)!!.title())

        activity.moveToState(Lifecycle.State.RESUMED)
        idle(200)
        assertNull(posted(TripNotifications.URGENT_ID))
        app.container.trip.end()
        activity.close()
    }

    @Test
    fun `매니페스트가 알림 권한을 선언한다`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\""))
    }
}

/** 알림 권한은 출발 전 설정에서 동행을 시작할 때(정차 중) 한 번 묻는다(안드로이드 13 이상). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1700dp-h1212dp-land-mdpi")
class NotificationPermissionTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `동행을 시작할 때 알림 권한을 한 번 묻는다`() {
        if (compose.onAllNodesWithText("확인했어요").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("확인했어요").performClick()
        }
        compose.onNodeWithText("동행 시작").performClick()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertNotNull(asked)
        assertTrue(asked!!.requestedPermissions.contains("android.permission.POST_NOTIFICATIONS"))
        val app = compose.activity.application as App
        app.container.trip.end()
    }
}
