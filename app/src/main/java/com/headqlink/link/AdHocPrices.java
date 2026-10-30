package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Precio ad hoc (pagar en el cargador, sin contrato) de los cargadores de Alemania, pedido en el foro LPMFF (sayaz #238,
 * como la app «Ad-Hoc Map»). Desde 2025 cada operador publica sus precios en la Mobilithek (reglamento AFIR, DATEX II);
 * Ladekönig (uekala, MIT) los junta una vez al día en cuadrículas de 0,25° que sirve como ficheros estáticos (GitHub
 * Pages, sin clave). Las licencias de los datos son CC0 y CC BY 4.0 (EnBW, Eco-Movement, GLS): se citan en «Acerca de».
 * - Solo se piden las cuadrículas de la ruta o del buscador en Alemania (5–90 KB comprimidas), con cualquier red, y se
 *   guardan un día; meta.json (~15 KB) dice cuáles existen.
 * - Cada cargador de la app se empareja con la estación de Ladekönig más cercana de su misma red (a menos de
 *   MATCH_NET_KM) o, sin red conocida, a menos de MATCH_KM; el precio es el de ahora (las franjas, en hora alemana) y el
 *   más barato de sus puntos del mismo tipo (continua o alterna), como hace Ladekönig.
 * La lógica de los precios es la de Ladekönig (pricing.py): con varios €/kWh a la vez gana el de franja y, de ellos, el
 * más caro (mejor enseñar de más que de menos); los días festivos no se pueden evaluar y no cuentan.
 */
final class AdHocPrices {
    static final String BASE = "https://uekala.github.io/ladekoenig/v1/";
    static final double TILE_DEG = 0.25;
    /** Los datos se rehacen una vez al día: lo guardado vale esto. */
    static final long MAX_AGE_MS = 20L * 3_600_000L;
    /** Tras un fallo, esa cuadrícula (o meta.json) no se vuelve a pedir hasta pasado esto. */
    static final long RETRY_MS = 30L * 60_000L;
    /** Una estación de la misma red que el cargador es la misma hasta esta distancia (km)… */
    static final double MATCH_NET_KM = 0.3;
    /** …y, sin red conocida, hasta esta (km). */
    static final double MATCH_KM = 0.08;
    /** Cuadrículas en memoria como mucho (una ruta de punta a punta de Alemania toca unas 60). */
    static final int MAX_MEM_TILES = 160;
    /** Cuadrículas guardadas en el móvil como mucho (las más viejas se borran). */
    static final int MAX_DISK_TILES = 400;
    private static final String DIR = "adhoc";
    static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    /** El precio de ahora de un cargador: €/kWh y, si los hay, €/min desde el minuto minuteFrom y lo fijo por carga. */
    static final class Price {
        final double kwh;
        double minute = Double.NaN;
        int minuteFrom;
        double session = Double.NaN;

        Price(double kwh) {
            this.kwh = kwh;
        }
    }

    static final class Station {
        String id = "";
        double lat;
        double lon;
        /** Operador (o marca): para la red (ChargerFilter.classify). */
        String operator = "";
        String network = ChargerFilter.OTHER;
        Group[] groups = new Group[0];
        Rate[] rates = new Rate[0];
    }

    /** Puntos iguales de una estación: continua o alterna (o sin saber), potencia y sus tarifas (índices de rates). */
    static final class Group {
        /** 1 continua, 0 alterna, -1 no se sabe. */
        int dc = -1;
        double kw;
        int[] rates = new int[0];
    }

    static final class Rate {
        boolean eur = true;
        Validity when;
        Part[] parts = new Part[0];
    }

    /** Un componente del precio: «kwh», «min», «base», «flat», «free» u «other». */
    static final class Part {
        String type = "other";
        double value;
        /** «Desde el minuto…» (las de €/min suelen ser por bloqueo; las de €/kWh con from > 0 no son la base). */
        int from;
        Validity when;
    }

    /** Cuándo vale: entre start y end (ms; sin límite si no), en alguno de los periodos in y en ninguno de ex. */
    static final class Validity {
        long start = Long.MIN_VALUE;
        long end = Long.MAX_VALUE;
        Period[] in = new Period[0];
        Period[] ex = new Period[0];

        boolean restricted() {
            return in.length > 0 || ex.length > 0;
        }
    }

    /** Un periodo: días (bits, lunes = 1), meses (bits, enero = 2), franjas en minutos del día y fechas. */
    static final class Period {
        boolean special;
        long from = Long.MIN_VALUE;
        long to = Long.MAX_VALUE;
        int days;
        int months;
        int[] windows = new int[0];
    }

    // ------------------------------------------------------------------ estado

    /** Las cuadrículas que existen (meta.json), o null si aún no se sabe. */
    private static volatile Set<String> available;
    private static volatile long metaAtMs;
    private static volatile long metaTryMs = Long.MIN_VALUE / 2;
    private static final Map<String, List<Station>> mem = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<String, Long> loadedAt = new HashMap<>();
    private static final Map<String, Long> triedAt = new HashMap<>();
    private static final Set<String> queue = new LinkedHashSet<>();
    private static volatile boolean busy;
    /** Sube cada vez que llegan precios (para rehacer la lista de cargadores de la ruta). */
    private static volatile int dataVersion;
    /** Hay alguna tarifa con franjas: los precios cambian con la hora. */
    private static volatile boolean timed;

    private AdHocPrices() {
    }

    /**
     * Cambia al llegar precios y, si alguno depende de la hora, cada cuarto de hora: con ella se rehace la lista de
     * cargadores de la ruta (OfficialChargers.version).
     */
    static int version() {
        return timed ? dataVersion * 31 + (int) (System.currentTimeMillis() / (15 * 60_000L)) : dataVersion;
    }

    /** Puro: la cuadrícula de Ladekönig de un punto («210_53»). */
    static String tileOf(double lat, double lon) {
        return (int) Math.floor(lat / TILE_DEG) + "_" + (int) Math.floor(lon / TILE_DEG);
    }

    /** Puro: las cuadrículas de Ladekönig que tocan los recuadros [sur, oeste, norte, este], sin repetir. */
    static List<String> tilesFor(Collection<double[]> boxes) {
        Set<String> out = new LinkedHashSet<>();
        for (double[] b : boxes) {
            int a0 = (int) Math.floor(b[0] / TILE_DEG);
            int a1 = (int) Math.floor(b[2] / TILE_DEG);
            int b0 = (int) Math.floor(b[1] / TILE_DEG);
            int b1 = (int) Math.floor(b[3] / TILE_DEG);
            for (int a = a0; a <= a1; a++) for (int c = b0; c <= b1; c++) out.add(a + "_" + c);
        }
        return new ArrayList<>(out);
    }

    /** Las de la ruta: las de sus cuadrículas de cargadores (ChargerCache.tileBox). */
    static List<String> tilesForCacheTiles(List<String> cacheTiles) {
        List<double[]> boxes = new ArrayList<>();
        for (String t : cacheTiles) boxes.add(ChargerCache.tileBox(t));
        return tilesFor(boxes);
    }

    /** Las de un círculo de radiusKm alrededor de un punto. */
    static List<String> tilesAround(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / 111.32;
        double dLon = radiusKm / (111.32 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        List<double[]> box = new ArrayList<>();
        box.add(new double[]{lat - dLat, lon - dLon, lat + dLat, lon + dLon});
        return tilesFor(box);
    }

    // ------------------------------------------------------------------ pedir

    /**
     * Pide (en otro hilo) las cuadrículas que falten de éstas, si existen en Ladekönig. Barato si no falta nada: se
     * puede llamar en cada vuelta del planificador.
     */
    static void want(Context ctx, List<String> tiles) {
        if (tiles == null || tiles.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        boolean start = false;
        synchronized (AdHocPrices.class) {
            for (String t : tiles) {
                if (fresh(t) || queue.contains(t)) continue;
                Long tried = triedAt.get(t);
                if (tried != null && now - tried < RETRY_MS) continue;
                Set<String> av = available;
                if (av != null && metaFresh() && !av.contains(t)) continue;
                queue.add(t);
                start = true;
            }
            if (!start || busy) return;
            busy = true;
        }
        Thread th = new Thread(() -> {
            try {
                work(app);
            } finally {
                busy = false;
            }
        }, "hql-adhoc");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    private static boolean metaFresh() {
        return metaAtMs > 0 && System.currentTimeMillis() - metaAtMs < MAX_AGE_MS;
    }

    private static boolean fresh(String tile) {
        Long at = loadedAt.get(tile);
        return at != null && System.currentTimeMillis() - at < MAX_AGE_MS && mem.containsKey(tile);
    }

    private static void work(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        if (!metaFresh()) loadMeta(dir);
        int got = 0;
        int net = 0;
        while (true) {
            String t;
            synchronized (AdHocPrices.class) {
                if (queue.isEmpty()) break;
                t = queue.iterator().next();
                queue.remove(t);
            }
            Set<String> av = available;
            if (av != null && !av.contains(t)) continue;
            File f = new File(dir, t + ".json.gz");
            long now = System.currentTimeMillis();
            List<Station> list = null;
            long at = 0;
            if (f.isFile() && now - f.lastModified() < MAX_AGE_MS) {
                try {
                    list = parseTile(readGz(f));
                    at = f.lastModified();
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            if (list == null) {
                if (av == null) {
                    // Sin saber qué cuadrículas hay (meta.json no llegó): no se piden a ciegas.
                    synchronized (AdHocPrices.class) {
                        triedAt.put(t, SystemClock.elapsedRealtime());
                    }
                    continue;
                }
                try {
                    String json = download(BASE + "tiles/" + t + ".json");
                    list = parseTile(json);
                    writeGz(f, json);
                    at = now;
                    net++;
                } catch (Exception e) {
                    L.w("precios ad hoc: cuadrícula " + t + " sin respuesta (" + Http.safeError(e) + ")");
                    synchronized (AdHocPrices.class) {
                        triedAt.put(t, SystemClock.elapsedRealtime());
                    }
                    continue;
                }
            }
            put(t, list, at);
            got++;
        }
        if (got > 0) {
            trimDisk(dir);
            L.i("precios ad hoc: " + got + " zonas de Alemania (" + net + " bajadas), " + stationsInMemory() + " estaciones con precio");
        }
    }

    private static synchronized void put(String tile, List<Station> list, long atMs) {
        mem.put(tile, list);
        loadedAt.put(tile, atMs);
        while (mem.size() > MAX_MEM_TILES) {
            String old = mem.keySet().iterator().next();
            mem.remove(old);
            loadedAt.remove(old);
        }
        if (!timed) {
            for (Station s : list) {
                if (hasTimed(s)) {
                    timed = true;
                    break;
                }
            }
        }
        dataVersion++;
    }

    private static synchronized int stationsInMemory() {
        int n = 0;
        for (List<Station> l : mem.values()) n += l.size();
        return n;
    }

    private static boolean hasTimed(Station s) {
        for (Rate r : s.rates) {
            if (r.when != null && r.when.restricted()) return true;
            for (Part p : r.parts) if (p.when != null && p.when.restricted()) return true;
        }
        return false;
    }

    private static void loadMeta(File dir) {
        File f = new File(dir, "meta.json");
        long now = System.currentTimeMillis();
        if (f.isFile() && now - f.lastModified() < MAX_AGE_MS) {
            try {
                available = parseMetaTiles(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                metaAtMs = f.lastModified();
                return;
            } catch (Exception ignored) {
                // Roto: se pide otra vez.
            }
        }
        if (SystemClock.elapsedRealtime() - metaTryMs < RETRY_MS) return;
        metaTryMs = SystemClock.elapsedRealtime();
        try {
            String json = download(BASE + "meta.json");
            Set<String> tiles = parseMetaTiles(json);
            java.nio.file.Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
            available = tiles;
            metaAtMs = now;
            L.i("precios ad hoc: datos de Ladekönig al día (" + tiles.size() + " zonas con cargadores)");
        } catch (Exception e) {
            L.w("precios ad hoc: sin la lista de Ladekönig (" + Http.safeError(e) + ")");
            // Lo de antes, aunque sea viejo, mejor que nada.
            if (available == null && f.isFile()) {
                try {
                    available = parseMetaTiles(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // Nada.
                }
            }
        }
    }

    /** Puro: las cuadrículas de meta.json. */
    static Set<String> parseMetaTiles(String json) throws IOException {
        Set<String> out = new HashSet<>();
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                if ("tiles".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) out.add(r.nextString());
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return out;
    }

    private static String download(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setRequestProperty("User-Agent", Http.USER_AGENT);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            // Llega comprimido (gzip) y HttpURLConnection lo descomprime solo.
            try (InputStream in = con.getInputStream()) {
                return new String(Http.readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }

    private static String readGz(File f) throws IOException {
        try (InputStream in = new GZIPInputStream(new java.io.FileInputStream(f))) {
            return new String(Http.readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeGz(File f, String json) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (GZIPOutputStream out = new GZIPOutputStream(new java.io.FileOutputStream(tmp))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }

    private static void trimDisk(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json.gz"));
        if (fs == null || fs.length <= MAX_DISK_TILES) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < fs.length - MAX_DISK_TILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
        }
    }

    /** Variante de pruebas («precios»): lo que hay en memoria a menos de radiusKm y la carga rápida más barata ahora. */
    static String labStatus(double lat, double lon, double radiusKm) {
        Calendar local = berlin(System.currentTimeMillis());
        int n = 0;
        int dc = 0;
        Station cheapest = null;
        double min = Double.MAX_VALUE;
        synchronized (AdHocPrices.class) {
            for (String t : tilesAround(lat, lon, radiusKm)) {
                List<Station> l = mem.get(t);
                if (l == null) continue;
                for (Station s : l) {
                    if (RoutePlanner.distanceKm(lat, lon, s.lat, s.lon) > radiusKm) continue;
                    n++;
                    Price p = stationPrice(s, true, local);
                    if (p == null) continue;
                    dc++;
                    if (p.kwh < min) {
                        min = p.kwh;
                        cheapest = s;
                    }
                }
            }
        }
        Set<String> av = available;
        return String.format(Locale.US, "precios ad hoc: meta %s · %d zonas en memoria · a %.0f km: %d estaciones con precio, %d en continua%s",
                av == null ? "sin bajar" : av.size() + " zonas", mem.size(), radiusKm, n, dc,
                cheapest == null ? "" : String.format(Locale.US, " · la más barata %.2f €/kWh (%s)", min, cheapest.operator));
    }

    /** Pruebas: datos de una cuadrícula como si hubieran llegado (meta incluida). */
    static synchronized void putForTest(String tile, String json) throws IOException {
        Set<String> av = available == null ? new HashSet<>() : new HashSet<>(available);
        av.add(tile);
        available = av;
        metaAtMs = System.currentTimeMillis();
        put(tile, parseTile(json), System.currentTimeMillis());
    }

    static synchronized void clearForTest() {
        mem.clear();
        loadedAt.clear();
        triedAt.clear();
        queue.clear();
        available = null;
        metaAtMs = 0;
        timed = false;
        dataVersion++;
    }

    // ------------------------------------------------------------------ a los cargadores

    /**
     * Pone a cada cargador su precio ad hoc de ahora (o NaN) y si está donde hay precios (adHocArea: con un tope de
     * precio, uno de Alemania sin precio conocido cuenta como «sin datos»). Desde cualquier hilo.
     */
    static void attach(List<RoutePlanner.Charger> list, long nowMs) {
        Set<String> av = available;
        if (av == null || list == null || list.isEmpty()) return;
        Calendar local = berlin(nowMs);
        for (RoutePlanner.Charger c : list) {
            String t = tileOf(c.lat, c.lon);
            c.adHocArea = av.contains(t);
            c.adHocKwh = Double.NaN;
            c.adHocMin = Double.NaN;
            c.adHocSession = Double.NaN;
            if (!c.adHocArea) continue;
            Price p = priceFor(c, local);
            if (p == null) continue;
            c.adHocKwh = p.kwh;
            c.adHocMin = p.minute;
            c.adHocMinFrom = p.minuteFrom;
            c.adHocSession = p.session;
        }
    }

    /** El precio de un cargador con lo que hay en memoria: la estación que le corresponde y su punto más barato. */
    private static Price priceFor(RoutePlanner.Charger c, Calendar local) {
        List<Station> near = new ArrayList<>();
        synchronized (AdHocPrices.class) {
            // Su cuadrícula y, si está cerca del borde, las de al lado.
            double pad = MATCH_NET_KM / 111.32;
            double padLon = pad / Math.max(0.2, Math.cos(Math.toRadians(c.lat)));
            List<double[]> box = new ArrayList<>();
            box.add(new double[]{c.lat - pad, c.lon - padLon, c.lat + pad, c.lon + padLon});
            for (String t : tilesFor(box)) {
                List<Station> l = mem.get(t);
                if (l != null) near.addAll(l);
            }
        }
        return best(near, c.lat, c.lon, c.network, !c.acOnly, local);
    }

    /**
     * Puro: el precio para un cargador en (lat, lon) de esta red y tipo (dc: continua) entre estas estaciones: la de su
     * red a menos de MATCH_NET_KM o, si su red no se sabe (u OTHER), la que tenga precio a menos de MATCH_KM; nunca una
     * de otra red conocida (en un área de servicio, el Supercharger y el Ionity están a 50 m).
     */
    static Price best(List<Station> stations, double lat, double lon, String network, boolean dc, Calendar local) {
        boolean known = network != null && !network.isEmpty() && !ChargerFilter.OTHER.equals(network);
        Price found = null;
        double foundKm = Double.MAX_VALUE;
        boolean foundSame = false;
        for (Station s : stations) {
            double d = RoutePlanner.distanceKm(lat, lon, s.lat, s.lon);
            if (d > MATCH_NET_KM) continue;
            boolean sKnown = !ChargerFilter.OTHER.equals(s.network);
            boolean same = known && sKnown && network.equals(s.network);
            if (known && sKnown && !same) continue;
            if (!same && d > MATCH_KM) continue;
            Price p = stationPrice(s, dc, local);
            if (p == null) continue;
            // Mejor la de su misma red; luego la más cercana.
            if (found == null || (same && !foundSame) || (same == foundSame && d < foundKm)) {
                found = p;
                foundKm = d;
                foundSame = same;
            }
        }
        return found;
    }

    /** Puro: el precio más barato de ahora en los puntos de este tipo (dc: continua) de una estación, o null. */
    static Price stationPrice(Station s, boolean dc, Calendar local) {
        Price best = null;
        double bestKw = 0;
        for (Group g : s.groups) {
            if (g.dc >= 0 && (g.dc == 1) != dc) continue;
            for (int i : g.rates) {
                if (i < 0 || i >= s.rates.length) continue;
                Price p = current(s.rates[i], local);
                if (p == null) continue;
                if (best == null || p.kwh < best.kwh || (p.kwh == best.kwh && g.kw > bestKw)) {
                    best = p;
                    bestKw = g.kw;
                }
            }
        }
        return best;
    }

    /**
     * Puro (como current_price de Ladekönig): el precio de una tarifa ahora. Con varios €/kWh a la vez, el de franja y,
     * de ellos, el más caro; €/min, el que empieza antes; lo fijo («base», «flat»), sumado. null si no es en euros, no
     * vale ahora o no tiene €/kWh ni es gratis.
     */
    static Price current(Rate r, Calendar local) {
        if (!r.eur || !valid(r.when, local)) return null;
        List<Part> active = new ArrayList<>();
        for (Part p : r.parts) if (valid(p.when, local)) active.add(p);
        List<Part> kwh = new ArrayList<>();
        for (Part p : active) if ("kwh".equals(p.type) && p.from == 0) kwh.add(p);
        if (kwh.isEmpty()) for (Part p : active) if ("kwh".equals(p.type)) kwh.add(p);
        boolean free = false;
        for (Part p : active) if ("free".equals(p.type)) free = true;
        double energy;
        if (!kwh.isEmpty()) {
            List<Part> restricted = new ArrayList<>();
            for (Part p : kwh) if (p.when != null && p.when.restricted()) restricted.add(p);
            energy = Double.NEGATIVE_INFINITY;
            for (Part p : restricted.isEmpty() ? kwh : restricted) energy = Math.max(energy, p.value);
        } else if (free) {
            energy = 0;
        } else {
            return null;
        }
        Price out = new Price(energy);
        Part minute = null;
        double session = 0;
        boolean hasSession = false;
        for (Part p : active) {
            if ("min".equals(p.type) && (minute == null || p.from < minute.from)) minute = p;
            if ("base".equals(p.type) || "flat".equals(p.type)) {
                session += p.value;
                hasSession = true;
            }
        }
        if (minute != null) {
            out.minute = minute.value;
            out.minuteFrom = minute.from;
        }
        if (hasSession) out.session = session;
        return out;
    }

    /** Puro: la hora de Alemania de un instante. */
    static Calendar berlin(long ms) {
        Calendar c = Calendar.getInstance(BERLIN, Locale.ROOT);
        c.setTimeInMillis(ms);
        return c;
    }

    static boolean valid(Validity v, Calendar local) {
        if (v == null) return true;
        long t = local.getTimeInMillis();
        if (t < v.start || t > v.end) return false;
        if (v.in.length > 0) {
            boolean any = false;
            for (Period p : v.in) {
                if (matches(p, local)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        for (Period p : v.ex) if (matches(p, local)) return false;
        return true;
    }

    /** Todo lo que pide un periodo se cumple (los días festivos no se pueden saber: nunca). */
    static boolean matches(Period p, Calendar local) {
        if (p.special) return false;
        long t = local.getTimeInMillis();
        if (t < p.from || t >= p.to) return false;
        // Lunes = bit 0 (como Ladekönig); Calendar: domingo = 1.
        int day = (local.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        if (p.days != 0 && (p.days & (1 << day)) == 0) return false;
        int month = local.get(Calendar.MONTH) + 1;
        if (p.months != 0 && (p.months & (1 << month)) == 0) return false;
        if (p.windows.length > 0) {
            int now = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
            boolean in = false;
            for (int i = 0; i + 1 < p.windows.length; i += 2) {
                int a = p.windows[i];
                int b = p.windows[i + 1];
                if (a == b || (a < b ? now >= a && now < b : now >= a || now < b)) {
                    in = true;
                    break;
                }
            }
            if (!in) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ leer una cuadrícula

    /** Puro: las estaciones con precio de una cuadrícula de Ladekönig (las que no tienen ninguna tarifa, fuera). */
    static List<Station> parseTile(String json) throws IOException {
        List<Station> out = new ArrayList<>();
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                if ("stations".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        Station s = station(r);
                        if (s != null && s.rates.length > 0) out.add(s);
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return out;
    }

    private static Station station(JsonReader r) throws IOException {
        Station s = new Station();
        String op = "";
        String brand = "";
        boolean pos = false;
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "id":
                    s.id = r.nextString();
                    break;
                case "la":
                    s.lat = r.nextDouble();
                    pos = true;
                    break;
                case "lo":
                    s.lon = r.nextDouble();
                    break;
                case "o":
                    op = r.nextString();
                    break;
                case "b":
                    brand = r.nextString();
                    break;
                case "g": {
                    List<Group> gs = new ArrayList<>();
                    r.beginArray();
                    while (r.hasNext()) gs.add(group(r));
                    r.endArray();
                    s.groups = gs.toArray(new Group[0]);
                    break;
                }
                case "r": {
                    List<Rate> rs = new ArrayList<>();
                    r.beginArray();
                    while (r.hasNext()) rs.add(rate(r));
                    r.endArray();
                    s.rates = rs.toArray(new Rate[0]);
                    break;
                }
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        if (!pos) return null;
        s.operator = op.isEmpty() ? brand : op;
        s.network = ChargerFilter.classify(brand, "", op, "");
        return s;
    }

    private static Group group(JsonReader r) throws IOException {
        Group g = new Group();
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "c": {
                    String c = r.nextString();
                    g.dc = "dc".equals(c) ? 1 : "ac".equals(c) ? 0 : -1;
                    break;
                }
                case "kw":
                    g.kw = r.nextDouble();
                    break;
                case "r":
                    g.rates = ints(r);
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return g;
    }

    private static Rate rate(JsonReader r) throws IOException {
        Rate rt = new Rate();
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "cur":
                    rt.eur = "EUR".equals(r.nextString());
                    break;
                case "w":
                    rt.when = validity(r);
                    break;
                case "p": {
                    List<Part> ps = new ArrayList<>();
                    r.beginArray();
                    while (r.hasNext()) ps.add(part(r));
                    r.endArray();
                    rt.parts = ps.toArray(new Part[0]);
                    break;
                }
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return rt;
    }

    private static Part part(JsonReader r) throws IOException {
        Part p = new Part();
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "t":
                    p.type = r.nextString();
                    break;
                case "v":
                    p.value = r.nextDouble();
                    break;
                case "f":
                    p.from = r.nextInt();
                    break;
                case "w":
                    p.when = validity(r);
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return p;
    }

    private static Validity validity(JsonReader r) throws IOException {
        Validity v = new Validity();
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "s":
                    v.start = time(r.nextString(), Long.MIN_VALUE);
                    break;
                case "e":
                    v.end = time(r.nextString(), Long.MAX_VALUE);
                    break;
                case "in":
                    v.in = periods(r);
                    break;
                case "ex":
                    v.ex = periods(r);
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return v;
    }

    private static Period[] periods(JsonReader r) throws IOException {
        List<Period> out = new ArrayList<>();
        r.beginArray();
        while (r.hasNext()) out.add(period(r));
        r.endArray();
        return out.toArray(new Period[0]);
    }

    private static Period period(JsonReader r) throws IOException {
        Period p = new Period();
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (n) {
                case "sp":
                    p.special = r.peek() == JsonToken.BOOLEAN ? r.nextBoolean() : !"".equals(r.nextString());
                    break;
                case "d":
                    for (int d : ints(r)) if (d >= 0 && d < 7) p.days |= 1 << d;
                    break;
                case "m":
                    for (int m : ints(r)) if (m >= 1 && m <= 12) p.months |= 1 << m;
                    break;
                case "a": {
                    r.beginArray();
                    int i = 0;
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.NULL) {
                            r.skipValue();
                        } else if (i == 0) {
                            p.from = time(r.nextString(), Long.MIN_VALUE);
                        } else if (i == 1) {
                            p.to = time(r.nextString(), Long.MAX_VALUE);
                        } else {
                            r.skipValue();
                        }
                        i++;
                    }
                    r.endArray();
                    break;
                }
                case "t": {
                    List<Integer> w = new ArrayList<>();
                    r.beginArray();
                    while (r.hasNext()) {
                        r.beginArray();
                        int a = r.hasNext() ? clock(r.nextString()) : -1;
                        int b = r.hasNext() ? clock(r.nextString()) : -1;
                        while (r.hasNext()) r.skipValue();
                        r.endArray();
                        if (a >= 0 && b >= 0) {
                            w.add(a);
                            w.add(b);
                        }
                    }
                    r.endArray();
                    p.windows = new int[w.size()];
                    for (int i = 0; i < w.size(); i++) p.windows[i] = w.get(i);
                    break;
                }
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return p;
    }

    private static int[] ints(JsonReader r) throws IOException {
        List<Integer> l = new ArrayList<>();
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() == JsonToken.NUMBER) l.add(r.nextInt());
            else r.skipValue();
        }
        r.endArray();
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    /** Puro: «07:30» → 450 minutos; «24:00» → 1440; -1 si no se entiende. */
    static int clock(String s) {
        Matcher m = CLOCK.matcher(s == null ? "" : s.trim());
        if (!m.lookingAt()) return -1;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h == 24 && min == 0) return 1440;
        if (h > 23 || min > 59) return -1;
        return h * 60 + min;
    }

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?");

    /**
     * Puro: una fecha ISO 8601 («2026-09-23T22:00:00Z», con desfase o sin él: entonces es hora alemana) en ms; si no
     * se entiende, dflt.
     */
    static long time(String s, long dflt) {
        Matcher m = ISO.matcher(s == null ? "" : s.trim());
        if (!m.lookingAt()) return dflt;
        String zone = m.group(7);
        TimeZone tz;
        if (zone == null) tz = BERLIN;
        else if ("Z".equals(zone)) tz = TimeZone.getTimeZone("UTC");
        else tz = TimeZone.getTimeZone("GMT" + (zone.contains(":") ? zone : zone.substring(0, 3) + ":" + zone.substring(3)));
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                m.group(4) == null ? 0 : Integer.parseInt(m.group(4)), m.group(5) == null ? 0 : Integer.parseInt(m.group(5)),
                m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        return c.getTimeInMillis();
    }
}
