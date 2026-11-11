package app.myeotbeonideun.signal

import app.myeotbeonideun.engine.model.Gear
import app.myeotbeonideun.engine.model.VehicleState
import app.myeotbeonideun.ui.Screen
import app.myeotbeonideun.ui.ShellUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주행 중 화면 잠금 규칙(결함 6). 안드로이드 없이 돈다. */
class DrivingLockTest {
    private val parkedDemo = VehicleState(gear = Gear.PARK, uxRestricted = false)
    private val drivingCar = VehicleState(gear = Gear.DRIVE, uxRestricted = true)

    @Test
    fun `주행 규제 값이 있으면 그것을 따른다`() {
        assertTrue(DrivingLock.restricted(VehicleState(gear = Gear.PARK, uxRestricted = true)))
        assertFalse(DrivingLock.restricted(VehicleState(gear = Gear.DRIVE, uxRestricted = false)))
    }

    @Test
    fun `주행 규제 값이 없으면 변속단이 P 가 아닐 때 잠근다`() {
        assertTrue(DrivingLock.restricted(VehicleState(gear = Gear.DRIVE)))
        assertTrue(DrivingLock.restricted(VehicleState(gear = Gear.REVERSE)))
        assertTrue(DrivingLock.restricted(VehicleState(gear = Gear.NEUTRAL)))
        assertFalse(DrivingLock.restricted(VehicleState(gear = Gear.PARK)))
    }

    @Test
    fun `둘 다 모르면 잠그지 않는다`() {
        assertFalse(DrivingLock.restricted(VehicleState()))
    }

    @Test
    fun `체험 모드의 모의 P단이어도 실차가 달리면 잠근다`() {
        assertTrue(DrivingLock.screenRestricted(current = parkedDemo, vehicle = drivingCar))
        assertTrue(DrivingLock.screenRestricted(current = parkedDemo, vehicle = VehicleState(gear = Gear.DRIVE)))
        assertFalse(DrivingLock.screenRestricted(current = parkedDemo, vehicle = VehicleState(gear = Gear.PARK)))
        // 차량용 기기가 아니면 실차 값이 없다
        assertFalse(DrivingLock.screenRestricted(current = parkedDemo, vehicle = null))
        // 모의 주행(체험 모드 D)도 잠근다
        assertTrue(DrivingLock.screenRestricted(current = VehicleState(gear = Gear.DRIVE, uxRestricted = true), vehicle = null))
    }

    @Test
    fun `좌석 값 자동 판별은 P단에서만 한다`() {
        assertTrue(DrivingLock.parkedForCalibration(VehicleState(gear = Gear.PARK)))
        assertTrue(DrivingLock.parkedForCalibration(VehicleState(gear = Gear.PARK, uxRestricted = true)))
        assertFalse(DrivingLock.parkedForCalibration(VehicleState(gear = Gear.DRIVE, uxRestricted = false)))
        assertFalse(DrivingLock.parkedForCalibration(VehicleState(gear = Gear.REVERSE)))
        // 변속단을 모르면 주행 규제가 아닐 때만
        assertTrue(DrivingLock.parkedForCalibration(VehicleState(gear = Gear.UNKNOWN, uxRestricted = false)))
        assertTrue(DrivingLock.parkedForCalibration(VehicleState()))
        assertFalse(DrivingLock.parkedForCalibration(VehicleState(gear = Gear.UNKNOWN, uxRestricted = true)))
    }

    @Test
    fun `정차 전용 화면은 체험 모드여도 실차 주행 중에 잠긴다`() {
        for (screen in Screen.entries.filter { it.parkedOnly }) {
            assertTrue(screen.name, ShellUi(screen = screen, restricted = true, demo = true, vehicleRestricted = true).locked)
        }
        assertFalse(ShellUi(screen = Screen.COMPANION, restricted = true, demo = true, vehicleRestricted = true).locked)
        assertFalse(ShellUi(screen = Screen.CARD, restricted = true, vehicleRestricted = true).locked)
    }

    @Test
    fun `체험 모드의 개발자 패널은 모의 주행만으로는 잠기지 않는다`() {
        assertFalse(ShellUi(screen = Screen.DEV, restricted = true, demo = true, vehicleRestricted = false).locked)
        assertTrue(ShellUi(screen = Screen.SETTINGS, restricted = true, demo = true, vehicleRestricted = false).locked)
        // 차량 모드의 개발자 패널은 주행 중에 잠긴다
        assertTrue(ShellUi(screen = Screen.DEV, restricted = true, demo = false, vehicleRestricted = true).locked)
        assertFalse(ShellUi(screen = Screen.SETTINGS).locked)
    }
}
