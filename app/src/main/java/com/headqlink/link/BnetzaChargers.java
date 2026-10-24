package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cargadores rápidos de Alemania según la Bundesnetzagentur (Ladesäulenregister: el registro oficial de puntos de carga
 * públicos; CSV, licencia CC BY 4.0), como DgtChargers para España: potencia y enchufes oficiales, que OpenStreetMap
 * muchas veces no tiene. No trae el voltaje: el plan usa el típico de cada red (ChargerFilter.typicalVolts).
 *
 * El registro sale una vez al mes con la fecha en el nombre del fichero (~55 MB), así que: la página se mira como mucho
 * una vez cada CHECK_MS, el fichero solo se baja si es otro y con una red sin límite de datos (Wi-Fi), y solo en los
 * móviles que han planificado alguna ruta cerca de Alemania (wanted). Se lee línea a línea y se guarda en el móvil en
 * forma compacta, solo los sitios con carga en continua (unos 19.000, agrupados por lugar y operador): la alterna sigue
 * saliendo de OpenStreetMap. Todo es automático; si un día cambia la página, siguen valiendo los guardados y
 * OpenStreetMap.
 */
final class BnetzaChargers {
    static final String PAGE = "https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/start.html";
    /** La página se mira como mucho una vez por semana (el registro cambia una vez al mes). */
    static final long CHECK_MS = 7L * 86_400_000L;
    /** Tras un fallo, no se vuelve a intentar hasta pasado esto. */
    static final long RETRY_MS = 6L * 3_600_000L;
    private static final String FILE = "bnetza-chargers.tsv";
    private static final String PREFS = "hql_bnetza";
    /** Alemania, por lo ancho. */
    static final double LAT_MIN = 47.2;
    static final double LAT_MAX = 55.1;
    static final double LON_MIN = 5.8;
    static final double LON_MAX = 15.1;

    private static List<DgtChargers.Site> sites;
    private static Map<String, List<DgtChargers.Site>> grid;
    private static boolean triedLoad;
    private static volatile boolean downloading;
    /** Sube cada vez que cambian los puntos (para rehacer la lista de cargadores de la ruta). */
    static volatile int version;

    private BnetzaChargers() {
    }

    /** ¿Pasa la ruta cerca de Alemania (algún punto dentro del recuadro)? */
    static boolean nearGermany(double[] lat, double[] lon) {
        for (int i = 0; i < lat.length; i++) {
            if (lat[i] >= LAT_MIN && lat[i] <= LAT_MAX && lon[i] >= LON_MIN && lon[i] <= LON_MAX) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Una ruta pasa por Alemania: desde ahora este móvil quiere el registro (y lo baja en cuanto pueda). */
    static void want(Context c) {
        if (!prefs(c).getBoolean("wanted", false)) {
            prefs(c).edit().putBoolean("wanted", true).apply();
            L.i("cargadores de Alemania: una ruta pasa por Alemania; el registro oficial se bajará con Wi-Fi");
        }
        refreshIfDue(c);
    }

    /** Pruebas («alemania off»): como si nunca se hubiera pedido; borra lo guardado. */
    static void forget(Context c) {
        prefs(c).edit().clear().apply();
        new File(c.getFilesDir(), FILE).delete();
        synchronized (BnetzaChargers.class) {
            sites = null;
            grid = null;
            triedLoad = false;
            version++;
        }
        L.i("cargadores de Alemania: registro borrado (pruebas)");
    }

    /** Los sitios guardados (cargándolos del móvil la primera vez); null si no hay. */
    static synchronized List<DgtChargers.Site> sites(Context ctx) {
        if (sites != null || triedLoad) return sites;
        triedLoad = true;
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            r.readLine();
            List<DgtChargers.Site> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                DgtChargers.Site s = DgtChargers.fromLine(line);
                if (s == null) continue;
                s.src = "de";
                out.add(s);
            }
            setSites(out);
            L.i("cargadores de Alemania: " + out.size() + " sitios del móvil");
            return sites;
        } catch (IOException | RuntimeException e) {
            L.w("cargadores de Alemania: no se pudieron leer los guardados (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static void setSites(List<DgtChargers.Site> out) {
        sites = out;
        Map<String, List<DgtChargers.Site>> g = new HashMap<>();
        for (DgtChargers.Site s : out) {
            String k = ChargerCache.tileOf(s.lat, s.lon);
            List<DgtChargers.Site> l = g.get(k);
            if (l == null) g.put(k, l = new ArrayList<>());
            l.add(s);
        }
        grid = g;
        version++;
    }

    /** Los sitios de una cuadrícula (ChargerCache.tileOf), o null si no hay ninguno (fuera de Alemania). */
    static synchronized List<DgtChargers.Site> inTile(String tile) {
        return grid == null ? null : grid.get(tile);
    }

    /** Para las pruebas: unos sitios como si fueran los descargados. */
    static synchronized void setForTest(List<DgtChargers.Site> out) {
        triedLoad = true;
        for (DgtChargers.Site s : out) s.src = "de";
        setSites(out);
    }

    /**
     * Puro: ¿toca mirar la página? Solo si este móvil lo quiere, no se miró hace menos de CHECK_MS (o no hay nada
     * guardado y no se miró en la última hora) y no falló hace menos de RETRY_MS.
     */
    static boolean due(boolean wanted, boolean haveData, long checkedAt, long failedAt, long now) {
        if (!wanted) return false;
        if (failedAt > 0 && now - failedAt < RETRY_MS) return false;
        return now - checkedAt >= (haveData ? CHECK_MS : 3_600_000L);
    }

    /** Si toca, mira la página y baja el registro nuevo en otro hilo (con Wi-Fi). Barato si no toca. */
    static void refreshIfDue(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        SharedPreferences p = prefs(app);
        boolean have = new File(app.getFilesDir(), FILE).exists();
        if (downloading || !due(p.getBoolean("wanted", false), have, p.getLong("checked", 0), p.getLong("failed", 0),
                System.currentTimeMillis())) {
            return;
        }
        Thread t = new Thread(() -> refresh(app), "hql-bnetza");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** ¿Red sin límite de datos (Wi-Fi o cable)? Los 55 MB, solo así. */
    static boolean unmetered(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered();
    }

    /** Mira la página y, si hay un registro nuevo y hay Wi-Fi, lo baja, lo lee y lo guarda (fuera del hilo principal). */
    static boolean refresh(Context ctx) {
        synchronized (BnetzaChargers.class) {
            if (downloading) return false;
            downloading = true;
        }
        SharedPreferences p = prefs(ctx);
        long t0 = System.currentTimeMillis();
        try {
            String link = newestCsv(Http.get(PAGE));
            if (link == null) throw new IOException("la página ya no enlaza el registro");
            boolean have = new File(ctx.getFilesDir(), FILE).exists();
            if (have && link.equals(p.getString("link", ""))) {
                p.edit().putLong("checked", t0).remove("failed").apply();
                L.i("cargadores de Alemania: el registro sigue siendo el mismo");
                return true;
            }
            if (!unmetered(ctx)) {
                // Sin Wi-Fi, ni se mira como hecho: se vuelve a intentar a la siguiente.
                L.i("cargadores de Alemania: hay registro nuevo; espero a una Wi-Fi para bajarlo (~55 MB)");
                return false;
            }
            HttpURLConnection con = (HttpURLConnection) new URL(link).openConnection();
            con.setConnectTimeout(15_000);
            con.setReadTimeout(120_000);
            con.setRequestProperty("User-Agent", Http.USER_AGENT);
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            List<DgtChargers.Site> out;
            try (InputStream in = con.getInputStream()) {
                out = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            if (out.size() < 1000) throw new IOException("solo " + out.size() + " sitios");
            save(ctx, out);
            p.edit().putString("link", link).putLong("checked", System.currentTimeMillis()).remove("failed").apply();
            synchronized (BnetzaChargers.class) {
                for (DgtChargers.Site s : out) s.src = "de";
                triedLoad = true;
                setSites(out);
            }
            L.i(String.format(Locale.US, "cargadores de Alemania: %d sitios con carga rápida al día en %.1f s", out.size(),
                    (System.currentTimeMillis() - t0) / 1000.0));
            return true;
        } catch (Exception e) {
            L.w("cargadores de Alemania: no se pudo bajar el registro (" + Http.safeError(e) + ")");
            p.edit().putLong("failed", System.currentTimeMillis()).apply();
            return false;
        } finally {
            downloading = false;
        }
    }

    private static final Pattern CSV_LINK = Pattern.compile(
            "https://data\\.bundesnetzagentur\\.de/[^\"'\\s<>]*?Ladesaeulenregister[^\"'\\s<>]*?\\.csv");

    /** Puro: el enlace al CSV más reciente de la página (las fechas van en el nombre: el mayor), o null. */
    static String newestCsv(String html) {
        if (html == null) return null;
        Matcher m = CSV_LINK.matcher(html);
        String best = null;
        while (m.find()) {
            String u = m.group().replace("&amp;", "&");
            if (best == null || u.compareTo(best) > 0) best = u;
        }
        return best;
    }

    // ------------------------------------------------------------------ el CSV (sin red: se prueba en el PC)

    /**
     * Puro: un registro del CSV (separado por «;», con campos entre comillas que pueden llevar «;» y saltos de línea), o
     * null al final.
     */
    static List<String> record(Reader r) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int c;
        while ((c = r.read()) != -1) {
            any = true;
            char ch = (char) c;
            if (quoted) {
                if (ch == '"') {
                    r.mark(1);
                    int n = r.read();
                    if (n == '"') {
                        f.append('"');
                    } else {
                        quoted = false;
                        if (n != -1) r.reset();
                    }
                } else {
                    f.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ';') {
                out.add(f.toString());
                f.setLength(0);
            } else if (ch == '\n') {
                break;
            } else if (ch != '\r') {
                f.append(ch);
            }
        }
        if (!any) return null;
        out.add(f.toString());
        return out;
    }

    /** Lo que se junta de un mismo sitio (lugar y operador). */
    private static final class Acc {
        final DgtChargers.Site s = new DgtChargers.Site();
    }

    /**
     * Puro: los sitios con carga en continua del registro, agrupados por lugar (~10 m) y operador: potencia máxima por
     * enchufe en continua y en alterna, enchufes (CCS, CHAdeMO, tipo 2) y número de puntos. Solo los «In Betrieb».
     */
    static List<DgtChargers.Site> parse(Reader in) throws IOException {
        // record mira un carácter por delante (comillas dobles): hace falta mark/reset.
        Reader r = in.markSupported() ? in : new java.io.BufferedReader(in, 1 << 16);
        Map<String, Integer> col = null;
        Map<String, Acc> sitesByKey = new LinkedHashMap<>();
        List<String> rec;
        while ((rec = record(r)) != null) {
            if (col == null) {
                String first = rec.isEmpty() ? "" : rec.get(0).replace("\uFEFF", "").trim();
                if (!first.startsWith("Ladeeinrichtungs-ID")) continue;
                col = new HashMap<>();
                for (int i = 0; i < rec.size(); i++) col.put(rec.get(i).trim(), i);
                continue;
            }
            if (rec.size() < 17 || rec.get(0).trim().isEmpty()) continue;
            if (!"In Betrieb".equals(get(rec, col, "Status"))) continue;
            double lat = num(get(rec, col, "Breitengrad"));
            double lon = num(get(rec, col, "Längengrad"));
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            double dcKw = 0;
            double acKw = 0;
            boolean ccs = false;
            boolean chademo = false;
            boolean type2 = false;
            for (int k = 1; k <= 6; k++) {
                String plugs = get(rec, col, "Steckertypen" + k);
                if (plugs.isEmpty()) continue;
                double kw = num(get(rec, col, "Nennleistung Stecker" + k));
                boolean dc = plugs.contains("DC");
                if (plugs.contains("Combo") || plugs.contains("CCS")) ccs = true;
                if (plugs.contains("CHAdeMO")) chademo = true;
                if (plugs.contains("Typ 2")) type2 = true;
                if (!Double.isNaN(kw)) {
                    if (dc) dcKw = Math.max(dcKw, kw);
                    if (plugs.contains("AC")) acKw = Math.max(acKw, kw);
                }
            }
            boolean fast = ccs || chademo || dcKw > 0 || get(rec, col, "Art der Ladeeinrichtung").startsWith("Schnell");
            if (!fast) continue;
            if (dcKw <= 0) {
                double total = num(get(rec, col, "Nennleistung Ladeeinrichtung [kW]"));
                if (!Double.isNaN(total)) dcKw = total;
            }
            String operator = get(rec, col, "Betreiber");
            String key = String.format(Locale.US, "%.4f|%.4f|%s", lat, lon, operator.toLowerCase(Locale.ROOT));
            Acc a = sitesByKey.get(key);
            if (a == null) {
                a = new Acc();
                a.s.id = get(rec, col, "Ladeeinrichtungs-ID");
                a.s.lat = lat;
                a.s.lon = lon;
                a.s.name = get(rec, col, "Anzeigename (Karte)");
                a.s.operator = operator;
                a.s.town = get(rec, col, "Ort");
                sitesByKey.put(key, a);
            }
            DgtChargers.Site s = a.s;
            s.dcKw = Math.max(s.dcKw, dcKw);
            s.acKw = Math.max(s.acKw, acKw);
            s.sockets |= ChargerFilter.SOCKET_DC | (acKw > 0 ? ChargerFilter.SOCKET_AC : 0);
            s.ccs |= ccs;
            s.chademo |= chademo;
            s.type2 |= type2;
            int pts = (int) Math.max(1, num0(get(rec, col, "Anzahl Ladepunkte")));
            s.points += pts;
        }
        List<DgtChargers.Site> out = new ArrayList<>(sitesByKey.size());
        for (Acc a : sitesByKey.values()) out.add(a.s);
        return out;
    }

    private static String get(List<String> rec, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= rec.size() ? "" : rec.get(i).trim();
    }

    /** «48,578533» o «100» → número; NaN si no lo es. */
    private static double num(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double num0(String s) {
        double v = num(s);
        return Double.isNaN(v) ? 0 : v;
    }

    private static void save(Context ctx, List<DgtChargers.Site> out) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(Long.toString(System.currentTimeMillis()));
            w.write('\n');
            for (DgtChargers.Site s : out) {
                w.write(DgtChargers.toLine(s));
                w.write('\n');
            }
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }
}
