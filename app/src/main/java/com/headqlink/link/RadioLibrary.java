package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.media.MediaSessionManager;
import androidx.media.utils.MediaConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lo que Android Auto ve de la radio (RadioBrowserService): dos carpetas, «Favoritos» (las emisoras con ★,
 * Favorites.RADIO) y «Recientes» (las últimas que han sonado, de la más nueva a la más vieja, guardadas aquí). Cada
 * emisora va con un id propio («fav:…», «recent:…»: la lista y un resumen de su URL, que así no sale del móvil). Las
 * canciones de «Mi música» no van a ninguna de las dos.
 */
final class RadioLibrary {
    static final String ROOT = "root";
    static final String FAVS = "fav";
    static final String RECENT = "recent";
    /** Recientes que se guardan como mucho. */
    static final int RECENT_MAX = 30;
    private static final String PREFS = "hql_radio_recent";
    private static final String KEY = "list";

    /** Quién ve las listas, además de Qonecta+ y el sistema: Android Auto (y Play Services), Google y el Bluetooth. */
    static final Set<String> TRUSTED = new HashSet<>(Arrays.asList("com.google.android.projection.gearhead",
            "com.google.android.gms", "com.google.android.googlequicksearchbox", "com.android.bluetooth",
            "com.google.android.bluetooth"));

    private RadioLibrary() {
    }

    static List<RadioPlayer.Station> favorites(Context c) {
        return stations(Favorites.load(new Config(c), Favorites.RADIO), logins(c));
    }

    static List<RadioPlayer.Station> recents(Context c) {
        return stations(Favorites.fromJson(prefs(c).getString(KEY, "")), logins(c));
    }

    /**
     * Una lista con usuario se ha cargado: las recientes guardadas antes con él dentro de la URL (0.3.14), con marcas y su
     * id (M3uSource.hideInFavorites, como los favoritos).
     */
    static void hideLoginInRecents(Context c, M3uSource.Login login, String loginId) {
        SharedPreferences p = prefs(c);
        JSONArray a = Favorites.fromJson(p.getString(KEY, ""));
        if (M3uSource.hideInFavorites(a, login, loginId) > 0) p.edit().putString(KEY, a.toString()).apply();
    }

    /** El usuario guardado de un id (M3uSource.Logins.get). */
    interface LoginLookup {
        M3uSource.Login get(String id);
    }

    /** Los usuarios de las listas, cada uno leído una sola vez (Logins descifra el archivo en cada get) y solo si hace falta. */
    static LoginLookup logins(Context c) {
        Context app = c.getApplicationContext();
        java.util.Map<String, M3uSource.Login> known = new java.util.HashMap<>();
        M3uSource.Logins[] store = {null};
        return id -> {
            M3uSource.Login l = known.get(id);
            if (l == null) {
                if (store[0] == null) store[0] = new M3uSource.Logins(app);
                known.put(id, l = store[0].get(id));
            }
            return l;
        };
    }

    /** Una emisora acaba de empezar a sonar: la primera de las recientes. */
    static void addRecent(Context c, RadioPlayer.Station s) {
        if (s == null || s.local || s.url == null || s.url.isEmpty()) return;
        SharedPreferences p = prefs(c);
        p.edit().putString(KEY, withRecent(Favorites.fromJson(p.getString(KEY, "")), s, RECENT_MAX).toString()).apply();
        RadioBrowserService.changed(RECENT);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Lo que hay dentro de una carpeta de Android Auto (a quien no puede ver las listas, mayList, o en una que no existe, nada). */
    static List<MediaBrowserCompat.MediaItem> children(Context c, String parentId, boolean mayList) {
        if (!mayList) return new ArrayList<>();
        if (ROOT.equals(parentId)) return folders(Str.get(R.string.hql_radio_aa_favorites), Str.get(R.string.hql_radio_aa_recent));
        if (FAVS.equals(parentId)) return items(FAVS, favorites(c));
        if (RECENT.equals(parentId)) return items(RECENT, recents(c));
        return new ArrayList<>();
    }

    /** «▶» en una emisora de Android Auto: suena, con su lista (favoritos o recientes) para ⏮ ⏭. */
    static void play(Context c, String mediaId) {
        String which = listOf(mediaId);
        List<RadioPlayer.Station> l = FAVS.equals(which) ? favorites(c) : RECENT.equals(which) ? recents(c) : null;
        int i = l == null ? -1 : indexOf(l, mediaId);
        if (i < 0) {
            L.w("radio: Android Auto pide una emisora que ya no está en la lista");
            return;
        }
        L.i("radio: desde Android Auto, «" + l.get(i).name + "» (" + (FAVS.equals(which) ? "favoritos" : "recientes") + ")");
        RadioPlayer.play(c, l, i);
    }

    /**
     * La búsqueda de Android Auto (onSearch) o de voz (onPlayFromSearch): entre las favoritas y las recientes (las únicas
     * que ve Android Auto), por nombre o grupo, sin mayúsculas ni acentos. Las listas importadas son solo del panel del coche.
     */
    static List<MediaBrowserCompat.MediaItem> search(Context c, String query, boolean mayList) {
        if (!mayList) return new ArrayList<>();
        List<MediaBrowserCompat.MediaItem> out = items(FAVS, RadioFilter.apply(favorites(c), RadioFilter.AZ, query));
        out.addAll(items(RECENT, notIn(RadioFilter.apply(recents(c), RadioFilter.AZ, query), favorites(c))));
        return out;
    }

    /** «Pon X» por voz: suena la primera favorita (o, si no, reciente) que encaja; sin texto o sin coincidencia, nada. */
    static void playFromSearch(Context c, String query) {
        List<RadioPlayer.Station> favs = RadioFilter.apply(favorites(c), RadioFilter.AZ, query);
        List<RadioPlayer.Station> recents = RadioFilter.apply(recents(c), RadioFilter.AZ, query);
        if (RadioFilter.words(query).length == 0 || favs.isEmpty() && recents.isEmpty()) {
            L.i("radio: Android Auto busca «" + query + "»: nada que poner");
            return;
        }
        List<RadioPlayer.Station> l = favs.isEmpty() ? recents : favs;
        L.i("radio: por búsqueda, «" + l.get(0).name + "»");
        RadioPlayer.play(c, l, 0);
    }

    /** Puro: las de stations cuya URL no está en other. */
    static List<RadioPlayer.Station> notIn(List<RadioPlayer.Station> stations, List<RadioPlayer.Station> other) {
        Set<String> urls = new HashSet<>();
        for (RadioPlayer.Station s : other) urls.add(s.url);
        List<RadioPlayer.Station> out = new ArrayList<>();
        for (RadioPlayer.Station s : stations) {
            if (!urls.contains(s.url)) out.add(s);
        }
        return out;
    }

    // ---------------------------------------------------------------- puro

    /** Puro: quien se conecta ve las listas (Qonecta+, el sistema o uno de TRUSTED). */
    static boolean trusted(String pkg, int uid, int myUid) {
        return uid == myUid || uid == android.os.Process.SYSTEM_UID || TRUSTED.contains(pkg);
    }

    /**
     * Puro: quien pide una carpeta (onLoadChildren) la ve: de confianza y de verdad dueño del paquete que dice
     * (packagesOfUid: los del uid que llama; por la mensajería de AndroidX el paquete lo pone quien llama). En Android
     * 8.1 o anterior, lo que llega por el camino del sistema no dice quién es (LEGACY_CONTROLLER), pero por ahí solo entra
     * quien onGetRoot dejó pasar.
     */
    static boolean mayList(String pkg, int uid, int myUid, String[] packagesOfUid, int sdk) {
        if (sdk < 28 && MediaSessionManager.RemoteUserInfo.LEGACY_CONTROLLER.equals(pkg)) return true;
        if (pkg == null || packagesOfUid == null || !trusted(pkg, uid, myUid)) return false;
        for (String p : packagesOfUid) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    /** Puro: en filas (sin logos: Android Auto solo abre imágenes content://) y con búsqueda (favoritas y recientes). */
    static Bundle rootExtras() {
        Bundle b = new Bundle();
        b.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true);
        b.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM);
        b.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM);
        return b;
    }

    /** Puro: las dos carpetas de la raíz (Android Auto las enseña como pestañas). */
    static List<MediaBrowserCompat.MediaItem> folders(String favTitle, String recentTitle) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        out.add(folder(FAVS, favTitle));
        out.add(folder(RECENT, recentTitle));
        return out;
    }

    private static MediaBrowserCompat.MediaItem folder(String id, String title) {
        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    /** Puro: las emisoras de una lista como elementos que se pueden poner. */
    static List<MediaBrowserCompat.MediaItem> items(String list, List<RadioPlayer.Station> stations) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        for (RadioPlayer.Station s : stations) {
            MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId(list, s.url))
                    .setTitle(s.name)
                    .build();
            out.add(new MediaBrowserCompat.MediaItem(d, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    /** Puro: el id de una emisora de la lista list. */
    static String mediaId(String list, String url) {
        return list + ":" + Integer.toHexString(url == null ? 0 : url.hashCode());
    }

    /** Puro: la lista de un id (FAVS o RECENT), o null si no es de una emisora. */
    static String listOf(String mediaId) {
        if (mediaId == null) return null;
        int i = mediaId.indexOf(':');
        String l = i > 0 ? mediaId.substring(0, i) : "";
        return FAVS.equals(l) || RECENT.equals(l) ? l : null;
    }

    /** Puro: dónde está la emisora del id en la lista, o -1 si ya no está. */
    static int indexOf(List<RadioPlayer.Station> stations, String mediaId) {
        String list = listOf(mediaId);
        if (list == null) return -1;
        for (int i = 0; i < stations.size(); i++) {
            if (mediaId.equals(mediaId(list, stations.get(i).url))) return i;
        }
        return -1;
    }

    /** Puro: las emisoras de un JSON como el de Favorites ([{name,url,logo,group}]); las que no tienen URL, fuera. */
    static List<RadioPlayer.Station> stations(JSONArray a) {
        return stations(a, id -> M3uSource.Login.NONE);
    }

    /** Puro: igual, con el usuario de su lista (logins) en las que lo tienen (readLogin). */
    static List<RadioPlayer.Station> stations(JSONArray a, LoginLookup logins) {
        List<RadioPlayer.Station> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty()) continue;
            RadioPlayer.Station s = new RadioPlayer.Station();
            s.name = o.optString("name");
            s.url = o.optString("url");
            s.logo = o.optString("logo");
            s.group = o.optString("group");
            readLogin(o, s, logins);
            out.add(s);
        }
        return out;
    }

    /** Puro: las recientes con s la primera (si ya estaba, sube), como mucho max. */
    static JSONArray withRecent(JSONArray old, RadioPlayer.Station s, int max) {
        JSONArray out = new JSONArray();
        JSONObject first = item(s);
        String key = first.optString("url");
        out.put(first);
        for (int i = 0; i < old.length() && out.length() < max; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty() || key.equals(o.optString("url"))) continue;
            out.put(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- guardadas sin el usuario de su lista

    /**
     * Puro: una emisora como se guarda (favoritos, recientes): en una lista con usuario, la URL sin él (en Xtream va en
     * la ruta: marcas, M3uSource.hideLogin), el id de su usuario y, con HTTP Basic, su servidor. Como la TV: ni las
     * copias de ajustes (SettingsBackup) ni los archivos de la app llevan el usuario ni la contraseña.
     */
    static JSONObject item(RadioPlayer.Station s) {
        JSONObject o = Favorites.item(s.name, storedUrl(s), s.logo, s.group, null);
        try {
            putLogin(o, s);
        } catch (org.json.JSONException ignored) {
            // No pasa con cadenas.
        }
        return o;
    }

    /** Puro: la URL de s como se guarda (y la clave de sus favoritos): sin el usuario de su lista. */
    static String storedUrl(RadioPlayer.Station s) {
        return s.loginId.isEmpty() ? s.url : M3uSource.hideLogin(s.url, s.login);
    }

    /** Puro: en lo guardado de s, el id del usuario de su lista y, con HTTP Basic, su servidor. */
    static void putLogin(JSONObject o, RadioPlayer.Station s) throws org.json.JSONException {
        if (s.loginId.isEmpty()) return;
        o.put("login", s.loginId);
        if (s.authHost != null) o.put("auth", s.authHost);
    }

    /**
     * Puro: al revés que putLogin: el usuario de su lista (logins) y la URL de verdad (M3uSource.showLogin). Si ya no
     * está (lista quitada, copia en otro móvil), las marcas se quedan: un error al ponerla, como en la TV.
     */
    static void readLogin(JSONObject o, RadioPlayer.Station s, LoginLookup logins) {
        s.loginId = o.optString("login", "");
        if (s.loginId.isEmpty()) return;
        M3uSource.Login l = logins.get(s.loginId);
        s.login = l != null ? l : M3uSource.Login.NONE;
        s.url = M3uSource.showLogin(s.url, s.login);
        s.authHost = o.has("auth") ? o.optString("auth") : null;
    }
}
