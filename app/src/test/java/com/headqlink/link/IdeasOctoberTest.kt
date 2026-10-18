package com.headqlink.link

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Perfil por CarType, diagnóstico del cable, favoritos y cifras del viaje. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class IdeasOctoberTest {
    @Test
    fun carTypeDecidesTheProfile() {
        assertEquals(CarCloudStore.PROFILE_T03, CarCloudStore.profileForCarType("2D6"))
        assertEquals(CarCloudStore.PROFILE_C10_LIFE, CarCloudStore.profileForCarType("2d4"))
        assertEquals(CarCloudStore.PROFILE_C10_LIFE, CarCloudStore.profileForCarType("2D5"))
        assertNull(CarCloudStore.profileForCarType("2D9"))
        assertNull(CarCloudStore.profileForCarType(null))
        // Sin perfil: el del coche. T03 con perfil de C10 (o al revés): el del coche. Entre C10, nada. A mano, nunca.
        assertEquals(CarCloudStore.PROFILE_T03, CarCloudStore.adoptFor("", "2D6"))
        assertEquals(CarCloudStore.PROFILE_T03, CarCloudStore.adoptFor(CarCloudStore.PROFILE_C10_PROMAX, "2D6"))
        assertEquals(CarCloudStore.PROFILE_C10_LIFE, CarCloudStore.adoptFor(CarCloudStore.PROFILE_T03, "2D4"))
        assertNull(CarCloudStore.adoptFor(CarCloudStore.PROFILE_C10_PROMAX, "2D4"))
        assertNull(CarCloudStore.adoptFor(CarCloudStore.PROFILE_T03, "2D6"))
        assertNull(CarCloudStore.adoptFor(CarCloudStore.PROFILE_CUSTOM, "2D6"))
        assertNull(CarCloudStore.adoptFor("", "zz"))
    }

    @Test
    fun usbDiagnosisPowerCutOnlyWhenTheCarComesBack() {
        val d = UsbDiagnosis()
        // Sesión larga que muere por EIO y 1,3 s después se va la corriente (el T03 del 2026-10-12).
        assertEquals(UsbDiagnosis.Kind.NONE, d.onSessionEnd(100_000, true, true, 340_000, 0))
        assertEquals(UsbDiagnosis.Kind.NONE, d.onPowerLost(101_300))
        // El coche vuelve a los 35 s: fue él.
        assertEquals(UsbDiagnosis.Kind.POWER_CUT, d.onCarInfo(136_000))
        assertEquals(34, d.lastCutSeconds)
        // Solo una vez por viaje.
        d.onSessionEnd(200_000, true, true, 60_000, 0)
        d.onPowerLost(200_500)
        assertEquals(UsbDiagnosis.Kind.NONE, d.onCarInfo(210_000))
    }

    @Test
    fun usbDiagnosisUnpluggingByHandIsNotACut() {
        val d = UsbDiagnosis()
        d.onSessionEnd(100_000, true, true, 340_000, 0)
        d.onPowerLost(101_000)
        // Vuelve a enchufar 10 min después: nada que avisar.
        assertEquals(UsbDiagnosis.Kind.NONE, d.onCarInfo(700_000))
        // Corriente fuera sin sesión caída: nada.
        assertEquals(UsbDiagnosis.Kind.NONE, d.onPowerLost(800_000))
    }

    @Test
    fun usbDiagnosisSilentAndEarlyCuts() {
        val d = UsbDiagnosis()
        assertEquals(UsbDiagnosis.Kind.NONE, d.onSessionEnd(1_000, false, false, 6_000, 1))
        assertEquals(UsbDiagnosis.Kind.NONE, d.onSessionEnd(2_000, false, false, 6_000, 2))
        assertEquals(UsbDiagnosis.Kind.SILENT, d.onSessionEnd(3_000, false, false, 6_000, 3))
        assertEquals(UsbDiagnosis.Kind.NONE, d.onSessionEnd(4_000, false, false, 6_000, 4))
        val e = UsbDiagnosis()
        assertEquals(UsbDiagnosis.Kind.NONE, e.onSessionEnd(1_000, true, true, 2_500, 0))
        assertEquals(UsbDiagnosis.Kind.NONE, e.onSessionEnd(2_000, true, true, 2_400, 0))
        // Una sesión larga entre medias borra la cuenta.
        assertEquals(UsbDiagnosis.Kind.NONE, e.onSessionEnd(3_000, true, true, 90_000, 0))
        assertEquals(UsbDiagnosis.Kind.NONE, e.onSessionEnd(4_000, true, true, 2_000, 0))
        assertEquals(UsbDiagnosis.Kind.NONE, e.onSessionEnd(5_000, true, true, 2_000, 0))
        assertEquals(UsbDiagnosis.Kind.EARLY_CUTS, e.onSessionEnd(6_000, true, true, 2_000, 0))
    }

    @Test
    fun favoritesToggleByUrl() {
        val a = JSONArray()
        assertTrue(Favorites.toggle(a, Favorites.item("La 1", "https://a/1.m3u8", "", "Generalistas", listOf("https://a/1.m3u8", "https://b/1.m3u8"))))
        assertTrue(Favorites.toggle(a, Favorites.item("Radio 3", "https://r/3", "", "", null)))
        assertTrue(Favorites.contains(a, "https://a/1.m3u8"))
        assertEquals(listOf("https://a/1.m3u8", "https://b/1.m3u8"), Favorites.urls(a.getJSONObject(0)))
        assertEquals(listOf("https://r/3"), Favorites.urls(a.getJSONObject(1)))
        // Otra vez el mismo: fuera.
        assertFalse(Favorites.toggle(a, Favorites.item("La 1", "https://a/1.m3u8", "", "", null)))
        assertEquals(1, a.length())
        // De ida y vuelta por JSON; roto → ninguno.
        assertEquals(1, Favorites.fromJson(a.toString()).length())
        assertEquals(0, Favorites.fromJson("no").length())
    }

    @Test
    fun tripNoticeText() {
        Str.init(org.robolectric.RuntimeEnvironment.getApplication())
        val s = SessionSummary.TripStats(75.0, 3, 70.0, 29.6, 2, 0, 1)
        val t = TripNotice.text(s, 38.4, 25.1, 24.3)!!
        assertTrue(t, t.contains("30 fps") && t.contains("2 ") && t.contains("38 °C") && t.contains("25,1") || t.contains("25.1"))
        // Sin ruta medida, sin esa línea; sin vídeo suficiente, nada.
        assertFalse(TripNotice.text(s, Double.NaN, Double.NaN, Double.NaN)!!.contains("kWh"))
        assertNull(TripNotice.text(SessionSummary.TripStats(3.0, 1, 1.0, 30.0, 0, 0, 0), 30.0, Double.NaN, Double.NaN))
    }

    /**
     * Al llegar (18-10, idea de un usuario): los % previsto y real con sus kWh, los km y cuántos km por cada punto de
     * batería. Sin %, los kWh; con menos de un punto de bajada, sin los km por punto.
     */
    /** Km por cada 1 % en la tarjeta flotante (18-10): lo real en cuanto bajan 2 puntos; antes, lo previsto con «≈». */
    @Test
    fun kmPerPointOnTheFloatingCard() {
        assertTrue(RoutePlanner.kmPerPctReal(10.0, 1.5).isNaN())
        assertTrue(RoutePlanner.kmPerPctReal(2.0, 3.0).isNaN())
        assertEquals(4.8, RoutePlanner.kmPerPctReal(24.0, 5.0), 0.001)
        // 100 km que cuestan 18 kWh de 60 (30 puntos): 3,3 km por punto; con Maps contando un 10 % más, 3,7.
        val r = ChargePlanner.Result(ChargePlanner.Outcome.NO_STOPS, emptyList(), 70.0, doubleArrayOf(0.0, 100.0),
            doubleArrayOf(0.0, 18.0), 0.0, 100.0, 60.0)
        assertEquals(3.33, r.kmPerPctPlanned(), 0.01)
        r.mapsRatio = 1.1
        assertEquals(3.67, r.kmPerPctPlanned(), 0.01)
        val n = String.format(java.util.Locale.getDefault(), "%.1f", 4.8)
        assertEquals("$n km/%", FloatCard.kmPerPctText(4.8, 3.3))
        assertEquals("≈ $n km/%", FloatCard.kmPerPctText(Double.NaN, 4.8))
        assertNull(FloatCard.kmPerPctText(Double.NaN, Double.NaN))
    }

    @Test
    fun arrivalSummaryWithPercentKwhAndKmPerPoint() {
        Str.init(org.robolectric.RuntimeEnvironment.getApplication())
        val t = RoutePlanner.arrivalSummary(4.2, 3.6, 25.0, 27.0, 41.0, 8.0).replace(',', '.')
        assertTrue(t, t.contains("25 %") && t.contains("4.2 kWh") && t.contains("27 %") && t.contains("3.6 kWh"))
        assertTrue(t, t.contains("41 km") && t.contains("5.1 km"))
        val noPct = RoutePlanner.arrivalSummary(4.2, 3.6, Double.NaN, Double.NaN, 12.0, Double.NaN)
        assertFalse(noPct, noPct.contains("%"))
        val tiny = RoutePlanner.arrivalSummary(0.4, 0.3, 60.0, 61.0, 2.0, 0.5)
        assertFalse(tiny, tiny.contains("1 %") && tiny.contains("km por"))
        // En el resumen del móvil, la misma línea.
        val s = SessionSummary.TripStats(75.0, 3, 70.0, 29.6, 2, 0, 1)
        val n = TripNotice.text(s, 38.4, doubleArrayOf(4.2, 3.6, 41.0, 25.0, 27.0, 8.0))!!.replace(',', '.')
        assertTrue(n, n.contains("27 %") && n.contains("5.1 km"))
    }
}
