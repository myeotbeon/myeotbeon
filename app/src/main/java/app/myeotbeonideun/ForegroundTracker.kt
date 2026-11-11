package app.myeotbeonideun

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 앱 화면이 보이는지 센다(시작된 액티비티가 하나라도 있는지). ProcessLifecycleOwner 의 onStart/onStop 과 같은 뜻이고,
 * 의존성을 더하지 않으려고 직접 셌다. 마지막 화면이 멈춘 뒤 [stopDelayMs] 동안 기다렸다가 false 로 바꾼다
 * (화면을 다시 만들거나 잠깐 다른 화면이 덮일 때 차량 신호 구독을 놓았다 붙이지 않게).
 */
class ForegroundTracker(private val stopDelayMs: Long = STOP_DELAY_MS) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var started = 0
    private val _foreground = MutableStateFlow(false)

    /** 앱 화면이 보이면 true. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val goBackground = Runnable { if (started == 0) _foreground.value = false }

    override fun onActivityStarted(activity: Activity) {
        started++
        handler.removeCallbacks(goBackground)
        _foreground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) handler.postDelayed(goBackground, stopDelayMs)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        const val STOP_DELAY_MS = 1_000L
    }
}
