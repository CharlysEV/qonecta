package com.headqlink.link;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.aap.AapAudio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Guía del camino: lo que merece la pena cerca de la carretera (castillos, pueblos con historia, embalses, sierras…),
 * sacado de Wikipedia en el idioma de la app, con su foto y un resumen, y si se quiere, contado en voz alta.
 * - En marcha: los artículos con coordenadas a menos de 10 km de un punto unos km por delante (geosearch), con su tamaño,
 *   tipo y foto; de los 20 que más prometen, el resumen y las visitas del último mes (Wikipedia da las visitas en tandas
 *   de unas 10: se siguen). Se enseña lo que queda por delante (±75° del rumbo) y se cuenta lo más visitado a menos de
 *   5 km: cada sitio una vez y como mucho uno cada 4 minutos, nunca encima de una llamada, de las indicaciones del
 *   navegador ni de otro aviso.
 * - Parado (más de un minuto): lo que hay cerca a pie, para estirar las piernas en una parada de carga.
 * Funciona mientras su pantalla (GuideScreen) está abierta, sola o junto a AA con la pantalla partida. El botón del panel
 * no sale hasta que el usuario lo enciende en Ajustes › Botones del panel. El registro no lleva sitios ni posiciones.
 */
final class RoadGuide {
    /** Radio de cada búsqueda (el máximo de Wikipedia) y cuánto por delante va su centro. */
    static final int SEARCH_M = 10_000;
    static final double LOOK_AHEAD_KM = 6;
    /** Se vuelve a buscar cuando el centro nuevo se aleja tanto del anterior, o pasado REFRESH_MS. */
    static final double REQUERY_KM = 4;
    static final long REFRESH_MS = 15 * 60_000L;
    /** Parado: lo que hay a pie, a cuánto se busca y desde cuándo se está parado. */
    static final double WALK_KM = 2;
    static final long STOP_MS = 60_000;
    /** En marcha por encima de MOVE_KMH; parado por debajo de STOP_KMH (entre medias, como estaba: semáforos, atascos). */
    static final double MOVE_KMH = 15;
    static final double STOP_KMH = 3;
    /** Delante: hasta 75° a cada lado del rumbo; muy cerca, hasta 100° (lo que queda al lado de la carretera). */
    static final double AHEAD_DEG = 75;
    static final double BESIDE_DEG = 100;
    static final double BESIDE_KM = 2;
    /** El trivial pregunta por lo que hay a esta distancia o menos. */
    static final double TRIVIA_KM = 25;
    /** Lo que se enseña, como mucho. */
    static final double LIST_KM = 15;
    static final int LIST_MAX = 8;
    /** Se cuenta en voz alta a esta distancia o menos, y como mucho uno cada VOICE_GAP_MS. */
    static final double SAY_KM = 5;
    static final long VOICE_GAP_MS = 4 * 60_000L;
    /** Lo tocado en la lista se queda en la tarjeta grande este tiempo. */
    static final long PICK_MS = 3 * 60_000L;
    /** Visitas del último mes para enseñarlo, y para contarlo en voz alta. */
    static final int MIN_VIEWS = 60;
    static final int SAY_MIN_VIEWS = 300;
    /** Resumen hablado: como mucho tantos caracteres (unos 20-25 s de voz); en la tarjeta, más. */
    static final int SAY_CHARS = 320;
    static final int CARD_CHARS = 700;
    /** Sin red: se reintenta pasado esto. */
    static final long RETRY_MS = 60_000;
    static final long TICK_MS = 4_000;
    /** Silencio del navegador de AA antes de hablar. */
    static final long GUIDANCE_QUIET_MS = 5_000;
    /** Al cerrar la pantalla, la guía espera esto antes de pararse (el cambio de día a noche la rehace al momento). */
    static final long END_GRACE_MS = 10_000;
    /** Lo guardado, solo hasta esta distancia (lo que queda atrás se olvida). */
    static final double KEEP_KM = 40;

    /** Quién habla en VoiceAlert cuando es la guía (para «Parar»). */
    static final String VOICE = "guide";

    /** Lado del sitio respecto al rumbo. */
    static final int AHEAD = 0;
    static final int RIGHT = 1;
    static final int LEFT = 2;
    static final int BEHIND = 3;

    /** Un artículo de Wikipedia con coordenadas. */
    static final class Place {
        final long id;
        final String title;
        final double lat;
        final double lon;
        /** Tipo de las coordenadas en Wikipedia (city, landmark, mountain…), o vacío. */
        String type = "";
        /** Visitas de los últimos 30 días, o -1 si Wikipedia no las dio. */
        int views = -1;
        /** Tamaño del artículo (bytes). */
        int bytes;
        String thumb = "";
        /** La introducción del artículo en texto llano (vacía hasta pedirla). */
        String extract = "";
        /** Ya se pidió su resumen (aunque viniera vacío). */
        boolean detailed;

        Place(long id, String title, double lat, double lon) {
            this.id = id;
            this.title = title;
            this.lat = lat;
            this.lon = lon;
        }
    }

    enum Status { WAITING_GPS, LOADING, OFFLINE, READY }

    /** Lo que enseña la pantalla. */
    static final class State {
        Status status = Status.WAITING_GPS;
        boolean moving = true;
        boolean voice;
        /** La guía está hablando ahora: la pantalla enseña «Parar». */
        boolean talking;
        double lat = Double.NaN;
        double lon = Double.NaN;
        double heading;
        /** La tarjeta grande: lo elegido, lo último contado o lo mejor de la lista (null si no hay nada). */
        Place current;
        /** Más adelante (en marcha) o cerca a pie (parado), por distancia y sin la de la tarjeta. */
        final List<Place> list = new ArrayList<>();
        /** Todo lo que merece la pena alrededor (cargado), para el trivial del camino (RoadTrivia). */
        final List<Place> pool = new ArrayList<>();
    }

    interface Listener {
        /** En el hilo principal. */
        void onGuide(State s);
    }

    // ------------------------------------------------------------------ puro

    /** Idiomas de la app con su Wikipedia; el resto, la inglesa. */
    private static final Set<String> LANGS = new HashSet<>(Arrays.asList("es", "en", "pt", "it", "fr", "de", "nl", "zh"));

    static String wikiLang(Locale l) {
        String lang = l == null ? "" : l.getLanguage();
        return LANGS.contains(lang) ? lang : "en";
    }

    /** Puro: los artículos con coordenadas a menos de radiusM de un punto, con tipo, foto y tamaño. */
    static String searchUrl(String lang, double lat, double lon, int radiusM) {
        return String.format(Locale.US, "https://%s.wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=geosearch&ggscoord=%.5f%%7C%.5f&ggsradius=%d&ggslimit=50"
                + "&prop=coordinates%%7Cpageimages%%7Cinfo&coprop=type&colimit=50"
                + "&piprop=thumbnail&pithumbsize=500&pilimit=50", lang, lat, lon, Math.min(SEARCH_M, radiusM));
    }

    /** Puro: la introducción en texto llano (unas frases) y las visitas de los últimos 30 días de hasta 20 artículos. */
    static String detailsUrl(String lang, Collection<Long> ids) {
        StringBuilder b = new StringBuilder();
        for (long id : ids) {
            if (b.length() > 0) b.append("%7C");
            b.append(id);
        }
        return "https://" + lang + ".wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&prop=extracts%7Cpageviews&exintro=1&explaintext=1&exsentences=4&exlimit=20&pvipdays=30&pageids=" + b;
    }

    /** Puro: lo que hay que añadir a la consulta para la tanda siguiente («continue» de la respuesta), o null si no hay. */
    static String continueParams(String json) throws JSONException {
        JSONObject c = new JSONObject(json).optJSONObject("continue");
        if (c == null) return null;
        StringBuilder b = new StringBuilder();
        for (Iterator<String> k = c.keys(); k.hasNext(); ) {
            String key = k.next();
            try {
                b.append('&').append(java.net.URLEncoder.encode(key, "UTF-8")).append('=')
                        .append(java.net.URLEncoder.encode(c.optString(key), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                return null;
            }
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** Puro: los artículos de una búsqueda (searchUrl); sin coordenadas, fuera. */
    static List<Place> parseSearch(String json) throws JSONException {
        List<Place> out = new ArrayList<>();
        JSONObject q = new JSONObject(json).optJSONObject("query");
        JSONArray pages = q == null ? null : q.optJSONArray("pages");
        if (pages == null) return out;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject o = pages.optJSONObject(i);
            if (o == null || o.optBoolean("missing")) continue;
            JSONArray co = o.optJSONArray("coordinates");
            JSONObject c0 = co == null ? null : co.optJSONObject(0);
            long id = o.optLong("pageid", -1);
            String title = o.optString("title", "").trim();
            if (c0 == null || id < 0 || title.isEmpty()) continue;
            double lat = c0.optDouble("lat", Double.NaN);
            double lon = c0.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            Place p = new Place(id, title, lat, lon);
            p.type = c0.optString("type", "");
            p.bytes = o.optInt("length", 0);
            JSONObject th = o.optJSONObject("thumbnail");
            if (th != null) p.thumb = th.optString("source", "");
            JSONObject pv = o.optJSONObject("pageviews");
            if (pv != null) {
                int sum = 0;
                for (Iterator<String> k = pv.keys(); k.hasNext(); ) sum += pv.optInt(k.next(), 0);
                p.views = sum;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Puro: pone a cada sitio de byId la introducción y las visitas que traiga una respuesta de detailsUrl (en las tandas
     * siguientes solo vienen visitas: lo que no venga no se toca).
     */
    static void applyDetails(String json, Map<Long, Place> byId) throws JSONException {
        JSONObject q = new JSONObject(json).optJSONObject("query");
        JSONArray pages = q == null ? null : q.optJSONArray("pages");
        if (pages == null) return;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject o = pages.optJSONObject(i);
            Place p = o == null ? null : byId.get(o.optLong("pageid", -1));
            if (p == null) continue;
            if (o.has("extract")) p.extract = o.optString("extract", "").trim();
            JSONObject pv = o.optJSONObject("pageviews");
            if (pv != null) {
                int sum = 0;
                for (Iterator<String> k = pv.keys(); k.hasNext(); ) sum += pv.optInt(k.next(), 0);
                p.views = sum;
            }
        }
    }

    /** Tipos de coordenadas que no son un sitio para ver: países, regiones, colegios, estaciones, aeropuertos. */
    private static final Set<String> SKIP_TYPES = new HashSet<>(Arrays.asList("country", "adm1st", "adm2nd", "adm3rd", "state",
            "edu", "railwaystation", "airport"));
    /** Sitios con más que ver (monumentos y naturaleza): un poco por delante de los pueblos. */
    private static final Set<String> SIGHT_TYPES = new HashSet<>(Arrays.asList("landmark", "mountain", "waterbody", "river",
            "isle", "forest", "glacier"));
    /** Títulos que no son un sitio para ver (carreteras, calles, estaciones, colegios…), en los idiomas de la app. */
    private static final Pattern SKIP_TITLE = Pattern.compile("^(anexo:|lista |list of |liste |elenco |lijst |autov[ií]a|autopista"
            + "|carretera|calle |avenida|paseo |plaza |ronda |estaci[oó]n|apeadero|l[ií]nea |aeropuerto|aer[oó]dromo|colegio"
            + "|instituto|universidad|hospital|pol[ií]gono|estadio|club |motorway|highway|street|avenue|square |railway"
            + "|airport|school|university|stadium|autoroute|route |rue |gare |a[ée]roport|lyc[ée]e|[ée]cole|autostrada|strada "
            + "|piazza|via |stazione|aeroporto|scuola|autobahn|bundesstra|bahnhof|flughafen|schule|rodovia|estrada|rua |pra[cç]a"
            + "|esta[cç][aã]o|escola|snelweg|straat|plein |vliegveld)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Títulos que son una carretera por su código (A-2, AP-7, N-II, E-90…). */
    private static final Pattern ROAD_CODE = Pattern.compile("^[A-Z]{1,3}-[0-9IVX]+\\b");

    /** Puro: el tipo sin sus datos entre paréntesis («city(3000)» → «city»), en minúsculas. */
    static String baseType(String type) {
        if (type == null) return "";
        int i = type.indexOf('(');
        return (i >= 0 ? type.substring(0, i) : type).trim().toLowerCase(Locale.ROOT);
    }

    /** Puro: ¿no es un sitio para ver (por su tipo o su título)? */
    static boolean skipped(Place p) {
        return SKIP_TYPES.contains(baseType(p.type)) || SKIP_TITLE.matcher(p.title).find() || ROAD_CODE.matcher(p.title).find();
    }

    /** Puro: ¿se enseña? Un sitio para ver, ya con su resumen pedido y con visitas (o, sin el dato, un artículo largo). */
    static boolean worth(Place p) {
        return p.detailed && !skipped(p) && (p.views >= MIN_VIEWS || (p.views < 0 && p.bytes >= 6000));
    }

    /** Puro: ¿se cuenta en voz alta? De los que se enseñan, los más visitados y con resumen. */
    static boolean sayable(Place p) {
        return worth(p) && p.detailed && !p.extract.isEmpty() && (p.views >= SAY_MIN_VIEWS || (p.views < 0 && p.bytes >= 15000));
    }

    /** Puro: cuánto promete antes de saber sus visitas (más artículo, con foto, monumento o naturaleza). */
    static double prescore(Place p) {
        double s = 0.35 * Math.log1p(p.bytes / 1000.0) + (p.thumb.isEmpty() ? 0 : 0.6);
        if (SIGHT_TYPES.contains(baseType(p.type))) s += 0.8;
        return s;
    }

    /** Puro: cuánto merece la pena (sobre todo, las visitas). */
    static double score(Place p) {
        return Math.log1p(Math.max(0, p.views)) + prescore(p);
    }

    /** Puro: el ángulo del sitio respecto al rumbo, en (-180, 180] (positivo a la derecha). */
    static double rel(double heading, double bearing) {
        double d = (bearing - heading) % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    /** Puro: delante (±25°), a la derecha, a la izquierda o ya detrás (más de 110°). */
    static int side(double rel) {
        double a = Math.abs(rel);
        if (a < 25) return AHEAD;
        if (a > 110) return BEHIND;
        return rel > 0 ? RIGHT : LEFT;
    }

    /** Puro: ¿a la vista por delante? Hasta AHEAD_DEG del rumbo; muy cerca, hasta BESIDE_DEG. */
    static boolean inView(double km, double rel) {
        double a = Math.abs(rel);
        return a <= AHEAD_DEG || (km <= BESIDE_KM && a <= BESIDE_DEG);
    }

    /** Puro: el punto a km del de partida siguiendo el rumbo (grados). */
    static double[] ahead(double lat, double lon, double heading, double km) {
        double d = km / 6371.0;
        double b = Math.toRadians(heading);
        double a = Math.toRadians(lat);
        double la = Math.asin(Math.sin(a) * Math.cos(d) + Math.cos(a) * Math.sin(d) * Math.cos(b));
        double lo = Math.toRadians(lon) + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(a), Math.cos(d) - Math.sin(a) * Math.sin(la));
        return new double[]{Math.toDegrees(la), Math.toDegrees(lo)};
    }

    private static double km(double lat, double lon, Place p) {
        return RoadInfo.dist(lat, lon, p.lat, p.lon) / 1000;
    }

    /** Puro: lo que se enseña por delante (en marcha), del más cercano al más lejano. */
    static List<Place> upcoming(Collection<Place> all, double lat, double lon, double heading) {
        List<Place> out = new ArrayList<>();
        for (Place p : all) {
            double k = km(lat, lon, p);
            if (worth(p) && k <= LIST_KM && inView(k, rel(heading, RoadInfo.bearing(lat, lon, p.lat, p.lon)))) out.add(p);
        }
        out.sort((a, b) -> Double.compare(km(lat, lon, a), km(lat, lon, b)));
        return out.size() > LIST_MAX ? new ArrayList<>(out.subList(0, LIST_MAX)) : out;
    }

    /** Puro: lo que hay cerca a pie (parado), del más cercano al más lejano. */
    static List<Place> nearby(Collection<Place> all, double lat, double lon) {
        List<Place> out = new ArrayList<>();
        for (Place p : all) if (worth(p) && km(lat, lon, p) <= WALK_KM) out.add(p);
        out.sort((a, b) -> Double.compare(km(lat, lon, a), km(lat, lon, b)));
        return out.size() > LIST_MAX ? new ArrayList<>(out.subList(0, LIST_MAX)) : out;
    }

    /** Puro: el siguiente que contar: de los de delante a SAY_KM o menos y aún sin contar, el que más merece la pena. */
    static Place next(List<Place> upcoming, Set<Long> told, double lat, double lon) {
        Place best = null;
        for (Place p : upcoming) {
            if (told.contains(p.id) || !sayable(p) || km(lat, lon, p) > SAY_KM) continue;
            if (best == null || score(p) > score(best)) best = p;
        }
        return best;
    }

    /** Puro: el que más merece la pena de la lista (la tarjeta grande si no hay nada contado ni elegido). */
    static Place best(List<Place> list) {
        Place best = null;
        for (Place p : list) if (best == null || score(p) > score(best)) best = p;
        return best;
    }

    /** Puro: ¿hay que buscar otra vez? Sin búsqueda, al pararse o arrancar, al alejarse o pasado REFRESH_MS. */
    static boolean needQuery(double qLat, double qLon, long qAt, boolean qWalk, double lat, double lon, boolean walk, long now) {
        if (Double.isNaN(qLat)) return true;
        if (walk != qWalk) return true;
        double moved = RoadInfo.dist(qLat, qLon, lat, lon) / 1000;
        if (walk) return moved > 0.5;
        return moved > REQUERY_KM || now - qAt > REFRESH_MS;
    }

    /**
     * Puro: la introducción para leer o escuchar: sin paréntesis (pronunciación, otros nombres) ni referencias, y con
     * frases enteras hasta max caracteres; si la primera ya no cabe, cortada en una palabra con «…».
     */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '（') depth++;
            else if ((ch == ')' || ch == '）') && depth > 0) depth--;
            else if (depth == 0) b.append(ch);
        }
        String t = b.toString().replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\s+", " ").replaceAll(" ([,.;:])", "$1").trim();
        if (t.length() <= max) return t;
        int end = -1;
        for (int i = 0; i < max; i++) {
            char ch = t.charAt(i);
            boolean stop = ch == '。' || ((ch == '.' || ch == '!' || ch == '?') && (i + 1 == t.length() || t.charAt(i + 1) == ' '));
            if (stop) end = i + 1;
        }
        if (end > max / 3) return t.substring(0, end).trim();
        int sp = t.lastIndexOf(' ', max - 1);
        return t.substring(0, sp > max / 2 ? sp : max).trim() + "…";
    }

    /** Puro: «800 m», «3,2 km», «12 km» (decimales del idioma de la app). */
    static String distText(double km, Locale l) {
        if (km < 1) return Math.max(100, Math.round(km * 10) * 100) + " m";
        if (km < 10) return String.format(l, "%.1f km", km);
        return Math.round(km) + " km";
    }

    /** Puro: minutos andando (un 30 % más que en línea recta, a 4,8 km/h). */
    static int walkMin(double km) {
        return (int) Math.max(1, Math.round(km * 1.3 / 4.8 * 60));
    }

    // ------------------------------------------------------------------ en marcha

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static RoadGuide instance;
    private static volatile State last;
    private static volatile boolean voice;
    // Lo de abajo solo se toca desde el hilo de la guía (una guía a la vez): sigue al cerrar y abrir la pantalla.
    private static final Map<Long, Place> places = new HashMap<>();
    private static final Set<Long> told = new HashSet<>();
    private static double qLat = Double.NaN;
    private static double qLon = Double.NaN;
    private static long qAt;
    private static boolean qWalk;
    private static boolean searched;
    private static boolean offline;
    private static long retryAt;
    private static long lastSaidMs = -1;
    private static Place said;
    private static Place picked;
    private static long pickedUntil;
    private static boolean moving = true;
    private static long slowSince = -1;
    /** Primera posición desde que arrancó: parado ya entonces, se empieza en «a pie» sin esperar STOP_MS. */
    private static boolean fresh;

    private final Context ctx;
    private final HandlerThread thread = new HandlerThread("road-guide");
    private Handler h;
    private CarSensors sensors;
    private int users;
    private final Runnable tick = this::tick;
    private static final Runnable END = RoadGuide::endIfUnused;

    private RoadGuide(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** ¿Contar en voz alta? (el interruptor de la pantalla; por defecto, sí) */
    static boolean voiceOn(Context c) {
        return new Config(c).guideVoice();
    }

    /** Hilo principal: la pantalla se abre; la guía arranca (o sigue) y le manda lo último que tenga. */
    static void start(Context c, Listener l) {
        listeners.add(l);
        synchronized (RoadGuide.class) {
            MAIN.removeCallbacks(END);
            if (instance == null) {
                instance = new RoadGuide(c);
                instance.begin();
            }
            instance.users++;
        }
        State s = last;
        if (s != null) l.onGuide(s);
    }

    /** Hilo principal: la pantalla se cierra; sin pantallas, la guía se para pasado END_GRACE_MS. */
    static void stop(Listener l) {
        listeners.remove(l);
        synchronized (RoadGuide.class) {
            if (instance != null && --instance.users <= 0) MAIN.postDelayed(END, END_GRACE_MS);
        }
    }

    private static void endIfUnused() {
        synchronized (RoadGuide.class) {
            if (instance == null || instance.users > 0) return;
            instance.end();
            instance = null;
        }
    }

    /** Enciende o apaga la voz (se guarda). Apagarla corta también lo que esté contando. */
    static void setVoice(Context c, boolean on) {
        new Config(c).setGuideVoice(on);
        voice = on;
        if (!on) VoiceAlert.stop(c, VOICE);
        L.i("guía: voz " + (on ? "sí" : "no"));
        later(null);
    }

    /** Lo tocado en la lista pasa a la tarjeta grande un rato. */
    static void select(Place p) {
        later(g -> {
            picked = p;
            pickedUntil = SystemClock.elapsedRealtime() + PICK_MS;
        });
    }

    /** «Parar»: corta lo que esté contando la guía (la voz sigue encendida para los siguientes). */
    static void hush(Context c) {
        VoiceAlert.stop(c, VOICE);
        L.i("guía: voz cortada");
        later(null);
    }

    /** «Contar»: lo dice ahora (aunque la voz esté apagada o haga poco del anterior). */
    static void tell(Place p) {
        later(g -> g.say(p, true, last));
    }

    private interface Action {
        void run(RoadGuide g);
    }

    /** En el hilo de la guía: la acción y una vuelta para que la pantalla lo vea ya. */
    private static void later(Action a) {
        RoadGuide g;
        synchronized (RoadGuide.class) {
            g = instance;
        }
        if (g == null || g.h == null) return;
        g.h.post(() -> {
            if (a != null) a.run(g);
            g.step();
        });
    }

    private void begin() {
        // Lo de un viaje anterior no se enseña mientras llega la primera posición.
        last = null;
        fresh = true;
        slowSince = -1;
        voice = new Config(ctx).guideVoice();
        sensors = CarSensors.start(ctx);
        // Al acabar de hablar, la pantalla quita «Parar» al momento (sin esperar a la siguiente vuelta).
        VoiceAlert.onIdle = () -> later(null);
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(tick);
        L.i("guía: en marcha (voz " + (voice ? "sí" : "no") + ")");
    }

    private void end() {
        if (h != null) h.removeCallbacksAndMessages(null);
        VoiceAlert.onIdle = null;
        VoiceAlert.stop(ctx, VOICE);
        thread.quitSafely();
        CarSensors.stop();
        L.i("guía: parada");
    }

    private void tick() {
        step();
        if (h != null) {
            h.removeCallbacks(tick);
            h.postDelayed(tick, TICK_MS);
        }
    }

    private void step() {
        try {
            publish(compute());
        } catch (RuntimeException e) {
            L.w("guía: " + e.getClass().getSimpleName());
        }
    }

    private State compute() {
        State st = new State();
        st.voice = voice;
        st.talking = VoiceAlert.speaking(VOICE);
        CarSensors.Snapshot s = sensors.snapshot();
        long now = SystemClock.elapsedRealtime();
        if (!s.gpsLive() || Double.isNaN(s.lat) || Double.isNaN(s.lon)) return st;
        if (fresh) {
            fresh = false;
            moving = s.speedKmh > STOP_KMH;
        }
        if (s.speedKmh >= MOVE_KMH) {
            moving = true;
            slowSince = -1;
        } else if (s.speedKmh <= STOP_KMH) {
            if (slowSince < 0) slowSince = now;
            else if (now - slowSince >= STOP_MS) moving = false;
        } else {
            slowSince = -1;
        }
        st.moving = moving;
        st.lat = s.lat;
        st.lon = s.lon;
        st.heading = s.headingDeg;
        double[] c = moving ? ahead(s.lat, s.lon, s.headingDeg, LOOK_AHEAD_KM) : new double[]{s.lat, s.lon};
        if (needQuery(qLat, qLon, qAt, qWalk, c[0], c[1], !moving, now) && now >= retryAt) {
            try {
                fetch(wikiLang(Str.locale()), c[0], c[1], moving ? SEARCH_M : (int) (WALK_KM * 1000), s.lat, s.lon);
                qLat = c[0];
                qLon = c[1];
                qAt = now;
                qWalk = !moving;
                offline = false;
            } catch (Exception e) {
                offline = true;
                retryAt = now + RETRY_MS;
                L.w("guía: Wikipedia no responde (" + Http.safeError(e) + ")");
            }
            searched = true;
        }
        List<Place> list = moving ? upcoming(places.values(), s.lat, s.lon, s.headingDeg) : nearby(places.values(), s.lat, s.lon);
        if (voice && moving && (lastSaidMs < 0 || now - lastSaidMs >= VOICE_GAP_MS)) {
            Place n = next(list, told, s.lat, s.lon);
            if (n != null && quiet(ctx, now)) say(n, false, st);
        }
        Place cur = null;
        if (picked != null && now < pickedUntil) cur = picked;
        else if (said != null && (moving ? list.contains(said) : km(s.lat, s.lon, said) <= WALK_KM)) cur = said;
        else cur = best(list);
        st.current = cur;
        for (Place p : list) if (p != cur) st.list.add(p);
        for (Place p : places.values()) if (worth(p) && km(s.lat, s.lon, p) <= TRIVIA_KM) st.pool.add(p);
        st.talking = VoiceAlert.speaking(VOICE);
        st.status = cur != null ? Status.READY : offline ? Status.OFFLINE : searched ? Status.READY : Status.LOADING;
        return st;
    }

    /** Busca alrededor de (lat, lon) y pide el resumen de lo que merece la pena y aún no lo tiene (lo mejor primero). */
    private void fetch(String lang, double lat, double lon, int radiusM, double hereLat, double hereLon) throws Exception {
        List<Place> found = parseSearch(Http.get(searchUrl(lang, lat, lon, radiusM)));
        List<Place> want = new ArrayList<>();
        for (Place p : found) {
            Place old = places.get(p.id);
            if (old == null) {
                places.put(p.id, p);
                old = p;
            }
            if (!old.detailed && !skipped(old)) want.add(old);
        }
        // Resumen y visitas de los 20 que más prometen (y las tandas de visitas que falten, hasta 4 más).
        want.sort((a, b) -> Double.compare(prescore(b), prescore(a)));
        if (want.size() > 20) want = new ArrayList<>(want.subList(0, 20));
        if (!want.isEmpty()) {
            Map<Long, Place> byId = new HashMap<>();
            for (Place p : want) byId.put(p.id, p);
            String url = detailsUrl(lang, byId.keySet());
            String json = Http.get(url);
            applyDetails(json, byId);
            for (int i = 0; i < 4; i++) {
                String more = continueParams(json);
                if (more == null) break;
                json = Http.get(url + more);
                applyDetails(json, byId);
            }
            for (Place p : want) p.detailed = true;
        }
        // Lo que queda lejos, fuera (menos lo que está en la tarjeta).
        for (Iterator<Place> it = places.values().iterator(); it.hasNext(); ) {
            Place p = it.next();
            if (p != said && p != picked && km(hereLat, hereLon, p) > KEEP_KM) it.remove();
        }
        L.i("guía: " + found.size() + " artículos alrededor, " + want.size() + " resúmenes nuevos, " + places.size() + " en memoria");
    }

    /** Lo dice en voz alta, con la posición de s. asked: «Contar» (no cuenta para el hueco entre sitios de la guía). */
    private void say(Place p, boolean asked, State s) {
        String text = spoken(p, s);
        if (text.isEmpty()) return;
        VoiceAlert.say(ctx, text, VOICE);
        told.add(p.id);
        // Al diario del viaje (solo el título, en el fichero del viaje; el registro sigue sin sitios).
        TripLog.notePlace(p.title);
        said = p;
        if (!asked) lastSaidMs = SystemClock.elapsedRealtime();
        L.i("guía: " + (asked ? "contado a petición" : "contado") + " (" + text.length() + " caracteres)");
    }

    /** «A tu derecha, a 3 km: castillo de X. Resumen…» (sin posición: solo el nombre y el resumen). */
    private static String spoken(Place p, State s) {
        String body = clean(p.extract, SAY_CHARS);
        String lead;
        if (s == null || Double.isNaN(s.lat)) {
            lead = p.title + ".";
        } else {
            double k = km(s.lat, s.lon, p);
            String d = distText(k, Str.locale());
            if (!s.moving) {
                lead = Str.get(R.string.hql_guide_say_near, p.title, d);
            } else {
                switch (side(rel(s.heading, RoadInfo.bearing(s.lat, s.lon, p.lat, p.lon)))) {
                    case RIGHT:
                        lead = Str.get(R.string.hql_guide_say_right, p.title, d);
                        break;
                    case LEFT:
                        lead = Str.get(R.string.hql_guide_say_left, p.title, d);
                        break;
                    case AHEAD:
                        lead = Str.get(R.string.hql_guide_say_ahead, p.title, d);
                        break;
                    default:
                        lead = p.title + ".";
                }
            }
        }
        return (lead + " " + body).trim();
    }

    /**
     * ¿Se puede hablar? No con otro aviso en curso, con el navegador de AA hablando (o hace muy poco), en una llamada,
     * ni con otra app dando indicaciones o con el asistente.
     */
    static boolean quiet(Context c, long now) {
        if (VoiceAlert.speaking()) return false;
        long g = AapAudio.getLastGuidanceMs();
        if (g > 0 && now - g < GUIDANCE_QUIET_MS) return false;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return true;
        int mode = am.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            for (android.media.AudioPlaybackConfiguration pc : am.getActivePlaybackConfigurations()) {
                int u = pc.getAudioAttributes().getUsage();
                if (u == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE || u == AudioAttributes.USAGE_VOICE_COMMUNICATION
                        || u == AudioAttributes.USAGE_ASSISTANT) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void publish(State s) {
        last = s;
        MAIN.post(() -> {
            for (Listener l : listeners) l.onGuide(s);
        });
    }
}
