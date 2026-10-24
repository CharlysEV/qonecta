package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** El tiempo en la barra lateral: el código WMO de Open-Meteo como emoticono, con luna de noche y nada si no se sabe. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class WeatherEmojiTest {
    @Test
    fun codesBecomeEmojiAndTheMoonAtNight() {
        assertEquals("☀️", RouteTab.weatherEmoji(0, true))
        assertEquals("🌙", RouteTab.weatherEmoji(0, false))
        assertEquals("🌤️", RouteTab.weatherEmoji(2, true))
        assertEquals("🌙", RouteTab.weatherEmoji(1, false))
        assertEquals("☁️", RouteTab.weatherEmoji(3, false))
        assertEquals("🌫️", RouteTab.weatherEmoji(45, true))
        assertEquals("🌦️", RouteTab.weatherEmoji(53, true))
        assertEquals("🌧️", RouteTab.weatherEmoji(63, true))
        assertEquals("❄️", RouteTab.weatherEmoji(73, true))
        assertEquals("🌦️", RouteTab.weatherEmoji(81, true))
        assertEquals("🌨️", RouteTab.weatherEmoji(85, true))
        assertEquals("⛈️", RouteTab.weatherEmoji(95, true))
        // Sin dato (sin red o aún sin consulta): sin emoticono; la barra deja solo la temperatura.
        assertEquals("", RouteTab.weatherEmoji(-1, true))
    }
}
