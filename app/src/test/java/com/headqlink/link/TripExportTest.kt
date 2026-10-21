package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** «Exportar viajes»: el CSV en el formato del idioma (para Excel) y el GPX de un recorrido. */
class TripExportTest {
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    private fun row(): TripExport.Row = TripExport.Row().apply {
        startMs = 1_792_565_520_000L // 2026-10-21 08:52 en Madrid
        endMs = startMs + 26 * 60_000L
        km = 18.44
        minutes = 26
        kwh = 2.61
        kwhPer100 = 14.15
        source = "real (por la batería)"
        cost = 0.522
        socStart = 74.4
        socEnd = 71.0
        climb = 120.4
        descent = 98.6
        maxKmh = 91.2
        fuelL = Double.NaN
    }

    @Test
    fun spanishCsvUsesSemicolonsAndDecimalComma() {
        val csv = TripExport.csv(listOf(row()),
            "fecha|salida|llegada|km|minutos|kWh|kWh/100 km|dato|coste (€)|% al salir|% al llegar|subida (m)|bajada (m)|vel. máx. (km/h)|gasolina (L)",
            Locale.forLanguageTag("es-ES"), madrid)
        assertTrue(csv.startsWith("﻿fecha;salida;llegada;km;"))
        val line = csv.split("\r\n")[1]
        assertEquals("2026-10-21;08:52;09:18;18,4;26;2,61;14,2;real (por la batería);0,52;74,4;71,0;120;99;91;", line)
    }

    @Test
    fun englishCsvUsesCommasAndQuotesWhatNeedsIt() {
        val r = row().apply {
            source = "car, estimated"
            socStart = Double.NaN
        }
        val csv = TripExport.csv(listOf(r), "a|b|c|d|e|f|g|h|i|j|k|l|m|n|o", Locale.UK, madrid)
        val line = csv.split("\r\n")[1]
        assertEquals("2026-10-21,08:52,09:18,18.4,26,2.61,14.2,\"car, estimated\",0.52,,71.0,120,99,91,", line)
    }

    @Test
    fun gpxHasEveryPointWithItsTime() {
        val t = TripLog.Trip().apply {
            startMs = 1_792_565_520_000L
            track = arrayOf(doubleArrayOf(40.123456, -3.5, 0.0), doubleArrayOf(40.2, -3.6, 65.0), doubleArrayOf(40.3, -3.7, Double.NaN))
        }
        val g = TripExport.gpx(t, "2026-10-21 08.52")
        assertTrue(g.contains("<name>2026-10-21 08.52</name>"))
        assertTrue(g.contains("<trkpt lat=\"40.123456\" lon=\"-3.500000\"><time>2026-10-21T06:52:00Z</time></trkpt>"))
        assertTrue(g.contains("<trkpt lat=\"40.200000\" lon=\"-3.600000\"><time>2026-10-21T06:53:05Z</time></trkpt>"))
        assertTrue(g.contains("<trkpt lat=\"40.300000\" lon=\"-3.700000\"></trkpt>"))
        assertEquals("2026-10-21 08.52", TripExport.name(t.startMs, madrid))
    }
}
