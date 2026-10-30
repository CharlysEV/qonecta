package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** «km desde la última carga» (LPMFF sayaz #238): qué lectura cuenta como carga, la marca y los km desde ella. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class LastChargeTest {
    private fun status(signal: String) = LeapStatus.parse(JSONObject("""{"signal":$signal}"""))

    @Test
    fun chargingOrFinishedWithTheCableInCountsAsACharge() {
        assertTrue(LastCharge.charged(status("""{"1149":1,"47":1,"3736":0,"1318":12480}""")))
        // Ya cargado y aún enchufado (se vio después de cargar).
        assertTrue(LastCharge.charged(status("""{"1149":1,"47":1,"3736":1,"1318":12480}""")))
        // Sin enchufar no cuenta, ni con chargeState puesto (la regeneración también lo pone).
        assertFalse(LastCharge.charged(status("""{"1149":1,"47":0,"1197":0,"3736":0,"1318":12480}""")))
        assertFalse(LastCharge.charged(status("""{"1149":0,"47":0,"1197":0,"1318":12480}""")))
        assertFalse(LastCharge.charged(null))
    }

    @Test
    fun kmSinceTheLastCharge() {
        val m = LastCharge.next(null, true, 12480.0, 1_000L)!!
        assertEquals(143.0, LastCharge.kmSince(m, 12623.0), 0.0)
        assertEquals(0.0, LastCharge.kmSince(m, 12480.0), 0.0)
        // Sin marca, sin km, o el cuentakilómetros hacia atrás (otro coche): nada.
        assertTrue(LastCharge.kmSince(null, 12623.0).isNaN())
        assertTrue(LastCharge.kmSince(m, Double.NaN).isNaN())
        assertTrue(LastCharge.kmSince(m, 9000.0).isNaN())
    }

    @Test
    fun onlyANewerChargeReadingMovesTheMark() {
        val m = LastCharge.next(null, true, 12480.0, 5_000L)!!
        // Conduciendo, sin km o con un dato más viejo: la de antes.
        assertSame(m, LastCharge.next(m, false, 12600.0, 9_000L))
        assertSame(m, LastCharge.next(m, true, Double.NaN, 9_000L))
        assertSame(m, LastCharge.next(m, true, 12470.0, 4_000L))
        // Otra carga más tarde: la nueva.
        val n = LastCharge.next(m, true, 12700.0, 9_000L)!!
        assertEquals(12700.0, n.odoKm, 0.0)
        assertEquals(9_000L, n.atMs)
    }

    @Test
    fun theMarkIsKeptOnThePhone() {
        val ctx = RuntimeEnvironment.getApplication()
        assertNull(LastCharge.get(ctx))
        LastCharge.observe(ctx, status("""{"1149":1,"1197":1,"3736":0,"1318":12480}"""), 1_700_000_000_000L)
        // Al irse, la lectura de después no la cambia.
        LastCharge.observe(ctx, status("""{"1149":0,"47":0,"1197":0,"1318":12560}"""), 1_700_000_900_000L)
        val m = LastCharge.get(ctx)!!
        assertEquals(12480.0, m.odoKm, 0.0)
        assertEquals(1_700_000_000_000L, m.atMs)
    }
}
