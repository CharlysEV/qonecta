package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gasto con el coche parado: lo que baja la batería entre el final de un viaje y el principio del siguiente, con los
 * datos del coche (nube de Leapmotor) que guarda cada viaje (TripLog: % y cuentakilómetros al empezar y al acabar).
 * Solo cuenta si el cuentakilómetros no ha cambiado (no se condujo sin la app), el % no ha subido (no se cargó) y se
 * estuvo parado al menos MIN_HOURS. El coche da el % con un decimal (0,1 % ≈ 0,08 kWh en un C10), así que los
 * aparcamientos cortos dicen poco: lo «normal» de cada coche sale de los largos.
 *
 * 22-10: en los viajes del usuario, una noche (12-17 h) se lleva 0,1-0,3 % (0,2-1,3 %/día); los 0,2 kW que enseña
 * Coche › Estado con el coche parado son un instante con el coche despierto, no la media.
 * Sin Android: se prueba en el PC.
 */
final class ParkedDrain {
    /** Lo mínimo parado para contarlo (h). */
    static final double MIN_HOURS = 2;
    /** Más de dos semanas entre dos viajes: no se cuenta (algo se ha perdido por medio). */
    static final double MAX_HOURS = 14 * 24;
    /** El cuentakilómetros puede variar un poco de una lectura a otra sin moverse (km). */
    static final double SAME_ODO_KM = 0.5;
    /** Una subida de más de esto es una carga (%). */
    static final double CHARGED_PCT = 0.15;
    /** Lo normal (%/día) mientras no haya aparcamientos largos con los que medirlo. */
    static final double DEFAULT_TYPICAL = 1.0;
    /** Aparcamientos con los que se mide lo normal: de al menos estas horas, y al menos TYPICAL_MIN_COUNT. */
    static final double TYPICAL_MIN_HOURS = 6;
    static final int TYPICAL_MIN_COUNT = 3;
    /** Raro: al menos UNUSUAL_MIN_HOURS parado, UNUSUAL_MIN_DROP % y max(UNUSUAL_MIN_PER_DAY, UNUSUAL_FACTOR × lo normal). */
    static final double UNUSUAL_MIN_HOURS = 3;
    static final double UNUSUAL_MIN_DROP = 0.5;
    static final double UNUSUAL_MIN_PER_DAY = 2.0;
    static final double UNUSUAL_FACTOR = 3.0;

    private ParkedDrain() {
    }

    /** Un aparcamiento. */
    static final class Park {
        final long fromMs;
        final long toMs;
        final double socFrom;
        final double socTo;
        /** Capacidad de la batería (kWh), para pasar el % a kWh; NaN si no se sabe. */
        final double capKwh;

        Park(long fromMs, long toMs, double socFrom, double socTo, double capKwh) {
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.socFrom = socFrom;
            this.socTo = socTo;
            this.capKwh = capKwh;
        }

        double hours() {
            return (toMs - fromMs) / 3_600_000.0;
        }

        /** Lo que bajó (%), nunca negativo. */
        double dropPct() {
            return Math.max(0, socFrom - socTo);
        }

        double kwh() {
            return capKwh > 0 ? dropPct() / 100 * capKwh : Double.NaN;
        }

        double perDayPct() {
            return dropPct() / hours() * 24;
        }
    }

    /**
     * Puro: el aparcamiento entre el final de un viaje (soc y odo al acabar, a las fromMs) y el principio del siguiente,
     * o null si no vale (sin datos, se condujo por medio, se cargó, muy corto o muy largo).
     */
    static Park park(double socFrom, double odoFrom, long fromMs, double socTo, double odoTo, long toMs, double capKwh) {
        if (Double.isNaN(socFrom) || Double.isNaN(socTo) || Double.isNaN(odoFrom) || Double.isNaN(odoTo)) return null;
        if (Math.abs(odoTo - odoFrom) > SAME_ODO_KM) return null;
        if (socTo > socFrom + CHARGED_PCT) return null;
        double h = (toMs - fromMs) / 3_600_000.0;
        if (h < MIN_HOURS || h > MAX_HOURS) return null;
        return new Park(fromMs, toMs, socFrom, socTo, capKwh);
    }

    /** Puro: los aparcamientos entre viajes seguidos, del más reciente al más antiguo (trips en cualquier orden). */
    static List<Park> parks(List<TripLog.Trip> trips, double defaultCapKwh) {
        List<TripLog.Trip> sorted = new ArrayList<>(trips);
        Collections.sort(sorted, (a, b) -> Long.compare(a.startMs, b.startMs));
        List<Park> out = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            TripLog.Trip a = sorted.get(i - 1);
            TripLog.Trip b = sorted.get(i);
            double cap = a.capKwh > 0 ? a.capKwh : b.capKwh > 0 ? b.capKwh : defaultCapKwh;
            Park p = park(a.socEnd, a.odoEnd, a.endMs(), b.socStart, b.odoStart, b.startMs, cap);
            if (p != null) out.add(p);
        }
        Collections.reverse(out);
        return out;
    }

    /** Puro: lo normal en este coche (%/día): la mediana de los aparcamientos largos, o DEFAULT_TYPICAL si aún no hay. */
    static double typicalPerDay(List<Park> parks) {
        List<Double> v = new ArrayList<>();
        for (Park p : parks) if (p.hours() >= TYPICAL_MIN_HOURS) v.add(p.perDayPct());
        if (v.size() < TYPICAL_MIN_COUNT) return DEFAULT_TYPICAL;
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }

    /** Puro: ¿más de lo normal? Con aparcamientos cortos o bajadas de 0,1-0,4 % no se dice (el % va de 0,1 en 0,1). */
    static boolean unusual(Park p, double typicalPerDay) {
        return p.hours() >= UNUSUAL_MIN_HOURS && p.dropPct() >= UNUSUAL_MIN_DROP
                && p.perDayPct() >= Math.max(UNUSUAL_MIN_PER_DAY, UNUSUAL_FACTOR * typicalPerDay);
    }

    /** Lo de un periodo: horas parado, % y kWh que se fueron. */
    static final class Summary {
        int count;
        double hours;
        double dropPct;
        double kwh;

        /** %/día de media (por las horas parado), o NaN sin datos. */
        double perDayPct() {
            return hours > 0 ? dropPct / hours * 24 : Double.NaN;
        }

        /** kWh al mes a ese ritmo, o NaN. */
        double kwhPerMonth() {
            return hours > 0 && dropPct > 0 ? kwh / hours * 24 * 30 : hours > 0 ? 0 : Double.NaN;
        }
    }

    /** Puro: los aparcamientos que acaban desde fromMs. */
    static Summary summary(List<Park> parks, long fromMs) {
        Summary s = new Summary();
        for (Park p : parks) {
            if (p.toMs < fromMs) continue;
            s.count++;
            s.hours += p.hours();
            s.dropPct += p.dropPct();
            double k = p.kwh();
            if (!Double.isNaN(k)) s.kwh += k;
        }
        return s;
    }

    // ------------------------------------------------------------------ al subir al coche

    /** El aparcamiento de antes de este viaje (lo calcula TripLog al tener el primer dato del coche), o null. */
    static volatile Park sinceParked;
    /** ¿Más de lo normal? (con sinceParked). */
    static volatile boolean sinceParkedUnusual;
    /** Lo normal en este coche cuando se calculó (%/día). */
    static volatile double sinceParkedTypical = DEFAULT_TYPICAL;
}
