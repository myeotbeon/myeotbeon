package app.myeotbeonideun.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.myeotbeonideun.App

/**
 * 동행 중에만 떠 있는 포그라운드 서비스.
 *
 * 주행 중에는 내비게이션이 화면 앞에 오고 이 앱은 뒤로 가는 일이 많다. 그때도 차량 신호를 받아
 * 안내 음성을 이어 가야 하므로, 동행을 시작할 때 띄우고 끝내면 바로 내린다.
 * 부팅 때 스스로 시작하지 않는다. 차량 신호 구독은 화면이 보이거나 동행 중일 때만 두고(AppContainer),
 * 신호는 리스너로 받는다(내비 남은 시간만 동행 중 30초마다 다시 묻는다).
 */
class CompanionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 로 시작했으면 반드시 startForeground 를 불러야 한다.
        // 알림 글은 평소 문구, 급한 운전자 알림이 떠 있으면 그 한 줄이다(TripNotifications, 결함 11).
        val trip = (application as App).container.trip
        val notification = TripNotifications.ongoing(this, trip.urgentLine)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(TripNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(TripNotifications.ONGOING_ID, notification)
        }
        if (!trip.inProgress) {
            // 그사이 동행이 끝났다.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            // 화면이 앞에 있을 때(동행 시작 버튼, Gleo 의 activity 호출) 시작한다.
            runCatching { context.startForegroundService(Intent(context, CompanionService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CompanionService::class.java)) }
        }
    }
}
