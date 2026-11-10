package com.headqlink.link;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * «Carga barata»: el tramo SEGUIDO más barato para cargar entre ahora y la hora de salida, con precios constantes
 * dentro de cada hora. El coste de una ventana según su comienzo es lineal a trozos, y solo cambia de pendiente cuando
 * el comienzo o el final cruzan una hora en punto: basta probar «ahora», los comienzos en punto, los finales en punto y
 * el último comienzo posible. A igual coste, la más temprana. Puro.
 */
final class CheapWindow {
    static final long HOUR_MS = 3_600_000L;
    static final long QUARTER_MS = 900_000L;
    static final long MINUTE_MS = 60_000L;
    /** De la energía que sale del enchufe, la que llega a la batería. */
    static final double EFFICIENCY = 0.9;
    /** El fin de la ventana que se manda al coche lleva este margen sobre lo calculado. */
    static final double MARGIN = 0.15;
    /** Si la ventana más barata empieza antes de esto, se carga ya (sin ventana). */
    static final long SOON_MS = 15 * MINUTE_MS;
    /** Dos costes más cerca que esto son el mismo (gana la ventana más temprana). */
    private static final double TIE = 1e-6;

    /** Precio por kWh de cada hora. */
    interface Prices {
        /** El de la hora que empieza en hourStartMs, o NaN si no se sabe. */
        double at(long hourStartMs);
    }

    enum Kind {
        /** La batería ya está en el objetivo: nada que cargar. */
        NOT_NEEDED,
        /** Hace falta más tiempo del que queda hasta la salida: se carga ya, sin ventana. */
        NO_FIT,
        /** Lo más barato es empezar ya (o en menos de SOON_MS): sin ventana. */
        NOW,
        /** Hay una ventana más barata más tarde. */
        WINDOW,
        /** No se sabe ningún precio: se carga ya, sin ventana. */
        NO_PRICES
    }

    /** Lo calculado. */
    static final class Result {
        final Kind kind;
        /** Lo que tarda la carga. */
        final long needMs;
        /** Energía que sale del enchufe (kWh). */
        final double kwh;
        /** Comienzo de la ventana (WINDOW), o «ahora». */
        final long startMs;
        /** Cuándo acabaría la carga empezando en startMs. */
        final long chargeEndMs;
        /** El fin que se manda al coche: con el margen, al cuarto de hora siguiente y sin pasar de la salida. */
        final long endMs;
        /** Coste estimado de la ventana elegida (NaN si no se sabe). */
        final double cost;
        /** Lo que costaría cargando desde ahora (NaN si no se sabe). */
        final double costNow;

        Result(Kind kind, long needMs, double kwh, long startMs, long chargeEndMs, long endMs, double cost, double costNow) {
            this.kind = kind;
            this.needMs = needMs;
            this.kwh = kwh;
            this.startMs = startMs;
            this.chargeEndMs = chargeEndMs;
            this.endMs = endMs;
            this.cost = cost;
            this.costNow = costNow;
        }

        /** Lo que se ahorra frente a cargar al enchufar (0 si no se sabe o no hay ahorro). */
        double saving() {
            return Double.isNaN(cost) || Double.isNaN(costNow) ? 0 : Math.max(0, costNow - cost);
        }
    }

    private CheapWindow() {
    }

    /** Lo que tarda en cargar de soc al objetivo, redondeado al minuto siguiente (0 si ya está). */
    static long needMs(double soc, int target, double capKwh, double kw) {
        if (Double.isNaN(soc) || !(capKwh > 0) || !(kw > 0) || soc >= target) return 0;
        double hours = (target - soc) / 100.0 * capKwh / kw / EFFICIENCY;
        return (long) Math.ceil(hours * 60 - 1e-9) * MINUTE_MS;
    }

    /**
     * La ventana más barata. nowMs: ahora; departMs: la hora de salida; soc: batería ahora (%); target: objetivo (%);
     * capKwh: capacidad de la batería; kw: potencia del cargador de casa.
     */
    static Result plan(long nowMs, long departMs, double soc, int target, double capKwh, double kw, Prices prices, TimeZone tz) {
        long need = needMs(soc, target, capKwh, kw);
        if (need <= 0) return new Result(Kind.NOT_NEEDED, 0, 0, nowMs, nowMs, nowMs, 0, 0);
        double kwh = kw * need / (double) HOUR_MS;
        double costNow = cost(nowMs, need, kw, prices, tz);
        if (need > departMs - nowMs) return new Result(Kind.NO_FIT, need, kwh, nowMs, nowMs + need, nowMs + need, costNow, costNow);
        // Los comienzos que pueden ser el mínimo, de más temprano a más tardío.
        long last = departMs - need;
        List<Long> starts = new ArrayList<>();
        starts.add(nowMs);
        for (long b = hourStart(nowMs, tz) + HOUR_MS; b <= departMs; b += HOUR_MS) {
            if (b <= last) starts.add(b);
            if (b - need > nowMs && b - need <= last) starts.add(b - need);
        }
        starts.add(last);
        Collections.sort(starts);
        long best = -1;
        double bestCost = Double.NaN;
        for (long s : starts) {
            double c = cost(s, need, kw, prices, tz);
            if (Double.isNaN(c)) continue;
            if (best < 0 || c < bestCost - TIE) {
                best = s;
                bestCost = c;
            }
        }
        if (best < 0) return new Result(Kind.NO_PRICES, need, kwh, nowMs, nowMs + need, nowMs + need, Double.NaN, Double.NaN);
        // La hora que se manda al coche es «HH:MM»: el comienzo, al minuto.
        long start = best - Math.floorMod(best, MINUTE_MS);
        long end = Math.min(departMs, ceilQuarter(start + Math.round(need * (1 + MARGIN)), tz));
        // Empieza ya (o casi), o la ventana daría la vuelta al reloj (comienzo y fin a la misma hora): sin ventana.
        if (best - nowMs < SOON_MS || end - start > 24 * HOUR_MS - QUARTER_MS) {
            double c = Double.isNaN(costNow) ? bestCost : costNow;
            return new Result(Kind.NOW, need, kwh, nowMs, nowMs + need, nowMs + need, c, costNow);
        }
        return new Result(Kind.WINDOW, need, kwh, start, start + need, end, bestCost, costNow);
    }

    /** Lo que cuesta cargar a kw desde startMs durante needMs; NaN si falta el precio de alguna hora. */
    static double cost(long startMs, long needMs, double kw, Prices prices, TimeZone tz) {
        double total = 0;
        long end = startMs + needMs;
        for (long h = hourStart(startMs, tz); h < end; h += HOUR_MS) {
            long from = Math.max(h, startMs);
            long to = Math.min(h + HOUR_MS, end);
            if (to <= from) continue;
            double p = prices.at(h);
            if (Double.isNaN(p)) return Double.NaN;
            total += p * kw * (to - from) / (double) HOUR_MS;
        }
        return total;
    }

    /** El comienzo de la hora (local) en la que cae t. */
    static long hourStart(long t, TimeZone tz) {
        return t - Math.floorMod(t + tz.getOffset(t), HOUR_MS);
    }

    /** El cuarto de hora (local) en punto igual o siguiente a t. */
    static long ceilQuarter(long t, TimeZone tz) {
        long over = Math.floorMod(t + tz.getOffset(t), QUARTER_MS);
        return over == 0 ? t : t + QUARTER_MS - over;
    }

    /** La próxima vez que el reloj (local) marca minuteOfDay después de nowMs: hoy si aún no ha pasado; si no, mañana. */
    static long departure(long nowMs, int minuteOfDay, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, minuteOfDay / 60)));
        c.set(Calendar.MINUTE, Math.max(0, Math.min(59, minuteOfDay % 60)));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** «HH:MM» (local) de un instante, como la quiere el plan del coche. */
    static String hhmm(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** «HH:MM» de unos minutos del día (la hora de salida de los ajustes). */
    static String hhmm(int minuteOfDay) {
        int m = Math.max(0, Math.min(24 * 60 - 1, minuteOfDay));
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }
}
