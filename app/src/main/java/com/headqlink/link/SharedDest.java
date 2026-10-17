package com.headqlink.link;

import android.content.Context;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Ruta en Qonecta+»: un sitio compartido desde el móvil (Google Maps, una ubicación de WhatsApp, una dirección) se
 * guarda para el próximo viaje (ShareDestActivity) y, al empezar la sesión con el coche, el planificador lo pone de
 * destino y abre la navegación (RoutePlanner.useShared). Caduca a las MAX_AGE_MS. Solo en el móvil y sin pasar por el
 * registro: el destino no se escribe en ningún log.
 */
final class SharedDest {
    static final String K = "shared_dest";
    static final long MAX_AGE_MS = 24 * 3600_000L;

    private SharedDest() {
    }

    // ---------------------------------------------------------------- guardado

    static void save(Context c, RoutePlanner.Place p) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", p.name);
            o.put("detail", p.detail == null ? "" : p.detail);
            o.put("lat", p.lat);
            o.put("lon", p.lon);
            o.put("at", System.currentTimeMillis());
            new Config(c).putString(K, o.toString());
        } catch (Exception e) {
            L.w("destino compartido: no se pudo guardar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** El destino guardado si no ha caducado (y se borra: se usa una vez), o null. */
    static RoutePlanner.Place take(Context c) {
        Config cfg = new Config(c);
        String s = cfg.getString(K);
        if (s == null || s.isEmpty()) return null;
        cfg.putString(K, "");
        try {
            JSONObject o = new JSONObject(s);
            if (System.currentTimeMillis() - o.optLong("at") > MAX_AGE_MS) {
                L.i("destino compartido: caducado, no lo uso");
                return null;
            }
            RoutePlanner.Place p = new RoutePlanner.Place();
            p.name = o.optString("name");
            p.detail = o.optString("detail");
            p.lat = o.getDouble("lat");
            p.lon = o.getDouble("lon");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- qué se ha compartido (puro)

    private static final String NUM = "(-?\\d{1,3}\\.\\d+)";
    /** La ficha de un sitio: «!3dLAT!4dLON» (el sitio de verdad; «@» es el centro del mapa). */
    private static final Pattern PLACE = Pattern.compile("!3d" + NUM + "!4d" + NUM);
    private static final Pattern PARAM = Pattern.compile("[?&](?:q|ll|query|destination|daddr|center)=" + NUM + "(?:,|%2C)\\s*" + NUM,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AT = Pattern.compile("@" + NUM + "," + NUM);
    private static final Pattern GEO = Pattern.compile("^geo:" + NUM + "," + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_RE = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /** Puro: {lat, lon} de un enlace de Google Maps o de un «geo:», o null. */
    static double[] coords(String s) {
        if (s == null) return null;
        Matcher m;
        for (Pattern p : new Pattern[]{PLACE, PARAM, GEO, AT}) {
            m = p.matcher(s);
            if (m.find()) {
                double lat = Double.parseDouble(m.group(1));
                double lon = Double.parseDouble(m.group(2));
                if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && (lat != 0 || lon != 0)) return new double[]{lat, lon};
            }
        }
        return null;
    }

    /** Puro: el texto a buscar de un enlace o un «geo:» sin coordenadas (q=, query=, /place/NOMBRE/), o null. */
    static String query(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("[?&](?:q|query|destination|daddr)=([^&]+)", Pattern.CASE_INSENSITIVE).matcher(s);
        String q = null;
        if (m.find()) q = m.group(1);
        if (q == null) {
            Matcher p = Pattern.compile("/maps/place/([^/@?]+)").matcher(s);
            if (p.find()) q = p.group(1);
        }
        if (q == null) return null;
        try {
            q = URLDecoder.decode(q.replace("+", "%20"), StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // Mal codificado: tal cual.
        }
        q = q.replaceAll("\\(([^)]*)\\)$", "").trim();
        return q.isEmpty() || coords("?q=" + q) != null ? null : q;
    }

    /** Puro: la etiqueta de un «geo:…(Etiqueta)», o null. */
    static String geoLabel(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("\\(([^)]+)\\)\\s*$").matcher(s);
        if (!m.find()) return null;
        try {
            return URLDecoder.decode(m.group(1).replace("+", "%20"), StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) {
            return m.group(1).trim();
        }
    }

    /** Puro: el primer enlace del texto compartido, o null. */
    static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_RE.matcher(text);
        return m.find() ? m.group().replaceAll("[)\\].,;]+$", "") : null;
    }

    /** Puro: el nombre del sitio (la primera línea que no es un enlace), o null. */
    static String name(String text) {
        if (text == null) return null;
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (!l.isEmpty() && !URL_RE.matcher(l).find()) return l.length() > 80 ? l.substring(0, 80) : l;
        }
        return null;
    }

    /** Puro: ¿es un enlace de Google Maps? (solo esos se siguen por la red). */
    static boolean mapsUrl(String url) {
        try {
            URL u = new URL(url);
            String h = u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getPath();
            if (h.equals("maps.app.goo.gl") || (h.equals("goo.gl") && path.startsWith("/maps"))) return true;
            boolean google = h.matches("(www\\.|maps\\.)?google\\.[a-z.]{2,6}");
            return google && (h.startsWith("maps.") || path.startsWith("/maps"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Puro: un enlace corto (hay que seguirlo por la red para ver el sitio). */
    static boolean shortUrl(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.contains("://maps.app.goo.gl/") || l.contains("://goo.gl/maps");
    }

    /**
     * Sigue un enlace corto de Google Maps hasta el largo (como mucho 6 saltos, solo por enlaces de Google Maps; la
     * página de consentimiento de Google lleva el destino en «continue»). Fuera del hilo principal. null si no se puede.
     */
    static String resolve(String url) {
        String cur = url;
        for (int hop = 0; hop < 6 && cur != null; hop++) {
            if (coords(cur) != null || query(cur) != null && !shortUrl(cur)) return cur;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Qonecta");
                int code = c.getResponseCode();
                String next = c.getHeaderField("Location");
                if (code < 300 || code >= 400 || next == null) return cur;
                next = new URL(new URL(cur), next).toString();
                if (next.contains("consent.google.")) {
                    Matcher m = Pattern.compile("[?&]continue=([^&]+)").matcher(next);
                    if (m.find()) next = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8.name());
                }
                if (!mapsUrl(next)) return coords(next) != null ? next : null;
                cur = next;
            } catch (Exception e) {
                L.w("destino compartido: no se pudo abrir el enlace (" + e.getClass().getSimpleName() + ")");
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return cur;
    }
}
