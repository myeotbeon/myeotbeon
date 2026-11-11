package app.myeotbeonideun.signal

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import app.myeotbeonideun.engine.model.Gear

/**
 * AAOS 표준 Car API 에서 얻는 값.
 *
 * - 주행 규제: CarUxRestrictionsManager (Pleos 주행 규제 개발 가이드가 정한 공식 경로)
 * - 변속단·속도: CarPropertyManager 의 GEAR_SELECTION, PERF_VEHICLE_SPEED
 *   (Pleos Vehicle SDK 에는 속도·변속단 API 가 없고, 문서는 이를 AAOS Car API 대상으로 분류한다)
 *
 * 이 클래스는 android.car 가 없는 기기에서는 만들 수 없다. 부르는 쪽에서 Throwable 을 잡는다.
 */
internal class CarApiBridge(
    private val context: Context,
    private val onUxRestricted: (Boolean) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onSpeedKph: (Float) -> Unit,
    private val note: (String) -> Unit,
) {
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null

    var hasUx = false
        private set
    var hasGear = false
        private set
    var hasSpeed = false
        private set

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r: CarUxRestrictions ->
        onUxRestricted(r.isRequiresDistractionOptimization)
    }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { onGear(gearOf(it)) }
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    (value.value as? Float)?.let { onSpeedKph(kotlin.math.abs(it) * 3.6f) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            note("차량 속성 $propertyId 읽기 오류")
        }
    }

    /**
     * Car 서비스에 붙고 주행 규제·변속단·속도를 등록한다. 주행 규제는 지금 값을 바로 읽어 넘긴다.
     * @return Car 서비스에 붙었으면 true. false 면 부르는 쪽이 나중에 다시 시도한다(부팅 직후 등)
     */
    fun start(): Boolean {
        val c = Car.createCar(context) ?: run {
            note("Car 서비스에 연결하지 못했습니다")
            return false
        }
        car = c

        (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.let { m ->
            ux = m
            m.registerListener(uxListener)
            m.currentCarUxRestrictions?.let { onUxRestricted(it.isRequiresDistractionOptimization) }
            hasUx = true
        }

        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return true
        properties = pm
        if (granted(Car.PERMISSION_POWERTRAIN) && pm.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) != null) {
            hasGear = pm.registerCallback(
                propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
            // 등록 콜백의 첫 값은 늦게 온다. 붙이는 순간 지금 변속단도 읽어 둔다(화면 밖 Gleo 호출이 주행 중인지 바로 알게).
            if (hasGear) runCatching { pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull()?.let { onGear(gearOf(it)) }
        } else {
            note("변속단(GEAR_SELECTION)을 읽을 수 없습니다 → 주행 규제 상태로 대신 판단")
        }
        // 속도는 위험 권한(CAR_SPEED)이다. 사용자가 허용한 경우에만 읽는다.
        if (granted(Car.PERMISSION_SPEED) && pm.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) != null) {
            hasSpeed = pm.registerCallback(
                propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL,
            )
        }
        return true
    }

    fun stop() {
        runCatching { ux?.unregisterListener() }
        runCatching { properties?.unregisterCallback(propertyCallback) }
        runCatching { car?.disconnect() }
        ux = null
        properties = null
        car = null
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun gearOf(value: Int): Gear = when (value) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE -> Gear.DRIVE
        else -> if (value > VehicleGear.GEAR_DRIVE) Gear.DRIVE else Gear.UNKNOWN // 1~9단은 주행
    }
}
