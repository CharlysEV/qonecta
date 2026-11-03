package com.headqlink.link

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import java.time.Duration

/**
 * La ruta planificada en el móvil en el modo «Auto» (PlannedNotice): al conectar no se abre nada solo, sale la pregunta
 * en una notificación; «Ir con las paradas» abre Google Maps una vez y la da por usada, «No» la descarta, sin responder
 * se quita al desconectar (y la ruta sigue), y en «Auto extendido» no sale (pregunta la tarjeta del panel). Coordenadas
 * inventadas.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class)
class PlannedNoticeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        Str.init(app)
        PlannedTrip.clear(app)
        PlannedNotice.onCarLost(app)
    }

    @After
    fun tearDown() {
        PlannedNotice.onCarLost(app)
        PlannedTrip.clear(app)
    }

    private fun place(name: String, lat: Double, lon: Double) = RoutePlanner.Place().apply {
        this.name = name
        detail = ""
        this.lat = lat
        this.lon = lon
    }

    private fun save() = PlannedTrip.save(app, PlannedTrip().apply {
        dest = place("Destino", 63.7, 25.0)
        savedAtMs = System.currentTimeMillis()
        stops = mutableListOf(PlannedTrip.Stop().apply {
            name = "Cargador A"
            key = "dgt:a"
            lat = 62.08
            lon = 25.0
        })
        totalKm = 300.0
        route = mutableListOf(doubleArrayOf(62.08, 25.0, 120.0), doubleArrayOf(63.7, 25.0, 300.0))
    })

    private fun connect(mode: String) {
        PlannedNotice.onCarConnected(app, mode)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlannedNotice.AFTER_MS + 1_000))
    }

    private fun shown() = shadowOf(nm).getNotification(PlannedNotice.ID)

    @Test
    fun inAutoItAsksInANotificationAndOpensNothing() {
        save()
        PlannedNotice.onCarConnected(app, Config.MODE_AA)
        assertNull("aún no: espera a que Android Auto esté en el coche", shown())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlannedNotice.AFTER_MS + 1_000))
        val n = shown()
        assertNotNull(n)
        assertEquals("Use the route you planned?", shadowOf(n).contentTitle.toString())
        assertEquals("Destino · 1 charging stop", shadowOf(n).contentText.toString())
        assertEquals(listOf("Go with the stops", "No"), n.actions.map { it.title.toString() })
        assertNull("nada se abre solo", shadowOf(app).nextStartedActivity)
        assertNotNull("preguntar no la gasta", PlannedTrip.peek(app))
    }

    @Test
    fun goOpensMapsOnceAndUsesTheRoute() {
        save()
        connect(Config.MODE_AA)
        val go = shadowOf(shown().actions[0].actionIntent).savedIntent
        assertEquals(PlannedNotice.Go::class.java.name, go.component!!.className)
        Robolectric.buildActivity(PlannedNotice.Go::class.java, go).setup()
        val maps = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, maps.action)
        assertEquals(NavApps.MAPS, maps.`package`)
        assertTrue(maps.dataString!!.contains("waypoints="))
        assertNull("una vez", shadowOf(app).nextStartedActivity)
        assertNull("usada", PlannedTrip.peek(app))
        assertNull("y la pregunta, fuera", shown())
        // Pulsada otra vez (la notificación ya no está, pero por si acaso): nada.
        Robolectric.buildActivity(PlannedNotice.Go::class.java, go).setup()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun noDiscardsIt() {
        save()
        connect(Config.MODE_AA)
        val no = shadowOf(shown().actions[1].actionIntent).savedIntent
        assertEquals(PlannedNotice.No::class.java.name, no.component!!.className)
        PlannedNotice.No().onReceive(app, no)
        assertNull(PlannedTrip.peek(app))
        assertNull(shown())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun disconnectingRemovesTheQuestionButKeepsTheRoute() {
        save()
        connect(Config.MODE_AA)
        assertNotNull(shown())
        PlannedNotice.onCarLost(app)
        assertNull(shown())
        assertNotNull("sigue para el viaje siguiente", PlannedTrip.peek(app))
        // Desconectado antes de que saliera: ya no sale.
        PlannedNotice.onCarConnected(app, Config.MODE_AA)
        PlannedNotice.onCarLost(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PlannedNotice.AFTER_MS + 1_000))
        assertNull(shown())
    }

    @Test
    fun removingTheRouteOnThePhoneRemovesTheQuestion() {
        save()
        connect(Config.MODE_AA)
        PlannedTrip.clear(app)
        assertNull(shown())
    }

    @Test
    fun inExtendedAutoThereIsNoNotification() {
        save()
        connect(Config.MODE_AA_EXT)
        assertNull("pregunta la tarjeta del panel", shown())
        assertNull(shadowOf(app).nextStartedActivity)
        assertNotNull(PlannedTrip.peek(app))
    }

    @Test
    fun withoutARouteNothing() {
        connect(Config.MODE_AA)
        assertNull(shown())
    }
}
