package com.headqlink.link

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Un juego a pantalla completa en la interfaz del coche (C10, 1920x882): sin panel ni barra de iconos y nuestra pantalla a
 * todo el ancho; con Android Auto delante no se hace caso, al salir el panel vuelve y abrir otra pantalla también lo quita.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CarUiImmersiveTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(ui: CarUi, name: String): T =
        CarUi::class.java.getDeclaredField(name).apply { isAccessible = true }.get(ui) as T

    @Test fun aGameInFullScreenHidesThePanelAndUsesTheWholeWidth() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        val ui = CarUi(context, 1920, 882, 320, 200) { }
        val build = CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }
        val open = CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        build.invoke(ui, context)
        try {
            val host: CarScreen.Host = field(ui, "host")
            val panel: View = field(ui, "panel")
            val content: FrameLayout = field(ui, "content")
            fun lp() = content.layoutParams as FrameLayout.LayoutParams
            // Con Android Auto delante no hay pantalla completa de juego.
            open.invoke(ui, "aa")
            host.setImmersive(true)
            assertEquals(View.VISIBLE, panel.visibility)
            open.invoke(ui, "games")
            assertEquals(320, lp().leftMargin)
            assertEquals(1600, lp().width)
            host.setImmersive(true)
            assertEquals(View.GONE, panel.visibility)
            assertEquals(0, lp().leftMargin)
            assertEquals(1920, lp().width)
            assertEquals(1920, host.width())
            // Al salir, el panel vuelve como estaba.
            host.setImmersive(false)
            assertEquals(View.VISIBLE, panel.visibility)
            assertEquals(320, lp().leftMargin)
            assertEquals(1600, lp().width)
            // Abrir otra pantalla también la quita.
            host.setImmersive(true)
            open.invoke(ui, "settings")
            assertEquals(View.VISIBLE, panel.visibility)
            assertEquals(320, lp().leftMargin)
        } finally {
            // Como en CarUiT03Test: stop() cierra en el hilo principal; sin dejarlo correr, quedan hilos vivos.
            ui.stop()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
