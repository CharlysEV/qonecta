package com.headqlink.link

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Memoria del cable por coche (CableMemory): tras dos viajes seguidos con el cable caído y sin una sesión buena, los
 * siguientes empiezan por Wi-Fi Direct con un aviso una sola vez; y todo lo que hace que se vuelva a probar el cable
 * (una sesión buena, elegirlo a mano, 7 días, una actualización, otro coche, el ajuste apagado).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CableMemoryTest {
    private val day = 24 * 3_600_000L
    private val t0 = 1_800_000_000_000L
    private val v = 317L
    private val car = CableMemory.carKey("UUID-DEL-COCHE-1234", "PROYECTO-T03")
    private val other = CableMemory.carKey("UUID-DE-OTRO-5678", "PROYECTO-C10")

    /** Un viaje en el que el cable acaba en Wi-Fi Direct sin sesión buena (S1 con vídeo: el coche ya se ha identificado). */
    private fun failedTrip(m: CableMemory, now: Long, key: String = car, version: Long = v): CableMemory.Start {
        val st = m.startTrip(now, version, true)
        if (!st.skip) {
            m.onCar(key)
            m.onCableFallback()
        }
        return st
    }

    private fun armed(): CableMemory {
        val m = CableMemory()
        assertFalse(failedTrip(m, t0).skip)
        assertFalse(m.armed())
        assertFalse(failedTrip(m, t0 + 3_600_000).skip)
        assertTrue(m.armed())
        return m
    }

    @Test
    fun afterTwoFailedTripsTheNextOnesStartOnWifiDirectWithOneNotice() {
        val m = armed()
        val third = m.startTrip(t0 + day, v, true)
        assertTrue(third.skip)
        assertTrue(third.why, third.why.contains("empieza sin él"))
        // El aviso, una sola vez.
        assertTrue(m.noticeDue())
        assertFalse(m.noticeDue())
        m.onCar(car)
        val fourth = m.startTrip(t0 + 2 * day, v, true)
        assertTrue(fourth.skip)
        assertFalse(m.noticeDue())
    }

    @Test
    fun oneFailedTripIsNotEnoughAndTheCountIsPerTrip() {
        val m = CableMemory()
        failedTrip(m, t0)
        // El mismo viaje no cuenta dos veces.
        assertNull(m.onCableFallback())
        assertFalse(m.armed())
        assertFalse(m.startTrip(t0 + day, v, true).skip)
        assertFalse(m.noticeDue())
    }

    @Test
    fun theCarMayIdentifyItselfAfterTheSwitch() {
        // Viaje sin vídeo por el cable: el coche se identifica ya por Wi-Fi Direct (su CAR_INFO), y entonces se apunta.
        val m = CableMemory()
        m.startTrip(t0, v, true)
        assertTrue(m.onCableFallback()!!.contains("cuando el coche se identifique"))
        assertTrue(m.onCar(car)!!.contains("1 de 2"))
        m.startTrip(t0 + day, v, true)
        m.onCableFallback()
        assertTrue(m.onCar(car)!!.contains("2 viajes seguidos"))
        assertTrue(m.armed())
    }

    @Test
    fun aGoodCableSessionForgetsEverything() {
        // Entre dos viajes fallidos, uno bueno: no son «seguidos».
        val m = CableMemory()
        failedTrip(m, t0)
        m.startTrip(t0 + day, v, true)
        m.onCar(car)
        assertTrue(m.onGoodCable()!!.contains("olvido"))
        // Tras una sesión buena, que el cable se caiga en ese viaje no cuenta (el C10 que se cuelga tras una hora).
        assertNull(m.onCableFallback())
        failedTrip(m, t0 + 2 * day)
        assertFalse(m.armed())
        // Con la memoria armada y el cable elegido a mano en el viaje: una sesión buena la borra, aviso incluido.
        val a = armed()
        assertTrue(a.startTrip(t0 + day, v, true).skip)
        assertTrue(a.noticeDue())
        a.onManualCable()
        a.onCar(car)
        a.onGoodCable()
        assertFalse(a.armed())
        assertFalse(a.startTrip(t0 + 2 * day, v, true).skip)
    }

    @Test
    fun choosingTheCableByHandTriesItAgain() {
        val m = armed()
        assertTrue(m.onManualCable()!!.contains("lo vuelvo a probar"))
        assertFalse(m.armed())
        assertFalse(m.startTrip(t0 + day, v, true).skip)
        // Desde cero: hacen falta otros dos viajes, y el aviso vuelve a salir.
        m.onCar(car)
        m.onCableFallback()
        assertFalse(m.armed())
        assertFalse(failedTrip(m, t0 + 2 * day).skip)
        assertTrue(m.startTrip(t0 + 3 * day, v, true).skip)
        assertTrue(m.noticeDue())
        // Sin nada que olvidar, nada que decir.
        assertNull(CableMemory().onManualCable())
    }

    @Test
    fun afterSevenDaysTheCableIsTriedAgain() {
        val m = armed()
        val since = t0 + 3_600_000
        assertTrue(m.startTrip(since + CableMemory.RETRY_MS - 1, v, true).skip)
        assertTrue(m.noticeDue())
        val retry = m.startTrip(since + CableMemory.RETRY_MS, v, true)
        assertFalse(retry.skip)
        assertTrue(retry.why, retry.why.contains("han pasado 7 días"))
        // Vuelve a fallar: el viaje siguiente ya empieza por Wi-Fi Direct (otros 7 días), sin repetir el aviso.
        m.onCar(car)
        m.onCableFallback()
        assertTrue(m.startTrip(since + CableMemory.RETRY_MS + day, v, true).skip)
        assertFalse(m.noticeDue())
        // Y si en la prueba el cable aguanta, se olvida.
        val ok = armed()
        ok.startTrip(since + CableMemory.RETRY_MS, v, true)
        ok.onCar(car)
        ok.onGoodCable()
        assertFalse(ok.startTrip(since + CableMemory.RETRY_MS + day, v, true).skip)
        // Un reloj que va hacia atrás no deja la memoria puesta para siempre.
        assertFalse(armed().startTrip(t0 - day, v, true).skip)
    }

    @Test
    fun afterAnAppUpdateTheCableIsTriedAgain() {
        val m = armed()
        val retry = m.startTrip(t0 + day, v + 1, true)
        assertFalse(retry.skip)
        assertTrue(retry.why, retry.why.contains("la app se ha actualizado"))
        // Con la versión nueva vuelve a fallar: se queda con ella.
        m.onCar(car)
        m.onCableFallback()
        assertTrue(m.startTrip(t0 + 2 * day, v + 1, true).skip)
    }

    @Test
    fun withTheSettingOffNothing() {
        // Apagado no hay paso que contar...
        val m = CableMemory()
        for (i in 0..3) {
            m.startTrip(t0 + i * day, v, false)
            m.onCar(car)
            assertNull(m.onCableFallback())
        }
        assertFalse(m.armed())
        // ... y con la memoria ya armada, el viaje empieza por el cable (sin borrarla: al encenderlo vuelve a valer).
        val a = armed()
        val off = a.startTrip(t0 + day, v, false)
        assertFalse(off.skip)
        assertTrue(off.why, off.why.contains("está apagado"))
        assertFalse(a.noticeDue())
        assertTrue(a.startTrip(t0 + 2 * day, v, true).skip)
    }

    @Test
    fun theMemoryIsPerCar() {
        // Un viaje fallido con cada coche: no son dos seguidos con el mismo.
        val m = CableMemory()
        failedTrip(m, t0, car)
        failedTrip(m, t0 + day, other)
        assertFalse(m.armed())
        // Armada con un coche; el viaje siguiente es con otro (se sabe al identificarse): no se toca, y el siguiente con
        // ese otro coche vuelve al cable.
        val a = armed()
        assertTrue(a.startTrip(t0 + day, v, true).skip)
        assertTrue(a.onCar(other)!!.contains("no es el coche"))
        val next = a.startTrip(t0 + 2 * day, v, true)
        assertFalse(next.skip)
        assertTrue(next.why, next.why.contains("otro coche"))
        // Una sesión buena con el otro coche no borra la del primero...
        a.onCar(other)
        assertNull(a.onGoodCable())
        assertTrue(a.armed())
        // ... que, al volver a él, prueba el cable un viaje (el último fue con el otro) y, tras verlo, vuelve a empezar sin él.
        assertFalse(a.startTrip(t0 + 3 * day, v, true).skip)
        a.onCar(car)
        assertTrue(a.startTrip(t0 + 4 * day, v, true).skip)
    }

    @Test
    fun withoutWifiTheTripStaysOnTheCable() {
        val m = armed()
        assertTrue(m.startTrip(t0 + day, v, true).skip)
        m.notSkipping()
        // No hubo viaje por Wi-Fi Direct: el aviso no se gasta.
        assertFalse(m.noticeDue())
        assertTrue(m.startTrip(t0 + 2 * day, v, true).skip)
        assertTrue(m.noticeDue())
    }

    @Test
    fun theCarIsAFingerprintNotItsIds() {
        assertEquals(car, CableMemory.carKey("UUID-DEL-COCHE-1234", "PROYECTO-T03"))
        assertNotEquals(car, other)
        assertEquals(16, car.length)
        assertTrue(car, car.all { it in "0123456789abcdef" })
        assertFalse(car.contains("UUID"))
        // Sin identificadores también vale (una sola huella).
        assertEquals(CableMemory.carKey(null, null), CableMemory.carKey("", ""))
        // Ni lo guardado ni lo que va al log llevan los identificadores.
        val m = CableMemory()
        val texts = ArrayList<String?>()
        m.startTrip(t0, v, true)
        texts += m.onCar(car)
        texts += m.onCableFallback()
        texts += failedTrip(m, t0 + day).why
        texts += m.startTrip(t0 + 2 * day, v, true).why
        texts += m.encode()
        for (t in texts) assertFalse(t.orEmpty(), t.orEmpty().contains("UUID") || t.orEmpty().contains("PROYECTO"))
        for (t in texts.dropLast(1)) assertFalse(t.orEmpty(), t.orEmpty().contains(car))
    }

    @Test
    fun itSurvivesTheProcess() {
        val ctx = RuntimeEnvironment.getApplication()
        assertFalse(CableMemory.load(ctx).armed())
        val m = CableMemory.load(ctx)
        failedTrip(m, t0)
        m.save(ctx)
        val n = CableMemory.load(ctx)
        failedTrip(n, t0 + day)
        n.save(ctx)
        val third = CableMemory.load(ctx)
        assertTrue(third.armed())
        assertTrue(third.startTrip(t0 + 2 * day, v, true).skip)
        assertTrue(third.noticeDue())
        third.save(ctx)
        assertFalse(CableMemory.load(ctx).apply { startTrip(t0 + 3 * day, v, true) }.noticeDue())
        // Algo que no se entiende: vacía, sin romper nada.
        assertFalse(CableMemory.decode("basura").armed())
        assertFalse(CableMemory.decode("1|x|dos|0|0|0|").armed())
        assertEquals(third.encode(), CableMemory.decode(third.encode()).encode())
    }
}
