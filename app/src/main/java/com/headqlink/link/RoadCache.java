package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;

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
 */
final class RoadCache {
    /** Se usa la carga guardada con el centro a esto (m) o menos: con su radio (RoadInfo.ROADS_RADIUS_M) cubre lo de delante. */
    static final double HIT_M = 1000;
    static final long MAX_AGE_MS = 60L * 86_400_000L;
    static final int MAX_FILES = 300;
    private static final String DIR = "roads";

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
        int best = -1;
        for (int i = 0; i < saved.size(); i++) {
            double[] s = saved.get(i);
            if (nowMs - s[2] > MAX_AGE_MS || RoadInfo.dist(lat, lon, s[0], s[1]) > HIT_M) continue;
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

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    private static List<String> names(Context ctx) {
        String[] l = dir(ctx).list();
        List<String> out = new ArrayList<>();
        if (l != null) for (String n : l) if (parseName(n) != null) out.add(n);
        return out;
    }

    /** La carga guardada que vale para (lat, lon), o null. */
    static synchronized Hit find(Context ctx, double lat, double lon, long nowMs) {
        List<String> names = names(ctx);
        List<double[]> saved = new ArrayList<>();
        for (String n : names) saved.add(parseName(n));
        int i = pick(saved, lat, lon, nowMs);
        if (i < 0) return null;
        File f = new File(dir(ctx), names.get(i));
        try (InputStream in = new GZIPInputStream(new FileInputStream(f))) {
            List<RoadAhead.Way> ways = RoadAhead.fromJson(new JSONArray(new String(Http.readAll(in), StandardCharsets.UTF_8)));
            double[] s = saved.get(i);
            return new Hit(s[0], s[1], (long) s[2], ways);
        } catch (Exception e) {
            L.w("vía: carreteras guardadas ilegibles (" + e.getClass().getSimpleName() + "): las borro");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    /** Guarda una carga buena con su centro y borra lo que sobre. */
    static synchronized void save(Context ctx, double lat, double lon, List<RoadAhead.Way> ways, long nowMs) {
        File d = dir(ctx);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        String name = name(lat, lon, nowMs);
        File tmp = new File(d, name + ".tmp");
        try (OutputStream out = new GZIPOutputStream(new FileOutputStream(tmp))) {
            out.write(RoadAhead.toJson(ways).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.w("vía: no se pudieron guardar las carreteras (" + e.getClass().getSimpleName() + ")");
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
        for (String n : toDelete(names(ctx), nowMs)) new File(d, n).delete();
    }
}
