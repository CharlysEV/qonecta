package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La carretera por delante sin ruta en el planificador (18-10), para los radares: las carreteras de OpenStreetMap de
 * alrededor (las de coches: de autovía a carretera local, con sus ramales), la que se lleva (la más cercana alineada con
 * la marcha y en un sentido permitido) y, por ella hacia delante, hasta una distancia; en cada cruce, la misma carretera
 * (misma referencia o nombre) o la que menos gira. Sin ruta, los radares se buscaban en línea recta: en curva se veía
 * tarde el de la propia carretera y en recta valía el de una vía paralela (RadarSimTest). Puro: se prueba en el PC.
 */
final class RoadAhead {
    /** Las clases de vía que se siguen (sin calles de barrio ni de servicio: los radares fijos van en las otras). */
    static final String CLASSES = "motorway|trunk|primary|secondary|tertiary|unclassified|motorway_link|trunk_link|primary_link"
            + "|secondary_link|tertiary_link";
    /** La vía que se lleva: a menos de esto (m) y con el rumbo a menos de MATCH_DEG de la marcha. */
    static final double MATCH_M = 30;
    static final double MATCH_DEG = 50;
    /** En un cruce no se sigue por donde haya que girar más de esto (grados): ahí se acaba lo que se sabe. */
    static final double MAX_TURN_DEG = 75;

    /** Una vía: sus nodos (ids), sus puntos, referencia, nombre y sentido (1: el del dibujo; −1: el contrario; 0: los dos). */
    static final class Way {
        final long[] nodes;
        final double[] lat;
        final double[] lon;
        final String ref;
        final String name;
        final boolean link;
        final int oneway;

        Way(long[] nodes, double[] lat, double[] lon, String ref, String name, boolean link, int oneway) {
            this.nodes = nodes;
            this.lat = lat;
            this.lon = lon;
            this.ref = ref == null ? "" : ref;
            this.name = name == null ? "" : name;
            this.link = link;
            this.oneway = oneway;
        }
    }

    private final List<Way> ways;
    /** Por cada nodo, las vías que pasan por él: {vía, posición}. */
    private final Map<Long, List<int[]>> byNode = new HashMap<>();

    RoadAhead(List<Way> ways) {
        this.ways = ways;
        for (int w = 0; w < ways.size(); w++) {
            long[] ns = ways.get(w).nodes;
            for (int i = 0; i < ns.length; i++) {
                List<int[]> l = byNode.get(ns[i]);
                if (l == null) byNode.put(ns[i], l = new ArrayList<>());
                l.add(new int[]{w, i});
            }
        }
    }

    int size() {
        return ways.size();
    }

    /** Las vías de una respuesta de OpenStreetMap con «out body geom» (nodos y puntos). */
    static List<Way> parse(JSONArray els) throws JSONException {
        List<Way> out = new ArrayList<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            if (!"way".equals(e.optString("type"))) continue;
            JSONArray ns = e.optJSONArray("nodes");
            JSONArray g = e.optJSONArray("geometry");
            if (ns == null || g == null || ns.length() != g.length() || ns.length() < 2) continue;
            long[] nodes = new long[ns.length()];
            double[] la = new double[ns.length()];
            double[] lo = new double[ns.length()];
            for (int k = 0; k < nodes.length; k++) {
                nodes[k] = ns.getLong(k);
                JSONObject p = g.getJSONObject(k);
                la[k] = p.getDouble("lat");
                lo[k] = p.getDouble("lon");
            }
            JSONObject t = e.optJSONObject("tags");
            String hw = t == null ? "" : t.optString("highway");
            int oneway = t == null ? 0 : RoadInfo.oneway(t);
            // Las autovías y sus ramales son de un sentido aunque no lo digan.
            if (oneway == 0 && t != null && (hw.equals("motorway") || hw.endsWith("_link")) && !"no".equals(t.optString("oneway"))) oneway = 1;
            out.add(new Way(nodes, la, lo, t == null ? "" : t.optString("ref"), t == null ? "" : t.optString("name"), hw.endsWith("_link"), oneway));
        }
        return out;
    }

    /**
     * El trazado por delante desde (lat, lon) yendo hacia heading (grados), hasta lengthM metros, empezando en el coche;
     * null si no se sabe por qué vía se va (lejos de todas, o ninguna alineada).
     */
    RoadInfo.Trace ahead(double lat, double lon, double heading, double lengthM) {
        // 1) La vía que se lleva, el tramo y el sentido.
        int bw = -1;
        int bs = -1;
        int bdir = 0;
        double bscore = Double.MAX_VALUE;
        double kx = 111_320.0 * Math.cos(Math.toRadians(lat));
        double ky = 111_320.0;
        for (int w = 0; w < ways.size(); w++) {
            Way way = ways.get(w);
            for (int i = 0; i < way.lat.length - 1; i++) {
                double d = segDist(way, i, lat, lon, kx, ky);
                if (d > MATCH_M) continue;
                double brg = RoadInfo.bearing(way.lat[i], way.lon[i], way.lat[i + 1], way.lon[i + 1]);
                for (int dir = 1; dir >= -1; dir -= 2) {
                    if (way.oneway != 0 && way.oneway != dir) continue;
                    double diff = Math.abs(RoutePlanner.angleDiff(heading, dir > 0 ? brg : brg + 180));
                    if (diff > MATCH_DEG) continue;
                    double score = d + diff;
                    if (score < bscore) {
                        bscore = score;
                        bw = w;
                        bs = i;
                        bdir = dir;
                    }
                }
            }
        }
        if (bw < 0) return null;
        // 2) Por ella hacia delante y, en cada cruce, la continuación.
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{lat, lon});
        double total = 0;
        int w = bw;
        int pos = bdir > 0 ? bs + 1 : bs;
        int dir = bdir;
        for (int hops = 0; hops < 60 && total < lengthM; hops++) {
            Way way = ways.get(w);
            double inBrg = Double.NaN;
            while (pos >= 0 && pos < way.lat.length && total < lengthM) {
                double[] last = pts.get(pts.size() - 1);
                double d = RoadInfo.dist(last[0], last[1], way.lat[pos], way.lon[pos]);
                if (d > 0.5) {
                    inBrg = RoadInfo.bearing(last[0], last[1], way.lat[pos], way.lon[pos]);
                    pts.add(new double[]{way.lat[pos], way.lon[pos]});
                    total += d;
                }
                pos += dir;
            }
            if (total >= lengthM) break;
            int endPos = dir > 0 ? way.lat.length - 1 : 0;
            int[] next = next(w, endPos, inBrg);
            if (next == null) break;
            w = next[0];
            dir = next[2];
            pos = next[1] + dir;
        }
        if (pts.size() < 2) return null;
        double[] la = new double[pts.size()];
        double[] lo = new double[pts.size()];
        double[] km = new double[pts.size()];
        double acc = 0;
        for (int i = 0; i < pts.size(); i++) {
            la[i] = pts.get(i)[0];
            lo[i] = pts.get(i)[1];
            if (i > 0) acc += RoadInfo.dist(la[i - 1], lo[i - 1], la[i], lo[i]);
            km[i] = acc / 1000;
        }
        return new RoadInfo.Trace(la, lo, km);
    }

    /**
     * La continuación al final de la vía w (en su punto endPos), llegando con rumbo inBrg: {vía, posición, sentido}, o
     * null. La misma referencia o el mismo nombre primero (y no un ramal si se iba por la carretera); si no, la que menos
     * gira (hasta MAX_TURN_DEG).
     */
    private int[] next(int w, int endPos, double inBrg) {
        Way cur = ways.get(w);
        List<int[]> at = byNode.get(cur.nodes[endPos]);
        if (at == null || Double.isNaN(inBrg)) return null;
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int[] c : at) {
            if (c[0] == w) continue;
            Way o = ways.get(c[0]);
            for (int dir = 1; dir >= -1; dir -= 2) {
                int nxt = c[1] + dir;
                if (nxt < 0 || nxt >= o.lat.length) continue;
                if (o.oneway != 0 && o.oneway != dir) continue;
                double out = RoadInfo.bearing(o.lat[c[1]], o.lon[c[1]], o.lat[nxt], o.lon[nxt]);
                double turn = Math.abs(RoutePlanner.angleDiff(out, inBrg));
                if (turn > MAX_TURN_DEG) continue;
                boolean same = (!cur.ref.isEmpty() && cur.ref.equals(o.ref)) || (!cur.name.isEmpty() && cur.name.equals(o.name));
                double score = turn + (same ? 0 : 100) + (o.link && !cur.link ? 50 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{c[0], c[1], dir};
                }
            }
        }
        return best;
    }

    private static double segDist(Way w, int i, double la, double lo, double kx, double ky) {
        double ax = (w.lon[i] - lo) * kx;
        double ay = (w.lat[i] - la) * ky;
        double bx = (w.lon[i + 1] - lo) * kx;
        double by = (w.lat[i + 1] - la) * ky;
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double f = l2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
        double px = ax + f * dx;
        double py = ay + f * dy;
        return Math.sqrt(px * px + py * py);
    }
}
