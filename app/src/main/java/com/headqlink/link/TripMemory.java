package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * El viaje en curso guardado en el móvil, para retomarlo si la app se reinicia a mitad (Android la cierra, un fallo, una
 * actualización): el destino, las paradas, si es de ida y vuelta, si salió de Google Maps y la longitud total de la ruta
 * de Maps. Antes, al reiniciar con paradas, la tarjeta de Maps enseñaba la siguiente parada y no el destino, y la app
 * acababa preguntando «¿A dónde vas?» (18-10).
 *
 * Solo en el móvil y mientras dura el viaje: se borra al terminarlo (Maps deja de navegar, «Terminar ruta», llegada) y,
 * en todo caso, a las MAX_AGE_MS. Nunca va al registro.
 */
final class TripMemory {
    static final long MAX_AGE_MS = 12 * 3600_000L;
    /** Rutas más cortas que esto (m) no se retoman: no hace falta y, tan cortas, dos navegaciones distintas miden casi igual. */
    static final int MIN_ROUTE_M = 3000;
    /** Margen para «la misma ruta» (m), o el 3 %. Antes 1 km (el de MapsNavListener.sameRoute), demasiado para una corta. */
    static final int SAME_ROUTE_M = 300;
    private static final String PREFS = "hql_trip";
    private static final String K_TRIP = "trip";

    private TripMemory() {
    }

    /** Un viaje guardado. */
    static final class Saved {
        RoutePlanner.Place dest;
        final List<RoutePlanner.Place> vias = new ArrayList<>();
        boolean round;
        boolean guessed;
        boolean guessChecked;
        /** Longitud total de la ruta de Google Maps (m), o -1 si aún no navegaba. */
        int mapsTotalM = -1;
        /** Hora (de pared) a la que se guardó. */
        long atMs;
        /** Lo recorrido del viaje (RoutePlanner.TripCarry): km hechos, líneas de recálculo y relieve. */
        double kmBase;
        double[] marks = new double[0];
        double[] histKm = new double[0];
        double[] histElev = new double[0];
    }

    // ------------------------------------------------------------------ puro

    static String toJson(Saved s) {
        try {
            JSONObject o = new JSONObject().put("dest", place(s.dest)).put("round", s.round).put("guessed", s.guessed)
                    .put("checked", s.guessChecked).put("mapsTotalM", s.mapsTotalM).put("at", s.atMs);
            JSONArray v = new JSONArray();
            for (RoutePlanner.Place p : s.vias) v.put(place(p));
            o.put("vias", v);
            o.put("kmBase", s.kmBase);
            o.put("marks", arr(s.marks));
            o.put("histKm", arr(s.histKm));
            o.put("histElev", arr(s.histElev));
            return o.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /** El viaje de un JSON, o null si no hay o no se entiende. */
    static Saved fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            Saved s = new Saved();
            s.dest = place(o.getJSONObject("dest"));
            JSONArray v = o.optJSONArray("vias");
            if (v != null) for (int i = 0; i < v.length(); i++) s.vias.add(place(v.getJSONObject(i)));
            s.round = o.optBoolean("round");
            s.guessed = o.optBoolean("guessed");
            s.guessChecked = o.optBoolean("checked");
            s.mapsTotalM = o.optInt("mapsTotalM", -1);
            s.atMs = o.optLong("at");
            s.kmBase = o.optDouble("kmBase", 0);
            s.marks = arr(o.optJSONArray("marks"));
            s.histKm = arr(o.optJSONArray("histKm"));
            s.histElev = arr(o.optJSONArray("histElev"));
            if (s.histKm.length != s.histElev.length) {
                s.histKm = new double[0];
                s.histElev = new double[0];
            }
            return s;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * ¿Es el viaje que lleva Google Maps ahora? Guardado hace menos de MAX_AGE_MS y Maps navegando la misma ruta: de más
     * de MIN_ROUTE_M y la misma longitud total (SAME_ROUTE_M o el 3 %). 20-10: un viaje cerrado sin terminar se retomó
     * horas después en otra navegación de 1 km al mismo sitio (con el margen de 1 km, «la misma»).
     */
    static boolean matches(Saved s, long nowMs, int mapsTotalM) {
        if (s == null || s.dest == null || s.mapsTotalM < MIN_ROUTE_M || mapsTotalM < MIN_ROUTE_M) return false;
        if (nowMs - s.atMs > MAX_AGE_MS || nowMs < s.atMs) return false;
        return Math.abs(s.mapsTotalM - mapsTotalM) <= Math.max(SAME_ROUTE_M, 0.03 * Math.max(s.mapsTotalM, mapsTotalM));
    }

    private static JSONArray arr(double[] a) throws JSONException {
        JSONArray j = new JSONArray();
        if (a != null) for (double d : a) j.put(Double.isNaN(d) ? 0 : Math.round(d * 1000) / 1000.0);
        return j;
    }

    private static double[] arr(JSONArray j) {
        if (j == null) return new double[0];
        double[] a = new double[j.length()];
        for (int i = 0; i < a.length; i++) a[i] = j.optDouble(i, 0);
        return a;
    }

    private static JSONObject place(RoutePlanner.Place p) throws JSONException {
        return new JSONObject().put("name", p.name == null ? "" : p.name).put("detail", p.detail == null ? "" : p.detail)
                .put("lat", p.lat).put("lon", p.lon);
    }

    private static RoutePlanner.Place place(JSONObject o) throws JSONException {
        RoutePlanner.Place p = new RoutePlanner.Place();
        p.name = o.getString("name");
        p.detail = o.optString("detail");
        p.lat = o.getDouble("lat");
        p.lon = o.getDouble("lon");
        return p;
    }

    // ------------------------------------------------------------------ en el móvil

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, Saved s) {
        sp(c).edit().putString(K_TRIP, toJson(s)).apply();
    }

    static Saved load(Context c) {
        return fromJson(sp(c).getString(K_TRIP, ""));
    }

    static void clear(Context c) {
        sp(c).edit().remove(K_TRIP).apply();
    }
}
