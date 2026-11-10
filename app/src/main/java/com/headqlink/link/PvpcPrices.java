package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Los precios por hora del PVPC (la tarifa regulada española) para «Carga barata», de Red Eléctrica (apidatos.ree.es,
 * datos abiertos, sin clave). Los de mañana se publican hacia las 20:20; antes no están. Lo leído se guarda en el móvil
 * y no se pregunta más de una vez cada MIN_GAP_MS, ni antes de la hora de publicación si lo de hoy ya está.
 */
final class PvpcPrices {
    static final String URL = "https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real";
    /** El indicador del PVPC en la respuesta (el otro, 600, es el mercado mayorista). */
    static final String ID_PVPC = "1001";
    static final long MIN_GAP_MS = 30 * 60_000L;
    /** Antes de esta hora (peninsular) los precios de mañana no están publicados: no se piden. */
    static final int PUBLISHED_MINUTE = 20 * 60 + 15;
    /** Se guardan las horas de hasta hace esto (las de ayer valen de estimación). */
    static final long KEEP_MS = 3 * 86_400_000L;
    static final TimeZone MADRID = TimeZone.getTimeZone("Europe/Madrid");
    private static final String FILE = "pvpc.json";

    /** Tabla inmutable: el precio (€/kWh) de cada hora, por su comienzo. */
    static final class Table implements CheapWindow.Prices {
        static final Table EMPTY = new Table(new TreeMap<>());
        private final TreeMap<Long, Double> byHour;

        Table(TreeMap<Long, Double> byHour) {
            this.byHour = byHour;
        }

        @Override
        public double at(long hourStartMs) {
            Double v = byHour.get(hourStartMs);
            return v == null ? Double.NaN : v;
        }

        boolean isEmpty() {
            return byHour.isEmpty();
        }

        int size() {
            return byHour.size();
        }

        /** El comienzo de la última hora con precio, o 0. */
        long lastHour() {
            return byHour.isEmpty() ? 0 : byHour.lastKey();
        }

        /** ¿Se sabe el precio de todas las horas de fromMs a toMs? */
        boolean covers(long fromMs, long toMs) {
            for (long h = fromMs - Math.floorMod(fromMs, CheapWindow.HOUR_MS); h < toMs; h += CheapWindow.HOUR_MS) {
                if (!byHour.containsKey(h)) return false;
            }
            return true;
        }

        /** Esta tabla con lo de other encima, sin las horas anteriores a fromMs. */
        Table merged(Table other, long fromMs) {
            TreeMap<Long, Double> m = new TreeMap<>(byHour);
            m.putAll(other.byHour);
            m.headMap(fromMs, false).clear();
            return new Table(m);
        }

        /**
         * Los precios con estimación: de las horas que aún no se saben (las de mañana antes de publicarse), el de la
         * misma hora del día anterior (o del anterior a ese).
         */
        CheapWindow.Prices withEstimates() {
            return h -> {
                for (int d = 0; d <= 2; d++) {
                    double v = at(h - d * 86_400_000L);
                    if (!Double.isNaN(v)) return v;
                }
                return Double.NaN;
            };
        }

        /** Los mismos precios por un factor (el cambio de la moneda de la app). */
        Table times(double k) {
            if (k == 1) return this;
            TreeMap<Long, Double> m = new TreeMap<>();
            for (Map.Entry<Long, Double> e : byHour.entrySet()) m.put(e.getKey(), e.getValue() * k);
            return new Table(m);
        }

        JSONArray toJson() {
            JSONArray a = new JSONArray();
            for (Map.Entry<Long, Double> e : byHour.entrySet()) {
                a.put(new JSONArray().put(e.getKey().longValue()).put(String.valueOf(e.getValue())));
            }
            return a;
        }

        static Table fromJson(JSONArray a) {
            TreeMap<Long, Double> m = new TreeMap<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray p = a.optJSONArray(i);
                if (p == null || p.length() < 2) continue;
                double v = p.optDouble(1, Double.NaN);
                if (!Double.isNaN(v)) m.put(p.optLong(0), v);
            }
            return new Table(m);
        }
    }

    private static Table memo;
    private static long triedAtMs;
    private static boolean loaded;
    /** Hay una consulta en marcha (otro hilo): no se lanza otra a la vez. */
    private static boolean fetching;

    private PvpcPrices() {
    }

    /**
     * Puro: lo que contesta Red Eléctrica. De «included», el indicador 1001 (PVPC), con «values» de {value: €/MWh,
     * datetime: "2026-11-10T00:00:00.000+01:00"}; sale en €/kWh por el comienzo de cada hora.
     */
    static Table parse(String json) throws JSONException {
        JSONArray inc = new JSONObject(json).optJSONArray("included");
        TreeMap<Long, Double> m = new TreeMap<>();
        for (int i = 0; inc != null && i < inc.length(); i++) {
            JSONObject o = inc.optJSONObject(i);
            if (o == null || !ID_PVPC.equals(o.optString("id"))) continue;
            JSONObject at = o.optJSONObject("attributes");
            JSONArray values = at == null ? null : at.optJSONArray("values");
            for (int k = 0; values != null && k < values.length(); k++) {
                JSONObject v = values.optJSONObject(k);
                if (v == null) continue;
                double mwh = v.optDouble("value", Double.NaN);
                if (Double.isNaN(mwh)) continue;
                long t = time(v.optString("datetime"));
                if (t > 0) m.put(t - Math.floorMod(t, CheapWindow.HOUR_MS), mwh / 1000.0);
            }
        }
        return new Table(m);
    }

    /**
     * Puro: «2026-11-10T00:00:00.000+01:00» en ms, o 0 si no vale. A mano: el patrón «XXX» de SimpleDateFormat es de
     * Android 7 y la app llega a Android 6.
     */
    static long time(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:[.][0-9]+)?(Z|[+-][0-9]{2}:?[0-9]{2})$")
                .matcher(s == null ? "" : s.trim());
        if (!m.matches()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long t = f.parse(m.group(1)).getTime();
            String z = m.group(2).replace(":", "");
            if (z.equals("Z")) return t;
            long off = (Integer.parseInt(z.substring(1, 3)) * 60L + Integer.parseInt(z.substring(3, 5))) * 60_000L;
            return z.charAt(0) == '-' ? t + off : t - off;
        } catch (ParseException | RuntimeException e) {
            return 0;
        }
    }

    /** Puro: la consulta de hoy y mañana (días de la península). */
    static String url(long nowMs) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        day.setTimeZone(MADRID);
        return URL + "?start_date=" + day.format(new java.util.Date(nowMs)) + "T00:00&end_date=" + day.format(new java.util.Date(nowMs + 86_400_000L))
                + "T23:59&time_trunc=hour";
    }

    /**
     * Puro: ¿se pregunta ahora? Si falta alguna hora de aquí a untilMs, hace más de MIN_GAP_MS de la última vez y, si lo
     * que falta es solo lo de mañana, ya es la hora a la que se publica.
     */
    static boolean due(Table t, long nowMs, long untilMs, long triedAtMs) {
        if (t.covers(nowMs, untilMs)) return false;
        if (triedAtMs > 0 && nowMs - triedAtMs < MIN_GAP_MS && nowMs >= triedAtMs) return false;
        Calendar c = Calendar.getInstance(MADRID);
        c.setTimeInMillis(nowMs);
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        boolean todayDone = t.covers(nowMs, Math.min(untilMs, c.getTimeInMillis()));
        return !todayDone || minute >= PUBLISHED_MINUTE;
    }

    /** Lo guardado en el móvil (sin red). */
    static synchronized Table cached(Context ctx) {
        if (!loaded) {
            loaded = true;
            memo = Table.EMPTY;
            File f = new File(ctx.getFilesDir(), FILE);
            if (f.isFile()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    JSONObject o = new JSONObject(new String(Http.readAll(in), StandardCharsets.UTF_8));
                    memo = Table.fromJson(o.optJSONArray("v"));
                    triedAtMs = o.optLong("tried");
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        return memo;
    }

    /**
     * Los precios de aquí a untilMs: los guardados y, si faltan y toca (due), una consulta a Red Eléctrica. Con red: fuera
     * del hilo principal. Si falla, lo que hubiera.
     */
    static Table get(Context ctx, long nowMs, long untilMs) {
        synchronized (PvpcPrices.class) {
            Table t = cached(ctx);
            if (fetching || !due(t, nowMs, untilMs, triedAtMs)) return t;
            fetching = true;
            triedAtMs = nowMs;
        }
        // La consulta, sin el cerrojo: la tarjeta de «Mi coche» lee lo guardado (cached) desde el hilo principal.
        Table fresh = null;
        try {
            fresh = parse(Http.get(url(nowMs)));
        } catch (Exception e) {
            L.w("carga barata: precios del PVPC no disponibles (" + Http.safeError(e) + ")");
        }
        synchronized (PvpcPrices.class) {
            fetching = false;
            Table t = cached(ctx);
            if (fresh != null) {
                t = t.merged(fresh, nowMs - KEEP_MS);
                memo = t;
                L.i("carga barata: precios del PVPC leídos (" + fresh.size() + " horas"
                        + (t.covers(nowMs, untilMs) ? "" : "; los de mañana aún no están") + ")");
            }
            save(ctx, t);
            return t;
        }
    }

    private static void save(Context ctx, Table t) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
            out.write(new JSONObject().put("tried", triedAtMs).put("v", t.toJson()).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.w("carga barata: no se pudieron guardar los precios (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Para las pruebas: como recién instalada. */
    static synchronized void resetForTest() {
        memo = null;
        triedAtMs = 0;
        loaded = false;
        fetching = false;
    }
}
