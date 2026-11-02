package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Paso solo del cable a Wi-Fi Direct cuando el puerto USB del coche se cuelga a mitad de viaje (CableFallback): el log
 * real del 01-11, el puerto que no vuelve, los intentos mudos y todo lo que no debe hacerlo pasar.
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
        // ... y vuelve a los 3 s (S2 abierta): ya no cuentan los 10 s, sino los intentos.
        assertTrue(f.onAccessoryBack(5_000).reasons().single().contains("ha vuelto en 3 s"))
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
        assertTrue(d.toString(), d.reasons().last().contains("el Bluetooth del coche ya no está"))
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
    fun withoutVideoInTheTripNothing() {
        // Al empezar, el coche a veces tarda en contestar por el cable: sesiones mudas sin vídeo antes no abren nada.
        val f = CableFallback()
        for (i in 1..6) assertTrue(f.onSessionEnd(i * 20_000L, end(i, false, 12_000, "WATCHDOG", 9_000), on).reasons().isEmpty())
        f.onAccessoryGone(130_000)
        assertEquals(-1L, f.timerAtMs())
        assertFalse(f.cableHung(130_000))
        assertFalse(f.active())
    }

    @Test
    fun settingOffOrAnotherChosenConnectionNothing() {
        val off = CableFallback.Env(false, true, CableFallback.Bt.CONNECTED)
        val f = CableFallback()
        val d = hungAndTwoMute(f, off)
        assertFalse(d.toWifi())
        assertTrue(d.toString(), d.reasons().last().contains("está apagado"))
        // El cable por delante de una Wi-Fi elegida: no es este caso (al quitarlo ya se vuelve a ella).
        val wifiChosen = CableFallback.Env(true, false, CableFallback.Bt.CONNECTED)
        val g = CableFallback()
        val e = hungAndTwoMute(g, wifiChosen)
        assertFalse(e.toWifi())
        assertTrue(e.toString(), e.reasons().last().contains("la conexión elegida no es el cable"))
    }

    @Test
    fun aNewTripGoesBackToTheCable() {
        val f = CableFallback()
        assertTrue(hungAndTwoMute(f, on).toWifi())
        assertEquals(Config.LINK_P2P, f.linkFor(Config.LINK_USB))
        f.startTrip()
        assertFalse(f.active())
        assertEquals(Config.LINK_USB, f.linkFor(Config.LINK_USB))
        // Y el viaje nuevo vuelve a vigilar el cable desde cero (sin vídeo aún, nada).
        assertTrue(f.onSessionEnd(10_000, end(1, false, 12_000, "WATCHDOG"), on).reasons().isEmpty())
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
        assertFalse(f.handlesHang(1_000, CableFallback.Env(true, false, CableFallback.Bt.CONNECTED)))
        // Un atasco viejo sin decidir, tampoco.
        assertFalse(f.handlesHang(CableFallback.EPISODE_MS + 1, on))
        // Ya en Wi-Fi Direct no hay nada que callar.
        f.onAccessoryGone(2_000)
        assertTrue(f.timer(12_000, on).toWifi())
        assertFalse(f.handlesHang(12_000, on))
        // Sin vídeo en el viaje no se abre el atasco: el aviso, como siempre.
        val g = CableFallback()
        g.onSessionEnd(0, end(1, false, 12_000, "WATCHDOG", 9_000), on)
        assertFalse(g.handlesHang(1_000, on))
        // Tras elegir a mano, tampoco.
        val h = CableFallback()
        hungAfterAnHour(h)
        assertTrue(h.onManualChoice(1_000, false))
        assertFalse(h.handlesHang(1_000, on))
    }

    @Test
    fun theTextsAreInEveryLanguage() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val keys = listOf("hql_cable_wifi_switch", "hql_cable_wifi_switch_sum", "hql_cable_wifi_title", "hql_cable_wifi_text")
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
        }
    }
}
