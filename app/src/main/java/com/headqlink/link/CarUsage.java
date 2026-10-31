package com.headqlink.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * «Consumo» de la pestaña «Mi coche» (28-10, como la app de Leapmotor): km, energía y consumo de la semana (los últimos
 * 7 días), del mes en curso o del año en curso, y los viajes de ese tiempo.
 * - Semana y mes: los viajes del coche (historial de la nube, 35 días) hasta su último viaje conocido, y después los que
 *   grabó Qonecta+ (rows). Sin historial, todos los de Qonecta+.
 * - Año: los meses guardados del informe del mes (MonthlyReport, hasta 24) de este año, con el de ahora. Si el primero
 *   no es enero, se dice desde cuándo (antes no había datos).
 * Puro: se prueba en el PC.
 */
final class CarUsage {
    enum Period { WEEK, MONTH, YEAR }

    static final long DAY_MS = 86_400_000L;
    /** Viajes que se listan bajo las cifras. */
    static final int MAX_ROWS = 5;

    /** Un viaje de la lista. kwh NaN: no se sabe. */
    static final class Row {
        final long startMs;
        final long endMs;
        final double km;
        final double kwh;
        /** Del historial del coche; si no, grabado por Qonecta+. */
        final boolean fromCar;

        Row(long startMs, long endMs, double km, double kwh, boolean fromCar) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.km = km;
            this.kwh = kwh;
            this.fromCar = fromCar;
        }
    }

    /** Lo de un periodo. */
    static final class Totals {
        Period period;
        /** Inicio del periodo. */
        long fromMs;
        /** Año: inicio del primer mes con datos si no es enero; si no, 0. */
        long sinceMs;
        double km;
        /** NaN: ningún viaje dijo su energía. */
        double kwh = Double.NaN;
        int trips;
        /** Todo según el coche (nube); si no, en parte o del todo de los viajes de Qonecta+. */
        boolean fromCar;
        /** En parte del coche y en parte de Qonecta+: hasta cuándo es del coche (0: no es mixto). */
        long carUntilMs;
        /** Del más reciente al más antiguo, como mucho MAX_ROWS. */
        final List<Row> rows = new ArrayList<>();

        double per100() {
            return km >= 1 && !Double.isNaN(kwh) && kwh > 0 ? kwh / km * 100 : Double.NaN;
        }
    }

    private CarUsage() {
    }

    /** Inicio del periodo: hace 7 días, el día 1 de este mes o el 1 de enero (a las 0:00 de la zona). */
    static long fromMs(Period p, long now, TimeZone tz) {
        int[] ym = MonthlyReport.monthOf(now, tz);
        switch (p) {
            case WEEK:
                return now - 7 * DAY_MS;
            case MONTH:
                return MonthlyReport.monthStart(ym[0], ym[1], tz);
            default:
                return MonthlyReport.monthStart(ym[0], 1, tz);
        }
    }

    /** Nombre del periodo para el log y la caché de la nube. */
    static String key(Period p) {
        return p == Period.WEEK ? "semana" : p == Period.MONTH ? "mes" : "año";
    }

    /** El periodo pedido. */
    static Totals compute(Period p, long now, TimeZone tz, List<CloudHistory.Trip> history, List<TripLog.Trip> app,
                          Map<String, MonthlyReport.Summary> months) {
        if (p == Period.YEAR) return year(now, tz, history, app, months);
        return span(p, fromMs(p, now, tz), now, history, app);
    }

    /** ¿Vale lo leído de la nube para este periodo? Del mismo inicio (la semana se mueve: hasta 1 h de diferencia). */
    static boolean matches(CloudUsage.Window w, long fromMs) {
        return w != null && w.useful() && Math.abs(w.fromMs - fromMs) <= 3_600_000L;
    }

    /** Los viajes entre from y to: los del coche si hay historial; si no, los de Qonecta+. */
    static Totals span(Period p, long from, long to, List<CloudHistory.Trip> history, List<TripLog.Trip> app) {
        Totals t = new Totals();
        t.period = p;
        t.fromMs = from;
        List<Row> rows = rows(from, to, history, app);
        boolean anyApp = false;
        boolean anyCar = false;
        for (Row r : rows) {
            if (r.fromCar) anyCar = true;
            else anyApp = true;
        }
        t.fromCar = history != null && !history.isEmpty() && !anyApp;
        if (anyApp && anyCar) t.carUntilMs = carUntilMs(history);
        double kwh = 0;
        boolean any = false;
        for (Row r : rows) {
            t.trips++;
            t.km += r.km;
            if (!Double.isNaN(r.kwh)) {
                kwh += r.kwh;
                any = true;
            }
        }
        if (any) t.kwh = kwh;
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) t.rows.add(rows.get(i));
        return t;
    }

    /** Fin del último viaje que conoce el historial del coche, o 0 si no hay ninguno. */
    static long carUntilMs(List<CloudHistory.Trip> history) {
        long until = 0;
        if (history != null) for (CloudHistory.Trip c : history) until = Math.max(until, c.endMs);
        return until;
    }

    /**
     * Los viajes entre from y to, del más reciente al más antiguo: los del coche hasta su último viaje conocido y, después,
     * los que grabó Qonecta+. El historial de la nube puede quedarse parado (desde el 28-10 la nube lo rechaza) y sin esto
     * los días siguientes no existirían. Un viaje de Qonecta+ que empieza antes de ese final es el mismo que ya cuenta el
     * coche (o uno que la nube no subió): manda el coche.
     */
    static List<Row> rows(long from, long to, List<CloudHistory.Trip> history, List<TripLog.Trip> app) {
        List<Row> out = new ArrayList<>();
        long until = carUntilMs(history);
        if (history != null) {
            for (CloudHistory.Trip c : history) {
                if (c.startMs < from || c.startMs >= to) continue;
                out.add(new Row(c.startMs, c.endMs, c.km, c.kwh, true));
            }
        }
        if (app != null) {
            for (TripLog.Trip a : app) {
                if (a.startMs < from || a.startMs >= to || a.startMs <= until || !(a.km > 0)) continue;
                double per100 = TripStats.bestKwhPer100(a);
                out.add(new Row(a.startMs, a.endMs(), a.km, Double.isNaN(per100) ? Double.NaN : per100 * a.km / 100, false));
            }
        }
        out.sort((x, y) -> Long.compare(y.startMs, x.startMs));
        return out;
    }

    /**
     * El año en curso: la suma de los meses guardados de este año (el de ahora, recién calculado, va en months). Los
     * viajes que se listan, los últimos que haya.
     */
    static Totals year(long now, TimeZone tz, List<CloudHistory.Trip> history, List<TripLog.Trip> app,
                       Map<String, MonthlyReport.Summary> months) {
        Totals t = new Totals();
        t.period = Period.YEAR;
        int[] ym = MonthlyReport.monthOf(now, tz);
        t.fromMs = fromMs(Period.YEAR, now, tz);
        double kwh = 0;
        boolean any = false;
        boolean allCar = true;
        int first = 13;
        if (months != null) {
            for (MonthlyReport.Summary s : months.values()) {
                if (s == null || s.year != ym[0] || s.month > ym[1]) continue;
                first = Math.min(first, s.month);
                t.trips += s.trips;
                t.km += s.km;
                if (!Double.isNaN(s.kwh)) {
                    kwh += s.kwh;
                    any = true;
                }
                if (s.trips > 0 && !s.fromCar) allCar = false;
            }
        }
        if (any) t.kwh = kwh;
        t.fromCar = t.trips > 0 && allCar;
        if (first > 1 && first <= 12) t.sinceMs = MonthlyReport.monthStart(ym[0], first, tz);
        List<Row> rows = rows(t.fromMs, now, history, app);
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) t.rows.add(rows.get(i));
        return t;
    }
}
