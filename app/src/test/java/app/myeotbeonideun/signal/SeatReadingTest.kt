package app.myeotbeonideun.signal

import app.myeotbeonideun.engine.CompanionEngine
import app.myeotbeonideun.engine.Speak
import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.SeatPosition
import app.myeotbeonideun.engine.model.SeatPosition.DRIVER
import app.myeotbeonideun.engine.model.SeatPosition.FRONT_PASSENGER
import app.myeotbeonideun.engine.model.SeatPosition.REAR_LEFT
import app.myeotbeonideun.engine.model.SeatPosition.REAR_RIGHT
import app.myeotbeonideun.engine.model.TripConfig
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.engine.script.ScriptBook
import app.myeotbeonideun.engine.stop.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 좌석 점유 값의 극성(2026-11-11 에뮬레이터에서 SDK Boolean 이 가상 콘솔과 반대로 옴). 안드로이드 없이 돈다.
 * 판별: 운전자가 화면을 조작하는 출발 전 설정(P단)과 동행 시작 순간에는 운전석에 사람이 있다.
 */
class SeatReadingTest {

    /** 에뮬레이터처럼: 사람이 앉은 자리(운전석, [occupied])가 false 로, 빈자리가 true 로 온다. */
    private fun SeatValueReader.emulatorLike(occupied: SeatPosition, now: Long = 0) {
        for (seat in SeatPosition.entries) setRaw(seat, !(seat == DRIVER || seat == occupied), now)
    }

    @Test
    fun `운전석 원시 값이 false 면 반대로, true 면 그대로, 없으면 정하지 않는다`() {
        assertEquals(SeatPolarity.INVERTED, SeatPolarityRule.fromDriverSeat(false))
        assertEquals(SeatPolarity.AS_IS, SeatPolarityRule.fromDriverSeat(true))
        assertNull(SeatPolarityRule.fromDriverSeat(null))
        val raw = mapOf(DRIVER to false, REAR_RIGHT to true)
        assertEquals(raw, SeatPolarityRule.apply(SeatPolarity.AS_IS, raw))
        assertEquals(mapOf(DRIVER to true, REAR_RIGHT to false), SeatPolarityRule.apply(SeatPolarity.INVERTED, raw))
    }

    @Test
    fun `자동은 운전석 값을 받기 전까지 그대로다`() {
        val r = SeatValueReader()
        assertEquals(SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.NO_DRIVER_VALUE), r.interpretation)
        r.driverAtScreen() // 운전석 값이 없으면 정하지 않는다
        assertEquals(SeatPolarityBasis.NO_DRIVER_VALUE, r.interpretation.basis)
        r.setRaw(REAR_RIGHT, true, 0)
        assertEquals(mapOf(REAR_RIGHT to true), r.seatOccupied)
    }

    @Test
    fun `출발 전 설정에서 운전석 기준으로 반대로 정하면 해석한 값으로 앉은 자리를 고른다`() {
        val r = SeatValueReader()
        r.emulatorLike(occupied = REAR_LEFT)
        // 해석 전에는 빈자리(앞자리 옆, 뒷좌석 오른쪽)가 앉은 자리로 보인다
        assertEquals(listOf(REAR_RIGHT, FRONT_PASSENGER), VehicleState(seatOccupied = r.seatOccupied).occupiedPassengerSeats())

        r.driverAtScreen()
        assertEquals(SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.DRIVER_SEAT), r.interpretation)
        val state = VehicleState(seatOccupied = r.seatOccupied)
        assertEquals(listOf(REAR_LEFT), state.occupiedPassengerSeats())
        assertEquals(true, state.forSeat(REAR_LEFT).driverSeatOccupied)
        assertEquals(true, state.forSeat(REAR_LEFT).seatOccupied)
        // 원시 값은 그대로 남는다(개발자 패널)
        assertEquals(false, r.raw[DRIVER])
    }

    @Test
    fun `그대로 오는 차에서는 그대로 읽는다`() {
        val r = SeatValueReader()
        r.setRaw(DRIVER, true, 0)
        r.setRaw(REAR_RIGHT, true, 0)
        r.setRaw(REAR_LEFT, false, 0)
        r.driverAtScreen()
        assertEquals(SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.DRIVER_SEAT), r.interpretation)
        assertEquals(listOf(REAR_RIGHT), VehicleState(seatOccupied = r.seatOccupied).occupiedPassengerSeats())
    }

    @Test
    fun `동행 중에는 운전석 값이 바뀌어도 판별을 바꾸지 않는다`() {
        val r = SeatValueReader()
        r.emulatorLike(occupied = REAR_RIGHT)
        r.holdForTrip(nowMs = 0)
        assertEquals(SeatPolarity.INVERTED, r.interpretation.polarity)
        assertTrue(r.interpretation.heldForTrip)

        // 운전자가 내렸다: 원시 값이 true(빈자리)로 바뀐다. 출발 전 설정 화면을 열어도 동행 중이라 다시 정하지 않는다.
        r.setRaw(DRIVER, true, 60_000)
        r.driverAtScreen()
        assertEquals(SeatPolarity.INVERTED, r.interpretation.polarity)
        assertEquals("내린 운전자는 빈자리로 읽는다", false, r.seatOccupied[DRIVER])

        r.releaseTrip()
        assertFalse(r.interpretation.heldForTrip)
        r.setRaw(DRIVER, false, 120_000) // 돌아와 앉았다
        r.driverAtScreen()
        assertEquals(SeatPolarity.INVERTED, r.interpretation.polarity)
    }

    @Test
    fun `동행 시작 순간에 운전석 값이 없으면 잠깐 안에 처음 오는 값으로 정한다`() {
        val r = SeatValueReader()
        r.holdForTrip(nowMs = 1_000) // 화면 밖(Gleo)에서 시작해 신호를 막 붙였다
        assertEquals(SeatPolarityBasis.NO_DRIVER_VALUE, r.interpretation.basis)
        r.setRaw(REAR_RIGHT, false, 1_500)
        assertEquals(SeatPolarityBasis.NO_DRIVER_VALUE, r.interpretation.basis)
        r.setRaw(DRIVER, false, 3_000)
        assertEquals(SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.DRIVER_SEAT, heldForTrip = true), r.interpretation)
        r.setRaw(DRIVER, true, 4_000) // 그 뒤로는 동행이 끝날 때까지 바꾸지 않는다
        assertEquals(SeatPolarity.INVERTED, r.interpretation.polarity)
    }

    @Test
    fun `유예 시간이 지나 들어온 운전석 값으로는 정하지 않는다`() {
        val r = SeatValueReader()
        r.holdForTrip(nowMs = 0)
        r.setRaw(DRIVER, false, SeatValueReader.TRIP_START_GRACE_MS + 1)
        assertEquals(SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.NO_DRIVER_VALUE, heldForTrip = true), r.interpretation)
    }

    @Test
    fun `직접 고르면 바로 그 해석을 쓰고 자동으로 돌아오면 운전석 기준 판별을 쓴다`() {
        val r = SeatValueReader()
        r.emulatorLike(occupied = REAR_RIGHT)
        r.driverAtScreen()
        r.setReading(SeatReading.AS_IS)
        assertEquals(SeatInterpretation(SeatPolarity.AS_IS, SeatPolarityBasis.MANUAL), r.interpretation)
        assertEquals(r.raw, r.seatOccupied)
        r.setReading(SeatReading.INVERTED)
        assertEquals(SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.MANUAL), r.interpretation)
        r.setReading(SeatReading.AUTO)
        assertEquals(SeatInterpretation(SeatPolarity.INVERTED, SeatPolarityBasis.DRIVER_SEAT), r.interpretation)
    }

    @Test
    fun `구독을 놓으면 원시 값만 비우고 판별은 남긴다`() {
        val r = SeatValueReader()
        r.emulatorLike(occupied = REAR_RIGHT)
        r.driverAtScreen()
        r.clearRaw()
        assertTrue(r.raw.isEmpty())
        assertTrue(r.seatOccupied.isEmpty())
        assertEquals(SeatPolarity.INVERTED, r.interpretation.polarity)
    }

    @Test
    fun `해석한 값으로 엔진이 운전자 이탈을 안다`() {
        val script = ScriptBook.parse(File("src/main/assets/scripts_ko.json").readText(Charsets.UTF_8))
        val config = TripConfig(place = "큰딸 집", seat = REAR_RIGHT)
        val engine = CompanionEngine(script, config)
        val reader = SeatValueReader()
        var now = 0L
        reader.emulatorLike(occupied = REAR_RIGHT, now = now)
        reader.holdForTrip(now) // 동행 시작: 운전석에 사람이 있다 → 반대로

        var vehicle = VehicleState(gear = Gear.PARK, uxRestricted = false, doorOpen = SeatPosition.entries.associateWith { false })
        val spoken = ArrayList<Speak>()
        fun push() {
            spoken += engine.onSignals(vehicle.copy(seatOccupied = reader.seatOccupied).forSeat(config.seat), now).filterIsInstance<Speak>()
        }
        fun tick(seconds: Int) = repeat(seconds) {
            now += 1_000
            spoken += engine.onTick(now).filterIsInstance<Speak>()
        }
        engine.start(now)
        push()
        vehicle = vehicle.copy(gear = Gear.DRIVE, uxRestricted = true)
        push()
        tick(30)
        vehicle = vehicle.copy(gear = Gear.PARK, uxRestricted = false)
        push()
        tick(2)
        spoken.clear()

        // 운전자가 문을 열고 내린다. SDK 원시 값은 true 로 바뀐다(에뮬레이터 극성에서는 빈자리).
        vehicle = vehicle.copy(doorOpen = vehicle.doorOpen + (DRIVER to true))
        push()
        reader.setRaw(DRIVER, true, now)
        push()
        tick(3)
        assertEquals("driver_away", spoken.single().situation)
        assertEquals(StopReason.DRIVER_AWAY, engine.snapshot(now).stopReason)

        // 해석 없이 원시 값을 그대로 쓰면 운전자가 앉아 있고 동승자는 없는 것으로 보인다(이탈을 알 수 없다)
        val asIs = VehicleState(seatOccupied = reader.raw).forSeat(config.seat)
        assertEquals(true, asIs.driverSeatOccupied)
        assertEquals(false, asIs.seatOccupied)
    }
}
