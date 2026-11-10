package app.myeotbeonideun.engine.journey

/**
 * 여정의 구간. 시간은 분 숫자가 아니라 구간 말로 전한다(명세 3.2).
 *
 * @property situation 이 구간에 들어설 때 쓰는 대본 상황 이름
 * @property cardLabel 동승자 카드 둘째 줄의 기본 문구(대본에 card_stage 가 6줄이면 그것을 쓴다)
 */
enum class Stage(val situation: String, val cardLabel: String) {
    JUST_DEPARTED("depart", "이제 출발해요"),
    ON_WAY("stage_onway", "가는 중이에요"),
    HALF("stage_half", "절반쯤 왔어요"),
    NEAR("stage_near", "거의 다 왔어요"),
    SOON("stage_5min", "곧 도착해요"),
    ARRIVED("arrive", "다 왔어요"),
}

/**
 * 내비의 남은 시간으로 구간을 정한다.
 *
 * | 구간 | 조건 |
 * |---|---|
 * | 곧 도착 | 남은 시간 5분 이하(또는 목적지 근접) |
 * | 거의 다 옴 | 4분의 3 이상 왔고 남은 시간 20분 이하 |
 * | 절반 | 10분의 4 이상 왔다 |
 * | 가는 중 | 10분의 1 이상 왔거나 출발 2분이 지났다 |
 * | 방금 출발 | 그 밖 |
 *
 * '도착'은 시간으로 정하지 않는다. 내비의 도착 이벤트나 목적지 근접 + P단으로만 정한다.
 */
object StageCalculator {
    const val SOON_SEC = 5 * 60
    const val NEAR_MAX_REMAIN_SEC = 20 * 60
    const val NEAR_PERCENT = 75
    const val HALF_PERCENT = 40
    const val ON_WAY_PERCENT = 10
    const val ON_WAY_ELAPSED_SEC = 2 * 60

    /**
     * @param totalSec 출발 때의 전체 예상 시간. 모르면 null
     * @param remainSec 남은 시간. 모르면 null(내비 없이 가는 길)
     * @param elapsedSec 출발한 뒤 지난 시간
     * @param nearDestination 근접 알림 등으로 목적지 근처임을 안 경우
     */
    fun stageOf(
        totalSec: Int?,
        remainSec: Int?,
        elapsedSec: Int,
        nearDestination: Boolean = false,
    ): Stage {
        if (nearDestination) return Stage.SOON
        if (remainSec == null) {
            return if (elapsedSec >= ON_WAY_ELAPSED_SEC) Stage.ON_WAY else Stage.JUST_DEPARTED
        }
        val remain = remainSec.coerceAtLeast(0)
        if (remain <= SOON_SEC) return Stage.SOON
        val total = maxOf(totalSec ?: remain, remain, 1).toLong()
        // 경계에서 흔들리지 않게 정수로 견준다: (온 시간) * 100 >= 기준% * (전체 시간)
        val done = (total - remain) * 100
        return when {
            done >= NEAR_PERCENT * total && remain <= NEAR_MAX_REMAIN_SEC -> Stage.NEAR
            done >= HALF_PERCENT * total -> Stage.HALF
            done >= ON_WAY_PERCENT * total || elapsedSec >= ON_WAY_ELAPSED_SEC -> Stage.ON_WAY
            else -> Stage.JUST_DEPARTED
        }
    }
}
