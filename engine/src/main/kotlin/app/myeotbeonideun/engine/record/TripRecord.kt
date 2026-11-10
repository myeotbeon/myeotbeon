package app.myeotbeonideun.engine.record

/**
 * 한 번의 이동에서 센 횟수. 기록은 횟수뿐이다(누가 무슨 말을 했는지는 남기지 않는다).
 */
data class TripCounts(
    /** 차가 먼저 말한 횟수(출발·구간·간격 안내). */
    val proactive: Int = 0,
    /** 운전자가 '다시 말씀드리기'로 시킨 횟수. */
    val driverRequested: Int = 0,
    /** 물음을 알아듣고 대답한 횟수(실험 기능). */
    val answered: Int = 0,
    /** 멈춤 안내(신호 대기·정체·잠깐 정차·운전자 이탈과 복귀·도착). */
    val stopNotices: Int = 0,
    /** 문·창문·안전띠 안내. */
    val safetyNotices: Int = 0,
) {
    /** 운전자가 직접 대답하지 않아도 된 횟수. 이 앱이 주장하는 것은 여기까지다(명세 2). */
    val handled: Int get() = proactive + driverRequested + answered + stopNotices

    operator fun plus(o: TripCounts) = TripCounts(
        proactive + o.proactive,
        driverRequested + o.driverRequested,
        answered + o.answered,
        stopNotices + o.stopNotices,
        safetyNotices + o.safetyNotices,
    )
}

data class TripRecord(
    val startedAtMs: Long,
    val durationSec: Int,
    val counts: TripCounts,
)

/** 하루치 합계. */
data class DaySummary(
    val trips: Int,
    val durationSec: Int,
    val counts: TripCounts,
)

object RecordAggregator {
    /** [dayStartMs, dayEndMs) 에 시작한 이동을 합친다. */
    fun summarize(records: List<TripRecord>, dayStartMs: Long, dayEndMs: Long): DaySummary {
        val day = records.filter { it.startedAtMs in dayStartMs until dayEndMs }
        return DaySummary(
            trips = day.size,
            durationSec = day.sumOf { it.durationSec },
            counts = day.fold(TripCounts()) { acc, r -> acc + r.counts },
        )
    }

    /** 최근 n번 이동의 '직접 대답하지 않아도 된 횟수'. 오래된 것부터. */
    fun recentTrend(records: List<TripRecord>, n: Int = 8): List<Int> =
        records.sortedBy { it.startedAtMs }.takeLast(n).map { it.counts.handled }

    /** 보관할 기록 수를 넘으면 오래된 것부터 버린다. */
    fun trim(records: List<TripRecord>, keep: Int = 60): List<TripRecord> =
        records.sortedBy { it.startedAtMs }.takeLast(keep)
}
