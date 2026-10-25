package com.headqlink.link;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * El fantasma: compites contra tu mejor vez en un camino que repites. Con los recorridos guardados (TripLog: un punto
 * cada 30 m con el segundo del viaje) se buscan los viajes que salieron del mismo sitio y pasaron por donde vas ahora (y
 * por la mitad de tu camino de hoy, para que sea el mismo camino y no otro que acaba igual); el fantasma es el que llegó
 * aquí antes. Puro: lo prueban los tests.
 */
final class Ghost {
    /** Mismo sitio de salida (o de llegada) a menos de esto. */
    static final double SAME_PLACE_M = 300;
    /** El otro viaje pasó por aquí si tiene un punto a menos de esto (los puntos van cada ~30 m). */
    static final double ON_ROUTE_M = 80;
    /** Hasta haber hecho esto no se compara (al salir todo el mundo va igual). */
    static final double MIN_KM = 1.0;

    private Ghost() {
    }

    /** La comparación de ahora: deltaSec > 0, vas por detrás; < 0, por delante. */
    static final class Result {
        final double deltaSec;
        /** Cuándo fue tu mejor vez (inicio de aquel viaje). */
        final long bestStartMs;
        /** Cuántas veces has hecho este camino hasta aquí (sin contar hoy). */
        final int runs;

        Result(double deltaSec, long bestStartMs, int runs) {
            this.deltaSec = deltaSec;
            this.bestStartMs = bestStartMs;
            this.runs = runs;
        }
    }

    /**
     * Compara el viaje de ahora (now: [lat, lon, segundo]) con los guardados. null si aún no se ha hecho MIN_KM o no hay
     * ningún viaje que saliera de aquí y pasara por el mismo camino.
     */
    static Result compare(List<TripLog.Trip> past, List<double[]> now, long nowStartMs) {
        if (now == null || now.size() < 3) return null;
        double km = 0;
        double[] cum = new double[now.size()];
        for (int i = 1; i < now.size(); i++) {
            double[] a = now.get(i - 1);
            double[] b = now.get(i);
            cum[i] = cum[i - 1] + RoadInfo.dist(a[0], a[1], b[0], b[1]);
        }
        km = cum[cum.length - 1] / 1000;
        if (km < MIN_KM) return null;
        double[] start = now.get(0);
        double[] here = now.get(now.size() - 1);
        // El punto de hoy a mitad de camino.
        int mid = 0;
        while (mid < cum.length - 1 && cum[mid] < cum[cum.length - 1] / 2) mid++;
        double[] half = now.get(mid);
        double bestSec = Double.NaN;
        long bestStart = 0;
        int runs = 0;
        for (TripLog.Trip t : past) {
            double[][] tr = t.track;
            if (tr == null || tr.length < 3 || Math.abs(t.startMs - nowStartMs) < 60_000L) continue;
            if (RoadInfo.dist(tr[0][0], tr[0][1], start[0], start[1]) > SAME_PLACE_M) continue;
            int j = nearest(tr, half, 0);
            if (j < 0) continue;
            int k = nearest(tr, here, j);
            if (k < 0) continue;
            double sec = tr[k][2] - tr[0][2];
            if (Double.isNaN(sec) || sec <= 0) continue;
            runs++;
            if (Double.isNaN(bestSec) || sec < bestSec) {
                bestSec = sec;
                bestStart = t.startMs;
            }
        }
        if (runs == 0) return null;
        double mine = here[2] - start[2];
        return new Result(mine - bestSec, bestStart, runs);
    }

    /** El índice del punto de tr (desde from) más cerca de p, si está a menos de ON_ROUTE_M; si no, -1. */
    static int nearest(double[][] tr, double[] p, int from) {
        int best = -1;
        double bd = ON_ROUTE_M;
        for (int i = Math.max(0, from); i < tr.length; i++) {
            double d = RoadInfo.dist(tr[i][0], tr[i][1], p[0], p[1]);
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Los viajes que son el récord de su camino: el más rápido (en minutos) de los que salieron y llegaron al mismo sitio,
     * si ese camino se ha hecho al menos dos veces. Devuelve sus índices en trips.
     */
    static Set<Integer> routeRecords(List<TripLog.Trip> trips) {
        Set<Integer> out = new HashSet<>();
        int n = trips.size();
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (seen[i] || !hasEnds(trips.get(i))) continue;
            int best = i;
            int count = 1;
            for (int j = i + 1; j < n; j++) {
                if (seen[j] || !hasEnds(trips.get(j)) || !sameRoute(trips.get(i), trips.get(j))) continue;
                seen[j] = true;
                count++;
                if (trips.get(j).minutes < trips.get(best).minutes) best = j;
            }
            if (count >= 2) out.add(best);
        }
        return out;
    }

    private static boolean hasEnds(TripLog.Trip t) {
        return t.track != null && t.track.length >= 3 && t.km >= MIN_KM && t.minutes > 0;
    }

    /** Mismo sitio de salida y de llegada, y la mitad del uno pasa por el otro. */
    static boolean sameRoute(TripLog.Trip a, TripLog.Trip b) {
        double[] a0 = a.track[0];
        double[] a1 = a.track[a.track.length - 1];
        double[] b0 = b.track[0];
        double[] b1 = b.track[b.track.length - 1];
        if (RoadInfo.dist(a0[0], a0[1], b0[0], b0[1]) > SAME_PLACE_M) return false;
        if (RoadInfo.dist(a1[0], a1[1], b1[0], b1[1]) > SAME_PLACE_M) return false;
        return nearest(b.track, a.track[a.track.length / 2], 0) >= 0;
    }
}
