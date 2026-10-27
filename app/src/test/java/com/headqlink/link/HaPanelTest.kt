package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** «Mi panel» de Home Assistant en el coche: la dirección del panel y la sesión con el token de la app. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class HaPanelTest {
    @Test
    fun theOriginIsWhatTheBrowserCallsIt() {
        assertEquals("https://casa.duckdns.org:8123", HomeAssistant.origin("https://casa.duckdns.org:8123"))
        // El puerto de siempre no se escribe (location.host no lo lleva): si no, la sesión no vale.
        assertEquals("https://abc.ui.nabu.casa", HomeAssistant.origin("https://abc.ui.nabu.casa:443"))
        assertEquals("http://192.168.1.10:8123", HomeAssistant.origin("http://192.168.1.10:8123/lovelace/0"))
    }

    @Test
    fun thePanelPath() {
        assertEquals("https://abc.ui.nabu.casa/", HomeAssistant.panelUrl("https://abc.ui.nabu.casa", ""))
        assertEquals("https://abc.ui.nabu.casa/dashboard-coche/0", HomeAssistant.panelUrl("https://abc.ui.nabu.casa", "/dashboard-coche/0/"))
        // Pegada la dirección entera desde el navegador: su ruta, sin «?edit=1».
        assertEquals("lovelace/coche", HomeAssistant.normalizePanel("https://abc.ui.nabu.casa/lovelace/coche?edit=1"))
        assertEquals("", HomeAssistant.normalizePanel("https://abc.ui.nabu.casa"))
        assertEquals("", HomeAssistant.normalizePanel(null))
    }

    @Test
    fun theSessionCarriesTheTokenForThatOrigin() {
        // El quote de Android escribe «/» como «\/» (en JavaScript, lo mismo): se compara sin eso.
        val js = HomeAssistant.sessionScript("https://abc.ui.nabu.casa", "eyJ\"raro'").replace("\\/", "/")
        assertTrue(js, js.contains("localStorage.setItem('hassTokens'"))
        assertTrue(js, js.contains("hassUrl:\"https://abc.ui.nabu.casa\""))
        assertTrue(js, js.contains("clientId:\"https://abc.ui.nabu.casa\"+'/'"))
        // El token va escapado (comillas incluidas): no rompe el guion.
        assertTrue(js, js.contains("access_token:\"eyJ\\\"raro'\""))
    }
}
