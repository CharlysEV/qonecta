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
        return stations(Favorites.load(new Config(c), Favorites.RADIO));
    }

    static List<RadioPlayer.Station> recents(Context c) {
        return stations(Favorites.fromJson(prefs(c).getString(KEY, "")));
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

    /** Puro: en filas (sin logos: Android Auto solo abre imágenes content://) y sin búsqueda. */
    static Bundle rootExtras() {
        Bundle b = new Bundle();
        b.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, false);
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
        List<RadioPlayer.Station> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty()) continue;
            RadioPlayer.Station s = new RadioPlayer.Station();
            s.name = o.optString("name");
            s.url = o.optString("url");
            s.logo = o.optString("logo");
            s.group = o.optString("group");
            out.add(s);
        }
        return out;
    }

    /** Puro: las recientes con s la primera (si ya estaba, sube), como mucho max. */
    static JSONArray withRecent(JSONArray old, RadioPlayer.Station s, int max) {
        JSONArray out = new JSONArray();
        out.put(Favorites.item(s.name, s.url, s.logo, s.group, null));
        for (int i = 0; i < old.length() && out.length() < max; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty() || s.url.equals(o.optString("url"))) continue;
            out.put(o);
        }
        return out;
    }
}
