package com.headqlink.link

import com.headqlink.link.LinkLifecycle.Env
import com.headqlink.link.LinkLifecycle.ShutdownPlan
import com.headqlink.link.LinkLifecycle.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Servidor de Android Auto encendido entre viajes (Config.aaServerKeepOn): al terminar no se apaga (ni se aparca AA
 * hasta desbloquear), el viaje siguiente no pide desbloquear, y se apaga en una Wi-Fi sin contraseña.
 */
class AaServerKeepOnTest {
    @Test
    fun atTheEndTheServerStaysOnEvenWithThePhoneLocked() {
        // Antes: bloqueado, AA aparcado hasta desbloquear y entonces se apagaba (y el viaje siguiente pedía desbloquear).
        val locked = Env().aa(true).locked(true).connected(true).keepOn(true)
        assertEquals(ShutdownPlan.KEEP_SERVER_ON, LinkLifecycle.shutdownPlan(locked))
        assertEquals(ShutdownPlan.KEEP_SERVER_ON, LinkLifecycle.shutdownPlan(Env().aa(true).connected(true).keepOn(true)))
        // Sin el ajuste (o en una Wi-Fi sin contraseña: LinkService pone keepOn a false), lo de siempre.
        assertEquals(ShutdownPlan.PARK_UNTIL_UNLOCK, LinkLifecycle.shutdownPlan(Env().aa(true).locked(true).connected(true)))
        assertEquals(ShutdownPlan.STOP_SERVER, LinkLifecycle.shutdownPlan(Env().aa(true).connected(true)))
        // El manual, como siempre (nunca lo apaga); sin Android Auto, nada.
        assertEquals(ShutdownPlan.LEAVE_SERVER_ON, LinkLifecycle.shutdownPlan(Env().aa(true).manual(true).keepOn(true)))
        assertEquals(ShutdownPlan.LINK_ONLY, LinkLifecycle.shutdownPlan(Env().keepOn(true)))
    }

    @Test
    fun theNextTripDoesNotAskToUnlock() {
        // Bluetooth del coche con el móvil bloqueado y el servidor encendido de antes: nada que arrancar ni desbloquear.
        val life = LinkLifecycle()
        val d = life.start(0, Trigger.BLUETOOTH, Env().aa(true).locked(true).serverOn(true).keepOn(true), 5 * 60_000L)
        assertFalse(d.has(LinkLifecycle.Action.START_SERVER_ON_UNLOCK))
        assertFalse(d.has(LinkLifecycle.Action.START_SERVER))
        // Con el servidor apagado (reinicio del móvil, AA actualizado), el aviso de desbloquear de siempre.
        val life2 = LinkLifecycle()
        val d2 = life2.start(0, Trigger.BLUETOOTH, Env().aa(true).locked(true).keepOn(true), 5 * 60_000L)
        assertTrue(d2.has(LinkLifecycle.Action.START_SERVER_ON_UNLOCK))
    }

    @Test
    fun policy() {
        val s = AaServerPolicy.State().keepOn(true)
        assertEquals(AaServerPolicy.End.KEEP_SERVER_ON, AaServerPolicy.onEnd(true, s))
        assertEquals(AaServerPolicy.End.STOP_SERVER, AaServerPolicy.onEnd(true, AaServerPolicy.State()))
        assertEquals(AaServerPolicy.End.LEAVE_SERVER_ON, AaServerPolicy.onEnd(true, AaServerPolicy.State().manual(true).keepOn(true)))
        assertEquals(AaServerPolicy.End.NOTHING, AaServerPolicy.onEnd(false, s))
    }

    @Test
    fun openWifisAreThoseWithoutPassword() {
        assertTrue(OpenWifiGuard.isOpen(0)) // SECURITY_TYPE_OPEN
        assertTrue(OpenWifiGuard.isOpen(6)) // SECURITY_TYPE_OWE (Enhanced Open: cifrada, pero entra cualquiera)
        assertFalse(OpenWifiGuard.isOpen(2)) // PSK (WPA2 con contraseña)
        assertFalse(OpenWifiGuard.isOpen(4)) // SAE (WPA3)
        assertFalse(OpenWifiGuard.isOpen(3)) // EAP (empresa)
    }

    @Test
    fun anOldOnIsNotTrustedAfterARebootOrAnAaUpdate() {
        val at = 1_000_000L
        assertTrue(AaServerStarter.stillValid(at, 12, 12, at - 5_000))
        // El móvil se reinició: el servidor está apagado.
        assertFalse(AaServerStarter.stillValid(at, 12, 13, at - 5_000))
        // Android Auto se actualizó después: también.
        assertFalse(AaServerStarter.stillValid(at, 12, 12, at + 60_000))
        // Sin datos (versiones viejas, apuntado antes de este cambio): no se invalida.
        assertTrue(AaServerStarter.stillValid(at, -1, 13, -1))
    }
}
