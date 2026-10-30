package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Las carreteras de alrededor (RoadAhead) guardadas en el móvil, una por cada carga buena con su centro, como mucho
 * MAX_AGE_MS y MAX_FILES: en los trayectos de siempre, el límite de la vía y los radares sin ruta salen de aquí sin
 * preguntar a Overpass, cuyos servidores públicos se saturan a ratos (27-10: ni una consulta del límite en toda la tarde).
 * Son datos públicos de OpenStreetMap, pero los centros dicen por dónde se ha ido: solo en el móvil (files/roads), nunca en
 * el log ni en lo que se exporta.
 * <p>
 * Los radares (RoadInfo.loadCameras) se guardan igual, en files/cams: los de menos de CAM_FRESH_MS se usan sin preguntar;
 * los más viejos, solo si Overpass no contesta (30-10: hasta 4 min sin radares por tiempos agotados de Overpass).
 */
final class RoadCache {
    /** Se usa la carga guardada con el centro a esto (m) o menos: con su radio (RoadInfo.ROADS_RADIUS_M) cubre lo de delante. */
    static final double HIT_M = 1000;
    static final long MAX_AGE_MS = 60L * 86_400_000L;
    static final int MAX_FILES = 300;
    private static final String DIR = "roads";
    /** Radares guardados con el centro a esto (m) o menos: de los 15 km de la carga quedan 10 por delante. */
    static final double CAM_HIT_M = 5000;
    /** Radares guardados más nuevos que esto: sin preguntar a Overpass. */
    static final long CAM_FRESH_MS = 7L * 86_400_000L;
    private static final String CAM_DIR = "cams";

    private RoadCache() {
    }

    /** Una carga guardada: su centro, de cuándo es y sus vías. */
    static final class Hit {
        final double lat;
        final double lon;
        final long atMs;
        final List<RoadAhead.Way> ways;

        Hit(double lat, double lon, long atMs, List<RoadAhead.Way> ways) {
            this.lat = lat;
            this.lon = lon;
            this.atMs = atMs;
            this.ways = ways;
        }
    }

    /** Puro: el nombre del fichero de una carga: el centro en millonésimas de grado y la hora en segundos. */
    static String name(double lat, double lon, long atMs) {
        return String.format(Locale.US, "r_%d_%d_%d.json.gz", Math.round(lat * 1e6), Math.round(lon * 1e6), atMs / 1000);
    }

    /** Puro: {lat, lon, ms} de un nombre de {@link #name}, o null si no lo es. */
    static double[] parseName(String n) {
        if (n == null || !n.startsWith("r_") || !n.endsWith(".json.gz")) return null;
        String[] p = n.substring(2, n.length() - ".json.gz".length()).split("_");
        if (p.length != 3) return null;
        try {
            return new double[]{Long.parseLong(p[0]) / 1e6, Long.parseLong(p[1]) / 1e6, Long.parseLong(p[2]) * 1000.0};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Puro: de las cargas guardadas ({lat, lon, ms}), la más nueva a HIT_M o menos y de menos de MAX_AGE_MS; -1 si ninguna. */
    static int pick(List<double[]> saved, double lat, double lon, long nowMs) {
        return pick(saved, lat, lon, nowMs, HIT_M, MAX_AGE_MS);
    }

    /** Puro: como {@link #pick(List, double, double, long)}, a hitM o menos y de menos de maxAgeMs. */
    static int pick(List<double[]> saved, double lat, double lon, long nowMs, double hitM, long maxAgeMs) {
        int best = -1;
        for (int i = 0; i < saved.size(); i++) {
            double[] s = saved.get(i);
            if (nowMs - s[2] > maxAgeMs || RoadInfo.dist(lat, lon, s[0], s[1]) > hitM) continue;
            if (best < 0 || s[2] > saved.get(best)[2]) best = i;
        }
        return best;
    }

    /** Puro: los nombres que sobran (caducados, o los más viejos pasados MAX_FILES), de los de {@link #name}. */
    static List<String> toDelete(List<String> names, long nowMs) {
        List<String> keep = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String n : names) {
            double[] p = parseName(n);
            if (p == null) continue;
            if (nowMs - p[2] > MAX_AGE_MS) out.add(n);
            else keep.add(n);
        }
        if (keep.size() > MAX_FILES) {
            Collections.sort(keep, (a, b) -> Double.compare(parseName(a)[2], parseName(b)[2]));
            out.addAll(keep.subList(0, keep.size() - MAX_FILES));
        }
        return out;
    }

    private static File dir(Context ctx, String name) {
        return new File(ctx.getFilesDir(), name);
    }

    private static List<String> names(Context ctx, String dirName) {
        String[] l = dir(ctx, dirName).list();
        List<String> out = new ArrayList<>();
        if (l != null) for (String n : l) if (parseName(n) != null) out.add(n);
        return out;
    }

    /** La carga guardada que vale para (lat, lon), o null. */
    static synchronized Hit find(Context ctx, double lat, double lon, long nowMs) {
        Saved got = read(ctx, DIR, lat, lon, nowMs, HIT_M, MAX_AGE_MS, "carreteras guardadas ilegibles");
        if (got == null) return null;
        try {
            return new Hit(got.lat, got.lon, got.atMs, RoadAhead.fromJson(got.json));
        } catch (Exception e) {
            L.w("vía: carreteras guardadas ilegibles (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** Guarda una carga buena con su centro y borra lo que sobre. */
    static synchronized void save(Context ctx, double lat, double lon, List<RoadAhead.Way> ways, long nowMs) {
        JSONArray json;
        try {
            json = RoadAhead.toJson(ways);
        } catch (Exception e) {
            L.w("vía: no se pudieron guardar las carreteras (" + e.getClass().getSimpleName() + ")");
            return;
        }
        write(ctx, DIR, lat, lon, json, nowMs, "no se pudieron guardar las carreteras");
    }

    /** Unos radares guardados: su centro, de cuándo son y la lista (como RoadInfo.parseCameras). */
    static final class Cams {
        final double lat;
        final double lon;
        final long atMs;
        final List<double[]> cams;

        Cams(double lat, double lon, long atMs, List<double[]> cams) {
            this.lat = lat;
            this.lon = lon;
            this.atMs = atMs;
            this.cams = cams;
        }
    }

    /** Los radares guardados que valen para (lat, lon) con menos de maxAgeMs (CAM_FRESH_MS o MAX_AGE_MS), o null. */
    static synchronized Cams findCameras(Context ctx, double lat, double lon, long nowMs, long maxAgeMs) {
        Saved got = read(ctx, CAM_DIR, lat, lon, nowMs, CAM_HIT_M, maxAgeMs, "radares guardados ilegibles");
        return got == null ? null : new Cams(got.lat, got.lon, got.atMs, camerasFromJson(got.json));
    }

    /** Guarda una carga buena de radares con su centro y borra lo que sobre. */
    static synchronized void saveCameras(Context ctx, double lat, double lon, List<double[]> cams, long nowMs) {
        write(ctx, CAM_DIR, lat, lon, camerasToJson(cams), nowMs, "no se pudieron guardar los radares");
    }

    /** Puro: los radares en JSON, un array de números por radar (NaN, que JSON no admite, va como null). */
    static JSONArray camerasToJson(List<double[]> cams) {
        JSONArray out = new JSONArray();
        for (double[] c : cams) {
            JSONArray a = new JSONArray();
            for (double v : c) {
                if (Double.isNaN(v) || Double.isInfinite(v)) a.put(JSONObject.NULL);
                else a.put(Double.valueOf(v));
            }
            out.put(a);
        }
        return out;
    }

    /** Puro: lo contrario de {@link #camerasToJson}. */
    static List<double[]> camerasFromJson(JSONArray a) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray c = a.optJSONArray(i);
            if (c == null) continue;
            double[] v = new double[c.length()];
            for (int j = 0; j < v.length; j++) v[j] = c.optDouble(j, Double.NaN);
            out.add(v);
        }
        return out;
    }

    /** Lo leído de un fichero guardado: su centro, de cuándo es y el JSON. */
    private static final class Saved {
        final double lat;
        final double lon;
        final long atMs;
        final JSONArray json;

        Saved(double lat, double lon, long atMs, JSONArray json) {
            this.lat = lat;
            this.lon = lon;
            this.atMs = atMs;
            this.json = json;
        }
    }

    private static Saved read(Context ctx, String dirName, double lat, double lon, long nowMs, double hitM, long maxAgeMs,
                              String unreadable) {
        List<String> names = names(ctx, dirName);
        List<double[]> saved = new ArrayList<>();
        for (String n : names) saved.add(parseName(n));
        int i = pick(saved, lat, lon, nowMs, hitM, maxAgeMs);
        if (i < 0) return null;
        File f = new File(dir(ctx, dirName), names.get(i));
        try (InputStream in = new GZIPInputStream(new FileInputStream(f))) {
            double[] s = saved.get(i);
            return new Saved(s[0], s[1], (long) s[2], new JSONArray(new String(Http.readAll(in), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            L.w("vía: " + unreadable + " (" + e.getClass().getSimpleName() + "): las borro");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    private static void write(Context ctx, String dirName, double lat, double lon, JSONArray json, long nowMs, String failed) {
        File d = dir(ctx, dirName);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        String name = name(lat, lon, nowMs);
        File tmp = new File(d, name + ".tmp");
        try (OutputStream out = new GZIPOutputStream(new FileOutputStream(tmp))) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.w("vía: " + failed + " (" + e.getClass().getSimpleName() + ")");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(new File(d, name))) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        for (String n : toDelete(names(ctx, dirName), nowMs)) new File(d, n).delete();
    }
}
