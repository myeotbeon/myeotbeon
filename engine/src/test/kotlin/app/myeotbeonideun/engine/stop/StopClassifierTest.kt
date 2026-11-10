package app.myeotbeonideun.engine.stop

import app.myeotbeonideun.engine.model.Motion
import org.junit.Assert.assertEquals
import org.junit.Test

class StopClassifierTest {
    private fun classify(input: StopInput, previous: StopReason = StopReason.NONE) =
        StopClassifier.classify(previous, input)

    @Test
    fun `주행 단에서 20초 서 있으면 신호 대기다`() {
        assertEquals(StopReason.NONE, classify(StopInput(Motion.IN_DRIVE, stationaryForMs = 19_999)))
        assertEquals(StopReason.SIGNAL_WAIT, classify(StopInput(Motion.IN_DRIVE, stationaryForMs = 20_000)))
    }

    @Test
    fun `서 있고 내비가 정체를 알리면 정체다`() {
        assertEquals(
            StopReason.TRAFFIC,
            classify(StopInput(Motion.IN_DRIVE, stationaryForMs = 25_000, congested = true)),
        )
    }

    @Test
    fun `달리는 중이면 정체 정보가 있어도 멈춤이 아니다`() {
        assertEquals(StopReason.NONE, classify(StopInput(Motion.IN_DRIVE, congested = true)))
    }

    @Test
    fun `도착 전에 P단으로 5초 서면 경유지 정차다`() {
        val base = StopInput(Motion.PARKED, driverSeatOccupied = true, passengerSeatOccupied = true)
        assertEquals(StopReason.NONE, classify(base.copy(parkedForMs = 4_999)))
        assertEquals(StopReason.WAYPOINT_STOP, classify(base.copy(parkedForMs = 5_000)))
    }

    @Test
    fun `출발 전의 P단은 경유지 정차가 아니다`() {
        assertEquals(
            StopReason.NONE,
            classify(StopInput(Motion.PARKED, parkedForMs = 60_000, departed = false)),
        )
    }

    @Test
    fun `P단에서 운전석 문이 열리고 운전석이 비고 동승자가 남으면 운전자 이탈이다`() {
        val away = StopInput(
            Motion.PARKED,
            parkedForMs = 1_000,
            driverSeatOccupied = false,
            driverDoorOpenedSincePark = true,
            passengerSeatOccupied = true,
        )
        assertEquals(StopReason.DRIVER_AWAY, classify(away))
    }

    @Test
    fun `운전석 문이 열린 적이 없으면 운전자 이탈로 보지 않는다`() {
        val input = StopInput(
            Motion.PARKED,
            parkedForMs = 10_000,
            driverSeatOccupied = false,
            driverDoorOpenedSincePark = false,
            passengerSeatOccupied = true,
        )
        assertEquals(StopReason.WAYPOINT_STOP, classify(input))
    }

    @Test
    fun `문 신호가 없는 차는 좌석 신호만으로 운전자 이탈을 본다`() {
        val input = StopInput(
            Motion.PARKED,
            driverSeatOccupied = false,
            driverDoorOpenedSincePark = null,
            passengerSeatOccupied = true,
        )
        assertEquals(StopReason.DRIVER_AWAY, classify(input))
    }

    @Test
    fun `동승자도 내렸으면 운전자 이탈 안내를 하지 않는다`() {
        val input = StopInput(
            Motion.PARKED,
            driverSeatOccupied = false,
            driverDoorOpenedSincePark = true,
            passengerSeatOccupied = false,
        )
        assertEquals(StopReason.NONE, classify(input))
    }

    @Test
    fun `좌석 신호가 없는 차는 운전자 이탈을 판단하지 않는다`() {
        val input = StopInput(Motion.PARKED, parkedForMs = 30_000, driverSeatOccupied = null)
        assertEquals(StopReason.WAYPOINT_STOP, classify(input))
    }

    @Test
    fun `이탈했던 운전자가 앉으면 운전자 복귀다`() {
        val back = StopInput(Motion.PARKED, parkedForMs = 90_000, driverSeatOccupied = true, passengerSeatOccupied = true)
        assertEquals(StopReason.DRIVER_BACK, classify(back, previous = StopReason.DRIVER_AWAY))
        // 복귀는 한 번만 나오고, 그다음은 다시 정차 상태로 본다
        assertEquals(StopReason.WAYPOINT_STOP, classify(back, previous = StopReason.DRIVER_BACK))
    }

    @Test
    fun `내비 도착 이벤트가 오면 도착이다`() {
        assertEquals(StopReason.ARRIVED, classify(StopInput(Motion.IN_DRIVE, navArrived = true)))
    }

    @Test
    fun `목적지 근처에서 P단이면 도착이고 주행 단이면 아니다`() {
        assertEquals(StopReason.ARRIVED, classify(StopInput(Motion.PARKED, nearDestination = true)))
        assertEquals(StopReason.NONE, classify(StopInput(Motion.IN_DRIVE, nearDestination = true)))
    }

    @Test
    fun `도착은 운전자 이탈보다 먼저 판단한다`() {
        val input = StopInput(
            Motion.PARKED,
            driverSeatOccupied = false,
            driverDoorOpenedSincePark = true,
            passengerSeatOccupied = true,
            nearDestination = true,
        )
        assertEquals(StopReason.ARRIVED, classify(input))
    }
}
