package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo real del viaje en curso en Eficiencia: la energía, el coste y el reparto, y cuándo se queda en lo estimado. */
class LiveEnergyTest {
    /** 10 % de 67 kWh en 50 km: 6,7 kWh, 13,4 kWh/100 km. */
    private val measured = CloudEnergy.between(80.0, 1000.0, 70.0, 1050.0, 67.0, false)

    /** Un viaje del modelo: unos 5 kWh de conducción, 1 de clima, 0,5 de electrónica y 1 recuperado. */
    private fun model(): EnergyBreakdown {
        val m = EnergyModel()
        val b = EnergyBreakdown()
        m.aeroKw = 12.0
        m.rollKw = 8.0
        m.gradeKw = 0.0
        m.accelKw = 2.0
        m.hvacKw = 4.0
        b.add(m, 0.25)
        m.aeroKw = 0.0
        m.rollKw = 0.0
        m.accelKw = -10.0
        m.hvacKw = 0.0
        b.add(m, 0.15)
        return b
    }

    @Test
    fun withoutMeasureItIsTheEstimate() {
        val e = LiveEnergy.of(4.5, 30.0, CloudEnergy.NONE, Double.NaN)
        assertFalse(e.real)
        assertEquals(4.5, e.batteryKwh, 1e-9)
        assertEquals(0.9, e.cost(0.2, 1.7), 1e-9)
        assertEquals(0.0, LiveEnergy.of(-0.3, 0.0, CloudEnergy.NONE, Double.NaN).batteryKwh, 1e-9)
    }

    @Test
    fun aLittleDropIsStillTheEstimate() {
        val little = CloudEnergy.between(80.0, 1000.0, 79.0, 1005.0, 67.0, false)
        assertFalse(LiveEnergy.of(4.5, 5.0, little, Double.NaN).real)
    }

    @Test
    fun measuredRateCoversTheWholeTrip() {
        // La primera lectura llegó después de salir: 60 km de viaje al ritmo medido en 50.
        val e = LiveEnergy.of(5.0, 60.0, measured, Double.NaN)
        assertTrue(e.real)
        assertEquals(8.04, e.batteryKwh, 1e-9)
        assertEquals(1.608, e.cost(0.2, 1.7), 1e-9)
        // Tras «Reiniciar viaje» solo cuentan los km desde entonces.
        assertEquals(1.34, LiveEnergy.of(5.0, 10.0, measured, Double.NaN).batteryKwh, 1e-9)
        assertEquals(0.0, LiveEnergy.of(5.0, -1.0, measured, Double.NaN).batteryKwh, 1e-9)
    }

    @Test
    fun reevAddsTheFuel() {
        val e = LiveEnergy.of(5.0, 60.0, measured, 2.0)
        assertEquals(2.4, e.fuelL, 1e-9)
        assertEquals(8.04 * 0.2 + 2.4 * 1.7, e.cost(0.2, 1.7), 1e-9)
        assertEquals(8.04 + 2.4 * TripStats.KWH_PER_LITER, e.totalKwh(), 1e-9)
    }

    @Test
    fun fitKeepsTheModelWithoutMeasure() {
        val model = model()
        val shown = EnergyBreakdown()
        val fit = LiveEnergy.of(4.0, 30.0, CloudEnergy.NONE, Double.NaN).fit(model, CloudUsage.Split(9.0, 1.0, 1.0), shown)
        assertSame(LiveEnergy.Fit.MODEL, fit)
        assertEquals(model.net(), shown.net(), 1e-9)
    }

    @Test
    fun fitToTheRealTotalKeepsThePercents() {
        val model = model()
        val shown = EnergyBreakdown()
        val e = LiveEnergy.of(model.net(), 50.0, measured, Double.NaN)
        assertSame(LiveEnergy.Fit.REAL, e.fit(model, null, shown))
        assertEquals(e.totalKwh(), shown.net(), 1e-9)
        for (i in 0 until EnergyBreakdown.PARTS) assertEquals(model.share(i), shown.share(i), 1e-9)
    }

    @Test
    fun carSplitGivesClimateAndOther() {
        val model = model()
        val shown = EnergyBreakdown()
        val e = LiveEnergy.of(model.net(), 50.0, measured, Double.NaN)
        // Una parte del viaje según el coche: 8 % de clima y 4 % de otros.
        assertSame(LiveEnergy.Fit.CAR, e.fit(model, CloudUsage.Split(4.4, 0.4, 0.2), shown))
        assertEquals(e.totalKwh(), shown.net(), 1e-9)
        assertEquals(e.totalKwh() * 0.08, shown.part(EnergyBreakdown.CLIMATE), 1e-9)
        assertEquals(e.totalKwh() * 0.04, shown.part(EnergyBreakdown.AUX), 1e-9)
        // El resto, como el modelo: aire y rodadura guardan su proporción.
        assertEquals(
            model.part(EnergyBreakdown.AIR) / model.part(EnergyBreakdown.ROLLING),
            shown.part(EnergyBreakdown.AIR) / shown.part(EnergyBreakdown.ROLLING), 1e-9
        )
    }

    @Test
    fun anEmptyOrTinyCarSplitIsNotUsed() {
        val model = model()
        val shown = EnergyBreakdown()
        val e = LiveEnergy.of(model.net(), 50.0, measured, Double.NaN)
        // Aún no ha subido nada del viaje: se queda con el consumo real.
        assertSame(LiveEnergy.Fit.REAL, e.fit(model, CloudUsage.Split(0.0, 0.0, 0.0), shown))
        assertSame(LiveEnergy.Fit.REAL, e.fit(model, CloudUsage.Split(0.2, 0.3, 0.1), shown))
    }

    @Test
    fun anImplausibleCarSplitFallsBackToTheRealTotal() {
        val model = model()
        val shown = EnergyBreakdown()
        val e = LiveEnergy.of(model.net(), 50.0, measured, Double.NaN)
        // Casi todo en clima: la conducción saldría casi a cero.
        assertSame(LiveEnergy.Fit.REAL, e.fit(model, CloudUsage.Split(0.1, 5.0, 0.1), shown))
        assertEquals(e.totalKwh(), shown.net(), 1e-9)
    }

    @Test
    fun anImplausibleRealTotalKeepsTheModel() {
        val model = model()
        val shown = EnergyBreakdown()
        // El real es más de 2,5 veces el modelo: uno de los dos está mal.
        val e = LiveEnergy.of(1.0, 500.0, measured, Double.NaN)
        assertTrue(e.totalKwh() / model.net() > EnergyBreakdown.MAX_FIT)
        assertSame(LiveEnergy.Fit.MODEL, e.fit(model, null, shown))
        assertEquals(model.net(), shown.net(), 1e-9)
    }
}
