package app.myeotbeonideun.engine.journey

import app.myeotbeonideun.engine.model.IntervalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageCalculatorTest {
    private val total = 40 * 60 // 40분 길

    private fun stage(remainMin: Double, elapsedSec: Int = 0, total: Int = this.total) =
        StageCalculator.stageOf(total, (remainMin * 60).toInt(), elapsedSec)

    @Test
    fun `출발 직후는 방금 출발이다`() {
        assertEquals(Stage.JUST_DEPARTED, stage(40.0))
        assertEquals(Stage.JUST_DEPARTED, stage(36.1)) // 10% 직전
    }

    @Test
    fun `10분의 1을 지나면 가는 중이다`() {
        assertEquals(Stage.ON_WAY, stage(36.0)) // 정확히 10%
        assertEquals(Stage.ON_WAY, stage(24.1)) // 40% 직전
    }

    @Test
    fun `길이 멀어 10분의 1이 안 됐어도 2분이 지나면 가는 중이다`() {
        val twoHours = 120 * 60
        assertEquals(Stage.JUST_DEPARTED, StageCalculator.stageOf(twoHours, twoHours - 119, 119))
        assertEquals(Stage.ON_WAY, StageCalculator.stageOf(twoHours, twoHours - 120, 120))
    }

    @Test
    fun `10분의 4를 지나면 절반이다`() {
        assertEquals(Stage.HALF, stage(24.0)) // 정확히 40%
        assertEquals(Stage.HALF, stage(10.1)) // 75% 직전
    }

    @Test
    fun `4분의 3을 지나고 20분 이하로 남으면 거의 다 옴이다`() {
        assertEquals(Stage.NEAR, stage(10.0)) // 정확히 75%
        assertEquals(Stage.NEAR, stage(5.1))
    }

    @Test
    fun `먼 길은 4분의 3을 지나도 20분 넘게 남았으면 아직 절반이다`() {
        val twoHours = 120 * 60
        assertEquals(Stage.HALF, StageCalculator.stageOf(twoHours, 30 * 60, 90 * 60)) // 75%, 30분 남음
        assertEquals(Stage.HALF, StageCalculator.stageOf(twoHours, 20 * 60 + 1, 0))
        assertEquals(Stage.NEAR, StageCalculator.stageOf(twoHours, 20 * 60, 0))
    }

    @Test
    fun `5분 이하로 남으면 곧 도착이다`() {
        assertEquals(Stage.NEAR, stage(5.02)) // 301초
        assertEquals(Stage.SOON, stage(5.0))
        assertEquals(Stage.SOON, stage(0.0))
    }

    @Test
    fun `짧은 길은 처음부터 곧 도착이다`() {
        assertEquals(Stage.SOON, StageCalculator.stageOf(240, 240, 0))
    }

    @Test
    fun `내비가 없으면 2분 뒤부터 가는 중이고 근접하면 곧 도착이다`() {
        assertEquals(Stage.JUST_DEPARTED, StageCalculator.stageOf(null, null, 119))
        assertEquals(Stage.ON_WAY, StageCalculator.stageOf(null, null, 120))
        assertEquals(Stage.SOON, StageCalculator.stageOf(null, null, 600, nearDestination = true))
    }

    @Test
    fun `전체 시간을 모르면 남은 시간을 전체로 본다`() {
        assertEquals(Stage.JUST_DEPARTED, StageCalculator.stageOf(null, 1800, 0))
    }

    @Test
    fun `시간으로는 도착이 되지 않는다`() {
        assertEquals(Stage.SOON, StageCalculator.stageOf(1800, 0, 1800))
    }
}

class IntervalPolicyTest {
    @Test
    fun `자동은 남은 시간의 6분의 1이다`() {
        assertEquals(6 * 60, IntervalPolicy.intervalSec(IntervalMode.AUTO, 36 * 60))
    }

    @Test
    fun `자동은 4분보다 짧아지지 않는다`() {
        assertEquals(4 * 60, IntervalPolicy.intervalSec(IntervalMode.AUTO, 12 * 60))
        assertEquals(4 * 60, IntervalPolicy.intervalSec(IntervalMode.AUTO, 24 * 60))
        assertEquals(4 * 60 + 1, IntervalPolicy.intervalSec(IntervalMode.AUTO, 24 * 60 + 6))
    }

    @Test
    fun `자동은 10분보다 길어지지 않는다`() {
        assertEquals(10 * 60, IntervalPolicy.intervalSec(IntervalMode.AUTO, 60 * 60))
        assertEquals(10 * 60, IntervalPolicy.intervalSec(IntervalMode.AUTO, 180 * 60))
    }

    @Test
    fun `남은 시간을 모르면 자동은 5분이다`() {
        assertEquals(5 * 60, IntervalPolicy.intervalSec(IntervalMode.AUTO, null))
    }

    @Test
    fun `5분 10분 끔`() {
        assertEquals(300, IntervalPolicy.intervalSec(IntervalMode.FIVE_MIN, 3600))
        assertEquals(600, IntervalPolicy.intervalSec(IntervalMode.TEN_MIN, 60))
        assertNull(IntervalPolicy.intervalSec(IntervalMode.OFF, 3600))
    }
}
