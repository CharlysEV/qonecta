package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Aviso de versión nueva, con las versiones de HeadQLink (el comprobador de Open Headunit mira las de open-headunit).
 * Como mucho una consulta cada 6 h a GitHub: la última release del repositorio, con sus notas. También «Novedades»:
 * la primera vez que arranca una versión recién instalada, las notas de esa versión.
 */
final class UpdateCheck {
    static final String REPO = "CharlysEV/qonecta";
    static final String RELEASES_WEB = "https://github.com/" + REPO + "/releases";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases";
    private static final long EVERY_MS = 6 * 3600_000L;

    /** Una release: versión limpia («0.2.34»), enlace, notas y el APK adjunto (o vacío). */
    static final class Release {
        final String version;
        final String url;
        final String notes;
        final String apkUrl;

        Release(String version, String url, String notes, String apkUrl) {
            this.version = version;
            this.url = url;
            this.notes = notes;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
        }

        boolean hasApk() {
            return !apkUrl.isEmpty();
        }
    }

    /** Puro: la release de GitHub (JSON) → Release, con el primer adjunto .apk. */
    static Release fromJson(JSONObject o) {
        String apk = "";
        org.json.JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url");
                    break;
                }
            }
        }
        return new Release(clean(o.optString("tag_name")), o.optString("html_url", RELEASES_WEB), o.optString("body", ""), apk);
    }

    private UpdateCheck() {
    }

    /** Puro: «v0.2.33-qdauto» → {0, 2, 33}; null si no empieza por números. */
    static int[] parse(String tag) {
        if (tag == null) return null;
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i >= parts.length) break;
            try {
                v[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return i == 0 ? null : v;
            }
        }
        return v;
    }

    /** Puro: ¿latest es más nueva que current? Compara número a número (0.2.30 > 0.2.9). */
    static boolean newer(String latest, String current) {
        int[] a = parse(latest);
        int[] b = parse(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** Puro: «v0.2.33-qdauto» → «0.2.33». */
    static String clean(String tag) {
        int[] v = parse(tag);
        if (v == null) return tag == null ? "" : tag;
        return v[0] + "." + v[1] + "." + v[2];
    }

    private static final java.util.regex.Pattern LANG_MARK = java.util.regex.Pattern.compile("<!--\\s*lang:([a-z]{2})\\s*-->");

    /**
     * Puro: las notas en el idioma del móvil. Desde la 0.3.4 cada idioma va en su bloque, tras «&lt;!-- lang:xx --&gt;»
     * (es, en, pt, it, fr, de, nl, zh), y el título general («## Qonecta+ 0.3.4») delante de todos. Se devuelve el título
     * con el bloque del idioma de l; si no lo hay, el inglés; si no, el español. Notas sin bloques (las de antes): tal
     * cual. Las versiones anteriores, que no saben de bloques, ven el español: los demás idiomas van bajo un «## Aviso…»,
     * que ellas se saltan.
     */
    static String localized(String md, Locale l) {
        if (md == null) return "";
        java.util.regex.Matcher m = LANG_MARK.matcher(md);
        java.util.Map<String, String> blocks = new java.util.HashMap<>();
        String head = null;
        String lang = null;
        int from = 0;
        while (m.find()) {
            if (head == null) head = md.substring(0, m.start());
            else blocks.put(lang, md.substring(from, m.start()));
            lang = m.group(1);
            from = m.end();
        }
        if (head == null) return md;
        blocks.put(lang, md.substring(from));
        String want = l == null ? "" : l.getLanguage();
        String b = blocks.get(want);
        if (b == null) b = blocks.get("en");
        if (b == null) b = blocks.get("es");
        if (b == null) return md;
        // La etiqueta del idioma («#### English», para GitHub) no hace falta en la ventana.
        b = b.trim();
        if (b.startsWith("#### ")) b = b.indexOf('\n') > 0 ? b.substring(b.indexOf('\n') + 1).trim() : "";
        return head.trim() + "\n\n" + b;
    }

    /**
     * Puro: las notas de GitHub (Markdown) en texto llano para un diálogo: títulos sin «#», listas con «•», sin
     * negritas ni código, y sin la sección «Aviso» (licencias, que ya están en la app) ni el SHA.
     */
    static String plainNotes(String md) {
        if (md == null) return "";
        StringBuilder out = new StringBuilder();
        boolean skip = false;
        for (String raw : md.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.startsWith("## ")) {
                skip = l.toLowerCase().contains("aviso");
                if (skip) continue;
                // El título general («0.2.33: …») ya va en el título del diálogo.
                if (out.length() == 0) continue;
                l = l.substring(3);
            }
            if (skip) continue;
            if (l.startsWith("### ")) l = l.substring(4).toUpperCase();
            if (l.startsWith("SHA-256")) continue;
            if (raw.startsWith("  - ")) l = "    – " + l.substring(2);
            else if (l.startsWith("- ")) l = "• " + l.substring(2);
            l = l.replace("**", "").replace("`", "");
            // Sin líneas en blanco seguidas ni al principio.
            if (l.isEmpty() && (out.length() == 0 || out.toString().endsWith("\n\n"))) continue;
            out.append(l).append('\n');
        }
        return out.toString().trim();
    }

    /** Una entrada de las notas: el título (la negrita de delante, sin «:»; vacío si no hay), su texto y sus subpuntos. */
    static final class NoteItem {
        final String title;
        final String body;
        final List<String> sub = new ArrayList<>();

        NoteItem(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    /** Las notas por apartados: novedades (estrellas), mejoras y arreglos; other, lo que no va en ninguno (texto llano). */
    static final class Notes {
        final List<NoteItem> added = new ArrayList<>();
        final List<NoteItem> improved = new ArrayList<>();
        final List<NoteItem> fixed = new ArrayList<>();
        String other = "";

        /** ¿Vienen con apartados? Si no (notas antiguas), se enseñan como texto. */
        boolean structured() {
            return !added.isEmpty() || !improved.isEmpty() || !fixed.isEmpty();
        }
    }

    /**
     * Puro: las notas de GitHub por apartados, como las escribimos: «### Nuevo» (también «Novedades», «New»), «### Mejorado»
     * o «### Cambiado» («Improved», «Changed») y «### Arreglado» («Corregido», «Fixed»), y sus equivalentes en los demás
     * idiomas de la app (Neu, Verbessert, Behoben; Novo, Melhorado; Novità, Migliorato, Corretto; Nouveau, Amélioré,
     * Corrigé; Nieuw, Verbeterd, Opgelost; 新功能, 改进, 修复). Cada «- » es una entrada; si
     * empieza en negrita («- **Título:** texto»), eso es su título. Los «  - » son subpuntos de la anterior. Sin la
     * sección «Aviso» ni el SHA, como plainNotes.
     */
    static Notes sections(String md) {
        Notes n = new Notes();
        if (md == null) return n;
        List<NoteItem> cur = null;
        NoteItem last = null;
        boolean skip = false;
        StringBuilder other = new StringBuilder();
        for (String raw : md.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.startsWith("## ")) {
                skip = l.toLowerCase(Locale.ROOT).contains("aviso");
                cur = null;
                last = null;
                continue;
            }
            if (skip || l.startsWith("**SHA-256") || l.startsWith("SHA-256")) continue;
            if (l.startsWith("### ")) {
                String h = l.substring(4).toLowerCase(Locale.ROOT);
                cur = startsWithAny(h, "nuev", "novedad", "new", "neu", "novo", "novit", "nouveau", "nieuw", "新") ? n.added
                        : startsWithAny(h, "mejor", "cambi", "improv", "chang", "verbess", "melhor", "miglior", "amélior",
                        "amelior", "verbeter", "改进") ? n.improved
                        : startsWithAny(h, "arregl", "correg", "fix", "behoben", "corrig", "corrett", "opgelost", "修复") ? n.fixed
                        : null;
                last = null;
                if (cur == null) other.append(l.substring(4).toUpperCase(Locale.ROOT)).append('\n');
                continue;
            }
            boolean subItem = raw.startsWith("  - ") || raw.startsWith("    - ") || raw.startsWith("\t- ");
            if (cur != null && subItem && last != null) {
                last.sub.add(inline(l.substring(2)));
            } else if (cur != null && l.startsWith("- ")) {
                last = item(l.substring(2));
                cur.add(last);
            } else if (cur != null && !l.isEmpty() && last != null) {
                // Una línea que sigue a la entrada (párrafo partido).
                NoteItem joined = new NoteItem(last.title, (last.body + " " + inline(l)).trim());
                joined.sub.addAll(last.sub);
                cur.set(cur.size() - 1, joined);
                last = joined;
            } else if (cur == null && !l.isEmpty()) {
                other.append(l.startsWith("- ") ? "• " + inline(l.substring(2)) : inline(l)).append('\n');
            }
        }
        n.other = other.toString().trim();
        return n;
    }

    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) if (s.startsWith(p)) return true;
        return false;
    }

    /** «**Título:** texto» → la entrada con su título; sin negrita delante, todo es texto. */
    private static NoteItem item(String s) {
        if (s.startsWith("**")) {
            int end = s.indexOf("**", 2);
            if (end > 2) {
                String title = s.substring(2, end).trim();
                String rest = s.substring(end + 2).trim();
                if (title.endsWith(":")) title = title.substring(0, title.length() - 1).trim();
                if (rest.startsWith(":")) rest = rest.substring(1).trim();
                return new NoteItem(inline(title), inline(rest));
            }
        }
        return new NoteItem("", inline(s));
    }

    /** Sin negritas ni código. */
    private static String inline(String s) {
        return s.replace("**", "").replace("`", "").trim();
    }

    /**
     * Comprueba si hay versión nueva (como mucho cada 6 h; antes, lo recordado) y avisa en el hilo principal con la
     * release si es más nueva que la instalada, o null.
     */
    static void check(Context ctx, Consumer<Release> cb) {
        check(ctx, false, cb);
    }

    /** force: consulta ahora aunque no hayan pasado las 6 h (pruebas: HomeActivity con el extra force_update_check). */
    static void check(Context ctx, boolean force, Consumer<Release> cb) {
        Context app = ctx.getApplicationContext();
        Config cfg = new Config(app);
        String installed = installedVersion(app);
        long last = cfg.updateCheckedAt();
        Handler main = new Handler(Looper.getMainLooper());
        if (!force && System.currentTimeMillis() - last < EVERY_MS) {
            Release r = cfg.updateRelease();
            cb.accept(r != null && newer(r.version, installed) ? r : null);
            return;
        }
        new Thread(() -> {
            Release r = null;
            try {
                r = fromJson(new JSONObject(Http.get(API + "/latest")));
                cfg.setUpdateRelease(r);
                L.i("versiones: la última publicada es " + r.version + " · instalada " + installed
                        + (newer(r.version, installed) ? " · hay versión nueva" : ""));
            } catch (Exception e) {
                L.w("versiones: no se pudo consultar GitHub: " + Http.safeError(e));
                r = cfg.updateRelease();
            }
            cfg.setUpdateCheckedAt(System.currentTimeMillis());
            Release fin = r;
            main.post(() -> cb.accept(fin != null && newer(fin.version, installed) ? fin : null));
        }, "hql-update-check").start();
    }

    /**
     * «Novedades»: si esta versión arranca por primera vez (versionCode distinto del último visto), busca sus notas y
     * las da en el hilo principal (null si no hay red o no hay release). Se marca como vista solo cuando hay notas,
     * para volver a intentarlo si no hubo red.
     */
    static void whatsNew(Context ctx, Consumer<Release> cb) {
        Context app = ctx.getApplicationContext();
        Config cfg = new Config(app);
        long code = installedCode(app);
        if (code <= 0 || cfg.seenVersionCode() == code) return;
        if (cfg.seenVersionCode() == 0) {
            // Primera instalación: sin novedades que contar.
            cfg.setSeenVersionCode(code);
            return;
        }
        String installed = installedVersion(app);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release r = null;
            try {
                int dash = installed.indexOf('-');
                String tag = "v" + installed + (dash < 0 ? "-qdauto" : "");
                r = fromJson(new JSONObject(Http.get(API + "/tags/" + tag)));
                cfg.setSeenVersionCode(code);
                L.i("versiones: novedades de la " + r.version);
            } catch (Exception e) {
                L.w("versiones: sin novedades de la " + installed + ": " + Http.safeError(e));
            }
            Release fin = r;
            main.post(() -> cb.accept(fin));
        }, "hql-whats-new").start();
    }

    static String installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static long installedCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }
}
