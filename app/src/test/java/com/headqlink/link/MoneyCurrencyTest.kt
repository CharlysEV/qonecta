package com.headqlink.link

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.util.Locale
import org.robolectric.annotation.Config as RoboConfig

/**
 * La moneda (pedido en el foro LPMFF desde Suiza, 27-10): automática por el país de la SIM, los importes y los precios
 * escritos en ella, y los precios guardados pasados con el cambio aproximado al cambiarla.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class)
class MoneyCurrencyTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    @Before
    fun clean() {
        Str.init(ctx)
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun backToEuros() {
        Money.setForTest("EUR")
    }

    @Test
    fun automaticBySimCountryThenRegion() {
        assertEquals("CHF", Money.autoCode("ch", "ES"))
        assertEquals("CHF", Money.autoCode("li", ""))
        // Una SIM española en Suiza sigue en euros: manda la SIM, no la red.
        assertEquals("EUR", Money.autoCode("es", "CH"))
        // Sin SIM (tableta), la región del móvil.
        assertEquals("SEK", Money.autoCode("", "SE"))
        assertEquals("DKK", Money.autoCode("dk", null))
        assertEquals("EUR", Money.autoCode("bg", "")) // Bulgaria, en euros desde 2026
        assertEquals("EUR", Money.autoCode(null, null))
    }

    @Test
    fun amountsInTheChosenCurrency() {
        Money.setForTest("CHF")
        assertEquals("CHF", plain(Money.symbol(Locale.forLanguageTag("de-CH"))))
        assertEquals("12.50", plain(Money.format(12.5, 2, Locale.forLanguageTag("de-CH"))).replace("CHF", "").replace("’", "").trim())
        assertEquals("0,49 CHF/kWh", SettingsRows.priceText(0.49, "kWh", Locale.forLanguageTag("es-ES")))
        // Sin céntimos en las monedas que no los tienen.
        Money.setForTest("ISK")
        assertEquals(false, plain(Money.format(1234.56, 2, Locale.forLanguageTag("is"))).contains(","))
    }

    @Test
    fun pricesAreConvertedWhenTheCurrencyChanges() {
        val cfg = Config(ctx)
        cfg.setFastChargePrice(0.59)
        cfg.setFuelPrice(1.80)
        assertEquals("EUR", cfg.pricesCurrency())
        // A coronas suecas: con el cambio aproximado y dentro del tope en coronas.
        cfg.convertPrices("EUR", "SEK")
        Money.setForTest("SEK")
        assertEquals("SEK", cfg.pricesCurrency())
        assertEquals(0.59 * Money.rate("SEK"), cfg.fastChargePrice(), 0.01)
        assertEquals(1.80 * Money.rate("SEK"), cfg.fuelPrice(), 0.01)
        // El que nunca se puso, el de serie pasado a coronas.
        assertEquals(0.20 * Money.rate("SEK"), cfg.electricityPrice(), 0.01)
        // Y los topes, también en coronas: 6,50 coronas por kWh caben (tope ≈ 16,8).
        cfg.setFastChargePrice(6.5)
        assertEquals(6.5, cfg.fastChargePrice(), 1e-6)
        // De vuelta a euros, como estaban (redondeado al céntimo).
        cfg.convertPrices("SEK", "EUR")
        Money.setForTest("EUR")
        assertEquals(0.58, cfg.fastChargePrice(), 0.011)
    }

    @Test
    fun init() {
        val cfg = Config(ctx)
        cfg.setCurrencyChoice("CHF")
        cfg.setDieselPrice(1.70)
        Money.init(ctx)
        assertEquals("CHF", Money.currency().currencyCode)
        assertEquals("CHF", cfg.pricesCurrency())
        assertEquals(1.70 * Money.rate("CHF"), cfg.dieselPrice(), 0.011)
        // Otra vez, sin cambio: no se vuelve a pasar.
        Money.init(ctx)
        assertEquals(1.70 * Money.rate("CHF"), cfg.dieselPrice(), 0.011)
    }

    @Test
    fun writtenPricesInOtherCurrencies() {
        assertEquals(1.85, SettingsRows.parsePrice("1,85 CHF/l", 4.0), 1e-9)
        assertEquals(2.5, SettingsRows.parsePrice("2,50 kr", 10.0), 1e-9)
        // En forintos, más cifras enteras (el tope lo permite) …
        assertEquals(615.0, SettingsRows.parsePrice("615 Ft/l", 4.0 * Money.rate("HUF")), 1e-9)
        // … pero no en euros.
        assertEquals(-1.0, SettingsRows.parsePrice("615", 4.0), 0.0)
    }
}
