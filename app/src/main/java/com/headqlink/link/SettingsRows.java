package com.headqlink.link;

import java.util.Locale;

/**
 * Puro: lo que no es vista de la pantalla de Ajustes del móvil (SettingsActivity): opciones de cada selector, el valor
 * que sale debajo de cada fila, la validación de lo que se escribe (precios y números de Avanzado) y si hay que volver a
 * negociar con Android Auto al reconectar. Lo prueba SettingsRowsTest.
 */
final class SettingsRows {
    /** «Tamaño de la interfaz del coche» (Config.uiScalePercent): 0 = automático, o el porcentaje elegido. */
    static final int[] UI_SCALE_CHOICES = {0, 60, 70, 80, 90, 100};
    /** Pantalla del T03 (10,1"), para decir en «Automático» a cuánto se queda allí la interfaz. */
    static final int T03_W = 1024;
    static final int T03_H = 600;
    /** Perfiles H.264 de Avanzado, en el orden del selector. */
    static final String[] H264 = {"baseline", "main", "high"};
    static final String[] H264_NAMES = {"Baseline", "Main", "High"};
    /** «Protección térmica», en el orden del selector. */
    static final String[] THERMAL = {ThermalPolicy.MODE_NORMAL, ThermalPolicy.MODE_SOFT, ThermalPolicy.MODE_OFF};
    /** Precios: lo mismo que admite Config (y los − y + de la pantalla del coche). */
    static final double PRICE_HOME_MAX = 1.0;
    static final double PRICE_FAST_MAX = 1.5;
    /** Rangos de los números de Avanzado (los del antiguo diálogo «Ajustes de imagen»). */
    static final int FPS_MAX = 120;
    static final int KBPS_MAX = 100_000;
    static final int SIZE_MAX = 4096;
    static final int WINDOW_MAX = 64;

    private SettingsRows() {
    }

    /** Puro: perfiles de imagen del selector: "" (Automático, sigue al recomendado) y después VideoProfile.ALL. */
    static String[] profileIds() {
        String[] ids = new String[VideoProfile.ALL.length + 1];
        ids[0] = "";
        System.arraycopy(VideoProfile.ALL, 0, ids, 1, VideoProfile.ALL.length);
        return ids;
    }

    /** Puro: posición de v entre las opciones, o -1 si no es ninguna. */
    static int indexOf(int[] choices, int v) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == v) return i;
        }
        return -1;
    }

    /** Puro: posición de v entre las opciones, o la de por defecto (def) si no es ninguna. */
    static int indexOf(String[] choices, String v, int def) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equals(v)) return i;
        }
        return def;
    }

    /** Puro: porcentaje al que se queda la interfaz del modo extendido en el T03 con «Automático» (UiScale). */
    static int uiScaleAutoT03() {
        return Math.round(UiScale.forCar(T03_W, T03_H, 0) * 100);
    }

    /** Puro: un número de Avanzado debajo de su fila: el valor, o autoLabel si es 0 (automático). */
    static String numberSummary(int v, String autoLabel) {
        return v > 0 ? String.valueOf(v) : autoLabel;
    }

    /** Puro: un precio como se enseña («0,20 €/kWh» en español). */
    static String priceText(double eurPerKwh, Locale loc) {
        return priceText(eurPerKwh, "kWh", loc);
    }

    /** Puro: un precio con su unidad, en la moneda de la app («1,60 €/l», «1.85 CHF/l»). */
    static String priceText(double price, String unit, Locale loc) {
        return String.format(loc, "%.2f %s/%s", price, Money.symbol(loc), unit);
    }

    /** Puro: el consumo del coche de comparación como se enseña («6,5 l/100 km»). */
    static String l100Text(double l100, Locale loc) {
        return String.format(loc, "%.1f l/100 km", l100);
    }

    /**
     * Puro: un consumo escrito (l/100 km) redondeado a la décima, o -1 si no vale: vacío, letras o fuera de
     * [Config.COMPARE_L100_MIN, Config.COMPARE_L100_MAX]. Admite coma o punto y que se escriba la unidad.
     */
    static double parseL100(String text) {
        if (text == null) return -1;
        String s = text.trim().replaceAll("(?i)l\\s*/\\s*100\\s*(km)?$", "").trim().replace(',', '.');
        if (!s.matches("[0-9]{1,2}(\\.[0-9]{0,3})?")) return -1;
        double v = Math.round(Double.parseDouble(s) * 10) / 10.0;
        return v >= Config.COMPARE_L100_MIN - 1e-9 && v <= Config.COMPARE_L100_MAX + 1e-9 ? v : -1;
    }

    /** Puro: el precio para el campo de texto (sin la unidad, con la coma o el punto del idioma). */
    static String priceField(double eurPerKwh, Locale loc) {
        return String.format(loc, "%.2f", eurPerKwh);
    }

    /** Lo que se quita de un precio escrito: los símbolos y códigos de las monedas de Money.CODES. */
    private static final String CURRENCY_MARKS = "(?i)(€|£|eur|chf|fr\\.|gbp|dkk|sek|nok|pln|czk|huf|ron|isk|kr|zł|kč|ft|lei)";

    /**
     * Puro: un precio escrito (por kWh o por litro) redondeado al céntimo, o -1 si no vale: vacío, letras, más cifras
     * enteras que el tope (dos como poco) o por encima de max. Admite coma o punto y que se escriba la moneda y la unidad
     * («0,25 €», «0.25 €/kWh», «2,05 €/l», «1.85 CHF/l»).
     */
    static double parsePrice(String text, double max) {
        if (text == null) return -1;
        String s = text.trim().replaceAll("(?i)\\s*/\\s*(kwh|l)$", "").replaceAll(CURRENCY_MARKS, "").trim().replace(',', '.');
        int digits = Math.max(2, Long.toString((long) Math.floor(Math.max(0, max))).length());
        if (!s.matches("[0-9]{0," + digits + "}(\\.[0-9]{0,4})?") || s.isEmpty() || s.equals(".")) return -1;
        double v = Math.round(Double.parseDouble(s) * 100) / 100.0;
        return v <= max + 1e-9 ? v : -1;
    }

    /**
     * Puro: al aplicar lo cambiado con el enlace en marcha, si Android Auto tiene que volver a negociar (resolución, fps y
     * densidad se negocian al conectar): otro perfil, otros fps (fluidez), otro «Tamaño de Android Auto» u otro tamaño de
     * la interfaz del coche (cambia el hueco que le queda a AA).
     */
    static boolean renegotiate(String beforeId, int beforeFps, String afterId, int afterFps, boolean aaSizeChanged,
                               boolean uiScaleChanged) {
        return !beforeId.equals(afterId) || beforeFps != afterFps || aaSizeChanged || uiScaleChanged;
    }
}
