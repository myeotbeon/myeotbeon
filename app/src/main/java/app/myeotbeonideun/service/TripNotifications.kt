package app.myeotbeonideun.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.myeotbeonideun.R
import app.myeotbeonideun.ui.MainActivity

/**
 * 동행 중의 알림 두 가지(결함 11).
 *
 * - 진행 중 알림(포그라운드 서비스, 중요도 낮음): 평소에는 "가는 길을 대신 말해 드리고 있어요", 급한 운전자 알림
 *   (주행 중 문 열림 등)이 떠 있는 동안은 그 한 줄로 바꾼다. 풀리면 되돌린다.
 * - 급한 알림(중요도 높음 채널의 한 줄 알림 = 헤드업): 급한 운전자 알림이 떠 있고 **앱이 화면 뒤에 있을 때만**
 *   한 번 띄운다(앱이 앞에 있으면 화면의 붉은 한 줄이 그 일을 한다). 풀리거나 앱이 앞으로 오면 지운다.
 *   같은 글은 다시 울리지 않고(setOnlyAlertOnce), 채널 소리는 끈다(소리는 앱이 내는 짧은 알림음 하나뿐).
 *
 * AAOS·Pleos 의 제약(확인하지 못한 것)
 * - 알림을 어떻게 보일지는 차의 시스템 UI 가 정한다. 헤드업은 중요도 높음(IMPORTANCE_HIGH)에만 뜨고, 주행 중에는
 *   시스템이 헤드업을 줄이거나 알림 센터의 글을 짧게 자를 수 있다(주행 규제의 UX_RESTRICTIONS_LIMIT_STRING_LENGTH 등).
 *   Pleos 문서에는 서드파티 헤드업의 주행 중 표시 규칙이 없고("헤드업 알림은 운전과 관련된 것만", 스팸·반복 알람 금지만
 *   있다) 에뮬레이터에서도 아직 보지 못했다. 그래서 운전과 관련된 급한 일에만, 한 일마다 한 번만 띄운다.
 * - 안드로이드 13 이상은 알림 권한(POST_NOTIFICATIONS)이 없으면 이 알림이 보이지 않는다(진행 중 알림도 알림 창에는
 *   안 보인다). 권한은 출발 전 설정에서 동행을 시작할 때(정차 중) 한 번 묻는다. 허용하지 않아도 앱은 그대로 돈다.
 * - 차량 자체의 '도어 열림' 경고(계기판)는 따로 뜬다(2026-11-11 에뮬레이터). 이 알림은 그것을 대신하지 않는다.
 */
object TripNotifications {
    const val CHANNEL_ONGOING = "companion"
    const val CHANNEL_URGENT = "urgent"
    const val ONGOING_ID = 1
    const val URGENT_ID = 2

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        val m = manager(context)
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW),
        )
        m.createNotificationChannel(
            NotificationChannel(
                CHANNEL_URGENT, context.getString(R.string.service_channel_urgent), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.service_channel_urgent_desc)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 진행 중 알림. [urgentLine] 이 있으면 그 한 줄을 보인다. */
    fun ongoing(context: Context, urgentLine: String?): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle(context.getString(R.string.service_title))
            .setContentText(urgentLine ?: context.getString(R.string.service_text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 진행 중 알림의 글을 바꾼다(포그라운드 서비스의 알림과 같은 번호). */
    fun updateOngoing(context: Context, urgentLine: String?) {
        runCatching { manager(context).notify(ONGOING_ID, ongoing(context, urgentLine)) }
    }

    /** 급한 운전자 알림 한 줄(헤드업). 알림을 끈 기기에서는 아무 일도 하지 않는다. */
    fun showUrgent(context: Context, line: String) {
        runCatching {
            ensureChannels(context)
            val m = manager(context)
            if (!m.areNotificationsEnabled()) return
            m.notify(
                URGENT_ID,
                Notification.Builder(context, CHANNEL_URGENT)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(line)
                    .setContentIntent(openApp(context))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setLocalOnly(true)
                    .build(),
            )
        }
    }

    fun cancelUrgent(context: Context) {
        runCatching { manager(context).cancel(URGENT_ID) }
    }

    /** 동행이 끝났다. 남은 알림을 모두 지운다(포그라운드 서비스가 뜨지 못해 직접 올린 진행 중 알림까지). */
    fun cancelAll(context: Context) {
        runCatching {
            manager(context).cancel(URGENT_ID)
            manager(context).cancel(ONGOING_ID)
        }
    }
}
