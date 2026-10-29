package com.jacobusoettle.satmap.location

import android.hardware.Sensor
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSensorManager

/** Android's trigger sensors (significant motion), which Robolectric doesn't shadow. Armed listeners are kept;
 *  `fire()` delivers one trigger to each and disarms it first, as Android does. A second request for an armed
 *  listener is refused, as Android refuses it. Call `reset()` before each test. Robolectric keeps one shadow per
 *  object, so this is the sensor manager's shadow too, `shadowOf(sensorManager)` working as before. */
@Implements(className = "android.hardware.SystemSensorManager", isInAndroidSdk = false)
class ShadowTriggerSensors : ShadowSensorManager() {
    @Implementation
    protected fun requestTriggerSensorImpl(listener: TriggerEventListener, sensor: Sensor): Boolean {
        if (armed.containsKey(listener)) return false
        armed[listener] = sensor
        return true
    }

    @Implementation
    protected fun cancelTriggerSensorImpl(listener: TriggerEventListener, sensor: Sensor?, disable: Boolean): Boolean =
        armed.remove(listener) != null

    companion object {
        /** The armed listeners and their sensors. */
        val armed = LinkedHashMap<TriggerEventListener, Sensor>()

        fun reset() {
            armed.clear()
        }

        /** The sensor triggers. `TriggerEvent`'s constructor is hidden. */
        fun fire() {
            for ((listener, sensor) in armed.entries.toList()) {
                armed.remove(listener)
                val event = TriggerEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .newInstance(1)
                event.sensor = sensor
                listener.onTrigger(event)
            }
        }
    }
}
