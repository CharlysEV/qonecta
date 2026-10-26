package com.headqlink.link

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El Bluetooth del coche, aprendido por su dirección. */
class CarBluetoothTest {
    private val car = BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    private val headphones = BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES

    @Test
    fun onlyACarClassDeviceIsLearnedAndNeverByGuessing() {
        assertEquals("AA:BB:CC:DD:B7:D6", CarBluetooth.pick(listOf(CarBluetooth.Seen("aa:bb:cc:dd:b7:d6", car))))
        // Auriculares conectados a la vez: el coche sigue siendo el de clase coche.
        assertEquals("AA:BB:CC:DD:B7:D6", CarBluetooth.pick(listOf(CarBluetooth.Seen("11:22:33:44:55:66", headphones),
            CarBluetooth.Seen("AA:BB:CC:DD:B7:D6", car))))
        // Solo auriculares, o dos de clase coche: no se adivina.
        assertNull(CarBluetooth.pick(listOf(CarBluetooth.Seen("11:22:33:44:55:66", headphones))))
        assertNull(CarBluetooth.pick(listOf(CarBluetooth.Seen("AA:BB:CC:DD:B7:D6", car), CarBluetooth.Seen("AA:BB:CC:DD:00:01", car))))
        assertNull(CarBluetooth.pick(emptyList()))
    }

    @Test
    fun theLastCarsAreRemembered() {
        assertEquals(listOf("C", "A", "B"), CarBluetooth.remember(listOf("A", "B"), "c", 3))
        assertEquals(listOf("A", "B"), CarBluetooth.remember(listOf("B", "A"), "a", 3))
        assertEquals(listOf("D", "A", "B"), CarBluetooth.remember(listOf("A", "B", "C"), "D", 3))
        assertEquals("…:B7:D6", CarBluetooth.masked("AA:BB:CC:DD:B7:D6"))
    }
}
