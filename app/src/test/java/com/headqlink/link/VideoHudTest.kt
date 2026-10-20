package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** El marcador de la imagen en la pantalla del coche (20-10: para todo el mundo, encendido de serie). */
class VideoHudTest {
    @Test
    fun textWithWhatIsKnown() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        try {
            assertEquals("35 °C · calor 0 · 53 fps · 0,7 Mbit/s · rtt 4/12 ms",
                VideoHud.text(35.2, 0, 53.0, 700.0, 4, 12, "calor"))
            // Por el cable no hay rtt; sin cifras de vídeo aún, solo el calor.
            assertEquals("41 °C · calor 2 · 60 fps · 15,5 Mbit/s", VideoHud.text(41.0, 2, 60.0, 15500.0, -1, -1, "calor"))
            assertEquals("36 °C · calor 1", VideoHud.text(36.0, 1, -1.0, -1.0, -1, -1, "calor"))
            // Nada conocido: vacío (el marcador no se enseña).
            assertEquals("", VideoHud.text(Double.NaN, -1, -1.0, -1.0, -1, -1, "calor"))
            // El máximo nunca por debajo del último.
            assertEquals("rtt 9/9 ms", VideoHud.text(Double.NaN, -1, -1.0, -1.0, 9, 3, "calor"))
        } finally {
            Locale.setDefault(before)
        }
    }
}
