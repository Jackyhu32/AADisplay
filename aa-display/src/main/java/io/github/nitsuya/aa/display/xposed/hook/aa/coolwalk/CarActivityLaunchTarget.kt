package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.Intent
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** AA's projection controller, not an Android Service/Activity launch. */
internal class CarActivityLaunchTarget private constructor(
    val method: Method,
    private val controllerGetter: Method,
    private val optionsConstructor: Constructor<*>,
) {
    fun launch(intent: Intent) {
        // The singleton belongs to the current connection; never retain its instance.
        val controller = checkNotNull(controllerGetter.invoke(null)) {
            "AA projection controller is not ready"
        }
        method.invoke(controller, intent, optionsConstructor.newInstance())
    }

    companion object {
        const val START_LOG = "startCarActivity(intent:%s, activityOptions:%s)"

        @JvmStatic
        fun resolve(method: Method): CarActivityLaunchTarget {
            val parameters = method.parameterTypes
            require(!Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                parameters.size == 2 &&
                parameters[0] == Intent::class.java &&
                !parameters[1].isPrimitive
            ) { "Incompatible AA CarActivity start method: $method" }

            val controllerClass = method.declaringClass
            val getters = controllerClass.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == controllerClass
            }
            require(getters.size == 1) {
                "Expected one AA controller getter on ${controllerClass.name}, found ${getters.size}"
            }
            val optionsConstructor = parameters[1].getDeclaredConstructor()
            method.isAccessible = true
            val getter = getters.single().apply { isAccessible = true }
            optionsConstructor.isAccessible = true
            return CarActivityLaunchTarget(method, getter, optionsConstructor)
        }
    }
}
