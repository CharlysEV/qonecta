package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cargadores rápidos de Suiza con su estado en directo, de la Oficina Federal de Energía (BFE: los datos de
 * ich-tanke-strom.ch que mandan los operadores; datos abiertos, uso libre citando la fuente), como DgtChargers para
 * España y BnetzaChargers para Alemania. Pedido en el foro LPMFF (27-10).
 * - Los puntos (OICP, ~1,1 MB comprimido): como mucho una vez cada MAX_AGE_MS, cuando una ruta pasa por Suiza o se
 *   buscan cargadores allí; se guardan en el móvil solo los de continua, agrupados por lugar y operador, con los puntos
 *   (EvseID) de cada sitio.
 * - El estado de cada punto (libre, ocupado, fuera de servicio; ~180 KB, al día cada minuto): como mucho cada STATUS_MS
 *   mientras haga falta (una ruta por Suiza o el buscador allí). La pantalla enseña cuántos hay libres y el plan no usa
 *   un sitio con todos sus puntos fuera de servicio.
 * El campo «Accessibility» no vale para quitar privados: IONITY, GoFast, Shell o Electra salen como «Restricted access».
 */
final class SwissChargers {
    static final String URL_DATA = "https://data.geo.admin.ch/ch.bfe.ladestellen-elektromobilitaet/data/oicp/"
            + "ch.bfe.ladestellen-elektromobilitaet.json";
    static final String URL_STATUS = "https://data.geo.admin.ch/ch.bfe.ladestellen-elektromobilitaet/status/oicp/"
            + "ch.bfe.ladestellen-elektromobilitaet.json";
    static final long MAX_AGE_MS = 7L * 86_400_000L;
    /** Tras un fallo de los puntos, no se vuelve a intentar hasta pasado esto. */
    static final long RETRY_MS = 30L * 60_000L;
    /** El estado se pide como mucho cada esto, y se enseña mientras no tenga más de STATUS_FRESH_MS. */
    static final long STATUS_MS = 3L * 60_000L;
    static final long STATUS_FRESH_MS = 15L * 60_000L;
    private static final String FILE = "ch-chargers.tsv";
    private static final String PREFS = "hql_ch_chargers";
    /** Suiza y Liechtenstein, por lo ancho. */
    static final double LAT_MIN = 45.8;
    static final double LAT_MAX = 47.85;
    static final double LON_MIN = 5.9;
    static final double LON_MAX = 10.55;

    /** Estado de un punto. */
    static final int FREE = 0;
    static final int BUSY = 1;
    static final int OUT = 2;
    static final int UNKNOWN = 3;

    private static List<DgtChargers.Site> sites;
    private static Map<String, List<DgtChargers.Site>> grid;
    /** Los sitios por su id de cargador («ch:…»), para el estado en la pantalla. */
    private static Map<String, DgtChargers.Site> byChargerId;
    private static long loadedAtMs;
    private static boolean triedLoad;
    private static volatile boolean downloading;
    /** Sube cada vez que cambian los puntos o los que están fuera de servicio (para rehacer la lista de la ruta). */
    static volatile int version;

    private static volatile Map<String, Integer> status;
    private static volatile long statusAtMs;
    private static volatile long statusTryMs = Long.MIN_VALUE / 2;
    private static volatile boolean statusBusy;
    /** Los sitios con todos sus puntos fuera de servicio en el último estado (para saber si cambian). */
    private static Set<String> allOutIds = new HashSet<>();

    private SwissChargers() {
    }

    static boolean inSwitzerland(double lat, double lon) {
        return lat >= LAT_MIN && lat <= LAT_MAX && lon >= LON_MIN && lon <= LON_MAX;
    }

    /** ¿Pasa la ruta por Suiza (algún punto dentro del recuadro)? */
    static boolean nearSwitzerland(double[] lat, double[] lon) {
        for (int i = 0; i < lat.length; i++) if (inSwitzerland(lat[i], lon[i])) return true;
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ los puntos

    /** Los sitios guardados (cargándolos del móvil la primera vez); null si no hay. */
    static synchronized List<DgtChargers.Site> sites(Context ctx) {
        if (sites != null || triedLoad) return sites;
        triedLoad = true;
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String head = r.readLine();
            long at = head == null ? 0 : Long.parseLong(head.trim());
            List<DgtChargers.Site> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                DgtChargers.Site s = fromLine(line);
                if (s != null) out.add(s);
            }
            setSites(out, at);
            L.i("cargadores de Suiza: " + out.size() + " sitios del móvil");
            return sites;
        } catch (IOException | RuntimeException e) {
            L.w("cargadores de Suiza: no se pudieron leer los guardados (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static void setSites(List<DgtChargers.Site> out, long at) {
        Map<String, List<DgtChargers.Site>> g = new HashMap<>();
        Map<String, DgtChargers.Site> ids = new HashMap<>();
        for (DgtChargers.Site s : out) {
            s.src = "ch";
            String k = ChargerCache.tileOf(s.lat, s.lon);
            List<DgtChargers.Site> l = g.get(k);
            if (l == null) g.put(k, l = new ArrayList<>());
            l.add(s);
            ids.put("ch:" + s.id, s);
        }
        sites = out;
        grid = g;
        byChargerId = ids;
        loadedAtMs = at;
        version++;
    }

    /** Los sitios de una cuadrícula (ChargerCache.tileOf), o null si no hay ninguno (fuera de Suiza). */
    static synchronized List<DgtChargers.Site> inTile(String tile) {
        return grid == null ? null : grid.get(tile);
    }

    /** Para las pruebas: unos sitios como si fueran los descargados. */
    static synchronized void setForTest(List<DgtChargers.Site> out) {
        triedLoad = true;
        setSites(out, System.currentTimeMillis());
    }

    /** ¿Hay que descargarlos (no hay, o tienen más de MAX_AGE_MS; y no falló hace poco)? */
    static boolean stale(Context ctx) {
        List<DgtChargers.Site> s = sites(ctx);
        long now = System.currentTimeMillis();
        long failed = prefs(ctx).getLong("failed", 0);
        if (failed > 0 && now - failed < RETRY_MS) return false;
        synchronized (SwissChargers.class) {
            return s == null || now - loadedAtMs > MAX_AGE_MS;
        }
    }

    /** Si no hay puntos o son viejos, los descarga en otro hilo (al terminar sube version). Barato si no toca. */
    static void refreshIfStale(Context ctx) {
        if (downloading || !stale(ctx)) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Thread t = new Thread(() -> refresh(app), "hql-ch");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Descarga, lee y guarda los puntos (llamar fuera del hilo principal). true si quedaron al día. */
    static boolean refresh(Context ctx) {
        synchronized (SwissChargers.class) {
            if (downloading) return false;
            downloading = true;
        }
        long t0 = System.currentTimeMillis();
        try {
            List<DgtChargers.Site> out;
            try (Reader in = open(URL_DATA, 120_000)) {
                out = parse(in, Str.locale().getLanguage());
            }
            if (out.size() < 200) throw new IOException("solo " + out.size() + " sitios");
            save(ctx, out, t0);
            prefs(ctx).edit().remove("failed").apply();
            synchronized (SwissChargers.class) {
                triedLoad = true;
                setSites(out, t0);
            }
            L.i(String.format(Locale.US, "cargadores de Suiza: %d sitios con carga rápida al día en %.1f s", out.size(),
                    (System.currentTimeMillis() - t0) / 1000.0));
            return true;
        } catch (Exception e) {
            L.w("cargadores de Suiza: no se pudieron bajar (" + Http.safeError(e) + ")");
            prefs(ctx).edit().putLong("failed", System.currentTimeMillis()).apply();
            return false;
        } finally {
            downloading = false;
        }
    }

    /** Una descarga de data.geo.admin.ch (llega comprimida: Android la descomprime sola). */
    private static Reader open(String url, int readMs) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(readMs);
        con.setRequestProperty("User-Agent", Http.USER_AGENT);
        int code = con.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        InputStream in = con.getInputStream();
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    // ------------------------------------------------------------------ el estado en directo

    /**
     * Si hace falta (hay puntos y han pasado STATUS_MS desde la última vez), pide el estado en otro hilo. Barato si no
     * toca: se puede llamar en cada vuelta del planificador.
     */
    static void statusIfDue(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (statusBusy || now - statusTryMs < STATUS_MS || sites(ctx) == null) return;
        statusTryMs = now;
        statusBusy = true;
        Thread t = new Thread(() -> {
            try {
                refreshStatus();
            } finally {
                statusBusy = false;
            }
        }, "hql-ch-status");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Pide el estado ahora, en este hilo (fuera del principal; el laboratorio lo usa directamente). */
    static void refreshStatus() {
        Set<String> keep = new HashSet<>();
        synchronized (SwissChargers.class) {
            if (sites == null) return;
            for (DgtChargers.Site s : sites) if (s.evses != null) java.util.Collections.addAll(keep, s.evses);
        }
        try {
            Map<String, Integer> st;
            try (Reader in = open(URL_STATUS, 60_000)) {
                st = parseStatus(in, keep);
            }
            setStatus(st, System.currentTimeMillis());
        } catch (Exception e) {
            L.w("cargadores de Suiza: sin estado en directo (" + Http.safeError(e) + ")");
        }
    }

    /** Un estado nuevo: si cambian los sitios fuera de servicio, sube version (el plan deja de usarlos). */
    static void setStatus(Map<String, Integer> st, long atMs) {
        Set<String> out = new HashSet<>();
        int free = 0;
        synchronized (SwissChargers.class) {
            status = st;
            statusAtMs = atMs;
            if (sites != null) {
                for (DgtChargers.Site s : sites) {
                    int[] c = count(s.evses, st);
                    if (c == null) continue;
                    if (c[OUT] == s.evses.length) out.add(s.id);
                    if (c[FREE] > 0) free++;
                }
            }
            if (!out.equals(allOutIds)) {
                allOutIds = out;
                version++;
            }
        }
        L.i("cargadores de Suiza: estado en directo: " + free + " sitios con algún punto libre, " + out.size()
                + " fuera de servicio");
    }

    /**
     * Puro: {libres, ocupados, fuera de servicio, sin estado} de unos puntos, o null si no hay puntos o estado. Un punto
     * que el estado no trae cuenta como sin estado.
     */
    static int[] count(String[] evses, Map<String, Integer> st) {
        if (evses == null || evses.length == 0 || st == null) return null;
        int[] c = new int[4];
        for (String e : evses) {
            Integer v = st.get(e);
            c[v == null ? UNKNOWN : v]++;
        }
        return c;
    }

    /** {libres, ocupados, fuera de servicio, sin estado} de un sitio con el estado de ahora, o null si no hay (o es viejo). */
    static int[] live(DgtChargers.Site s) {
        Map<String, Integer> st = status;
        if (s == null || st == null || System.currentTimeMillis() - statusAtMs > STATUS_FRESH_MS) return null;
        return count(s.evses, st);
    }

    /** El estado de ahora de un cargador de la ruta por su id («ch:…»), o null si no es de Suiza o no hay. */
    static int[] live(String chargerId) {
        if (chargerId == null || !chargerId.startsWith("ch:")) return null;
        DgtChargers.Site s;
        synchronized (SwissChargers.class) {
            s = byChargerId == null ? null : byChargerId.get(chargerId);
        }
        return live(s);
    }

    /** ¿Tiene ahora todos sus puntos fuera de servicio? (el plan no lo usa). */
    static boolean allOut(DgtChargers.Site s) {
        int[] c = live(s);
        return c != null && s.evses != null && c[OUT] == s.evses.length;
    }

    /** Puro: el código de un estado de OICP. */
    static int code(String oicp) {
        if ("Available".equals(oicp)) return FREE;
        if ("Occupied".equals(oicp) || "Reserved".equals(oicp)) return BUSY;
        if ("OutOfService".equals(oicp)) return OUT;
        return UNKNOWN;
    }

    // ------------------------------------------------------------------ OICP (sin red: se prueba en el PC)

    /** Un punto de carga en continua leído del fichero. */
    private static final class Evse {
        String id = "";
        double lat = Double.NaN;
        double lon = Double.NaN;
        String name = "";
        String town = "";
        double dcKw;
        double acKw;
        double dcVolts;
        boolean ccs;
        boolean chademo;
        boolean type2;
        boolean dc;
        boolean test;
    }

    /**
     * Puro: los sitios con carga en continua, agrupados por lugar (~10 m) y operador, con sus puntos (EvseID) para el
     * estado. lang: el idioma en el que se prefiere el nombre («de», «fr», «it», «en»).
     */
    static List<DgtChargers.Site> parse(Reader in, String lang) throws IOException {
        JsonReader r = new JsonReader(in);
        Map<String, DgtChargers.Site> byKey = new LinkedHashMap<>();
        Map<String, List<String>> evsesByKey = new HashMap<>();
        r.beginObject();
        while (r.hasNext()) {
            if (!"EVSEData".equals(r.nextName())) {
                r.skipValue();
                continue;
            }
            r.beginArray();
            while (r.hasNext()) {
                // Un operador: su nombre puede ir antes o después de sus puntos.
                String operator = "";
                List<Evse> list = new ArrayList<>();
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("OperatorName".equals(n)) {
                        operator = str(r);
                    } else if ("EVSEDataRecord".equals(n) && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            Evse e = evse(r, lang);
                            if (e != null) list.add(e);
                        }
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                for (Evse e : list) add(byKey, evsesByKey, operator, e);
            }
            r.endArray();
        }
        r.endObject();
        List<DgtChargers.Site> out = new ArrayList<>(byKey.size());
        for (Map.Entry<String, DgtChargers.Site> e : byKey.entrySet()) {
            DgtChargers.Site s = e.getValue();
            s.evses = evsesByKey.get(e.getKey()).toArray(new String[0]);
            s.points = s.evses.length;
            out.add(s);
        }
        return out;
    }

    private static void add(Map<String, DgtChargers.Site> byKey, Map<String, List<String>> evses, String operator, Evse e) {
        String key = String.format(Locale.US, "%.4f|%.4f|%s", e.lat, e.lon, operator.toLowerCase(Locale.ROOT));
        DgtChargers.Site s = byKey.get(key);
        if (s == null) {
            s = new DgtChargers.Site();
            s.id = e.id;
            s.lat = e.lat;
            s.lon = e.lon;
            s.name = e.name;
            s.operator = operator.trim();
            s.town = e.town;
            s.src = "ch";
            byKey.put(key, s);
            evses.put(key, new ArrayList<>());
        }
        s.dcKw = Math.max(s.dcKw, e.dcKw);
        s.acKw = Math.max(s.acKw, e.acKw);
        s.dcVolts = Math.max(s.dcVolts, e.dcVolts);
        s.sockets |= ChargerFilter.SOCKET_DC | (e.acKw > 0 || e.type2 ? ChargerFilter.SOCKET_AC : 0);
        s.ccs |= e.ccs;
        s.chademo |= e.chademo;
        s.type2 |= e.type2;
        if (!e.id.isEmpty()) evses.get(key).add(e.id);
    }

    /** Un punto del fichero, o null si no es de continua, es de pruebas o no tiene posición. */
    private static Evse evse(JsonReader r, String lang) throws IOException {
        Evse e = new Evse();
        String[] nameAny = {""};
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "EvseID":
                    e.id = str(r);
                    break;
                case "Accessibility":
                    e.test = "Test Station".equals(str(r));
                    break;
                case "GeoCoordinates":
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("Google".equals(r.nextName()) && r.peek() != JsonToken.NULL) {
                            String[] p = str(r).trim().split("\\s+");
                            if (p.length == 2) {
                                e.lat = num(p[0]);
                                e.lon = num(p[1]);
                            }
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                    break;
                case "ChargingFacilities":
                    each(r, () -> {
                        String type = "";
                        double kw = Double.NaN;
                        double volts = Double.NaN;
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("powertype".equals(k)) type = str(r);
                            else if ("power".equals(k)) kw = num(str(r));
                            else if ("Voltage".equals(k)) volts = num(str(r));
                            else r.skipValue();
                        }
                        r.endObject();
                        if ("DC".equals(type)) {
                            e.dc = true;
                            if (kw > 0) e.dcKw = Math.max(e.dcKw, kw);
                            // Solo un voltaje creíble de alto voltaje: el resto trae «400» o «230» también en hubs de 1000 V.
                            if (volts >= ChargePlanner.HIGH_VOLTS) e.dcVolts = Math.max(e.dcVolts, volts);
                        } else if (type.startsWith("AC") && kw > 0) {
                            e.acKw = Math.max(e.acKw, kw);
                        }
                    });
                    break;
                case "Plugs":
                    each(r, () -> {
                        String p = str(r);
                        if (p.contains("CCS")) e.ccs = true;
                        if (p.contains("CHAdeMO")) e.chademo = true;
                        if (p.contains("Type 2")) e.type2 = true;
                    });
                    break;
                case "ChargingStationNames":
                    // Una lista de {lang, value} o, a veces, uno solo sin lista.
                    each(r, () -> {
                        String l = "";
                        String v = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("lang".equals(k)) l = str(r);
                            else if ("value".equals(k)) v = str(r);
                            else r.skipValue();
                        }
                        r.endObject();
                        v = v.trim().replaceAll("\\s+", " ");
                        if (v.isEmpty()) return;
                        if (nameAny[0].isEmpty() || "de".equals(l)) nameAny[0] = v;
                        if (l.equals(lang)) e.name = v;
                    });
                    break;
                case "Address":
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("City".equals(r.nextName()) && r.peek() != JsonToken.NULL) e.town = str(r).trim();
                        else r.skipValue();
                    }
                    r.endObject();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        if (e.name.isEmpty()) e.name = nameAny[0];
        if (e.ccs || e.chademo) e.dc = true;
        if (!e.dc || e.test || Double.isNaN(e.lat) || Double.isNaN(e.lon)) return null;
        return e;
    }

    /**
     * Puro: el estado de los puntos de keep (null: de todos), por EvseID (FREE, BUSY, OUT o UNKNOWN), del fichero de
     * estado de OICP.
     */
    static Map<String, Integer> parseStatus(Reader in, Set<String> keep) throws IOException {
        JsonReader r = new JsonReader(in);
        Map<String, Integer> out = new HashMap<>();
        r.beginObject();
        while (r.hasNext()) {
            if (!"EVSEStatuses".equals(r.nextName())) {
                r.skipValue();
                continue;
            }
            r.beginArray();
            while (r.hasNext()) {
                r.beginObject();
                while (r.hasNext()) {
                    if (!"EVSEStatusRecord".equals(r.nextName()) || r.peek() != JsonToken.BEGIN_ARRAY) {
                        r.skipValue();
                        continue;
                    }
                    r.beginArray();
                    while (r.hasNext()) {
                        String id = "";
                        String st = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("EvseID".equals(k)) id = str(r);
                            else if ("EVSEStatus".equals(k)) st = str(r);
                            else r.skipValue();
                        }
                        r.endObject();
                        if (!id.isEmpty() && (keep == null || keep.contains(id))) out.put(id, code(st));
                    }
                    r.endArray();
                }
                r.endObject();
            }
            r.endArray();
        }
        r.endObject();
        return out;
    }

    /** Lee un elemento (el lector está delante de él). */
    private interface Item {
        void read() throws IOException;
    }

    /**
     * Un campo que es una lista o un solo elemento sin lista (ChargingStationNames llega de las dos formas): cada
     * elemento, a item; los null de dentro, se saltan.
     */
    private static void each(JsonReader r, Item item) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            item.read();
            return;
        }
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() == JsonToken.NULL) r.skipValue();
            else item.read();
        }
        r.endArray();
    }

    /** Un valor como texto: cadena, número o booleano («power» llega como «22.0» o como 22.0); "" si es null. */
    private static String str(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) {
            r.nextNull();
            return "";
        }
        if (t == JsonToken.BOOLEAN) return Boolean.toString(r.nextBoolean());
        if (t == JsonToken.STRING || t == JsonToken.NUMBER) return r.nextString();
        r.skipValue();
        return "";
    }

    private static double num(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ guardado compacto

    /** La línea de DgtChargers con los puntos detrás, separados por comas. */
    static String toLine(DgtChargers.Site s) {
        // TextUtils.join y no String.join: este es de Android 8 y la app llega a Android 6.
        return DgtChargers.toLine(s) + "\t" + (s.evses == null ? "" : android.text.TextUtils.join(",", s.evses));
    }

    static DgtChargers.Site fromLine(String line) {
        DgtChargers.Site s = DgtChargers.fromLine(line);
        if (s == null) return null;
        String[] p = line.split("\t", -1);
        s.evses = p.length > 12 && !p[12].isEmpty() ? p[12].split(",") : new String[0];
        s.src = "ch";
        return s;
    }

    private static void save(Context ctx, List<DgtChargers.Site> out, long at) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(Long.toString(at));
            w.write('\n');
            for (DgtChargers.Site s : out) {
                w.write(toLine(s));
                w.write('\n');
            }
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }
}
