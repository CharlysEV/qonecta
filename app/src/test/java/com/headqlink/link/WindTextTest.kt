package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Locale

/** El viento de la pestaña Eficiencia: el del parte y sus rachas (EfficiencyScreen necesita Android para cargarse). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class WindTextTest {
    @Test
    fun windShowsTheForecastAndItsGusts() {
        val es = Locale("es", "ES")
        assertEquals("35 km/h · rachas 70", EfficiencyScreen.windText(35.0, 70.0, "rachas", es))
        // Rachas que apenas pasan del viento, o sin dato: solo el viento.
        assertEquals("12 km/h", EfficiencyScreen.windText(12.0, 18.0, "rachas", es))
        assertEquals("12 km/h", EfficiencyScreen.windText(12.0, Double.NaN, "rachas", es))
        // Lo que nota el coche para el consumo es menos que el del parte.
        assertEquals(0.7, CarSensors.WIND_AT_CAR, 0.0)
    }
}
