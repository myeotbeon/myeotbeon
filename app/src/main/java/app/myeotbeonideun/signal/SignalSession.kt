package app.myeotbeonideun.signal

// 이 파일은 안드로이드에 의존하지 않는다(순수 Kotlin). SignalSessionTest 가 확인한다.

/**
 * 차량 신호 구독 한 번(세대)의 장부.
 *
 * - 비동기 SDK 호출(check*Capabilities, getCurrent*, NaviHelper.initialize 등)마다 [begin] 으로 표를 받고,
 *   그 콜백에서 [Ticket.finish] 를 꼭 한 번 부른다.
 * - [close]: 리스너 해제([onClose])는 바로 하고, SDK 를 놓는 일([onRelease], release())은 기다리던 콜백이
 *   모두 돌아온 뒤에 한다. Pleos Vehicle 문서: "비동기 콜백이 끝나기 전에 release()를 호출하지 않도록".
 * - 닫은 뒤에 돌아온 콜백은 [Ticket.finish] 가 false 를 돌려주므로 본문을 건너뛴다(옛 세대의 콜백 무시).
 *   다시 시작하면 새 세대를 만든다.
 * - 콜백이 끝내 오지 않으면 부르는 쪽이 시간을 정해 [forceRelease] 를 부른다(마지막 수단).
 *
 * 한 스레드(메인)에서만 부른다.
 */
class SignalSession(val generation: Int) {
    private var pending = 0
    private val closeActions = ArrayList<() -> Unit>()
    private val releaseActions = ArrayList<() -> Unit>()

    /** 닫는 중이거나 닫혔다. 이 세대의 콜백은 더 이상 아무것도 하지 않는다. */
    var closing = false
        private set

    /** SDK 를 놓았다. */
    var released = false
        private set

    /** 아직 돌아오지 않은 비동기 호출 수. */
    val pendingCalls: Int get() = pending

    /** 비동기 호출 하나. */
    inner class Ticket internal constructor() {
        private var finished = false

        /**
         * 콜백이 돌아왔다. 두 번 불러도 한 번만 센다.
         * @return 이 세대가 아직 살아 있어 본문을 실행해도 되면 true
         */
        fun finish(): Boolean {
            if (!finished) {
                finished = true
                pending--
                if (closing) releaseIfIdle()
            }
            return !closing
        }
    }

    fun begin(): Ticket {
        pending++
        return Ticket()
    }

    /** 닫을 때 바로 할 일(리스너 해제). 이미 닫혔으면 바로 한다. */
    fun onClose(action: () -> Unit) {
        if (closing) runCatching(action) else closeActions += action
    }

    /** SDK 를 놓을 때 할 일(release). 이미 놓았으면 바로 한다. */
    fun onRelease(action: () -> Unit) {
        if (released) runCatching(action) else releaseActions += action
    }

    /**
     * 닫는다. 리스너는 바로 풀고(등록한 반대 순서), 기다리는 콜백이 없으면 SDK 도 바로 놓는다.
     * @return SDK 까지 놓았으면 true. false 면 콜백이 다 돌아올 때 놓는다
     */
    fun close(): Boolean {
        if (!closing) {
            closing = true
            closeActions.asReversed().forEach { runCatching(it) }
            closeActions.clear()
            releaseIfIdle()
        }
        return released
    }

    /** 콜백을 더 기다리지 않고 SDK 를 놓는다. @return 그때까지 돌아오지 않은 콜백 수 */
    fun forceRelease(): Int {
        val left = pending
        release()
        return left
    }

    private fun releaseIfIdle() {
        if (pending <= 0) release()
    }

    private fun release() {
        if (released) return
        released = true
        releaseActions.asReversed().forEach { runCatching(it) }
        releaseActions.clear()
    }
}

/** 지원 영역 목록에서 그 자리의 영역을 고른다. 목록에 든 자리만 등록한다. */
object SupportedAreas {
    /**
     * @param supported check*Capabilities 가 준 지원 영역들
     * @param contains 영역에 그 자리 값이 들어 있는지(한 영역이 여러 자리를 묶을 수 있다)
     * @param whenUnlisted 목록이 비어 있을 때(차가 영역을 알려 주지 않음) 그 자리 하나로 영역을 만든다
     * @return 등록할 영역. 목록이 있는데 그 자리가 없으면 null(등록하지 않는다)
     */
    fun <A, V> pick(supported: Collection<A>, value: V, contains: (A, V) -> Boolean, whenUnlisted: (V) -> A): A? =
        if (supported.isEmpty()) whenUnlisted(value) else supported.firstOrNull { contains(it, value) }

    /**
     * 여러 자리를 한꺼번에 고른다([pick] 을 자리마다). 다만 목록이 있는데 어느 자리도 맞지 않으면 영역 표기를
     * 잘못 읽은 것으로 보고, 예전처럼 자리마다 그 자리 하나로 시도한다(목록 해석이 틀려 신호를 통째로 잃지 않게).
     * @return 자리 순서대로의 영역(등록하지 않을 자리는 null)과, 그렇게 되돌렸는지
     */
    fun <A, V> pickAll(supported: Collection<A>, values: List<V>, contains: (A, V) -> Boolean, whenUnlisted: (V) -> A): Picked<A> {
        val picked = values.map { pick(supported, it, contains, whenUnlisted) }
        return if (supported.isNotEmpty() && values.isNotEmpty() && picked.all { it == null }) {
            Picked(values.map(whenUnlisted), fellBack = true)
        } else {
            Picked(picked, fellBack = false)
        }
    }

    data class Picked<A>(val areas: List<A?>, val fellBack: Boolean)
}

/** check*Capabilities 등이 실패했을 때 다시 시도하는 간격(지수 간격, 세 번까지). */
object RetryPolicy {
    const val MAX_RETRIES = 3
    const val BASE_DELAY_MS = 2_000L

    /** [attempt] 번째(0부터) 실패 뒤 다시 시도하기까지 기다릴 시간: 2초, 4초, 8초. 더 시도하지 않으면 null */
    fun delayMs(attempt: Int): Long? =
        if (attempt in 0 until MAX_RETRIES) BASE_DELAY_MS shl attempt else null
}
