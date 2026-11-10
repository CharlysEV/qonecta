package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Paso solo del cable a Wi-Fi Direct cuando el puerto USB del coche se cuelga a mitad de viaje (CableFallback): el log
 * real del 01-11, el puerto que no vuelve, los intentos mudos y todo lo que no debe hacerlo pasar. Y el del T03 del 06-11
 * al 10-11 (el cable no aguanta nunca): el paso con un solo intento mudo, sin bucles por el cable ni «intento 3 de 2».
 */
class CableFallbackTest {
    private val on = CableFallback.Env(true, true, CableFallback.Bt.CONNECTED)

    private fun end(sid: Int, carInfo: Boolean, livedMs: Long, kind: String, stallMs: Long = 0, gone: Boolean = false) =
        CableFallback.End(sid, carInfo, livedMs, kind, stallMs, gone)

    /** Una hora bien por el cable y el coche deja de leer: S1 cerrada por el WATCHDOG con «el coche no lee» 8,8 s. */
    private fun hungAfterAnHour(f: CableFallback, now: Long = 0L, env: CableFallback.Env = on) =
        f.onSessionEnd(now, end(1, true, 3_869_000, "WATCHDOG", 8_753), env)

    /** Atasco y luego dos intentos mudos (sin CAR_INFO) por el cable. */
    private fun hungAndTwoMute(f: CableFallback, env: CableFallback.Env): CableFallback.Decision {
        hungAfterAnHour(f, 0, env)
        f.onAccessoryBack(6_000)
        assertFalse(f.onSessionEnd(18_000, end(2, false, 12_000, "WATCHDOG"), env).toWifi())
        f.onAccessoryBack(24_000)
        return f.onSessionEnd(36_000, end(3, false, 12_000, "WATCHDOG"), env)
    }

    @Test
    fun theLogOfThe1stOfNovemberEndsOnWifiDirect() {
        val f = CableFallback()
        // 11:03:57 S1 cerrada por el WATCHDOG tras 64 min: se abre el atasco (aún nada más).
        val s1 = hungAfterAnHour(f)
        assertFalse(s1.toWifi())
        assertTrue(s1.toString(), s1.reasons().single().contains("atasco del cable"))
        // 11:03:59 el coche reinicia su puerto: el accesorio se va (10 s para que vuelva)...
        assertTrue(f.onAccessoryGone(2_000).reasons().single().contains("si no vuelve en 10 s"))
        assertEquals(12_000L, f.timerAtMs())
        // ... y vuelve a los 3 s (S2 abierta): ya no cuentan los 10 s, sino los intentos (a los 5 s se mira si sigue muda).
        val back = f.onAccessoryBack(5_000).reasons().single()
        assertTrue(back, back.contains("ha vuelto en 3 s") && back.contains("2 fallidos"))
        assertEquals(5_000L + CableFallback.MUTE_ATTEMPT_MS, f.timerAtMs())
        // El coche ha contestado (CAR_INFO): no es un intento mudo.
        assertTrue(f.timer(10_000, on).reasons().isEmpty())
        assertEquals(-1L, f.timerAtMs())
        // S2: CAR_INFO, pero el coche no lee el primer IDR y el write se queda 10 s bloqueado: fallida.
        val s2 = f.onSessionEnd(16_000, end(2, true, 11_000, "WRITE_STALL", 9_792), on)
        assertFalse(s2.toWifi())
        assertTrue(s2.toString(), s2.reasons().single().contains("intento 1 de 2 fallido tras el atasco (se atascó otra vez)"))
        // S3: muda (ni un mensaje del coche): segundo intento fallido → Wi-Fi Direct, ~32 s tras el atasco.
        f.onAccessoryBack(22_000)
        val s3 = f.onSessionEnd(32_000, end(3, false, 10_000, "WATCHDOG", 9_600), on)
        assertTrue(s3.toString(), s3.toWifi())
        assertTrue(s3.toString(), s3.reasons().last().contains("sigo por Wi-Fi Direct hasta el final del viaje"))
        assertTrue(f.active())
        // Temporal: el cable elegido va por Wi-Fi Direct; lo que no es el cable no cambia.
        assertEquals(Config.LINK_P2P, f.linkFor(Config.LINK_USB))
        assertEquals(Config.LINK_HOTSPOT, f.linkFor(Config.LINK_HOTSPOT))
        // Ya en Wi-Fi Direct, otra sesión por el cable (no debería haberla) no decide nada.
        assertTrue(f.onSessionEnd(40_000, end(4, false, 10_000, "WATCHDOG"), on).reasons().isEmpty())
    }

    @Test
    fun aPortThatDoesNotComeBackIn10sSwitches() {
        val f = CableFallback()
        hungAfterAnHour(f)
        f.onAccessoryGone(2_000)
        assertFalse(f.timer(11_999, on).toWifi())
        val d = f.timer(12_000, on)
        assertTrue(d.toString(), d.toWifi())
        assertTrue(d.toString(), d.reasons().single().contains("el accesorio no ha vuelto en 10 s"))
        assertTrue(f.active())
        assertEquals(-1L, f.timerAtMs())
    }

    @Test
    fun theCarCuttingThePortBeforeTheSessionClosesCountsFromTheCut() {
        // El coche corta el puerto con el atasco en curso: llega antes «accesorio fuera» que el fin de la sesión.
        val f = CableFallback()
        f.onAccessoryGone(0)
        assertEquals(-1L, f.timerAtMs())
        val d = f.onSessionEnd(300, end(1, true, 1_800_000, "LOCAL", 5_000), on)
        assertTrue(d.toString(), d.reasons().single().contains("atasco del cable"))
        assertEquals(10_000L, f.timerAtMs())
        assertTrue(f.timer(10_000, on).toWifi())
    }

    @Test
    fun aStallEndingInEioIsAHangNotAnUnplug() {
        // Como la S8 del 01-11: 58 s con vídeo, «el coche no lee» 7,3 s y luego EIO (el coche cortó el puerto).
        val f = CableFallback()
        assertTrue(f.onSessionEnd(0, end(8, true, 58_300, "WRITE_ERROR", 7_296, gone = true), on).reasons().isNotEmpty())
        f.onAccessoryGone(1_000)
        assertTrue(f.timer(11_000, on).toWifi())
    }

    @Test
    fun hungAndTwoMuteAttemptsSwitch() {
        val f = CableFallback()
        val d = hungAndTwoMute(f, on)
        assertTrue(d.toString(), d.toWifi())
        assertTrue(d.toString(), d.reasons().any { it.contains("2 intentos fallidos por el cable tras el atasco") })
    }

    @Test
    fun threeMuteSessionsAfterVideoAlsoSwitch() {
        // Sin atasco «no lee» (p. ej. tras un corte de corriente): la primera sesión muda con el cable puesto lo abre.
        val f = CableFallback()
        f.onSessionEnd(0, end(1, true, 1_200_000, "READ_ERROR", gone = true), on)
        assertFalse(f.onSessionEnd(30_000, end(2, false, 12_000, "WATCHDOG"), on).toWifi())
        assertFalse(f.onSessionEnd(48_000, end(3, false, 12_000, "WATCHDOG"), on).toWifi())
        assertTrue(f.onSessionEnd(66_000, end(4, false, 12_000, "WATCHDOG"), on).toWifi())
    }

    @Test
    fun unplugWithoutAStallDoesNothing() {
        // Quitar el cable a propósito: EIO (o sin corriente) en el acto, sin atasco antes; se sigue esperando como siempre.
        val f = CableFallback()
        assertTrue(f.onSessionEnd(0, end(1, true, 1_800_000, "WRITE_ERROR", gone = true), on).reasons().isEmpty())
        assertTrue(f.onAccessoryGone(500).reasons().isEmpty())
        assertEquals(-1L, f.timerAtMs())
        assertTrue(f.timer(60_000, on).reasons().isEmpty())
        // Lo mismo con un atasco cortito justo al quitarlo, o al quedarse sin corriente.
        val g = CableFallback()
        assertTrue(g.onSessionEnd(0, end(1, true, 1_800_000, "READ_ERROR", 1_200, gone = true), on).reasons().isEmpty())
        assertTrue(g.onSessionEnd(5_000, end(2, true, 900_000, "LOCAL"), on).reasons().isEmpty())
        g.onAccessoryGone(6_000)
        assertEquals(-1L, g.timerAtMs())
        assertFalse(g.active())
    }

    @Test
    fun withoutTheCarBluetoothNothing() {
        val gone = CableFallback.Env(true, true, CableFallback.Bt.GONE)
        val f = CableFallback()
        val d = hungAndTwoMute(f, gone)
        assertFalse(d.toWifi())
        assertTrue(d.toString(), d.reasons().last().contains("el Bluetooth del coche no está conectado"))
        assertFalse(f.active())
        // Si Android no lo dice, tampoco: no se sabe si el coche sigue encendido.
        val unknown = CableFallback.Env(true, true, CableFallback.Bt.UNKNOWN)
        val g = CableFallback()
        hungAfterAnHour(g, 0, unknown)
        g.onAccessoryGone(1_000)
        val t = g.timer(11_000, unknown)
        assertFalse(t.toWifi())
        assertTrue(t.toString(), t.reasons().last().contains("no se sabe si el Bluetooth del coche sigue"))
    }

    @Test
    fun withoutVideoInTheTripTwoMoreMuteAttemptsSwitch() {
        // Viaje que empieza con el QDLink del coche ya colgado (S4-S11 del 10-11: 8 sesiones mudas de 10 s sin una línea
        // en el log). Ahora la primera abre el atasco; como al empezar el coche a veces tarda en contestar, hacen falta 2 más.
        val f = CableFallback()
        val s1 = f.onSessionEnd(10_000, end(1, false, 10_000, "WRITE_STALL", 9_600), on)
        assertTrue(s1.toString(), s1.reasons().single().contains("fallan los 2 intentos siguientes"))
        assertEquals(2, f.needed())
        val s2 = f.onSessionEnd(26_000, end(2, false, 10_000, "WATCHDOG", 9_600), on)
        assertFalse(s2.toWifi())
        assertTrue(s2.toString(), s2.reasons().single().contains("intento 1 de 2 fallido"))
        assertTrue(f.onSessionEnd(42_000, end(3, false, 10_000, "WATCHDOG", 9_600), on).toWifi())
        // Sin vídeo, lo que no es un atasco (un cable quitado, un saludo que no llega y se cierra aquí) no abre nada.
        val g = CableFallback()
        assertTrue(g.onSessionEnd(0, end(1, false, 12_000, "HANDSHAKE"), on).reasons().isEmpty())
        assertTrue(g.onSessionEnd(20_000, end(2, false, 300, "WRITE_ERROR", gone = true), on).reasons().isEmpty())
        assertFalse(g.cableHung(20_000))
    }

    @Test
    fun aCableThatNeverLastedSwitchesAfterOneMuteAttempt() {
        // T03, 10-11 00:59: S1 con vídeo y «el coche no lee» a los 0,5 s (WRITE_STALL a los 11 s), S2 muda. Antes, S3 también
        // (43 s); ahora S2 cuenta a los 5 s de abrirse: 11 + 6 + 5 = 22 s.
        val f = CableFallback()
        val s1 = f.onSessionEnd(11_000, end(1, true, 11_000, "WRITE_STALL", 10_000), on)
        assertTrue(s1.toString(), s1.reasons().single().contains("falla el intento siguiente"))
        assertEquals(1, f.needed())
        assertTrue(f.onAccessoryBack(17_000).reasons().isEmpty())
        assertEquals(22_000L, f.timerAtMs())
        val mute = CableFallback.Env(true, true, CableFallback.Bt.CONNECTED, true, 5_000)
        val d = f.timer(22_000, mute)
        assertTrue(d.toString(), d.toWifi())
        assertFalse(d.toChosen())
        assertTrue(d.toString(), d.reasons().first().contains("5 s sin un mensaje del coche: intento 1 de 1 fallido"))
        assertTrue(d.toString(), d.reasons().last().contains("1 intento fallido por el cable tras el atasco"))
        assertTrue(f.active())
        // Lo mismo si se llega al final de la sesión muda (el temporizador no llegó a mirar).
        val g = CableFallback()
        g.onSessionEnd(11_000, end(1, true, 11_000, "WATCHDOG", 9_100), on)
        g.onAccessoryBack(17_000)
        assertTrue(g.onSessionEnd(27_000, end(2, false, 10_000, "WATCHDOG", 9_600), on).toWifi())
    }

    @Test
    fun theMuteCheckWaitsForTheSessionAndCountsOnce() {
        val f = CableFallback()
        hungAfterAnHour(f)
        f.onAccessoryBack(6_000)
        // El temporizador se adelantó a la sesión (lleva 4,8 s muda): se vuelve a mirar cuando lleve los 5 s.
        val early = CableFallback.Env(true, true, CableFallback.Bt.CONNECTED, true, 4_800)
        assertTrue(f.timer(11_000, early).reasons().isEmpty())
        assertEquals(11_500L, f.timerAtMs())
        val mute = CableFallback.Env(true, true, CableFallback.Bt.CONNECTED, true, 5_300)
        val d = f.timer(11_500, mute)
        assertFalse(d.toWifi())
        assertTrue(d.toString(), d.reasons().single().contains("intento 1 de 2 fallido"))
        // El final de esa misma sesión no vuelve a contar.
        assertTrue(f.onSessionEnd(16_000, end(2, false, 10_000, "WATCHDOG", 9_600), on).reasons().isEmpty())
        f.onAccessoryBack(22_000)
        assertTrue(f.timer(27_000, mute).toWifi())
    }

    @Test
    fun neverAttemptThreeOfTwoAndTheSwitchWaitsForTheBluetooth() {
        // 09-11 13:35: el viaje arranca por el cable antes de que el coche conecte su Bluetooth. Antes: «intento 3 de 2»…
        // «7 de 2», el motivo repetido en cada intento y el paso solo al acabar un intento (cada vez más espaciados).
        val noBt = CableFallback.Env(true, true, CableFallback.Bt.GONE)
        val f = CableFallback()
        val all = ArrayList<String>()
        all += f.onSessionEnd(11_000, end(57, true, 11_000, "WATCHDOG", 9_100), noBt).reasons()
        var t = 11_000L
        for (sid in 58..62) {
            t += 6_000
            f.onAccessoryBack(t)
            t += 10_000
            val d = f.onSessionEnd(t, end(sid, false, 10_000, "WATCHDOG", 9_600), noBt)
            assertFalse(d.toWifi())
            all += d.reasons()
        }
        val attempt = Regex("intento (\\d+) de (\\d+)")
        for (r in all) attempt.findAll(r).forEach { assertTrue(r, it.groupValues[1].toInt() <= it.groupValues[2].toInt()) }
        // Lo que lo impide se dice una vez, claro, y no en cada intento.
        assertEquals(all.toString(), 1, all.count { it.contains("el Bluetooth del coche no está conectado") })
        assertTrue(all.toString(), all.any { it.contains("lo vuelvo a mirar cada 5 s") })
        assertTrue(all.toString(), all.last().contains("otro intento fallido tras el atasco (van 5"))
        assertFalse(f.active())
        // Entre intentos se vuelve a mirar cada 5 s: sin Bluetooth, nada (ni una línea más)...
        assertEquals(t + CableFallback.RECHECK_MS, f.timerAtMs())
        val still = f.timer(t + 5_000, noBt)
        assertFalse(still.toWifi())
        assertTrue(still.reasons().isEmpty())
        assertEquals(t + 10_000, f.timerAtMs())
        // ... y en cuanto el coche conecta su Bluetooth, se pasa, sin esperar al intento siguiente.
        val d = f.timer(t + 10_000, on)
        assertTrue(d.toString(), d.toWifi())
        assertTrue(f.active())
    }

    @Test
    fun wifiOffIsSaidOnceAndTheSwitchHappensWhenItIsBack() {
        val wifiOff = CableFallback.Env(true, true, CableFallback.Bt.CONNECTED, false, -1)
        val f = CableFallback()
        f.onSessionEnd(11_000, end(1, true, 11_000, "WRITE_STALL", 10_000), wifiOff)
        f.onAccessoryBack(17_000)
        val d = f.onSessionEnd(27_000, end(2, false, 10_000, "WATCHDOG", 9_600), wifiOff)
        assertFalse(d.toWifi())
        assertTrue(d.wifiOff())
        assertTrue(d.toString(), d.reasons().last().contains("el Wi-Fi del móvil está apagado"))
        assertFalse(f.handlesHang(27_000, wifiOff))
        // Sigue apagado: ni otro aviso ni otra línea.
        val again = f.timer(32_000, wifiOff)
        assertFalse(again.wifiOff())
        assertTrue(again.reasons().isEmpty())
        // Con una sesión por cable abierta no se cambia a mitad: se espera a su final.
        f.onAccessoryBack(33_000)
        assertFalse(f.timer(38_000, on).toWifi())
        assertTrue(f.onSessionEnd(43_000, end(3, false, 10_000, "WATCHDOG", 9_600), on).toWifi())
    }

    @Test
    fun theCableAheadOfAChosenWifiGoesBackToIt() {
        // 07-11 12:40 (zona Wi-Fi elegida) y 09-11 14:14 (Wi-Fi Direct elegido), con el móvil enchufado al coche: el cable
        // pasaba por delante y se quedaba en bucle («la conexión elegida no es el cable», 18 sesiones). Ahora se vuelve a la
        // Wi-Fi elegida, sin mirar el Bluetooth del coche.
        val wifiChosen = CableFallback.Env(true, false, CableFallback.Bt.GONE)
        val f = CableFallback()
        f.onSessionEnd(11_000, end(1, true, 11_000, "WRITE_STALL", 9_900), wifiChosen)
        assertTrue(f.handlesHang(12_000, wifiChosen))
        f.onAccessoryBack(17_000)
        val d = f.onSessionEnd(27_000, end(2, false, 10_000, "WATCHDOG", 9_600), wifiChosen)
        assertTrue(d.toString(), d.toWifi())
        assertTrue(d.toChosen())
        assertTrue(d.toString(), d.reasons().last().contains("vuelvo a ella hasta el final del viaje"))
        // No es «Wi-Fi Direct en lugar del cable»: el enlace es la Wi-Fi elegida, y el accesorio ya no pasa por delante.
        assertFalse(f.active())
        assertEquals(Config.LINK_HOTSPOT, f.linkFor(Config.LINK_HOTSPOT))
        assertTrue(f.holdsUsb())
        assertFalse(f.handlesHang(28_000, wifiChosen))
        assertTrue(f.onSessionEnd(40_000, end(3, false, 10_000, "WATCHDOG"), wifiChosen).reasons().isEmpty())
        // «Cable USB» a mano lo suelta, y manda lo elegido.
        assertTrue(f.onManualChoice(50_000, true))
        assertFalse(f.holdsUsb())
        // Sin vídeo en el viaje (S85-S93 del 09-11), con dos intentos más.
        val g = CableFallback()
        g.onSessionEnd(10_000, end(85, false, 10_000, "WATCHDOG", 9_600), wifiChosen)
        assertFalse(g.onSessionEnd(26_000, end(86, false, 10_000, "WRITE_STALL", 9_600), wifiChosen).toWifi())
        assertTrue(g.onSessionEnd(42_000, end(87, false, 10_000, "WRITE_STALL", 9_600), wifiChosen).toChosen())
    }

    @Test
    fun aCarThatRecoversOnItsOwnStaysOnTheCable() {
        // El C10 que se recupera solo: tras el atasco, un intento mudo y luego una sesión que aguanta. Como siempre.
        val f = CableFallback()
        hungAfterAnHour(f)
        assertEquals(2, f.needed())
        f.onAccessoryBack(6_000)
        val mute = CableFallback.Env(true, true, CableFallback.Bt.CONNECTED, true, 5_000)
        val one = f.timer(11_000, mute)
        assertFalse(one.toWifi())
        assertTrue(one.toString(), one.reasons().single().contains("intento 1 de 2 fallido"))
        assertFalse(f.onSessionEnd(16_000, end(2, false, 10_000, "WATCHDOG", 9_600), on).toWifi())
        // El siguiente contesta (no está mudo a los 5 s) y aguanta 5 min: el atasco se olvida.
        f.onAccessoryBack(22_000)
        assertTrue(f.timer(27_000, on).reasons().isEmpty())
        val ok = f.onSessionEnd(322_000, end(3, true, 300_000, "WRITE_ERROR", gone = true), on)
        assertFalse(ok.toWifi())
        assertTrue(ok.toString(), ok.reasons().single().contains("se ha recuperado"))
        assertFalse(f.cableHung(322_000))
        assertFalse(f.active())
    }

    @Test
    fun aTripWithoutTheCableFromTheStart() {
        // El cable no funciona con este coche (CableMemory): el viaje empieza por Wi-Fi Direct con el accesorio retenido.
        val f = CableFallback()
        f.startTrip(true, true)
        assertTrue(f.active())
        assertTrue(f.holdsUsb())
        assertEquals(Config.LINK_P2P, f.linkFor(Config.LINK_USB))
        // «Cable USB» a mano vuelve a él.
        assertTrue(f.onManualChoice(5_000, true))
        assertFalse(f.active())
        assertFalse(f.holdsUsb())
        assertEquals(Config.LINK_USB, f.linkFor(Config.LINK_USB))
        // Con una Wi-Fi elegida: solo se retiene el accesorio (el cable no pasa por delante).
        val g = CableFallback()
        g.startTrip(false, true)
        assertFalse(g.active())
        assertTrue(g.holdsUsb())
        // Y el viaje siguiente, de cero.
        g.startTrip()
        assertFalse(g.holdsUsb())
    }

    @Test
    fun settingOffNothing() {
        val off = CableFallback.Env(false, true, CableFallback.Bt.CONNECTED)
        val f = CableFallback()
        val d = hungAndTwoMute(f, off)
        assertFalse(d.toWifi())
        assertTrue(d.toString(), d.reasons().last().contains("está apagado"))
        // Apagado no se vuelve a mirar nada (ni temporizador ni más líneas con el motivo).
        assertEquals(-1L, f.timerAtMs())
        f.onAccessoryBack(42_000)
        val more = f.onSessionEnd(52_000, end(4, false, 10_000, "WATCHDOG"), off)
        assertFalse(more.toWifi())
        assertFalse(more.toString(), more.reasons().any { it.contains("está apagado") })
        // Tampoco con el cable por delante de una Wi-Fi elegida.
        val g = CableFallback()
        assertFalse(hungAndTwoMute(g, CableFallback.Env(false, false, CableFallback.Bt.CONNECTED)).toWifi())
        assertFalse(g.holdsUsb())
    }

    @Test
    fun aNewTripGoesBackToTheCable() {
        val f = CableFallback()
        assertTrue(hungAndTwoMute(f, on).toWifi())
        assertEquals(Config.LINK_P2P, f.linkFor(Config.LINK_USB))
        f.startTrip()
        assertFalse(f.active())
        assertEquals(Config.LINK_USB, f.linkFor(Config.LINK_USB))
        // Y el viaje nuevo vuelve a vigilar el cable desde cero (un atasco nuevo, con su cuenta).
        val d = f.onSessionEnd(10_000, end(1, false, 12_000, "WATCHDOG"), on)
        assertFalse(d.toWifi())
        assertTrue(d.toString(), d.reasons().single().contains("atasco del cable"))
    }

    @Test
    fun aManualChoiceWins() {
        val f = CableFallback()
        assertTrue(hungAndTwoMute(f, on).toWifi())
        // «Cable USB» elegido a mano (widget o «Cambiar»): vuelve el cable...
        assertTrue(f.holdsUsb())
        assertTrue(f.onManualChoice(40_000, true))
        assertFalse(f.active())
        assertFalse(f.holdsUsb())
        assertEquals(Config.LINK_USB, f.linkFor(Config.LINK_USB))
        // ... y aunque se vuelva a colgar, en este viaje ya no se cambia solo.
        f.onSessionEnd(100_000, end(5, true, 50_000, "WATCHDOG", 9_000), on)
        f.onSessionEnd(120_000, end(6, false, 12_000, "WATCHDOG"), on)
        val d = f.onSessionEnd(140_000, end(7, false, 12_000, "WATCHDOG"), on)
        assertFalse(d.toWifi())
        assertTrue(d.toString(), d.reasons().last().contains("se eligió a mano"))
        // Elegir otra Wi-Fi con el cable atascado también cuenta como elección a mano (y el accesorio, retenido).
        assertTrue(f.cableHung(140_000))
        assertTrue(f.onManualChoice(141_000, false))
        assertTrue(f.holdsUsb())
    }

    @Test
    fun choosingTheCableByHandAlwaysReleasesTheAccessory() {
        // El paso hecho: el accesorio, retenido.
        val f = CableFallback()
        assertTrue(hungAndTwoMute(f, on).toWifi())
        assertTrue(f.holdsUsb())
        // Wi-Fi Direct elegido a mano: manda lo elegido y sigue retenido (el paso ya no está hecho ni hay atasco abierto).
        assertTrue(f.onManualChoice(40_000, false))
        assertTrue(f.holdsUsb())
        assertFalse(f.active())
        assertFalse(f.cableHung(40_000))
        // Luego «Cable USB» a mano: ya no es una elección «con el cable caído» (false), pero el accesorio se suelta igual.
        assertFalse(f.onManualChoice(60_000, true))
        assertFalse(f.holdsUsb())
        assertEquals(Config.LINK_USB, f.linkFor(Config.LINK_USB))
        // Con el cable atascado (sin paso hecho): una Wi-Fi a mano lo retiene...
        val g = CableFallback()
        hungAfterAnHour(g)
        assertTrue(g.onManualChoice(1_000, false))
        assertTrue(g.holdsUsb())
        // ... otra Wi-Fi después no lo suelta (el cable sigue colgado) y el cable a mano, sí.
        assertFalse(g.onManualChoice(5_000, false))
        assertTrue(g.holdsUsb())
        assertFalse(g.onManualChoice(9_000, true))
        assertFalse(g.holdsUsb())
        // El viaje siguiente empieza sin retener.
        val h = CableFallback()
        assertTrue(hungAndTwoMute(h, on).toWifi())
        h.startTrip()
        assertFalse(h.holdsUsb())
    }

    @Test
    fun aManualChoiceWithTheCableFineChangesNothing() {
        // Sin atasco ni paso hecho, una elección a mano no bloquea la red de seguridad del viaje ni retiene el cable.
        val f = CableFallback()
        assertFalse(f.onManualChoice(0, false))
        assertFalse(f.holdsUsb())
        assertFalse(f.onManualChoice(0, true))
        assertTrue(hungAndTwoMute(f, on).toWifi())
    }

    @Test
    fun theCarNoticeIsSpokenOutsideAutoExtended() {
        // «Auto extendido» lo enseña en su pantalla; en «Auto» (toda la pantalla es de Android Auto), por voz.
        assertFalse(CableFallback.carNoticeByVoice(Config.MODE_AA_EXT))
        assertTrue(CableFallback.carNoticeByVoice(Config.MODE_AA))
        assertTrue(CableFallback.carNoticeByVoice(Config.MODE_APP))
        assertTrue(CableFallback.carNoticeByVoice(null))
    }

    @Test
    fun aRecoveredCableForgetsTheHang() {
        val f = CableFallback()
        hungAfterAnHour(f)
        f.onAccessoryBack(6_000)
        // 5 min de vídeo y luego quitado a propósito: el cable se había recuperado.
        val d = f.onSessionEnd(306_000, end(2, true, 300_000, "WRITE_ERROR", gone = true), on)
        assertTrue(d.toString(), d.reasons().single().contains("se ha recuperado"))
        assertFalse(f.cableHung(306_000))
        f.onAccessoryGone(307_000)
        assertEquals(-1L, f.timerAtMs())
        // Una sesión con vídeo pero corta (sin atascarse) ni suma ni resta.
        hungAfterAnHour(f, 400_000)
        val short = f.onSessionEnd(440_000, end(3, true, 30_000, "READ_ERROR", gone = true), on)
        assertTrue(short.toString(), short.reasons().single().contains("sigo vigilando"))
        assertFalse(f.onSessionEnd(460_000, end(4, false, 12_000, "WATCHDOG"), on).toWifi())
        assertTrue(f.onSessionEnd(480_000, end(5, false, 12_000, "WATCHDOG"), on).toWifi())
    }

    @Test
    fun anOldUndecidedHangIsForgotten() {
        val f = CableFallback()
        hungAfterAnHour(f)
        f.onAccessoryBack(6_000)
        assertFalse(f.onSessionEnd(18_000, end(2, false, 12_000, "WATCHDOG"), on).toWifi())
        // Más de 10 min después: se olvida y esta sesión muda abre uno nuevo (con su cuenta desde cero).
        val late = f.onSessionEnd(18_000 + CableFallback.EPISODE_MS + 1, end(3, false, 12_000, "WATCHDOG"), on)
        assertFalse(late.toWifi())
        assertTrue(late.toString(), late.reasons().first().contains("lo olvido"))
        assertTrue(late.toString(), late.reasons().last().contains("atasco del cable"))
    }

    @Test
    fun hungAndBluetooth() {
        assertTrue(CableFallback.hung(end(1, true, 1_000, "WATCHDOG")))
        assertTrue(CableFallback.hung(end(1, true, 1_000, "WRITE_STALL")))
        assertTrue(CableFallback.hung(end(1, true, 1_000, "LOCAL", 3_000)))
        assertFalse(CableFallback.hung(end(1, true, 1_000, "LOCAL", 2_999)))
        assertFalse(CableFallback.hung(end(1, true, 1_000, "READ_ERROR", gone = true)))
        assertTrue(end(3, false, 10_001, "WATCHDOG", 9_600).toString().contains("«el coche no lee» 9,6 s"))
        val on = 2
        val off = 0
        assertEquals(CableFallback.Bt.CONNECTED, CableFallback.btFrom(true, on, off))
        assertEquals(CableFallback.Bt.CONNECTED, CableFallback.btFrom(true, off, on))
        assertEquals(CableFallback.Bt.GONE, CableFallback.btFrom(true, off, off))
        assertEquals(CableFallback.Bt.GONE, CableFallback.btFrom(false, on, on))
        assertEquals(android.bluetooth.BluetoothProfile.STATE_CONNECTED, CableFallback.BT_STATE_CONNECTED)
        // Sin el permiso «Dispositivos cercanos»: por las salidas de audio (altavoz 2, auricular 1).
        assertEquals(CableFallback.Bt.CONNECTED, CableFallback.btFromAudio(intArrayOf(2, CableFallback.AUDIO_BT_A2DP)))
        assertEquals(CableFallback.Bt.CONNECTED, CableFallback.btFromAudio(intArrayOf(CableFallback.AUDIO_BT_SCO)))
        assertEquals(CableFallback.Bt.GONE, CableFallback.btFromAudio(intArrayOf(1, 2)))
        assertEquals(CableFallback.Bt.GONE, CableFallback.btFromAudio(intArrayOf()))
        assertEquals(CableFallback.Bt.UNKNOWN, CableFallback.btFromAudio(null))
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, CableFallback.AUDIO_BT_A2DP)
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, CableFallback.AUDIO_BT_SCO)
    }

    @Test
    fun theHangNoticeIsMutedOnlyWhileTheSwitchWillHappen() {
        // «La app del coche se ha colgado» (por voz: «desenchufa el cable») sobra si se va a pasar solo a Wi-Fi Direct.
        val f = CableFallback()
        assertFalse(f.handlesHang(0, on))
        hungAfterAnHour(f)
        assertTrue(f.handlesHang(1_000, on))
        // Si algo lo impide, el aviso sale como siempre.
        assertFalse(f.handlesHang(1_000, CableFallback.Env(true, true, CableFallback.Bt.GONE)))
        assertFalse(f.handlesHang(1_000, CableFallback.Env(true, true, CableFallback.Bt.UNKNOWN)))
        assertFalse(f.handlesHang(1_000, CableFallback.Env(false, true, CableFallback.Bt.CONNECTED)))
        assertFalse(f.handlesHang(1_000, CableFallback.Env(true, true, CableFallback.Bt.CONNECTED, false, -1)))
        // Un atasco viejo sin decidir, tampoco.
        assertFalse(f.handlesHang(CableFallback.EPISODE_MS + 1, on))
        // Ya en Wi-Fi Direct no hay nada que callar.
        f.onAccessoryGone(2_000)
        assertTrue(f.timer(12_000, on).toWifi())
        assertFalse(f.handlesHang(12_000, on))
        // Sin vídeo en el viaje también se abre el atasco (y se pasará): sin aviso.
        val g = CableFallback()
        g.onSessionEnd(0, end(1, false, 12_000, "WATCHDOG", 9_000), on)
        assertTrue(g.handlesHang(1_000, on))
        // Tras elegir a mano, tampoco.
        val h = CableFallback()
        hungAfterAnHour(h)
        assertTrue(h.onManualChoice(1_000, false))
        assertFalse(h.handlesHang(1_000, on))
    }

    @Test
    fun theTextsAreInEveryLanguage() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val keys = listOf("hql_cable_wifi_switch", "hql_cable_wifi_switch_sum", "hql_cable_wifi_title", "hql_cable_wifi_text",
            "hql_cable_memory_title", "hql_cable_memory_text", "hql_cable_back_title", "hql_cable_back_text",
            "hql_cable_wifi_off_title", "hql_cable_wifi_off_text")
        for (dir in listOf("values", "values-es", "values-pt-rBR", "values-pt-rPT", "values-it", "values-fr", "values-de",
            "values-nl", "values-zh-rCN")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/hql_strings_cablewifi.xml"))
            val nodes = doc.documentElement.getElementsByTagName("string")
            val strings = (0 until nodes.length).associate {
                val e = nodes.item(it) as org.w3c.dom.Element
                e.getAttribute("name") to e.textContent
            }
            assertEquals(dir, keys, strings.keys.toList())
            for ((k, v) in strings) {
                assertTrue("$dir/$k vacío", v.isNotBlank())
                assertFalse("$dir/$k sin huecos de formato", v.contains('%'))
            }
            assertTrue("$dir: Wi-Fi Direct", strings.getValue("hql_cable_wifi_title").contains("Wi-Fi Direct"))
            assertTrue("$dir: Wi-Fi Direct", strings.getValue("hql_cable_memory_title").contains("Wi-Fi Direct"))
            assertTrue("$dir: Wi-Fi Direct", strings.getValue("hql_cable_wifi_off_text").contains("Wi-Fi Direct"))
            // Alemán con sus comillas „“; ninguna recta en ningún idioma.
            for ((k, v) in strings) assertFalse("$dir/$k con comillas rectas", v.contains('"'))
            if (dir == "values-de") assertTrue(strings.getValue("hql_cable_memory_text").contains("„Ändern“"))
        }
    }
}
