package app.myeotbeonideun.engine.journey

import app.myeotbeonideun.engine.model.IntervalMode

/** 먼저 말해 주기 간격(명세 3.2): 자동 = 남은 시간의 1/6 을 4~10분으로 제한. */
object IntervalPolicy {
    const val AUTO_MIN_SEC = 4 * 60
    const val AUTO_MAX_SEC = 10 * 60
    /** 남은 시간을 모를 때(내비 없이 가는 길)의 자동 간격. */
    const val AUTO_UNKNOWN_SEC = 5 * 60

    /** @return 간격(초). 끔이면 null */
    fun intervalSec(mode: IntervalMode, remainSec: Int?): Int? = when (mode) {
        IntervalMode.OFF -> null
        IntervalMode.FIVE_MIN -> 5 * 60
        IntervalMode.TEN_MIN -> 10 * 60
        IntervalMode.AUTO ->
            if (remainSec == null) AUTO_UNKNOWN_SEC
            else (remainSec / 6).coerceIn(AUTO_MIN_SEC, AUTO_MAX_SEC)
    }
}
