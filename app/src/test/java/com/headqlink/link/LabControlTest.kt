package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** La versión que se publica (sin -Plab) no acepta órdenes de la variante de pruebas ni aplica sus topes. */
class LabControlTest {
    @Test
    fun theNormalBuildRefusesOrders() {
        assertFalse(LabControl.enabled())
        assertEquals("no: esta no es la variante de pruebas", LabControl.handle(null, "fps 20"))
        assertEquals(0, LabControl.fpsCap)
        assertEquals(0, LabControl.maxKbps)
        assertEquals("", LabControl.overrides())
    }
}
