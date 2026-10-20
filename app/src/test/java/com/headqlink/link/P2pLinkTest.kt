package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wi-Fi Direct: qué interfaz dice que ya hay un grupo en marcha (y entonces no se pide otra conexión al coche). */
class P2pLinkTest {
    @Test
    fun p2pInterfaces() {
        assertTrue(P2pLink.isP2pInterface("p2p-wlan0-0"))
        assertTrue(P2pLink.isP2pInterface("p2p0"))
        assertFalse(P2pLink.isP2pInterface("wlan0"))
        assertFalse(P2pLink.isP2pInterface("swlan0"))
        assertFalse(P2pLink.isP2pInterface("ap0"))
        assertFalse(P2pLink.isP2pInterface(null))
    }
}
