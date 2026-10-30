package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Lo que gasta la app (AppEnergy) y el móvil en cada viaje: las cuentas con las estadísticas de Android, el reinicio al
 * cargar, las bolsas por día y los textos del viaje y de la tarjeta.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES")
class AppEnergyTest {
    @Before
    fun texts() {
        Str.init(RuntimeEnvironment.getApplication())
    }

    private fun snap(cpuMs: Double = 0.0, gpsMs: Double = 0.0, videoMs: Double = 0.0, radio: Double = 0.0, mob: Double = 0.0, wifi: Double = 0.0) =
        AppEnergy.Snap().apply {
            this.cpuMs = cpuMs; this.gpsMs = gpsMs; this.videoMs = videoMs; radioMams = radio; mobileBytes = mob; wifiBytes = wifi
        }

    @Test
    fun theEstimateAddsCpuGpsVideoAndRadio() {
        val h = 3_600_000.0
        // Una hora de procesador, de GPS y de vídeo.
        assertEquals(130.0, AppEnergy.mahBetween(snap(), snap(cpuMs = h)), 1e-9)
        assertEquals(60.0, AppEnergy.mahBetween(snap(), snap(gpsMs = h)), 1e-9)
        assertEquals(25.0, AppEnergy.mahBetween(snap(), snap(videoMs = h)), 1e-9)
        // Sin la energía de la radio, por los datos: 10 MB móviles = 20 mAh; con ella, la suya (mA·ms).
        assertEquals(20.0, AppEnergy.mahBetween(snap(), snap(mob = 10e6)), 1e-9)
        assertEquals(5.0, AppEnergy.mahBetween(snap(), snap(radio = 5 * h, mob = 10e6)), 1e-9)
        // Entre dos fotos, la diferencia.
        assertEquals(13.0, AppEnergy.mahBetween(snap(cpuMs = h), snap(cpuMs = 1.1 * h)), 1e-9)
    }

    @Test
    fun afterChargingTheCountersStartAgainAndTheNewValueIsWhatWasUsed() {
        val h = 3_600_000.0
        // Android pone las cifras a cero al cargar: la foto nueva es menor; lo gastado es lo de ahora.
        assertEquals(13.0, AppEnergy.mahBetween(snap(cpuMs = 5 * h), snap(cpuMs = 0.1 * h)), 1e-9)
        assertEquals(13.0, AppEnergy.mahBetween(null, snap(cpuMs = 0.1 * h)), 1e-9)
    }

    @Test
    fun daysKeepTheirTwoBagsAndOnlyTheLastFourteen() {
        val days = org.json.JSONObject()
        AppEnergy.add(days, "2026-10-24", false, 0.4, 600.0)
        AppEnergy.add(days, "2026-10-24", true, 50.0, 60.0)
        AppEnergy.add(days, "2026-10-24", false, 0.1, 120.0)
        val t = AppEnergy.total(days, listOf("2026-10-24", "2026-10-23"))
        assertEquals(0.5, t[0], 1e-9)
        assertEquals(50.0, t[1], 1e-9)
        assertEquals(60.0, t[2], 1e-9)
        for (d in 1..20) AppEnergy.add(days, String.format("2026-09-%02d", d), false, 1.0, 1.0)
        assertEquals(14, days.length())
        assertTrue(days.has("2026-10-24"))
    }

    @Test
    fun theTripLinesSayWhatThePhoneUsed() {
        // En el registro del viaje: %, mAh del contador, la media y el % por hora.
        val line = SessionSummary.batteryLine(82, 76, 4_100_000, 3_788_000, 25.0, false)
        assertTrue(line, line.startsWith("batería del móvil: 82 % → 76 % en 25") && line.contains("312 mAh")
            && line.contains("≈ 749 mA de media") && line.contains("−14 % por hora") && line.endsWith("sin enchufar"))
        assertEquals("batería del móvil: 60 % → 68 % en 20,0 min · enchufado".replace("20,0", SessionSummaryTestHelper.f1(20.0)),
            SessionSummary.batteryLine(60, 68, 1, 2, 20.0, true))
        // 30-10: enchufado solo por el camino (sin cable al empezar ni al acabar) salía «−-68 % por hora · sin enchufar».
        val road = SessionSummary.batteryLine(23, 41, 1_100_000, 2_050_000, 15.9, false)
        assertTrue(road, road.endsWith("· enchufado") && !road.contains("por hora"))
        val start = longArrayOf(23, 1_100_000, 0, 0)
        val end = longArrayOf(41, 2_050_000, 0, 0)
        assertTrue(PhoneBattery.charged(start, end))
        assertFalse(PhoneBattery.charged(end, start))
        assertEquals("Batería del móvil: 23 % → 41 % (cargando)", TripNotice.phoneText(start, end))
        // Para ti: en el aviso del viaje y en el detalle.
        assertEquals("Batería del móvil: −6 % (312 mAh)", TripNotice.phoneText(82, 76, 312.0, false))
        assertEquals("Batería del móvil: −6 %", TripNotice.phoneText(82, 76, Double.NaN, false))
        assertEquals("Batería del móvil: 60 % → 68 % (cargando)", TripNotice.phoneText(60, 68, Double.NaN, true))
        assertEquals("", TripNotice.phoneText(-1, 76, Double.NaN, false))
    }

    @Test
    fun theCardShowsPercentWithTheCapacityAndMahWithoutIt() {
        assertEquals(5000.0, PhoneBattery.capacityMah(50, 2_500_000), 1e-9)
        assertEquals(0.0, PhoneBattery.capacityMah(3, 150_000), 0.0)
        assertEquals(String.format(java.util.Locale.getDefault(), "%.2f %%", 0.02), PhoneInsights.energyText(1.0, 5000.0))
        assertEquals(String.format(java.util.Locale.getDefault(), "%.1f %%", 1.1), PhoneInsights.energyText(55.0, 5000.0))
        assertEquals("≈ 55 mAh", PhoneInsights.energyText(55.0, 0.0))
    }
}

/** El formato de decimales del resumen (el de SessionSummary, con el idioma del sistema). */
object SessionSummaryTestHelper {
    fun f1(v: Double): String = String.format(java.util.Locale.getDefault(), "%.1f", v)
}
