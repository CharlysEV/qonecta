package com.headqlink.link;

import android.content.Context;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Bloqueo de anuncios y rastreadores del navegador y de la TV del coche, por dominio: lo que una página pide a uno de
 * ellos recibe una respuesta vacía (WebScreen, IptvScreen). Dos listas:
 * - la corta de siempre (HOSTS), que va dentro de la app;
 * - la grande, HaGeZi Pro mini (https://github.com/hagezi/dns-blocklists, GPL-3.0; unos 84.000 dominios de publicidad,
 *   rastreo y telemetría), que se baja una vez a la semana y se guarda compacta (un número de 64 bits por dominio,
 *   ordenados: ~670 KB y búsqueda binaria).
 * Cada dominio bloquea también sus subdominios. Lo de NEVER (YouTube y sus vídeos, Google, Twitch…) no se bloquea nunca,
 * diga lo que diga una lista. Lo de YouTube (los anuncios que van con el propio vídeo) no se quita por dominio: eso es
 * AdBlockJs. Lo puro se prueba en el PC.
 */
final class AdBlock {
    private static final String[] HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com", "pagead2.googlesyndication.com",
            "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com",
            "scorecardresearch.com", "quantserve.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "moatads.com", "teads.tv", "smartadserver.com", "3lift.com", "sharethrough.com",
            "hotjar.com", "ads-twitter.com", "adform.net", "media.net", "zemanta.com", "yieldmo.com", "bidswitch.net",
            "advertising.com", "adcolony.com", "applovin.com", "unityads.unity3d.com", "chartbeat.com", "mgid.com",
            "seedtag.com", "adition.com", "smartclip.net", "sascdn.com",
    };

    /** Nunca se bloquean (ni sus subdominios): sin ellos no van YouTube, los vídeos, Google ni Twitch. */
    private static final String[] NEVER = {
            "youtube.com", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "ggpht.com", "youtubei.googleapis.com",
            "google.com", "google.es", "gstatic.com", "accounts.google.com", "twitch.tv", "ttvnw.net", "jtvnw.net",
            "charlysev.github.io",
    };

    static final String LIST_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.mini-onlydomains.txt";
    static final String LIST_NAME = "HaGeZi Pro mini";
    /** Se vuelve a bajar a la semana; tras un fallo, no antes de RETRY_MS. */
    static final long MAX_AGE_MS = 7L * 86_400_000L;
    static final long RETRY_MS = 30L * 60_000L;
    /** Una lista con menos de esto es un error (una página de aviso, un fichero a medias): no se guarda. */
    static final int MIN_ENTRIES = 10_000;
    private static final String FILE = "adblock-hosts.bin";
    private static final int MAGIC = 0x48514142; // «HQAB»

    /** La lista grande cargada (hashes ordenados), o null si aún no hay. */
    private static volatile long[] list;
    private static volatile long listAtMs;
    private static volatile boolean busy;
    private static volatile long lastTryMs;

    private AdBlock() {
    }

    // ------------------------------------------------------------------ puro

    /** Puro: ¿anuncio o rastreador? El dominio, o uno de los que lo contienen, está en una lista y no en NEVER. */
    static boolean isAd(String host) {
        return isAd(host, list);
    }

    static boolean isAd(String host, long[] big) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (matches(h, NEVER)) return false;
        if (matches(h, HOSTS)) return true;
        if (big == null || big.length == 0) return false;
        // El dominio y cada uno que lo contiene (a.b.c.com, b.c.com, c.com), sin llegar al de primer nivel.
        String d = h;
        while (true) {
            if (Arrays.binarySearch(big, hash(d)) >= 0) return true;
            int dot = d.indexOf('.');
            if (dot < 0 || d.indexOf('.', dot + 1) < 0) return false;
            d = d.substring(dot + 1);
        }
    }

    private static boolean matches(String h, String[] domains) {
        for (String d : domains) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    /** Puro: FNV-1a de 64 bits del dominio (en minúsculas). Con 84.000 dominios, una coincidencia por azar es imposible en la práctica. */
    static long hash(String domain) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < domain.length(); i++) {
            h ^= domain.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Puro: una lista de dominios (uno por línea; «#» comenta, también «*.» delante) → hashes ordenados y sin repetir. */
    static long[] parse(BufferedReader r) throws IOException {
        long[] out = new long[1 << 17];
        int n = 0;
        String line;
        while ((line = r.readLine()) != null) {
            String s = line.trim();
            int hashMark = s.indexOf('#');
            if (hashMark >= 0) s = s.substring(0, hashMark).trim();
            if (s.startsWith("*.")) s = s.substring(2);
            if (s.isEmpty() || s.indexOf('.') < 0 || s.indexOf(' ') >= 0) continue;
            if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
            out[n++] = hash(s.toLowerCase(Locale.ROOT));
        }
        long[] a = Arrays.copyOf(out, n);
        Arrays.sort(a);
        int u = 0;
        for (int i = 0; i < a.length; i++) {
            if (i == 0 || a[i] != a[i - 1]) a[u++] = a[i];
        }
        return Arrays.copyOf(a, u);
    }

    // ------------------------------------------------------------------ en el móvil

    /** Cuántos dominios tiene la lista grande cargada (0 si no hay). */
    static int size() {
        long[] l = list;
        return l == null ? 0 : l.length;
    }

    /**
     * Fuera del hilo principal: carga la lista guardada (una vez) y, si no hay o tiene más de una semana, la baja de
     * nuevo. Sin red o si falla, se sigue con la que haya (o con la corta).
     */
    static void ensure(Context ctx) {
        if (busy) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        long now = System.currentTimeMillis();
        boolean stale = list == null || now - listAtMs > MAX_AGE_MS;
        if (list != null && !stale) return;
        if (list != null && lastTryMs > 0 && now - lastTryMs < RETRY_MS) return;
        busy = true;
        Thread t = new Thread(() -> {
            try {
                if (list == null) load(app);
                if (list == null || System.currentTimeMillis() - listAtMs > MAX_AGE_MS) download(app);
            } finally {
                busy = false;
            }
        }, "hql-adblock");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException("formato");
            long at = in.readLong();
            int n = in.readInt();
            if (n < MIN_ENTRIES || n > 2_000_000) throw new IOException("tamaño " + n);
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = in.readLong();
            list = a;
            listAtMs = at;
            L.i("anuncios: lista " + LIST_NAME + " con " + n + " dominios (de hace " + (System.currentTimeMillis() - at) / 86_400_000L + " días)");
        } catch (IOException | RuntimeException e) {
            L.w("anuncios: no se pudo leer la lista guardada (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void download(Context ctx) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(LIST_URL).openConnection();
            con.setConnectTimeout(15_000);
            con.setReadTimeout(60_000);
            con.setRequestProperty("User-Agent", Http.USER_AGENT);
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long[] a;
            try (InputStream in = con.getInputStream()) {
                a = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
            if (a.length < MIN_ENTRIES) throw new IOException("solo " + a.length + " dominios");
            long now = System.currentTimeMillis();
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeLong(now);
                out.writeInt(a.length);
                for (long v : a) out.writeLong(v);
            }
            File dst = new File(ctx.getFilesDir(), FILE);
            if (!tmp.renameTo(dst)) {
                dst.delete();
                if (!tmp.renameTo(dst)) throw new IOException("no se pudo guardar");
            }
            list = a;
            listAtMs = now;
            lastTryMs = 0;
            L.i(String.format(Locale.US, "anuncios: lista %s al día, %d dominios en %.1f s", LIST_NAME, a.length,
                    (now - t0) / 1000.0));
        } catch (Exception e) {
            lastTryMs = System.currentTimeMillis();
            L.w("anuncios: no se pudo bajar la lista (" + Http.safeError(e) + "); sigo con " + (list != null ? "la guardada" : "la corta"));
        }
    }
}
