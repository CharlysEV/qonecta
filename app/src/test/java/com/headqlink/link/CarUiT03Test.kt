package com.headqlink.link

import android.app.Application
import android.os.Looper
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * La interfaz del modo extendido en la pantalla del T03 (1024x498, escala 0,6): se crea y abre sus secciones sin fallar. El 22-10 no se veía en el T03 porque un texto de tamaño automático quedaba con mínimo = máximo (FloatCard).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CarUiT03Test {
    @After
    fun reset() {
        CarStyle.setUiScale(1f, false)
    }

    @Test fun theExtendedUiBuildsAndOpensItsSectionsOnTheT03Screen() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        val scale = UiScale.forCar(1024, 498, 0)
        assertEquals(0.6f, scale, 0f)
        val w = UiScale.canvas(1024, scale)
        val h = UiScale.canvas(498, scale)
        val ui = CarUi(context, w, h, 320, 200, scale) { }
        val build = CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }
        val open = CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        val root = build.invoke(ui, context) as View
        try {
            // Sin TV, radio, web ni casa: cargan en otro hilo (la TV, de internet) y acabarían en mitad de otras pruebas.
            for (name in listOf("car", "gauges", "efficiency", "settings")) {
                open.invoke(ui, name)
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
            }
        } finally {
            // stop() cierra en el hilo principal: sin dejarlo correr, el planificador de ruta que arranca Coche sigue
            // vivo y sus hilos caen en mitad de las pruebas siguientes.
            ui.stop()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
