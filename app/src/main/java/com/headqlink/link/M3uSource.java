package com.headqlink.link;

import android.content.Context;
import android.net.Uri;

import com.andrerinas.openheadunit.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Las listas de TV y de radio del usuario (M3U o la JSON de TDTChannels): de dónde salen y cómo se bajan.
 * - Páginas conocidas que no son una lista (foro #299: el GitHub de iptv-org o la web de iprd pegados tal cual; en el
 *   coche cada línea del HTML salía como un canal de «signos raros»): su lista M3U de verdad, la del país del móvil
 *   (convert). Un enlace «blob» de GitHub: el archivo en crudo (raw.githubusercontent.com).
 * - Lo bajado (text): gzip, UTF-8 con BOM, o Windows-1252 si no es UTF-8; una página web, o algo que no es una lista, da
 *   un mensaje claro (ListError) en vez de canales basura. Vale también para una lista que ya estaba guardada.
 * - Usuario y contraseña opcionales por lista (foro #290): en un servidor estilo Xtream Codes, get.php con ellos; si no,
 *   HTTP Basic (request). Se guardan cifrados con el Android Keystore (Logins), fuera de las copias de seguridad, y no
 *   salen en el log (Redact → redactUrls) ni en la pantalla (mask); en los favoritos de la TV, cambiados por marcas
 *   (hideLogin).
 */
final class M3uSource {
    /** Listas de iptv-org (TV de todo el mundo, por países) y de iprd (radio, por países); comprobadas el 1-11-2026. */
    static final String IPTV_ORG = "https://iptv-org.github.io/iptv/";
    static final String IPRD = "https://iprd-org.github.io/iprd/site_data/";
    /** Como mucho esto de una lista, ya descomprimida: lo que pase se corta por la última línea (sobra para 20 000 canales). */
    static final int MAX_BYTES = 24 << 20;
    /** Para comprobar en el móvil una lista recién añadida basta su principio. */
    static final int PROBE_BYTES = 256 << 10;
    private static final int MAX_REDIRECTS = 5;

    private M3uSource() {
    }

    // ------------------------------------------------------------------ páginas conocidas

    private static final Pattern GITHUB_FILE = Pattern.compile(
            "(?i)https?://(?:www\\.)?github\\.com/([^/?#]+)/([^/?#]+)/(?:blob|raw)/([^?#]+\\.(?:m3u8?|json))(?:[?#].*)?");
    /** Un archivo de lista (por la extensión): no se toca. */
    private static final Pattern LIST_FILE = Pattern.compile("(?i)[^?#]*\\.(?:m3u8?|json)(?:[?#].*)?");
    private static final Pattern IPTV_ORG_PAGE = Pattern.compile(
            "(?i)https?://(?:(?:www\\.)?github\\.com/iptv-org|iptv-org\\.github\\.io)(?:[/?#].*)?");
    private static final Pattern IPRD_PAGE = Pattern.compile(
            "(?i)https?://(?:(?:www\\.)?github\\.com/iprd-org|iprd-org\\.github\\.io)(?:[/?#].*)?");
    private static final Pattern IPTV_ORG_COUNTRY = Pattern.compile("https://iptv-org\\.github\\.io/iptv/countries/[a-z]{2}\\.m3u");
    private static final Pattern IPRD_COUNTRY = Pattern.compile("https://iprd-org\\.github\\.io/iprd/site_data/by_country/[a-z]{2}\\.m3u");

    /**
     * Puro: la URL de la lista de verdad de una página conocida, o url tal cual si no hay nada que cambiar. cc: el país
     * del móvil (ISO de dos letras) o "" (entonces, la lista de todos los países).
     * - github.com/…/blob/…/x.m3u (o /raw/): el archivo en crudo, raw.githubusercontent.com/….
     * - El GitHub o la web de iptv-org (TV): iptv-org.github.io/iptv/countries/«cc».m3u, o index.m3u (todos).
     * - El GitHub o la web de iprd (radio): iprd-org.github.io/iprd/site_data/by_country/«cc».m3u, o all_stations.m3u.
     */
    static String convert(String url, String cc) {
        if (url == null) return null;
        String u = url.trim();
        Matcher m = GITHUB_FILE.matcher(u);
        if (m.matches()) return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
        if (LIST_FILE.matcher(u).matches()) return url;
        String c = isoCountry(cc);
        if (IPTV_ORG_PAGE.matcher(u).matches()) {
            // iptv-org llama «uk» al Reino Unido.
            return IPTV_ORG + (c.isEmpty() ? "index.m3u" : "countries/" + ("gb".equals(c) ? "uk" : c) + ".m3u");
        }
        if (IPRD_PAGE.matcher(u).matches()) return IPRD + (c.isEmpty() ? "all_stations.m3u" : "by_country/" + c + ".m3u");
        return url;
    }

    /** Puro: si la lista de un país de iptv-org o de iprd no existe (404), la de todos; null para las demás. */
    static String fallback(String url) {
        if (url == null) return null;
        if (IPTV_ORG_COUNTRY.matcher(url).matches()) return IPTV_ORG + "index.m3u";
        if (IPRD_COUNTRY.matcher(url).matches()) return IPRD + "all_stations.m3u";
        return null;
    }

    /** Puro: un código de país de dos letras, en minúsculas; si no lo es, "". */
    static String isoCountry(String cc) {
        if (cc == null) return "";
        String s = cc.trim().toLowerCase(Locale.ROOT);
        return s.matches("[a-z]{2}") ? s : "";
    }

    /** Puro: el país de la SIM y, si no hay, el de la región del sistema (como la moneda, Money.autoCode); "" si ninguno. */
    static String pickCountry(String sim, String region) {
        String s = isoCountry(sim);
        return s.isEmpty() ? isoCountry(region) : s;
    }

    /** El país del móvil para las listas por país (pickCountry). */
    static String phoneCountry(Context c) {
        String sim = "";
        try {
            android.telephony.TelephonyManager tm = c.getSystemService(android.telephony.TelephonyManager.class);
            if (tm != null && tm.getSimCountryIso() != null) sim = tm.getSimCountryIso();
        } catch (RuntimeException ignored) {
            // Sin telefonía (tableta): la región.
        }
        android.content.res.Configuration sys = android.content.res.Resources.getSystem().getConfiguration();
        @SuppressWarnings("deprecation")
        Locale region = android.os.Build.VERSION.SDK_INT >= 24 ? sys.getLocales().get(0) : sys.locale;
        return pickCountry(sim, region == null ? "" : region.getCountry());
    }

    // ------------------------------------------------------------------ ¿es una lista?

    /** Lo bajado es una lista (o está vacío: sin canales). */
    static final int OK = 0;
    /** Una página web. */
    static final int HTML = 1;
    /** Otra cosa: ni M3U ni la JSON de TDTChannels. */
    static final int NOT_LIST = 2;

    private static final Pattern M3U_TAG = Pattern.compile("^[ \\t]*#EXT(?:M3U|INF)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern URL_LINE = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");

    /**
     * Puro: qué es lo bajado. OK: una M3U (con #EXTM3U o #EXTINF a principio de línea), la JSON de TDTChannels o solo
     * direcciones, una por línea; HTML: una página web (por cómo empieza, o servida como text/html sin ser una lista);
     * NOT_LIST: otra cosa. Una M3U servida como text/html vale (hay servidores mal configurados).
     */
    static int check(String text, String contentType) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("\uFEFF")) t = t.substring(1).trim();
        if (t.isEmpty()) return OK;
        if (looksLikeHtml(t)) return HTML;
        if (t.startsWith("{") || M3U_TAG.matcher(t).find() || onlyUrls(t)) return OK;
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html") ? HTML : NOT_LIST;
    }

    /** Puro: empieza como una página web: <!doctype html>, o una etiqueta y enseguida <html>, <head> o <body>. */
    static boolean looksLikeHtml(String text) {
        String head = text.substring(0, Math.min(text.length(), 4096)).trim();
        if (head.startsWith("\uFEFF")) head = head.substring(1).trim();
        if (!head.startsWith("<")) return false;
        head = head.toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.contains("<html") || head.contains("<head") || head.contains("<body");
    }

    /** Puro: una lista sin #EXTINF, solo direcciones (una por línea; las # se saltan): al menos una, y todas URLs. */
    static boolean onlyUrls(String text) {
        int n = 0;
        int start = 0;
        int len = text.length();
        while (start < len && n < 500) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = len;
            String line = text.substring(start, end).trim();
            start = end + 1;
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!URL_LINE.matcher(line).matches()) return false;
            n++;
        }
        return n > 0;
    }

    /**
     * Puro: el texto de lo bajado: descomprimido si viene en gzip (por sus primeros bytes: hay servidores que lo mandan sin
     * avisar, o es un .gz), sin BOM (UTF-8 o UTF-16) y en UTF-8; si no es UTF-8 válido, en Windows-1252 (listas antiguas
     * con acentos en Latin-1, que leídas como UTF-8 salían con signos raros).
     */
    static String decode(byte[] b) throws IOException {
        if (b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) b = gunzip(b, MAX_BYTES);
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, latin());
        }
    }

    private static Charset latin() {
        try {
            return Charset.forName("windows-1252");
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Puro: descomprime gzip, como mucho max bytes (cortado por la última línea); si viene cortado, lo que haya. */
    static byte[] gunzip(byte[] b, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                int room = max - out.size();
                if (n > room) {
                    out.write(buf, 0, room);
                    return toLastLine(out.toByteArray());
                }
                out.write(buf, 0, n);
            }
        } catch (EOFException cut) {
            // Cortado (el principio de una lista, al comprobarla): lo que se haya podido leer.
        }
        return out.toByteArray();
    }

    /**
     * Puro: el texto de una lista bajada, comprobado (decode y check): una página web o algo que no es una lista lanza
     * ListError en vez de dar canales basura.
     */
    static String text(byte[] body, String contentType, Login login) throws ListError {
        String t;
        try {
            t = decode(body);
        } catch (IOException e) {
            throw ListError.of(e, login);
        }
        requireList(t, contentType, login);
        return t;
    }

    /**
     * Puro: lanza ListError si el texto no es una lista. Con usuario, que el servidor no lo ha aceptado: los Xtream
     * contestan a un usuario malo con una página o con algo que no es una lista.
     */
    static void requireList(String text, String contentType, Login login) throws ListError {
        int k = check(text, contentType);
        if (k == OK) return;
        if (login != null && !login.isEmpty()) throw ListError.of(ListError.LOGIN_REJECTED);
        throw ListError.of(k == HTML ? ListError.WEB_PAGE : ListError.NOT_LIST);
    }

    /** Por qué no se pudo leer una lista; el mensaje, ya sin usuario ni contraseña. */
    static final class ListError extends IOException {
        static final int FAILED = 0;
        /** Una página web, no una lista. */
        static final int WEB_PAGE = 1;
        /** Ni M3U ni la JSON de TDTChannels. */
        static final int NOT_LIST = 2;
        /** El servidor pide usuario (401) y la lista no lo tiene. */
        static final int NEEDS_LOGIN = 3;
        /** Con usuario, el servidor no da la lista (401, 403, una página o nada útil). */
        static final int LOGIN_REJECTED = 4;
        /** Otro error del servidor (404, 500…). */
        static final int HTTP = 5;
        private static final String[] LOG = {"error", "página web, no una lista", "no es una lista M3U",
                "el servidor pide usuario", "el servidor no acepta el usuario", "HTTP"};

        final int kind;
        final int http;
        private final String log;

        private ListError(int kind, int http, String message, String log) {
            super(message);
            this.kind = kind;
            this.http = http;
            this.log = log;
        }

        static ListError of(int kind) {
            return new ListError(kind, 0, LOG[kind], LOG[kind]);
        }

        static ListError http(int code) {
            return new ListError(HTTP, code, "HTTP " + code, "HTTP " + code);
        }

        /** Cualquier error como ListError, sin credenciales: el mensaje puede llevar la URL, y en Xtream la URL lleva el usuario. */
        static ListError of(Throwable e, Login login) {
            if (e instanceof ListError) return (ListError) e;
            String m = mask(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), login);
            return new ListError(FAILED, 0, m, e.getClass().getSimpleName() + ": " + m);
        }

        /** El texto para el usuario: los que se explican solos, tal cual; los demás, dentro de wrap («No se pudo leer…: %1$s»). */
        String text(int wrap) {
            switch (kind) {
                case WEB_PAGE:
                    return Str.get(R.string.hql_list_web_page);
                case NOT_LIST:
                    return Str.get(R.string.hql_list_not_m3u);
                case NEEDS_LOGIN:
                    return Str.get(R.string.hql_list_needs_login);
                case LOGIN_REJECTED:
                    return Str.get(R.string.hql_list_login_rejected);
                default:
                    return Str.get(wrap, getMessage());
            }
        }

        /** Para el log (L): qué ha pasado, sin credenciales. */
        @Override
        public String toString() {
            return log;
        }
    }

    // ------------------------------------------------------------------ bajarla

    /** Lo bajado: el contenido, su Content-Type (null en un archivo del móvil) y qué URL se pidió (sin redirecciones). */
    static final class Fetched {
        final byte[] body;
        final String contentType;
        final String url;

        Fetched(byte[] body, String contentType, String url) {
            this.body = body;
            this.contentType = contentType;
            this.url = url;
        }
    }

    /** Una lista bajada y comprobada. */
    static final class Loaded {
        final String text;
        /** La URL nueva si se cambió (página conocida, o un país sin lista); null si no. */
        final String converted;
        final Login login;
        /** Con HTTP Basic: su servidor y la cabecera, para sus vídeos (VideoPlayer.newPlayer); null si no. */
        final String authHost;
        final String authHeader;

        Loaded(String text, String converted, Login login, String authHost, String authHeader) {
            this.text = text;
            this.converted = converted;
            this.login = login;
            this.authHost = authHost;
            this.authHeader = authHeader;
        }
    }

    /**
     * Baja y comprueba la lista src (en segundo plano), con el usuario guardado con loginId (Logins; "" si no tiene): una
     * página conocida, antes convertida en su lista (convert, con el país del móvil). Los errores, como ListError.
     */
    static Loaded load(Context c, String src, String loginId) throws ListError {
        Login login = new Logins(c).get(loginId);
        String url = isHttp(src) ? convert(src, phoneCountry(c)) : src;
        Fetched f = download(c, url, login, MAX_BYTES);
        String text = text(f.body, f.contentType, login);
        requireNotEmpty(text, login, f.url);
        String[] req = request(f.url, login);
        return new Loaded(text, f.url.equals(src) ? null : f.url, login, req[1] != null ? host(f.url) : null, req[1]);
    }

    /**
     * Comprueba (en segundo plano) que una lista recién añadida en el móvil lo es, con solo su principio: ListError si es
     * una página web, no es una lista o el servidor pide (o no acepta) el usuario; cualquier otro fallo (sin red), FAILED.
     */
    static void probe(Context c, String src, String loginId) throws ListError {
        Login login = new Logins(c).get(loginId);
        try {
            Fetched f = download(c, src, login, PROBE_BYTES);
            String t = decode(f.body);
            // Solo el principio: sin la última línea, que puede ir a medias.
            int nl = t.lastIndexOf('\n');
            requireList(nl > 0 ? t.substring(0, nl) : t, f.contentType, login);
            requireNotEmpty(t, login, f.url);
        } catch (IOException | RuntimeException e) {
            throw ListError.of(e, login);
        }
    }

    /**
     * Puro: con usuario, un servidor Xtream que contesta con nada es que no lo ha aceptado (o la cuenta ha caducado):
     * mejor decirlo que «la lista no tiene canales».
     */
    static void requireNotEmpty(String text, Login login, String url) throws ListError {
        if (login == null || login.isEmpty() || xtreamUrl(url, login) == null) return;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch) && ch != 0xFEFF) return;
        }
        throw ListError.of(ListError.LOGIN_REJECTED);
    }

    /**
     * Baja la lista src con su usuario (en segundo plano), como mucho max bytes; si es la de un país de iptv-org o de
     * iprd que no existe (404), la de todos (fallback). Los errores, como ListError y sin credenciales.
     */
    static Fetched download(Context c, String src, Login login, int max) throws ListError {
        try {
            try {
                return fetch(c, src, login, max);
            } catch (ListError e) {
                String all = fallback(src);
                if (e.kind != ListError.HTTP || e.http != 404 || all == null) throw e;
                L.i("listas: la del país no existe (404); la de todos");
                return fetch(c, all, login, max);
            }
        } catch (IOException | RuntimeException e) {
            throw ListError.of(e, login);
        }
    }

    private static Fetched fetch(Context c, String src, Login login, int max) throws IOException {
        if (src.startsWith("content://") || src.startsWith("file://")) {
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(src));
            if (in == null) throw new IOException("archivo no disponible");
            try (InputStream i = in) {
                return new Fetched(readUpTo(i, max), null, src);
            }
        }
        String[] req = request(src, login);
        String url = req[0];
        String auth = req[1];
        String authHost = host(url);
        boolean withLogin = login != null && !login.isEmpty();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isHttp(url)) throw new IOException("dirección no válida");
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            try {
                con.setConnectTimeout(15_000);
                con.setReadTimeout(30_000);
                // Las redirecciones, a mano: HttpURLConnection no pasa sola de http a https, y el usuario (HTTP Basic) solo
                // va a su servidor.
                con.setInstanceFollowRedirects(false);
                if (auth != null && host(url).equalsIgnoreCase(authHost)) con.setRequestProperty("Authorization", auth);
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    String to = con.getHeaderField("Location");
                    if (to == null || to.isEmpty()) throw ListError.http(code);
                    url = new URL(new URL(url), to).toString();
                    continue;
                }
                if (code == 401) throw ListError.of(withLogin ? ListError.LOGIN_REJECTED : ListError.NEEDS_LOGIN);
                if (code == 403 && withLogin) throw ListError.of(ListError.LOGIN_REJECTED);
                if (code >= 400) throw ListError.http(code);
                try (InputStream in = con.getInputStream()) {
                    return new Fetched(readUpTo(in, max), con.getContentType(), src);
                }
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("demasiadas redirecciones");
    }

    /** Todo el flujo, como mucho max bytes: lo que pase se corta por la última línea entera. */
    static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            int room = max - out.size();
            if (n > room) {
                out.write(buf, 0, room);
                return toLastLine(out.toByteArray());
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Puro: hasta el último salto de línea (sin la línea a medias del final); sin ninguno, tal cual. */
    static byte[] toLastLine(byte[] b) {
        for (int i = b.length - 1; i >= 0; i--) {
            if (b[i] == '\n') return Arrays.copyOf(b, i + 1);
        }
        return b;
    }

    private static String host(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (MalformedURLException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ usuario y contraseña

    /** Usuario y contraseña de una lista; los dos pueden ir vacíos (la mayoría de listas no piden nada). */
    static final class Login {
        static final Login NONE = new Login("", "");
        final String user;
        final String pass;

        Login(String user, String pass) {
            this.user = user == null ? "" : user.trim();
            // La contraseña, tal cual: un espacio puede ser parte de ella.
            this.pass = pass == null ? "" : pass;
        }

        boolean isEmpty() {
            return user.isEmpty() && pass.isEmpty();
        }
    }

    private static final Pattern URL_PARTS = Pattern.compile(
            "(?i)(https?://)(?:([^/?#@]*)@)?([^/?#]*)(/[^?#]*)?(?:\\?([^#]*))?(#.*)?");

    static boolean isHttp(String url) {
        return url != null && (url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8));
    }

    /** Puro: cómo se pide la lista: {URL, cabecera Authorization o null}. Con usuario: Xtream (get.php) o HTTP Basic. */
    static String[] request(String url, Login login) {
        if (login == null || login.isEmpty() || !isHttp(url)) return new String[]{url, null};
        String x = xtreamUrl(url, login);
        return x != null ? new String[]{x, null} : new String[]{url, basicAuth(login)};
    }

    /** Puro: la ruta de un servidor estilo Xtream Codes: ninguna (solo el servidor), …/get.php o …/player_api.php. */
    static boolean isXtreamPath(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return p.isEmpty() || p.equals("/") || p.endsWith("/get.php") || p.endsWith("/player_api.php");
    }

    /**
     * Puro: la lista M3U de un servidor estilo Xtream Codes (http(s)://servidor[:puerto] sin ruta, …/get.php o
     * …/player_api.php) con el usuario: get.php?username=…&password=…&type=m3u_plus&output=ts (el type y el output que ya
     * trajera, se respetan); null si la URL no es de ese estilo (entonces va con HTTP Basic).
     */
    static String xtreamUrl(String url, Login login) {
        Matcher m = URL_PARTS.matcher(url.trim());
        if (!m.matches()) return null;
        String path = m.group(4) == null ? "" : m.group(4);
        if (!isXtreamPath(path)) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        String query = m.group(5);
        if (!lower.endsWith("/get.php")) {
            // Solo el servidor, o su API (player_api.php): la lista es get.php, en la misma carpeta.
            path = lower.endsWith("/player_api.php")
                    ? path.substring(0, path.length() - "player_api.php".length()) + "get.php" : "/get.php";
            query = null;
        }
        StringBuilder q = new StringBuilder("username=").append(enc(login.user)).append("&password=").append(enc(login.pass));
        boolean type = false;
        boolean output = false;
        if (query != null) {
            for (String p : query.split("&")) {
                String k = key(p);
                if (p.isEmpty() || k.equals("username") || k.equals("password")) continue;
                type |= k.equals("type");
                output |= k.equals("output");
                q.append('&').append(p);
            }
        }
        if (!type) q.append("&type=m3u_plus");
        if (!output) q.append("&output=ts");
        return m.group(1) + m.group(3) + path + "?" + q;
    }

    /** Puro: la cabecera HTTP Basic (RFC 7617, en UTF-8). */
    static String basicAuth(Login login) {
        byte[] b = (login.user + ":" + login.pass).getBytes(StandardCharsets.UTF_8);
        return "Basic " + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    /** Una URL pegada, partida: sin el usuario y la contraseña, y ellos aparte (vacíos si no los traía). */
    static final class Split {
        final String url;
        final Login login;

        Split(String url, Login login) {
            this.url = url;
            this.login = login;
        }
    }

    /**
     * Puro: si la URL pegada trae el usuario (http://usuario:clave@servidor/… o, en un servidor Xtream,
     * get.php?username=…&password=…), lo saca para guardarlo cifrado y deja la URL sin él; si no, la misma y sin usuario.
     * Otras URLs con usuario en la consulta se quedan como están (el servidor lo espera ahí); en pantalla y en el log,
     * tapado (mask).
     */
    static Split splitLogin(String url) {
        String u = url == null ? "" : url.trim();
        Matcher m = URL_PARTS.matcher(u);
        if (!m.matches()) return new Split(u, Login.NONE);
        String info = m.group(2);
        String path = m.group(4) == null ? "" : m.group(4);
        String query = m.group(5);
        String user = "";
        String pass = "";
        boolean found = info != null;
        if (info != null) {
            int colon = info.indexOf(':');
            user = unescape(colon >= 0 ? info.substring(0, colon) : info, false);
            pass = colon >= 0 ? unescape(info.substring(colon + 1), false) : "";
        }
        if (query != null && isXtreamPath(path)) {
            StringBuilder keep = new StringBuilder();
            for (String p : query.split("&")) {
                String k = key(p);
                int eq = p.indexOf('=');
                String v = eq >= 0 ? p.substring(eq + 1) : "";
                if (k.equals("username")) {
                    user = unescape(v, true);
                    found = true;
                } else if (k.equals("password")) {
                    pass = unescape(v, true);
                    found = true;
                } else if (!p.isEmpty()) {
                    if (keep.length() > 0) keep.append('&');
                    keep.append(p);
                }
            }
            query = keep.length() > 0 ? keep.toString() : null;
        }
        if (!found) return new Split(u, Login.NONE);
        String clean = m.group(1) + m.group(3) + path + (query != null ? "?" + query : "") + (m.group(6) != null ? m.group(6) : "");
        return new Split(clean, new Login(user, pass));
    }

    private static String key(String param) {
        int eq = param.indexOf('=');
        return (eq >= 0 ? param.substring(0, eq) : param).toLowerCase(Locale.ROOT);
    }

    private static String unescape(String s, boolean plusIsSpace) {
        try {
            return URLDecoder.decode(plusIsSpace ? s : s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /**
     * Los usuarios de las listas, cifrados con una clave AES-GCM del Android Keystore (la suya, como Home Assistant), en un
     * archivo fuera de las copias de seguridad: {id: [usuario, contraseña]}. La lista guarda solo el id
     * (Config.m3uLoginId), así que la copia de los ajustes (SettingsBackup) no lleva ni el usuario ni la contraseña.
     */
    static final class Logins {
        private static final String FILE = "m3u_logins.bin";
        private static final String ALIAS = "hql_m3u_v1";
        private final File file;
        private final CarCloudStore.Box box;

        Logins(Context c) {
            this(new File(c.getApplicationContext().getNoBackupFilesDir(), FILE), new CarCloudStore.KeystoreBox(ALIAS));
        }

        Logins(File file, CarCloudStore.Box box) {
            this.file = file;
            this.box = box;
        }

        /** Un id nuevo para el usuario de una lista. */
        static String newId() {
            return UUID.randomUUID().toString();
        }

        /** El usuario de id (NONE si no hay); queda recordado para taparlo en el log (remember). */
        Login get(String id) {
            if (id == null || id.isEmpty()) return Login.NONE;
            synchronized (Logins.class) {
                JSONArray a = read().optJSONArray(id);
                Login l = a == null ? Login.NONE : new Login(a.optString(0), a.optString(1));
                remember(l);
                return l;
            }
        }

        /** Los ids con usuario guardado. */
        Set<String> ids() {
            synchronized (Logins.class) {
                Set<String> out = new HashSet<>();
                for (Iterator<String> it = read().keys(); it.hasNext(); ) out.add(it.next());
                return out;
            }
        }

        /** Guarda el usuario de id; vacío, lo borra. */
        void put(String id, Login l) throws IOException, GeneralSecurityException {
            synchronized (Logins.class) {
                JSONObject o = read();
                try {
                    if (l == null || l.isEmpty()) o.remove(id);
                    else o.put(id, new JSONArray().put(l.user).put(l.pass));
                } catch (JSONException e) {
                    throw new IOException("usuarios de las listas: " + e.getClass().getSimpleName());
                }
                write(o);
                remember(l);
            }
        }

        /** Borra el de id (al quitar su lista), sin error si no se puede. */
        void remove(String id) {
            if (id == null || id.isEmpty()) return;
            try {
                put(id, null);
            } catch (Exception e) {
                L.w("listas: no se pudo borrar un usuario (" + e.getClass().getSimpleName() + ")");
            }
        }

        private JSONObject read() {
            if (!file.isFile()) return new JSONObject();
            try (InputStream in = new FileInputStream(file)) {
                return new JSONObject(new String(box.open(Http.readAll(in)), StandardCharsets.UTF_8));
            } catch (Exception e) {
                // Clave perdida (los ajustes de otro móvil) o archivo roto: sin usuarios (se vuelven a poner).
                L.w("listas: usuarios ilegibles (" + e.getClass().getSimpleName() + ")");
                return new JSONObject();
            }
        }

        private void write(JSONObject o) throws IOException, GeneralSecurityException {
            if (o.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("sin carpeta");
            File tmp = new File(file.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(box.seal(o.toString().getBytes(StandardCharsets.UTF_8)));
            }
            // En Android, renameTo sustituye al anterior; en el PC (pruebas) hay que borrarlo antes.
            if (!tmp.renameTo(file) && !(file.delete() && tmp.renameTo(file))) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("no se pudo guardar");
            }
        }
    }

    // ------------------------------------------------------------------ que no se vean

    private static final Pattern USERINFO = Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@\\s]+@");
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)([?&](?:username|user|password|passwd|pass|pwd)=)[^&#\\s]*");
    private static final Pattern XTREAM_STREAM = Pattern.compile(
            "(?i)(/(?:live|movie|series|timeshift)/)[^/?#\\s]+/[^/?#\\s]+/");
    private static final Pattern URL_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");

    /** Usuarios y contraseñas de las listas usadas desde que se abrió la app (solo en memoria): mask y Redact los tapan. */
    private static final Set<String> SECRETS = Collections.synchronizedSet(new HashSet<>());

    static void remember(Login l) {
        if (l == null) return;
        if (!l.user.isEmpty()) SECRETS.add(l.user);
        if (!l.pass.isEmpty()) SECRETS.add(l.pass);
    }

    /**
     * Un texto (URL, error) sin credenciales, para el log y la pantalla: «usuario:clave@», los valores de username,
     * password… en la consulta, la ruta /live/usuario/clave/ de Xtream y, donde salgan, el usuario y la contraseña de la
     * lista (login) y los de las listas usadas (remember), cambiados por ***.
     */
    static String mask(String s, Login login) {
        if (s == null || s.isEmpty()) return s;
        String out = USERINFO.matcher(s).replaceAll("$1***@");
        out = SECRET_PARAM.matcher(out).replaceAll("$1***");
        out = XTREAM_STREAM.matcher(out).replaceAll("$1***/***/");
        for (String secret : secrets(login)) out = hide(out, secret);
        return out;
    }

    static String mask(String s) {
        return mask(s, null);
    }

    /**
     * Para el log (Redact, en cada línea): dentro de cada URL, lo mismo que mask. Fuera de las URLs no se toca (un usuario
     * «test» no debe tapar cada «test» del log).
     */
    static String redactUrls(String line) {
        if (line == null || line.indexOf("://") < 0) return line;
        Matcher m = URL_TOKEN.matcher(line);
        StringBuffer sb = null;
        while (m.find()) {
            String masked = mask(m.group(), null);
            if (masked.equals(m.group())) continue;
            if (sb == null) sb = new StringBuffer(line.length());
            m.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        if (sb == null) return line;
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> secrets(Login login) {
        Set<String> all = new HashSet<>();
        synchronized (SECRETS) {
            all.addAll(SECRETS);
        }
        if (login != null) {
            if (!login.user.isEmpty()) all.add(login.user);
            if (!login.pass.isEmpty()) all.add(login.pass);
        }
        List<String> out = new ArrayList<>(all);
        // Los largos primero: si uno va dentro de otro, que no quede a medias.
        Collections.sort(out, (a, b) -> b.length() - a.length());
        return out;
    }

    /** El secreto (tal cual y codificado para URL) por ***; los de menos de 3 letras, solo como trozo de ruta (/x/). */
    private static String hide(String s, String secret) {
        String out = s;
        for (String v : new String[]{secret, enc(secret)}) {
            if (v.length() >= 3) out = out.replace(v, "***");
            else out = out.replace("/" + v + "/", "/***/");
        }
        return out;
    }

    /** Marcas del usuario y la contraseña en las URLs guardadas de los favoritos. */
    static final String USER_MARK = "{u}";
    static final String PASS_MARK = "{p}";

    /**
     * Puro: la URL de un canal con el usuario y la contraseña de su lista cambiados por marcas, para guardarla sin ellos
     * (favoritos de una lista Xtream: /usuario/clave/ va en la ruta de cada canal). showLogin la deja como estaba.
     */
    static String hideLogin(String url, Login login) {
        if (url == null || login == null || login.isEmpty()) return url;
        String out = url;
        if (!login.pass.isEmpty()) out = out.replace(login.pass, PASS_MARK);
        if (!login.user.isEmpty()) out = out.replace(login.user, USER_MARK);
        return out;
    }

    /** Puro: al revés que hideLogin (las marcas, con el usuario y la contraseña de la lista). */
    static String showLogin(String url, Login login) {
        if (url == null || login == null) return url;
        return url.replace(USER_MARK, login.user).replace(PASS_MARK, login.pass);
    }

    /**
     * Puro: los favoritos guardados antes con el usuario de esta lista dentro de la URL (los dos, usuario y contraseña),
     * con marcas y el id de su usuario (como los nuevos). Devuelve cuántos ha cambiado.
     */
    static int hideInFavorites(JSONArray favs, Login login, String loginId) {
        if (login == null || login.user.isEmpty() || login.pass.isEmpty() || loginId == null || loginId.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < favs.length(); i++) {
            JSONObject o = favs.optJSONObject(i);
            if (o == null || o.has("login")) continue;
            String url = o.optString("url");
            if (!url.contains(login.user) || !url.contains(login.pass)) continue;
            try {
                o.put("url", hideLogin(url, login));
                JSONArray urls = o.optJSONArray("urls");
                if (urls != null) {
                    for (int k = 0; k < urls.length(); k++) urls.put(k, hideLogin(urls.optString(k), login));
                }
                o.put("login", loginId);
                n++;
            } catch (JSONException ignored) {
                // No pasa con cadenas.
            }
        }
        return n;
    }
}
