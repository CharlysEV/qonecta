package com.headqlink.link;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Cuentas de los viajes guardados (TripLog) para la pestaña Viajes: consumo y velocidad media de cada uno, km por día
 * de las dos últimas semanas, totales de un periodo y récords. Sin Android: se prueba en el PC.
 */
final class TripStats {
    /** Viajes más cortos que esto no cuentan para el récord de eficiencia (el consumo de 1 km no dice nada). */
    static final double MIN_KM_FOR_RECORD = 5;

    private TripStats() {
    }

    /** kWh/100 km estimados del viaje, o NaN si es demasiado corto. */
    static double kwhPer100(TripLog.Trip t) {
        return t.km >= 0.5 ? t.kwh / t.km * 100 : Double.NaN;
    }

    /** Consumo real del viaje con los datos del coche (nube de Leapmotor) al empezar y al terminar. */
    static CloudEnergy.Result real(TripLog.Trip t) {
        return CloudEnergy.between(t.socStart, t.odoStart, t.socEnd, t.odoEnd, t.capKwh, t.charged);
    }

    /** kWh/100 km reales del viaje, o NaN si no hay datos del coche o no dan una medida fiable. */
    static double realKwhPer100(TripLog.Trip t) {
        CloudEnergy.Result r = real(t);
        return r.ok() ? r.kwhPer100 : Double.NaN;
    }

    /** El mejor dato del viaje: el real si lo hay; si no, el estimado. */
    static double bestKwhPer100(TripLog.Trip t) {
        double r = realKwhPer100(t);
        return Double.isNaN(r) ? kwhPer100(t) : r;
    }

    // ------------------------------------------------------------------ lo que dice el coche (historial de la nube)

    /** Lo que dice el coche de este viaje (sus viajes del historial de la nube que caen dentro), o null. */
    static CloudHistory.Match car(TripLog.Trip t, List<CloudHistory.Trip> history) {
        return CloudHistory.match(history, t.startMs, t.endMs());
    }

    /** kWh/100 km según el coche, o NaN. */
    static double carKwhPer100(TripLog.Trip t, List<CloudHistory.Trip> history) {
        CloudHistory.Match m = car(t, history);
        return m == null ? Double.NaN : m.kwhPer100();
    }

    /** De dónde sale el consumo que se enseña. */
    enum Source {CAR, REAL, ESTIMATED}

    /** La fuente del mejor dato: el coche (historial), el real (bajada del %) o el estimado. */
    static Source source(TripLog.Trip t, List<CloudHistory.Trip> history) {
        if (!Double.isNaN(carKwhPer100(t, history))) return Source.CAR;
        if (!Double.isNaN(realKwhPer100(t))) return Source.REAL;
        return Source.ESTIMATED;
    }

    /** El mejor dato del viaje: el del coche, el real o el estimado. */
    static double bestKwhPer100(TripLog.Trip t, List<CloudHistory.Trip> history) {
        double c = carKwhPer100(t, history);
        return Double.isNaN(c) ? bestKwhPer100(t) : c;
    }

    /** Electricidad que da un litro de gasolina en el generador de un REEV (kWh), para separar el coste. */
    static final double KWH_PER_LITER = 3.0;

    /** Litros de gasolina del viaje (REEV): los del coche si los dice; si no, los del depósito; NaN si no es REEV. */
    static double fuelL(TripLog.Trip t, List<CloudHistory.Trip> history) {
        CloudHistory.Match m = car(t, history);
        if (m != null && !Double.isNaN(m.fuelL)) return m.fuelL;
        return t.fuelUsedL();
    }

    /** L/100 km de gasolina del viaje, o NaN. */
    static double litersPer100(TripLog.Trip t, List<CloudHistory.Trip> history) {
        double l = fuelL(t, history);
        return Double.isNaN(l) || t.km < 0.5 ? Double.NaN : l / t.km * 100;
    }

    /**
     * Coste del viaje: la electricidad de la red (la bajada del % si se midió; si no, la del coche o la estimada) por su
     * precio; en un REEV, la de la batería (energy) por su precio más la gasolina por la suya.
     */
    static double cost(TripLog.Trip t, List<CloudHistory.Trip> history, double elecPrice, double fuelPrice) {
        Energy e = energy(t, history);
        if (e != null) return e.batteryKwh * elecPrice + e.fuelL * fuelPrice;
        CloudEnergy.Result r = real(t);
        if (r.ok()) return r.kwh * elecPrice;
        CloudHistory.Match m = car(t, history);
        return (m != null && m.km >= 0.5 ? m.kwh : t.kwh) * elecPrice;
    }

    /** Cómo se movió un REEV en el viaje: solo con la batería, con las dos o casi todo con gasolina. */
    enum Drive {ELECTRIC, MIXED, FUEL}

    /** Por debajo de esta parte de la energía, la batería apenas aportó: «modo combustible». */
    static final double FUEL_MODE_BATTERY_SHARE = 0.10;

    /**
     * La energía de un viaje de un REEV por fuente: la batería (lo que dice el coche, que es solo lo que salió de ella:
     * 0,07 kWh en 68,7 km en modo combustible, 25-10) y la gasolina (litros y su electricidad en el generador,
     * KWH_PER_LITER). Lo que se puede comparar con un viaje en eléctrico es el total.
     */
    static final class Energy {
        final double fuelL;
        final double fuelKwh;
        final double batteryKwh;
        final double km;

        Energy(double fuelL, double batteryKwh, double km) {
            this.fuelL = Math.max(0, fuelL);
            this.fuelKwh = this.fuelL * KWH_PER_LITER;
            this.batteryKwh = Math.max(0, batteryKwh);
            this.km = km;
        }

        double totalKwh() {
            return fuelKwh + batteryKwh;
        }

        private double per100(double v) {
            return km >= 0.5 ? v / km * 100 : Double.NaN;
        }

        double totalPer100() {
            return per100(totalKwh());
        }

        double batteryPer100() {
            return per100(batteryKwh);
        }

        double litersPer100() {
            return per100(fuelL);
        }

        /** Parte de la energía que vino de la gasolina (0-1). */
        double fuelShare() {
            double tot = totalKwh();
            return tot > 0 ? fuelKwh / tot : 0;
        }

        Drive drive() {
            if (fuelL < 0.05) return Drive.ELECTRIC;
            return 1 - fuelShare() < FUEL_MODE_BATTERY_SHARE ? Drive.FUEL : Drive.MIXED;
        }
    }

    /**
     * La energía del viaje por fuente si es de un REEV (hay litros), o null. La batería: la del coche si la dice; si no, la
     * bajada del %; si no, lo estimado menos lo que dio el generador.
     */
    static Energy energy(TripLog.Trip t, List<CloudHistory.Trip> history) {
        double fuel = fuelL(t, history);
        if (Double.isNaN(fuel)) return null;
        double litres = Math.max(0, fuel);
        CloudHistory.Match m = car(t, history);
        double battery;
        if (m != null && m.km >= 0.5) {
            battery = m.kwh;
        } else {
            CloudEnergy.Result r = real(t);
            battery = r.ok() ? r.kwh : t.kwh - litres * KWH_PER_LITER;
        }
        return new Energy(litres, battery, t.km);
    }

    /** kWh/100 km para comparar viajes: en un REEV con gasolina, el total (batería y generador); si no, el mejor dato. */
    static double comparableKwhPer100(TripLog.Trip t, List<CloudHistory.Trip> history) {
        Energy e = energy(t, history);
        if (e != null && e.drive() != Drive.ELECTRIC) return e.totalPer100();
        return bestKwhPer100(t, history);
    }

    /** Índice del viaje de menos kWh/100 km (el del coche o el real si lo tiene) entre los de al menos MIN_KM_FOR_RECORD. */
    static int mostEfficient(List<TripLog.Trip> trips, List<CloudHistory.Trip> history) {
        int best = -1;
        double bestV = Double.MAX_VALUE;
        for (int i = 0; i < trips.size(); i++) {
            TripLog.Trip t = trips.get(i);
            // En un REEV, con la gasolina incluida: si no, un viaje en modo combustible (0,1 kWh/100 km) sería el récord.
            double v = comparableKwhPer100(t, history);
            if (t.km < MIN_KM_FOR_RECORD || Double.isNaN(v) || v <= 0) continue;
            if (v < bestV) {
                bestV = v;
                best = i;
            }
        }
        return best;
    }

    /** Velocidad media (km/h, con las paradas), o NaN sin duración. */
    static double avgKmh(TripLog.Trip t) {
        return t.minutes > 0 ? t.km / (t.minutes / 60.0) : Double.NaN;
    }

    /** Medianoche local del día de ms. */
    static long startOfDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Km por día de los últimos days días; el último índice es hoy (según nowMs). */
    static double[] dailyKm(List<TripLog.Trip> trips, long nowMs, int days, TimeZone tz) {
        double[] out = new double[days];
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(startOfDay(nowMs, tz));
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        long[] starts = new long[days + 1];
        for (int i = 0; i <= days; i++) {
            starts[i] = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        for (TripLog.Trip t : trips) {
            for (int i = 0; i < days; i++) {
                if (t.startMs >= starts[i] && t.startMs < starts[i + 1]) {
                    out[i] += t.km;
                    break;
                }
            }
        }
        return out;
    }

    /** Totales de los viajes que empiezan en [fromMs, toMs). */
    static final class Totals {
        int trips;
        double km;
        double kwh;
        long minutes;
        /** Solo los viajes con consumo real (km del cuentakilómetros y kWh de la bajada del %). */
        int realTrips;
        double realKm;
        double realKwh;

        double kwhPer100() {
            return km >= 0.5 ? kwh / km * 100 : Double.NaN;
        }

        /** kWh/100 km reales de los viajes que los tienen (ponderado por km), o NaN. */
        double realKwhPer100() {
            return realKm >= 0.5 ? realKwh / realKm * 100 : Double.NaN;
        }
    }

    static Totals totals(List<TripLog.Trip> trips, long fromMs, long toMs) {
        Totals t = new Totals();
        for (TripLog.Trip x : trips) {
            if (x.startMs < fromMs || x.startMs >= toMs) continue;
            t.trips++;
            t.km += x.km;
            t.kwh += x.kwh;
            t.minutes += x.minutes;
            CloudEnergy.Result r = real(x);
            if (r.ok()) {
                t.realTrips++;
                t.realKm += r.km;
                t.realKwh += r.kwh;
            }
        }
        return t;
    }

    /** kWh/100 km de los viajes con el mejor dato de cada uno (el del coche, el real o el estimado), ponderado por km. */
    static double avgKwhPer100(List<TripLog.Trip> trips, List<CloudHistory.Trip> history) {
        double km = 0;
        double kwh = 0;
        for (TripLog.Trip t : trips) {
            double v = comparableKwhPer100(t, history);
            if (Double.isNaN(v)) continue;
            km += t.km;
            kwh += v * t.km / 100;
        }
        return km >= 0.5 ? kwh / km * 100 : Double.NaN;
    }

    /** Índice del viaje más largo (km), o -1. */
    static int longest(List<TripLog.Trip> trips) {
        int best = -1;
        for (int i = 0; i < trips.size(); i++) {
            if (best < 0 || trips.get(i).km > trips.get(best).km) best = i;
        }
        return best;
    }

    /** Índice del viaje de menos kWh/100 km (el real si lo tiene) entre los de al menos MIN_KM_FOR_RECORD, o -1. */
    static int mostEfficient(List<TripLog.Trip> trips) {
        int best = -1;
        double bestV = Double.MAX_VALUE;
        for (int i = 0; i < trips.size(); i++) {
            TripLog.Trip t = trips.get(i);
            double v = bestKwhPer100(t);
            if (t.km < MIN_KM_FOR_RECORD || Double.isNaN(v) || v <= 0) continue;
            if (v < bestV) {
                bestV = v;
                best = i;
            }
        }
        return best;
    }

    /** Índice del viaje con más desnivel subido, o -1. */
    static int mostClimb(List<TripLog.Trip> trips) {
        int best = -1;
        for (int i = 0; i < trips.size(); i++) {
            if (best < 0 || trips.get(i).climb > trips.get(best).climb) best = i;
        }
        return best;
    }

    /** kWh/100 km de todos los viajes juntos (ponderado por km), o NaN. */
    static double avgKwhPer100(List<TripLog.Trip> trips) {
        double km = 0;
        double kwh = 0;
        for (TripLog.Trip t : trips) {
            km += t.km;
            kwh += t.kwh;
        }
        return km >= 0.5 ? kwh / km * 100 : Double.NaN;
    }
}
