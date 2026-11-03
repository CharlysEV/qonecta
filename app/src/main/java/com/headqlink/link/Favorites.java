package com.headqlink.link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Favoritos de la TV y de la radio del coche (puro, lo prueban los tests): se marcan manteniendo pulsado un canal o una
 * emisora, y salen en la pestaña ★ aunque vengan de otra lista. Guardados en Config como JSON [{name,url,logo,group,urls}],
 * con la URL (la primera fuente) de clave. Los de la radio se ordenan, renombran y quitan en el móvil (RadioFavsEditor).
 */
final class Favorites {
    static final String TV = "tv";
    static final String RADIO = "radio";
    static final int MAX = 200;
    /** El nombre de su lista, guardado mientras se le haya puesto otro (rename). */
    private static final String ORIG = "orig";

    private Favorites() {
    }

    static JSONArray load(Config c, String kind) {
        return fromJson(c.favorites(kind));
    }

    static void save(Config c, String kind, JSONArray a) {
        c.setFavorites(kind, a.toString());
    }

    /** Puro: el JSON guardado → lista; vacío o roto → ninguno. */
    static JSONArray fromJson(String json) {
        if (json == null || json.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static boolean contains(JSONArray a, String url) {
        return indexOf(a, url) >= 0;
    }

    static int indexOf(JSONArray a, String url) {
        if (url == null) return -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && url.equals(o.optString("url"))) return i;
        }
        return -1;
    }

    /** Puro: quita el item si está (por URL) o lo añade al final; devuelve true si ha quedado dentro. */
    static boolean toggle(JSONArray a, JSONObject item) {
        String url = item.optString("url");
        int i = indexOf(a, url);
        if (i >= 0) {
            a.remove(i);
            return false;
        }
        if (a.length() >= MAX) a.remove(0);
        a.put(item);
        return true;
    }

    // ---------------------------------------------------------------- editar (la app del móvil, RadioFavsEditor)

    /** Puro: el de from pasa al puesto to (los de en medio corren uno); fuera de rango, nada. Se cambia a en su sitio. */
    static void move(JSONArray a, int from, int to) {
        int n = a.length();
        if (from < 0 || from >= n || to < 0 || to >= n || from == to) return;
        List<Object> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(a.opt(i));
        l.add(to, l.remove(from));
        try {
            for (int i = 0; i < n; i++) a.put(i, l.get(i));
        } catch (JSONException ignored) {
            // No pasa: los mismos objetos en otro orden.
        }
    }

    /** Puro: quita el del puesto i (fuera de rango, nada). */
    static void removeAt(JSONArray a, int i) {
        if (i >= 0 && i < a.length()) a.remove(i);
    }

    /** El nombre con el que entró (el de su lista), aunque se le haya puesto otro. */
    static String originalName(JSONObject o) {
        return o.has(ORIG) ? o.optString(ORIG) : o.optString("name");
    }

    /**
     * Puro: le pone otro nombre; vacío, o el de su lista, vuelve al de su lista. Mientras lleve otro, el de su lista se
     * guarda en «orig». El resto (url, urls, logo, grupo, login, auth) no se toca: la URL sigue siendo su clave.
     */
    static void rename(JSONObject o, String name) {
        String orig = originalName(o);
        String n = name == null ? "" : name.trim();
        try {
            if (n.isEmpty() || n.equals(orig)) {
                o.put("name", orig);
                o.remove(ORIG);
            } else {
                o.put(ORIG, orig);
                o.put("name", n);
            }
        } catch (JSONException ignored) {
            // No pasa con cadenas.
        }
    }

    /** Puro: si lleva otro nombre que el de su lista. */
    static boolean renamed(JSONObject o) {
        return o.has(ORIG);
    }

    static JSONObject item(String name, String url, String logo, String group, List<String> urls) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name == null ? "" : name);
            o.put("url", url == null ? "" : url);
            o.put("logo", logo == null ? "" : logo);
            o.put("group", group == null ? "" : group);
            if (urls != null && urls.size() > 1) o.put("urls", new JSONArray(urls));
        } catch (JSONException ignored) {
            // No pasa con cadenas.
        }
        return o;
    }

    /** Las fuentes guardadas (urls, o la url sola). */
    static List<String> urls(JSONObject o) {
        List<String> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("urls");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        if (out.isEmpty()) out.add(o.optString("url"));
        return out;
    }
}
