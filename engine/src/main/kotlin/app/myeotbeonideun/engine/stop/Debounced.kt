package app.myeotbeonideun.engine.stop

/**
 * 좌석 점유처럼 흔들릴 수 있는 참/거짓 신호를 걸러 받는다. 값이 바뀌면 [holdMs] 동안 그대로일 때만 받아들인다.
 *
 * 가벼운 어르신이 자세를 바꾸거나 운전자가 앉은 채 몸을 들면 점유 값이 잠깐 꺼졌다 켜진다. 그대로 받으면
 * "따님이 잠깐 내렸어요"와 "돌아왔어요"가 번갈아 나온다.
 *
 * 처음 값, 그리고 신호가 끊기거나(null) 다시 들어올 때는 바로 받는다. 그것은 흔들림이 아니라 신호가 있고 없음의 일이다.
 */
class Debounced(private val holdMs: Long) {
    /** 받아들인 값. null = 신호 없음 */
    var value: Boolean? = null
        private set

    private var started = false
    private var candidate: Boolean? = null
    private var candidateSinceMs = 0L

    /** 지금 들어온 값과 시각을 넣고, 받아들인 값을 돌려준다. */
    fun update(raw: Boolean?, nowMs: Long): Boolean? {
        if (!started || raw == null || value == null) {
            started = true
            value = raw
            candidate = raw
            return value
        }
        if (raw == value) {
            candidate = raw
            return value
        }
        if (raw != candidate) {
            candidate = raw
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs >= holdMs) value = raw
        return value
    }
}
