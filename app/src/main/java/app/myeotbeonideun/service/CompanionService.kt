package app.myeotbeonideun.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.myeotbeonideun.App
import app.myeotbeonideun.R
import app.myeotbeonideun.ui.MainActivity

/**
 * 동행 중에만 떠 있는 포그라운드 서비스.
 *
 * 주행 중에는 내비게이션이 화면 앞에 오고 이 앱은 뒤로 가는 일이 많다. 그때도 차량 신호를 받아
 * 안내 음성을 이어 가야 하므로, 동행을 시작할 때 띄우고 끝내면 바로 내린다.
 * 부팅 때 스스로 시작하지 않고, 신호는 폴링이 아니라 리스너로 받는다.
 */
class CompanionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 로 시작했으면 반드시 startForeground 를 불러야 한다.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!(application as App).container.trip.inProgress) {
            // 그사이 동행이 끝났다.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "companion"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // 화면이 앞에 있을 때(동행 시작 버튼, Gleo 의 activity 호출) 시작한다.
            runCatching { context.startForegroundService(Intent(context, CompanionService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CompanionService::class.java)) }
        }
    }
}
