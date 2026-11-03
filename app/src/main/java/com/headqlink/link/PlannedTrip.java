package com.headqlink.link;

import android.content.Context;

import com.andrerinas.openheadunit.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * La ruta planificada en la app del móvil (pestaña «Ruta», PhoneRoute) para el próximo viaje: el destino, las paradas
 * intermedias, si es de ida y vuelta, las paradas de carga elegidas a mano («Cambiar»), la velocidad elegida y, para
 * decir al conectar si el plan ha cambiado, las paradas que salieron en el móvil. Al empezar la sesión con el coche, la
 * tarjeta «¿Usar la ruta que planificaste?» (RoutePlanner.offerPlanned); con «Sí» se recalcula con los datos reales y se
 * abre Google Maps con las paradas, con «No» se descarta. En el modo «Auto» (sin nuestra pantalla en el coche), la
 * misma pregunta en una notificación del móvil (PlannedNotice). Una sola guardada; caduca al usarse o a las MAX_AGE_MS. Solo en el móvil y sin pasar
 * por el registro: los lugares no se escriben en el log. Guardarla quita el destino compartido (SharedDest), y al revés.
 */
final class PlannedTrip {
    static final String K = "planned_trip";
    static final long MAX_AGE_MS = 48 * 3600_000L;

    RoutePlanner.Place dest;
    List<RoutePlanner.Place> vias = new ArrayList<>();
    boolean round;
    /** Paradas de carga elegidas a mano en el móvil (valen para este destino). */
    List<ChargerMarks.Mark> pins = new ArrayList<>();
    /** Velocidad máxima elegida en el móvil (km/h; 0: ninguna). */
    int speedCap;
    /** Hora de salida elegida (de pared; 0: «Ahora»). */
    long departAtMs;
    /** Cuándo se guardó (de pared). */
    long savedAtMs;
    /** Las paradas de carga del plan del móvil: nombre, clave (ChargerMarks.key) y dónde, en orden. */
    List<Stop> stops = new ArrayList<>();
    /** Con qué % se planificó, con cuánto se llegaba y los km. */
    double socPct = Double.NaN;
    double arrivalPct = Double.NaN;
    double totalKm;
    /**
     * Los puntos del viaje {lat, lon, km} en el orden de la ruta (paradas de carga, intermedias, destino y vuelta), tal
     * y como salieron en el móvil: para el modo «Auto», donde no hay planificador en el coche (PlannedNotice.go).
     */
    List<double[]> route = new ArrayList<>();

    static final class Stop {
        String name = "";
        String key = "";
        double lat;
        double lon;
    }

    // ------------------------------------------------------------------ guardar y leer

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("dest", place(dest));
            JSONArray v = new JSONArray();
            for (RoutePlanner.Place p : vias) v.put(place(p));
            o.put("vias", v);
            o.put("round", round);
            o.put("pins", new JSONArray(ChargerMarks.toJson(pins)));
            o.put("cap", speedCap);
            o.put("depart", departAtMs);
            o.put("at", savedAtMs);
            JSONArray st = new JSONArray();
            for (Stop s : stops) st.put(new JSONObject().put("n", s.name).put("k", s.key).put("la", s.lat).put("lo", s.lon));
            o.put("stops", st);
            if (!Double.isNaN(socPct)) o.put("soc", socPct);
            if (!Double.isNaN(arrivalPct)) o.put("arrive", arrivalPct);
            o.put("km", totalKm);
            JSONArray r = new JSONArray();
            for (double[] x : route) r.put(new JSONArray().put(x[0]).put(x[1]).put(x[2]));
            o.put("route", r);
            return o.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /** Puro: la ruta guardada, o null si el texto está vacío o roto. */
    static PlannedTrip fromJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(s);
            PlannedTrip t = new PlannedTrip();
            t.dest = placeFrom(o.getJSONObject("dest"));
            JSONArray v = o.optJSONArray("vias");
            for (int i = 0; v != null && i < v.length() && t.vias.size() < SavedPlaces.MAX_VIA; i++) t.vias.add(placeFrom(v.getJSONObject(i)));
            t.round = o.optBoolean("round");
            JSONArray pins = o.optJSONArray("pins");
            t.pins = ChargerMarks.parse(pins == null ? "" : pins.toString());
            t.speedCap = Math.max(0, o.optInt("cap"));
            t.departAtMs = o.optLong("depart");
            t.savedAtMs = o.optLong("at");
            JSONArray st = o.optJSONArray("stops");
            for (int i = 0; st != null && i < st.length(); i++) {
                JSONObject x = st.getJSONObject(i);
                Stop s2 = new Stop();
                s2.name = x.optString("n");
                s2.key = x.optString("k");
                s2.lat = x.optDouble("la");
                s2.lon = x.optDouble("lo");
                t.stops.add(s2);
            }
            t.socPct = o.optDouble("soc", Double.NaN);
            t.arrivalPct = o.optDouble("arrive", Double.NaN);
            t.totalKm = o.optDouble("km", 0);
            JSONArray r = o.optJSONArray("route");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONArray x = r.getJSONArray(i);
                t.route.add(new double[]{x.getDouble(0), x.getDouble(1), x.getDouble(2)});
            }
            return t;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Puro: ¿ya no vale? A las MAX_AGE_MS de guardarla (o con la hora del móvil hacia atrás). */
    boolean expired(long nowMs) {
        return nowMs - savedAtMs > MAX_AGE_MS || nowMs < savedAtMs - 60_000;
    }

    /** Hasta cuándo vale (de pared). */
    long expiresAtMs() {
        return savedAtMs + MAX_AGE_MS;
    }

    /** La guarda para el próximo viaje (sustituye a la anterior y al destino compartido). */
    static void save(Context c, PlannedTrip t) {
        Config cfg = new Config(c);
        cfg.putString(K, t.toJson());
        cfg.putString(SharedDest.K, "");
        L.i("ruta del móvil: guardada para el próximo viaje (" + t.vias.size() + " paradas intermedias, " + t.stops.size()
                + " de carga, " + t.pins.size() + " elegidas a mano)");
    }

    /** La guardada si no ha caducado (sin quitarla), o null; caducada, se borra. */
    static PlannedTrip peek(Context c) {
        Config cfg = new Config(c);
        PlannedTrip t = fromJson(cfg.getString(K));
        if (t == null) return null;
        if (t.dest == null || t.expired(System.currentTimeMillis())) {
            L.i("ruta del móvil: caducada, no la uso");
            cfg.putString(K, "");
            return null;
        }
        return t;
    }

    /** La quita (y, si estaba, la pregunta del modo «Auto» en la notificación). */
    static void clear(Context c) {
        new Config(c).putString(K, "");
        PlannedNotice.cancel(c);
    }

    /**
     * La respuesta a «¿Usar la ruta que planificaste?»: con Sí, la ruta para aplicarla; con No, null. En los dos casos se
     * quita (se usa una vez). Sin ruta guardada (o caducada), null.
     */
    static PlannedTrip answer(Context c, boolean yes) {
        PlannedTrip t = peek(c);
        clear(c);
        if (t != null) L.i("ruta del móvil: el conductor " + (yes ? "la usa" : "no la usa") + " en este viaje");
        return yes ? t : null;
    }

    // ------------------------------------------------------------------ antes y ahora

    /** Las paradas de un plan de carga, para guardarlas con la ruta. */
    static List<Stop> stopsOf(ChargePlanner.Result cp) {
        List<Stop> out = new ArrayList<>();
        if (cp == null) return out;
        for (ChargePlanner.Stop st : cp.stops) {
            Stop s = new Stop();
            s.name = RoutePlanner.chargerName(st.charger);
            s.key = ChargerMarks.key(st.charger);
            s.lat = st.charger.lat;
            s.lon = st.charger.lon;
            out.add(s);
        }
        return out;
    }

    /** «2 paradas de carga» o «sin paradas de carga». */
    static String stopsText(int n) {
        return n <= 0 ? Str.get(R.string.hql_pr_no_stops) : Str.plural(R.plurals.hql_pr_stops, n, n);
    }

    /**
     * Lo que ha cambiado entre el plan del móvil y el de ahora (en el coche, con la batería real y los tiempos de Google):
     * «Ayer: 1 parada de carga; hoy: 2.» si cambia el número, «Ahora para en …» si son otros cargadores; null si es el
     * mismo plan. Con el mismo sitio (ChargerMarks.SAME_KM) o la misma clave, es la misma parada.
     */
    static String diff(PlannedTrip t, ChargePlanner.Result now, long nowMs) {
        if (t == null || now == null) return null;
        List<Stop> after = stopsOf(now);
        if (after.size() != t.stops.size()) {
            String before = stopsText(t.stops.size());
            String nowText = stopsText(after.size());
            return Str.get(yesterday(t.savedAtMs, nowMs) ? R.string.hql_planned_changed_yesterday : R.string.hql_planned_changed, before, nowText);
        }
        StringBuilder other = new StringBuilder();
        for (int i = 0; i < after.size(); i++) {
            if (same(t.stops.get(i), after.get(i))) continue;
            if (other.length() > 0) other.append(", ");
            other.append(after.get(i).name);
        }
        return other.length() == 0 ? null : Str.get(R.string.hql_planned_other_stops, other.toString());
    }

    /** Puro: la misma parada (la misma clave o a menos de ChargerMarks.SAME_KM). */
    static boolean same(Stop a, Stop b) {
        if (!a.key.isEmpty() && a.key.equals(b.key)) return true;
        return RoutePlanner.segmentKm(a.lat, a.lon, a.lat, a.lon, b.lat, b.lon) < ChargerMarks.SAME_KM;
    }

    /** Puro: ¿se guardó el día de antes que nowMs (en la zona del móvil)? */
    static boolean yesterday(long savedMs, long nowMs) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(nowMs);
        a.add(Calendar.DAY_OF_YEAR, -1);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(savedMs);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    // ------------------------------------------------------------------ piezas

    private static JSONObject place(RoutePlanner.Place p) throws JSONException {
        return new JSONObject().put("n", p.name == null ? "" : p.name).put("d", p.detail == null ? "" : p.detail).put("la", p.lat).put("lo", p.lon);
    }

    private static RoutePlanner.Place placeFrom(JSONObject o) throws JSONException {
        RoutePlanner.Place p = new RoutePlanner.Place();
        p.name = o.optString("n");
        p.detail = o.optString("d");
        p.lat = o.getDouble("la");
        p.lon = o.getDouble("lo");
        return p;
    }
}
