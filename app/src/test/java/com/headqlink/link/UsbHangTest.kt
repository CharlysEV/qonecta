package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** La app del coche colgada con el cable puesto (C10, 2026-10-25): cuándo se avisa y cuándo no. */
class UsbHangTest {
    private val k = UsbDiagnosis.Kind.CAR_HUNG
    private val none = UsbDiagnosis.Kind.NONE

    @Test
    fun theTripOfTheTwentyFifth() {
        val d = UsbDiagnosis()
        // S1: 16 min bien y el coche se calla (WATCHDOG) con el cable puesto: aún no se avisa.
        assertEquals(none, d.onSessionEnd(970_000, false, true, 970_000, 0, true))
        // S2, a los 6 s: el coche no manda nada. Colgada: aviso.
        assertEquals(k, d.onSessionEnd(986_000, false, false, 10_000, 1, true))
        // Las siguientes, igual: una vez.
        assertEquals(none, d.onSessionEnd(1_010_000, false, false, 10_000, 2, true))
        // Vuelve (CAR_INFO): si se cuelga otra vez en el viaje, se avisa otra vez.
        d.onCarInfo(1_100_000)
        assertEquals(none, d.onSessionEnd(2_000_000, false, true, 800_000, 0, true))
        assertEquals(k, d.onSessionEnd(2_016_000, false, false, 10_000, 1, true))
    }

    @Test
    fun noWarningWhenItComesBackOrWasUnplugged() {
        val d = UsbDiagnosis()
        // Silencio, pero la siguiente sesión trae CAR_INFO: nada.
        assertEquals(none, d.onSessionEnd(970_000, false, true, 970_000, 0, true))
        assertEquals(none, d.onSessionEnd(990_000, false, true, 300_000, 0, false))
        // Silencio y después el cable fuera (lo que lo arregla): la sesión fallida de después no avisa.
        val e = UsbDiagnosis()
        assertEquals(none, e.onSessionEnd(970_000, false, true, 970_000, 0, true))
        assertEquals(none, e.onSessionEnd(975_000, true, false, 2_000, 0, false))
        assertEquals(none, e.onSessionEnd(990_000, false, false, 10_000, 1, true))
        // Pasado HANG_WINDOW_MS, una sesión fallida ya no es de ese cuelgue.
        val f = UsbDiagnosis()
        assertEquals(none, f.onSessionEnd(970_000, false, true, 970_000, 0, true))
        assertEquals(none, f.onSessionEnd(970_000 + UsbDiagnosis.HANG_WINDOW_MS + 1, false, false, 10_000, 1, true))
        // Una sesión corta que acaba en silencio (no venía bien): tampoco.
        val g = UsbDiagnosis()
        assertEquals(none, g.onSessionEnd(30_000, false, true, 20_000, 0, true))
        assertEquals(none, g.onSessionEnd(46_000, false, false, 10_000, 1, true))
    }
}
