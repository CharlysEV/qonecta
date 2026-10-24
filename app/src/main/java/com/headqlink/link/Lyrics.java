package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Letras de canciones para el karaoke (KaraokeScreen), de LRCLIB (lrclib.net: abierto, gratis y sin clave). Primero la
 * canción exacta (artista, título, álbum y duración); si no está, una búsqueda por artista y título limpio («- Remastered
 * 2011», «(feat. …)» fuera), quedándose con la de duración más parecida y, mejor, sincronizada. Las letras sincronizadas
 * vienen en formato LRC («[01:23.45] texto»). Se recuerdan las últimas 40 en memoria; no se guarda nada en el móvil.
 */
final class Lyrics {
    private Lyrics() {
    }

    private static final String API = "https://lrclib.net/api/";
    /** Diferencia de duración (s) para dar por buena una canción de la búsqueda. */
    static final int DURATION_SLACK_S = 5;

    /** Una línea de la letra sincronizada: desde cuándo (ms) y qué se canta (vacía: un hueco sin letra). */
    static final class Line {
        final long ms;
        final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    /** Lo encontrado: letra sincronizada, solo el texto, instrumental o nada. */
    static final class Result {
        List<Line> synced = Collections.emptyList();
        String plain = "";
        boolean instrumental;

        boolean found() {
            return !synced.isEmpty() || !plain.isEmpty() || instrumental;
        }
    }

    // ------------------------------------------------------------------ puro

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    /** Puro: un LRC en líneas ordenadas por tiempo (varias marcas en una línea: una línea por marca; [offset:] aplicado). */
    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        long offset = 0;
        for (String raw : lrc.replace("\r", "").split("\n")) {
            String l = raw.trim();
            Matcher off = OFFSET.matcher(l);
            if (off.find()) {
                // [offset:+500]: la letra va 500 ms antes.
                offset = Long.parseLong(off.group(1).replace("+", ""));
                continue;
            }
            List<Long> times = new ArrayList<>();
            int pos = 0;
            Matcher m = TIME.matcher(l);
            while (m.find() && m.start() == pos) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                String frac = m.group(3);
                if (frac != null) {
                    // .5 = 500 ms, .45 = 450 ms, .450 = 450 ms.
                    ms += Math.round(Long.parseLong(frac) * Math.pow(10, 3 - frac.length()));
                }
                times.add(ms);
                pos = m.end();
            }
            if (times.isEmpty()) continue;
            String text = l.substring(pos).trim();
            for (long t : times) out.add(new Line(Math.max(0, t - offset), text));
        }
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out;
    }

    /** Puro: la línea que toca a ms (la última que ya empezó), o -1 antes de la primera. */
    static int indexAt(List<Line> lines, long ms) {
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).ms <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private static final Pattern TITLE_JUNK = Pattern.compile(
            "\\s*(?:-\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|remasterizad[oa]|live|en vivo|en directo|radio edit|single version"
                    + "|mono|stereo|versi[oó]n|version|edit|mix|acoustic|ac[uú]stic[oa])\\b.*"
                    + "|[(\\[](?:feat\\.?|ft\\.?|with|con|remaster|from|de la|live|en vivo|en directo)[^)\\]]*[)\\]])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Puro: el título sin lo que no forma parte de la canción («- Remastered 2011», «(feat. X)», «- Live»…). */
    static String cleanTitle(String title) {
        if (title == null) return "";
        String t = TITLE_JUNK.matcher(title).replaceAll("").trim();
        return t.isEmpty() ? title.trim() : t;
    }

    /**
     * Puro: solo el primer artista de una lista («A, B», «A feat. B» → «A»): así lo guarda casi siempre LRCLIB. «&» e «y»
     * no se parten: son parte del nombre de muchos grupos («Simon & Garfunkel»).
     */
    static String mainArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(",|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+")[0].trim();
        return a.isEmpty() ? artist.trim() : a;
    }

    /** Puro: el artista sin lo que añade la app de música detrás de « • » («Eternelles • Audio sin pérdida»). */
    static String stripBadge(String artist) {
        if (artist == null) return "";
        String a = artist.split("\\s+[\u2022\u00B7]\\s+")[0].trim();
        return a.isEmpty() ? artist.trim() : a;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    /** Puro: la canción exacta (LRCLIB pide la duración en segundos, con ±2 s de margen). */
    static String getUrl(String artist, String title, String album, int durationS) {
        StringBuilder b = new StringBuilder(API).append("get?artist_name=").append(enc(artist)).append("&track_name=").append(enc(title));
        if (album != null && !album.isEmpty()) b.append("&album_name=").append(enc(album));
        if (durationS > 0) b.append("&duration=").append(durationS);
        return b.toString();
    }

    /** Puro: la búsqueda por título y artista. */
    static String searchUrl(String artist, String title) {
        return API + "search?track_name=" + enc(title) + "&artist_name=" + enc(artist);
    }

    /** Puro: una canción de LRCLIB (respuesta de getUrl o un elemento de la búsqueda). */
    static Result fromJson(JSONObject o) {
        Result r = new Result();
        if (o == null) return r;
        r.instrumental = o.optBoolean("instrumental");
        r.synced = parseLrc(o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", ""));
        r.plain = o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "").trim();
        return r;
    }

    /**
     * Puro: de una búsqueda, la de duración más parecida (hasta DURATION_SLACK_S; sin duración, cualquiera), mejor con
     * letra sincronizada; vacío si ninguna vale.
     */
    static Result pick(String searchJson, int durationS) throws JSONException {
        JSONArray a = new JSONArray(searchJson);
        JSONObject best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            double d = durationS > 0 ? Math.abs(o.optDouble("duration", 0) - durationS) : 0;
            if (d > DURATION_SLACK_S) continue;
            boolean synced = !o.isNull("syncedLyrics") && !o.optString("syncedLyrics", "").isEmpty();
            boolean any = synced || o.optBoolean("instrumental")
                    || (!o.isNull("plainLyrics") && !o.optString("plainLyrics", "").isEmpty());
            if (!any) continue;
            // Sincronizada antes que solo texto; y entre iguales, la más parecida de duración.
            double score = d + (synced ? 0 : 100);
            if (score < bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return fromJson(best);
    }

    // ------------------------------------------------------------------ red

    private static final Map<String, Result> CACHE = new LinkedHashMap<String, Result>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> e) {
            return size() > 40;
        }
    };

    /** Lo que se recuerda de una canción, o null si aún no se ha buscado. */
    static synchronized Result cached(String artist, String title, long durationMs) {
        return CACHE.get(key(artist, title, durationMs));
    }

    private static String key(String artist, String title, long durationMs) {
        return (artist + "|" + title + "|" + Math.round(durationMs / 1000.0)).toLowerCase(Locale.ROOT);
    }

    /** Busca la letra (fuera del hilo principal). Sin red o con LRCLIB caído, una excepción (y no se recuerda nada). */
    static Result fetch(String artist, String title, String album, long durationMs) throws Exception {
        Result r = cached(artist, title, durationMs);
        if (r != null) return r;
        int dur = (int) Math.round(durationMs / 1000.0);
        r = null;
        try {
            r = fromJson(new JSONObject(get(getUrl(artist, title, album, dur))));
        } catch (NotFound e) {
            // No está tal cual: a buscarla (primer artista y título limpio).
        }
        if (r == null || !r.found()) {
            try {
                r = pick(get(searchUrl(mainArtist(artist), cleanTitle(title))), dur);
            } catch (NotFound e) {
                r = new Result();
            }
        }
        synchronized (Lyrics.class) {
            CACHE.put(key(artist, title, durationMs), r);
        }
        return r;
    }

    private static final class NotFound extends Exception {
    }

    /** Intentos a LRCLIB cuando dice «ocupado» (503, 429): se satura a ratos y a la tercera o cuarta contesta. */
    static final int BUSY_TRIES = 4;

    /** GET a LRCLIB: 404 es «no está»; ocupado, otra vez tras 1,5, 3 y 4,5 s. */
    private static String get(String url) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return Http.get(url);
            } catch (IllegalStateException e) {
                String m = String.valueOf(e.getMessage());
                if (m.startsWith("HTTP 404")) throw new NotFound();
                if (attempt < BUSY_TRIES && (m.startsWith("HTTP 503") || m.startsWith("HTTP 429"))) {
                    Thread.sleep(1500L * attempt);
                    continue;
                }
                throw e;
            }
        }
    }
}
