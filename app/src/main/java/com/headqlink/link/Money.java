package com.headqlink.link;

import android.content.Context;
import android.telephony.TelephonyManager;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Importes en la interfaz con el formato del idioma de la app: «1,23 €» en español, francés o alemán, «€1.23» en inglés,
 * «€ 1,23» en neerlandés. Para logs, CSV y JSON no: ahí siguen las cifras sin formato.
 *
 * La moneda (27-10, pedido en el foro LPMFF desde Suiza): la elegida en Ajustes o, en automático, la del país de la SIM
 * (no la de la red: viajando a Suiza, los precios de casa siguen en euros), si no la de la región del móvil, y si no,
 * euros. Los precios que se guardan (Config) van en esa moneda; al cambiarla se pasan con un cambio aproximado (RATES),
 * igual que los topes y los valores de serie, para que en coronas o en forintos tengan sentido.
 */
public final class Money {
    static final Currency EUR = Currency.getInstance("EUR");

    /** Las monedas que se pueden elegir: las de Europa donde se vende Leapmotor y alrededores. */
    static final String[] CODES = {"EUR", "CHF", "GBP", "DKK", "SEK", "NOK", "PLN", "CZK", "HUF", "RON", "ISK"};
    /** Unidades por euro, aproximadas (octubre de 2026): solo para topes, valores de serie y pasar precios al cambiar. */
    private static final double[] RATES = {1, 0.94, 0.86, 7.46, 11.2, 11.7, 4.3, 24.5, 395, 5.0, 150};

    private static volatile Currency current = EUR;

    private Money() {
    }

    /** La moneda de los importes de ahora. */
    static Currency currency() {
        return current;
    }

    /** Puro: unidades de code por euro (aproximado); 1 si no se conoce. */
    static double rate(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return RATES[i];
        return 1;
    }

    /** Unidades de la moneda de ahora por euro (para los topes y los valores de serie de los precios). */
    static double rate() {
        return rate(current.getCurrencyCode());
    }

    /**
     * Puro: la moneda en automático por el país (ISO de dos letras) de la SIM o, si no hay, el de la región del móvil:
     * la propia de Suiza, Liechtenstein, Reino Unido, los nórdicos fuera del euro, Polonia, Chequia, Hungría y Rumanía;
     * euros en el resto (también Bulgaria desde 2026).
     */
    static String autoCode(String simIso, String regionIso) {
        String iso = simIso != null && !simIso.isEmpty() ? simIso : regionIso == null ? "" : regionIso;
        switch (iso.toUpperCase(Locale.ROOT)) {
            case "CH":
            case "LI":
                return "CHF";
            case "GB":
                return "GBP";
            case "DK":
            case "FO":
            case "GL":
                return "DKK";
            case "SE":
                return "SEK";
            case "NO":
                return "NOK";
            case "PL":
                return "PLN";
            case "CZ":
                return "CZK";
            case "HU":
                return "HUF";
            case "RO":
                return "RON";
            case "IS":
                return "ISK";
            default:
                return "EUR";
        }
    }

    /** La moneda en automático de este móvil (SIM; si no, región). */
    static String autoCode(Context ctx) {
        String sim = "";
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && tm.getSimCountryIso() != null) sim = tm.getSimCountryIso();
        } catch (RuntimeException ignored) {
            // Sin telefonía (tableta): la región.
        }
        android.content.res.Configuration sys = android.content.res.Resources.getSystem().getConfiguration();
        //noinspection deprecation: getLocales es de Android 7 y la app llega a Android 6
        Locale region = android.os.Build.VERSION.SDK_INT >= 24 ? sys.getLocales().get(0) : sys.locale;
        return autoCode(sim, region == null ? "" : region.getCountry());
    }

    /** La moneda que toca (la elegida o la automática). */
    static String code(Context ctx, Config cfg) {
        String chosen = cfg.currencyChoice();
        return chosen.isEmpty() ? autoCode(ctx) : chosen;
    }

    /**
     * Al arrancar y al cambiarla en Ajustes: la moneda que toca y, si los precios guardados estaban en otra, se pasan
     * con el cambio aproximado.
     */
    public static void init(Context ctx) {
        Config cfg = new Config(ctx);
        String code = code(ctx, cfg);
        String was = cfg.pricesCurrency();
        if (!was.equals(code)) {
            cfg.convertPrices(was, code);
            L.i("moneda: " + code + " (antes " + was + "): precios pasados con un cambio aproximado");
        }
        try {
            current = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            current = EUR;
        }
    }

    /** Para las pruebas: la moneda de los importes. */
    static void setForTest(String code) {
        current = Currency.getInstance(code);
    }

    /** El símbolo de la moneda en el idioma de la app («€», «CHF», «£», «kr»), para los campos de los precios. */
    static String symbol() {
        return symbol(Str.locale());
    }

    /** Puro: el símbolo de la moneda de ahora en loc. */
    static String symbol(Locale loc) {
        return current.getSymbol(loc == null ? Locale.getDefault() : loc);
    }

    /** Un importe con dos decimales en el idioma de la app. */
    static String format(double amount) {
        return format(amount, 2, Str.locale());
    }

    /** Un importe con {@code decimals} decimales en el idioma de la app («≈ 12 €» con 0). */
    static String format(double amount, int decimals) {
        return format(amount, decimals, Str.locale());
    }

    /** Un precio por unidad en el idioma de la app: «0,25 €/kWh», «€0.25/kWh». */
    static String per(double price, String unit) {
        return per(price, unit, Str.locale());
    }

    /** Puro: el importe en la moneda de ahora con el formato de {@code loc} y {@code decimals} decimales. */
    static String format(double amount, int decimals, Locale loc) {
        return currency(decimals, loc).format(amount);
    }

    /** Un importe en euros, sea cual sea la moneda elegida (los precios ad hoc de Alemania): «0,59 €», «€0.59». */
    static String formatEur(double amount) {
        return currency(2, Str.locale(), EUR).format(amount);
    }

    /** Un precio por unidad en euros: «0,59 €/kWh». */
    static String perEur(double price, String unit) {
        return formatEur(price) + "/" + unit;
    }

    /** Puro: precio por unidad («0,25 €/kWh», «€0.25/kWh», «€ 0,25/kWh»). */
    static String per(double price, String unit, Locale loc) {
        return format(price, 2, loc) + "/" + unit;
    }

    /**
     * Puro: el importe partido para las cifras grandes del coche (número grande y unidad pequeña detrás):
     * {cifra, «€»} si el idioma pone el símbolo detrás («12,34» + «€»); si lo pone delante, todo en la cifra
     * («€12.34» + «») para no escribir «12.34 €» en inglés.
     */
    static String[] parts(double amount, int decimals, Locale loc) {
        NumberFormat f = currency(decimals, loc);
        String s = f.format(amount);
        String sym = f instanceof DecimalFormat ? ((DecimalFormat) f).getDecimalFormatSymbols().getCurrencySymbol() : "€";
        int at = s.lastIndexOf(sym);
        if (at <= 0 || at + sym.length() < s.length()) return new String[]{s, ""};
        return new String[]{s.substring(0, at).trim().replace(' ', ' ').replace(' ', ' ').trim(), sym};
    }

    /** {@link #parts(double, int, Locale)} en el idioma de la app, con dos decimales. */
    static String[] parts(double amount) {
        return parts(amount, 2, Str.locale());
    }

    private static NumberFormat currency(int decimals, Locale loc) {
        return currency(decimals, loc, current);
    }

    private static NumberFormat currency(int decimals, Locale loc, Currency cur) {
        NumberFormat f = NumberFormat.getCurrencyInstance(loc == null ? Locale.getDefault() : loc);
        f.setCurrency(cur);
        // Las monedas sin céntimos (corona islandesa), sin decimales.
        if (cur.getDefaultFractionDigits() == 0) decimals = 0;
        f.setMinimumFractionDigits(decimals);
        f.setMaximumFractionDigits(decimals);
        // Como String.format (0,125 → 0,13), no al par más cercano.
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }
}
