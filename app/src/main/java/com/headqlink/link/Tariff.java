package com.headqlink.link;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * La tarifa de la luz de casa para «Carga barata»: PVPC (precios por hora de Red Eléctrica), tres tramos propios
 * (valle, llano y punta: un precio por tramo y qué tramo es cada hora del día, con los fines de semana enteros en valle
 * si se quiere) o plana (un solo precio: no hay hora barata). Puro.
 */
final class Tariff {
    static final String PVPC = "pvpc";
    static final String BANDS = "bands";
    static final String FLAT = "flat";

    static final int VALLEY = 0;
    static final int MID = 1;
    static final int PEAK = 2;

    /**
     * Los tramos de la 2.0TD española en laborables, una cifra por hora (0 valle, 1 llano, 2 punta): valle de 0 a 8,
     * llano de 8 a 10, de 14 a 18 y de 22 a 24, punta de 10 a 14 y de 18 a 22.
     */
    static final String DEFAULT_HOURS = "000000001122221111222211";
    /** Precios de serie por kWh (valle, llano, punta), en euros. */
    static final double[] DEFAULT_PRICES_EUR = {0.10, 0.15, 0.22};

    /** PVPC, BANDS o FLAT. */
    final String mode;
    /** El tramo de cada hora del día (24). */
    final int[] hours;
    /** Precio por kWh de cada tramo (valle, llano, punta), en la moneda de la app. */
    final double[] prices;
    /** Sábados y domingos, todo el día en valle. */
    final boolean weekendValley;
    /** Precio por kWh de la tarifa plana. */
    final double flat;

    Tariff(String mode, int[] hours, double[] prices, boolean weekendValley, double flat) {
        this.mode = mode(mode, BANDS);
        this.hours = hours != null && hours.length == 24 ? hours.clone() : parseHours(DEFAULT_HOURS);
        this.prices = prices != null && prices.length == 3 ? prices.clone() : DEFAULT_PRICES_EUR.clone();
        this.weekendValley = weekendValley;
        this.flat = flat;
    }

    /** El modo si es uno de los tres; si no, el de reserva. */
    static String mode(String s, String fallback) {
        return PVPC.equals(s) || BANDS.equals(s) || FLAT.equals(s) ? s : fallback;
    }

    /** «000000001122…» (24 cifras de 0 a 2) como tramos por hora; si no vale, los de serie. */
    static int[] parseHours(String s) {
        String v = s != null && s.matches("[0-2]{24}") ? s : DEFAULT_HOURS;
        int[] out = new int[24];
        for (int i = 0; i < 24; i++) out[i] = v.charAt(i) - '0';
        return out;
    }

    static String hoursString(int[] hours) {
        StringBuilder b = new StringBuilder(24);
        for (int i = 0; i < 24; i++) b.append(hours != null && i < hours.length ? Math.max(VALLEY, Math.min(PEAK, hours[i])) : VALLEY);
        return b.toString();
    }

    /** El tramo que sigue al tocar una hora en el editor: valle → llano → punta → valle. */
    static int next(int band) {
        return band >= PEAK || band < VALLEY ? VALLEY : band + 1;
    }

    /** El tramo de una hora (0 a 23) de un día de la semana (Calendar.SUNDAY… Calendar.SATURDAY). */
    int band(int dayOfWeek, int hour) {
        if (weekendValley && (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)) return VALLEY;
        return hours[Math.max(0, Math.min(23, hour))];
    }

    /** El precio por kWh de la hora que empieza en hourStartMs, con los tres tramos (o el único de la plana). */
    double priceAt(long hourStartMs, TimeZone tz) {
        if (FLAT.equals(mode)) return flat;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(hourStartMs);
        return prices[band(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.HOUR_OF_DAY))];
    }

    /** Los precios por hora de esta tarifa (tres tramos o plana) para el cálculo de la ventana. */
    CheapWindow.Prices prices(TimeZone tz) {
        return h -> priceAt(h, tz);
    }
}
