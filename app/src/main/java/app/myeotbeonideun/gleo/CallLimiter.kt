package app.myeotbeonideun.gleo

// 이 파일은 안드로이드에 의존하지 않는다(순수 Kotlin). CallLimiterTest 가 확인한다.

/**
 * 바깥에서 부르는 함수의 연속 호출 제한(결함 18).
 *
 * Gleo 를 받는 리시버와 '동행 시작' 화면은 권한 없이 열려 있어(Gleo 의 송신 권한·패키지가 문서에 없다) 기기 안의
 * 다른 앱도 부를 수 있다. 막지 못하는 대신, 짧은 사이에 거듭 불러 차가 쉬지 않고 말하게 하거나 동행을 거듭 시작하는
 * 일을 막는다. 실제로 일을 할 때(말하기, 동행 시작)만 센다 — 거절된 호출(주행 중, 동행 없음)은 세지 않는다.
 * 일을 하기 전에는 거절될지 모르는 호출('다 왔다고 말하기'는 정차 중에만 받는다)은 [canAcquire] 로 먼저 보고, 실제로 했을 때만
 * [record] 로 센다.
 * 사람이 Gleo 에게 말로 시키면 한 번에 몇 초가 걸리므로 이 제한에 걸리지 않는다.
 *
 * @param minGapMs 받아들인 호출 사이의 최소 간격
 * @param maxInWindow [windowMs] 안에 받아들이는 최대 횟수
 */
class CallLimiter(
    private val minGapMs: Long,
    private val maxInWindow: Int,
    private val windowMs: Long,
) {
    private val accepted = ArrayDeque<Long>()

    /** 지금 받아들여도 되는지 본다. 세지 않는다(실제로 일을 했으면 [record]). */
    fun canAcquire(nowMs: Long): Boolean {
        while (accepted.isNotEmpty() && nowMs - accepted.first() >= windowMs) accepted.removeFirst()
        val last = accepted.lastOrNull()
        if (last != null && nowMs - last < minGapMs) return false
        return accepted.size < maxInWindow
    }

    /** 받아들여 실제로 일을 한 호출을 센다. */
    fun record(nowMs: Long) {
        accepted.addLast(nowMs)
    }

    /** 지금 받아들여도 되면 true 를 돌려주고 센다. 아니면 false(세지 않는다). */
    fun tryAcquire(nowMs: Long): Boolean {
        if (!canAcquire(nowMs)) return false
        record(nowMs)
        return true
    }
}

/** Gleo 함수별 제한. 앱(AppContainer)마다 하나. */
class GleoLimits {
    /** '다시 말씀드리기'와 '다 왔다고 말씀드려 줘': 4초에 한 번, 1분에 네 번까지. */
    val speak = CallLimiter(minGapMs = 4_000, maxInWindow = 4, windowMs = 60_000)

    /** '잠시 조용히': 4초에 한 번, 1분에 네 번까지. */
    val pause = CallLimiter(minGapMs = 4_000, maxInWindow = 4, windowMs = 60_000)

    /** '동행 시작': 10초에 한 번, 10분에 세 번까지. */
    val start = CallLimiter(minGapMs = 10_000, maxInWindow = 3, windowMs = 10 * 60_000)
}
