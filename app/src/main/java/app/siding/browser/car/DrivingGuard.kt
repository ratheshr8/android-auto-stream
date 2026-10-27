package app.siding.browser.car

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Proxy

/**
 * Reads Android Automotive OS distraction restrictions when the platform provides them.
 * Phone builds and cars without the car service leave the flag clear. Playback is still
 * stopped from the activity lifecycle, which is what covers the driving lock on AAOS.
 */
class DrivingGuard(
    private val context: Context,
    private val onRestricted: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Any? = null
    private var manager: Any? = null
    private var listener: Any? = null

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            post(false)
            return
        }
        try {
            val carClass = Class.forName("android.car.Car")
            val created = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            car = created
            val serviceName = runCatching {
                carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
            }.getOrDefault("uxrestriction")
            val ux = carClass.getMethod("getCarManager", String::class.java).invoke(created, serviceName)
            manager = ux
            val listenerClass = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener",
            )
            val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
                if (method.declaringClass == Any::class.java) {
                    return@newProxyInstance when (method.name) {
                        "toString" -> "SidingUxListener"
                        "hashCode" -> System.identityHashCode(listener)
                        "equals" -> listener === args?.getOrNull(0)
                        else -> null
                    }
                }
                if (method.name == "onUxRestrictionsChanged") {
                    post(readRestricted(args?.getOrNull(0)))
                }
                null
            }
            listener = proxy
            ux.javaClass.getMethod("registerListener", listenerClass).invoke(ux, proxy)
            val current = ux.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(ux)
            post(readRestricted(current))
        } catch (_: Throwable) {
            post(false)
        }
    }

    fun stop() {
        val ux = manager
        val callback = listener
        if (ux != null && callback != null) {
            runCatching {
                val listenerClass = Class.forName(
                    "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener",
                )
                ux.javaClass.getMethod("unregisterListener", listenerClass).invoke(ux, callback)
            }
        }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        manager = null
        listener = null
    }

    private fun post(restricted: Boolean) {
        handler.post { onRestricted(restricted) }
    }

    private fun readRestricted(restrictions: Any?): Boolean {
        if (restrictions == null) return false
        return restrictions.javaClass
            .getMethod("isRequiresDistractionOptimization")
            .invoke(restrictions) as? Boolean ?: false
    }
}
