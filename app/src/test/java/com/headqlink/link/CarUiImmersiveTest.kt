package com.headqlink.link

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.Duration

/**
 * Un juego a pantalla completa en la interfaz del coche (C10, 1920x882): sin panel ni barra de iconos y nuestra pantalla a
 * todo el ancho; con Android Auto delante no se hace caso, al salir el panel vuelve y abrir otra pantalla también lo quita.
 * Cerrar la sesión a pantalla completa no deja nada programado, y el aviso del plan de carga espera a que vuelva el panel.
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

    /** La interfaz en marcha (como tras start): la que recoge el panel y enseña los avisos. */
    private fun setCurrent(ui: CarUi?) =
        CarUi::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, ui)

    @Test fun closingTheSessionWithAGameInFullScreenLeavesNoTimerBehind() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        val ui = CarUi(context, 1920, 882, 320, 200) { }
        CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(ui, context)
        CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }.invoke(ui, "games")
        val uiHost: CarScreen.Host = field(ui, "host")
        // Panel en barra (lo normal pasados unos segundos) y un juego a pantalla completa que, al pararse, la quita (como
        // NesScreen.stopEngine): al apagar el coche o cortarse la conexión.
        CarUi::class.java.getDeclaredMethod("setHidden", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(ui, true)
        uiHost.setImmersive(true)
        val game = object : CarScreen {
            override fun create(host: CarScreen.Host) = View(context)
            override fun destroy() = uiHost.setImmersive(false)
        }
        CarUi::class.java.getDeclaredField("screen").apply { isAccessible = true }.set(ui, game)
        ui.stop()
        shadowOf(Looper.getMainLooper()).idle()
        // Nada vuelve a programarse en la interfaz cerrada: railTick (cada 30 s) la dejaba viva para siempre.
        val main: android.os.Handler = field(ui, "main")
        assertFalse(main.hasCallbacks(field<Runnable>(ui, "railTick")))
        assertFalse(main.hasCallbacks(field<Runnable>(ui, "hideTask")))
    }

    @Test fun theChargeAlertWaitsForThePanelWhileAGameIsInFullScreen() {
        val context = RuntimeEnvironment.getApplication()
        Str.init(context)
        val ui = CarUi(context, 1920, 882, 320, 200) { }
        CarUi::class.java.getDeclaredMethod("buildRoot", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(ui, context)
        val open = CarUi::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        val showAlert = CarUi::class.java.getDeclaredMethod("showAlert", String::class.java, String::class.java,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        val looper = shadowOf(Looper.getMainLooper())
        setCurrent(ui)
        try {
            open.invoke(ui, "games")
            val host: CarScreen.Host = field(ui, "host")
            val card: View = field(ui, "alertCard")
            // Aparcado, cargando y jugando a pantalla completa: llega «ya puedes seguir». No se ve.
            host.setImmersive(true)
            showAlert.invoke(ui, "Listo", "Ya puedes seguir", RoutePlanner.ALERT_READY)
            assertEquals(View.GONE, card.visibility)
            // Mucho rato jugando: ni se da por visto (20 s) ni caduca (10 min).
            looper.idleFor(Duration.ofMinutes(12))
            assertTrue(field<Boolean>(ui, "alertPending"))
            assertFalse(field<Boolean>(ui, "alertSeen"))
            // Al salir de la pantalla completa sale con el panel desplegado, y a los 20 s a la vista cuenta como visto.
            host.setImmersive(false)
            assertEquals(View.VISIBLE, card.visibility)
            assertFalse(field<Boolean>(ui, "hidden"))
            looper.idleFor(Duration.ofSeconds(21))
            assertTrue(field<Boolean>(ui, "alertSeen"))
            assertEquals(View.GONE, card.visibility)

            // Otro aviso a la vista, en ventana: entrar en la pantalla completa lo tapa sin darlo por visto, y al abrir
            // otra pantalla (sin salir antes) vuelve a salir.
            showAlert.invoke(ui, "Plan", "El plan de carga ha cambiado", RoutePlanner.ALERT_WARN)
            assertEquals(View.VISIBLE, card.visibility)
            host.setImmersive(true)
            assertEquals(View.GONE, card.visibility)
            looper.idleFor(Duration.ofMinutes(1))
            assertFalse(field<Boolean>(ui, "alertSeen"))
            open.invoke(ui, "settings")
            assertEquals(View.VISIBLE, card.visibility)
            looper.idleFor(Duration.ofSeconds(21))
            assertTrue(field<Boolean>(ui, "alertSeen"))
        } finally {
            ui.stop()
            looper.idle()
            setCurrent(null)
        }
    }
}
