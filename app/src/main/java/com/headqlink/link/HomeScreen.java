package com.headqlink.link;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Casa (Home Assistant) en el coche: los elementos elegidos en el móvil en botones grandes (luces, enchufes, garaje,
 * persianas, cerraduras, escenas, scripts…) con su estado. Un toque hace lo suyo (HomeAssistant.action); las cerraduras y
 * puertas piden confirmar. Los sensores y el clima solo se miran. Se actualiza cada 5 s mientras se ve.
 *
 * Solo cosas de casa: lo de Leapmotor Mate (el coche) va en la sección Coche, pestañas Estado y Mandos.
 */
final class HomeScreen implements CarScreen {
    private static final int COLUMNS = 4;
    private static final long REFRESH_MS = 5_000;

    private Host host;
    private HomeAssistant ha;
    private FrameLayout area;
    private TextView status;
    private GridLayout grid;
    private View empty;
    private volatile boolean running;
    /** Cada vez que se (re)empiezan las lecturas: las de una vuelta anterior se paran solas. */
    private volatile int gen;
    private final List<HomeAssistant.Entity> shown = new ArrayList<>();
    /** Elemento con un toque en curso (se pinta «…» hasta la respuesta). */
    private String busy;
    /** Los botones de la casa (para volver a ellos desde «Mi panel»). */
    private View tiles;
    /** «Mi panel» abierto: la web de Home Assistant y el guion de su sesión. */
    private android.webkit.WebView panelWeb;
    private androidx.webkit.ScriptHandler sessionHandle;

    @Override
    public View create(Host h) {
        host = h;
        ha = new HomeAssistant(h.context());
        tiles = buildTiles(h.context());
        // Se salió de Casa con el panel abierto: se vuelve a él.
        if (ha.configured() && ha.panelOpen()) return buildPanel(h.context());
        startTiles();
        return tiles;
    }

    /** La página no se rehace al pasar de día a noche con el panel abierto (se volvería a cargar). */
    @Override
    public boolean keepOnThemeChange() {
        return panelWeb != null;
    }

    private void startTiles() {
        if (grid == null) return;
        running = true;
        int g = ++gen;
        new Thread(() -> tick(g), "hql-home").start();
    }

    /** Una lectura y la siguiente a los REFRESH_MS, otra vez en su hilo (en el de la pantalla, la red está prohibida). */
    private void tick(int g) {
        if (!running || g != gen) return;
        load();
        host.post(() -> {
            if (running && g == gen && area != null) area.postDelayed(() -> new Thread(() -> tick(g), "hql-home").start(), REFRESH_MS);
        });
    }

    private View buildTiles(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(CarKit.BG);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(28, 22, 28, 10);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_ha_title), 34, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title);
        status = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        status.setPadding(24, 0, 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // «Mi panel»: el panel completo de Home Assistant (sus tarjetas, como en casa).
        if (ha.configured()) {
            TextView panel = CarStyle.pill(c, "▦ " + Str.get(R.string.hql_ha_panel));
            panel.setOnClickListener(v -> openPanel());
            head.addView(panel);
        }
        col.addView(head);
        area = new FrameLayout(c);
        col.addView(area, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!ha.configured()) {
            area.addView(CarStyle.message(c, Str.get(R.string.hql_ha_not_set)));
            return col;
        }
        if (ha.entities().isEmpty()) {
            area.addView(CarStyle.message(c, Str.get(R.string.hql_ha_none_chosen)));
            return col;
        }
        grid = new GridLayout(c);
        grid.setColumnCount(COLUMNS);
        grid.setPadding(20, 4, 20, 20);
        ScrollView sv = new ScrollView(c);
        sv.addView(grid);
        area.addView(sv);
        empty = CarStyle.message(c, Str.get(R.string.hql_ha_none_chosen));
        empty.setVisibility(View.GONE);
        area.addView(empty);
        status.setText(Str.get(R.string.hql_ha_loading));
        return col;
    }

    @Override
    public void destroy() {
        running = false;
        closePanelWeb();
    }

    // ------------------------------------------------------------------ «Mi panel»

    private void openPanel() {
        running = false;
        ha.setPanelOpen(true);
        host.setContent(buildPanel(host.context()));
    }

    /** «‹ Casa»: de vuelta a los botones. */
    private void closePanel() {
        ha.setPanelOpen(false);
        closePanelWeb();
        host.setContent(tiles);
        startTiles();
    }

    /**
     * El panel de Home Assistant a pantalla completa: su web con la sesión hecha con el token de la app (sin pedir usuario
     * ni contraseña), con «‹ Casa» y «⟳» arriba. Nunca se registra la dirección ni el token.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private View buildPanel(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(CarKit.BG);
        LinearLayout bar = CarStyle.bar(c);
        TextView back = CarStyle.pill(c, "‹ " + Str.get(R.string.hql_ha_title));
        TextView title = CarStyle.text(c, Str.get(R.string.hql_ha_panel), 24, CarStyle.TEXT_DIM);
        title.setPadding(22, 0, 22, 0);
        title.setSingleLine(true);
        TextView reload = CarStyle.pill(c, "⟳");
        bar.addView(back);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(reload);
        col.addView(bar);
        FrameLayout body = new FrameLayout(c);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        android.webkit.WebView w = new android.webkit.WebView(c);
        panelWeb = w;
        android.webkit.WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        WebScreen.applyDark(s);
        w.setBackgroundColor(CarKit.BG);
        TextView error = CarStyle.text(c, "", 26, CarStyle.TEXT_DIM);
        error.setGravity(Gravity.CENTER);
        error.setPadding(60, 0, 60, 0);
        error.setVisibility(View.GONE);
        body.addView(w, CarStyle.match());
        body.addView(error, CarStyle.match());
        String origin = HomeAssistant.origin(ha.url());
        String target = ha.panelUrl();
        // Sin el guion antes de la página (WebView viejo): primero una página suya que no arranca la web, se deja la
        // sesión y luego el panel.
        boolean early = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT);
        String[] script = new String[1];
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView v, String url) {
                if (!early && url != null && url.endsWith("/manifest.json") && script[0] != null) {
                    v.evaluateJavascript(script[0], r -> v.loadUrl(target));
                }
            }

            @Override
            public void onReceivedError(android.webkit.WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (!req.isForMainFrame()) return;
                L.w("Casa: el panel de Home Assistant no carga (" + err.getErrorCode() + ")");
                error.setText(Str.get(R.string.hql_ha_error, String.valueOf(err.getDescription())));
                error.setVisibility(View.VISIBLE);
            }
        });
        back.setOnClickListener(v -> closePanel());
        reload.setOnClickListener(v -> {
            error.setVisibility(View.GONE);
            w.reload();
        });
        // El token se descifra fuera de la pantalla (el Keystore puede tardar).
        new Thread(() -> {
            String js;
            try {
                js = ha.sessionScript();
            } catch (Exception e) {
                String why = HomeAssistant.reason(e);
                L.w("Casa: sin sesión para el panel (" + why + ")");
                host.post(() -> {
                    error.setText(Str.get(R.string.hql_ha_error, why));
                    error.setVisibility(View.VISIBLE);
                });
                return;
            }
            host.post(() -> {
                if (panelWeb != w) return;
                L.i("Casa: abro el panel de Home Assistant" + (ha.panel().isEmpty() ? "" : " (uno elegido)")
                        + (early ? "" : " · sesión sin guion previo"));
                if (early) {
                    try {
                        sessionHandle = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, js,
                                java.util.Collections.singleton(origin));
                    } catch (RuntimeException e) {
                        L.w("Casa: el guion de la sesión no se pudo poner (" + e.getClass().getSimpleName() + ")");
                    }
                    w.loadUrl(target);
                } else {
                    script[0] = js;
                    w.loadUrl(origin + "/manifest.json");
                }
            });
        }, "hql-home-panel").start();
        return col;
    }

    /** Cierra la web del panel y borra su sesión (el token no se queda guardado en el navegador). */
    private void closePanelWeb() {
        android.webkit.WebView w = panelWeb;
        if (w == null) return;
        panelWeb = null;
        if (sessionHandle != null) {
            sessionHandle.remove();
            sessionHandle = null;
        }
        w.stopLoading();
        w.evaluateJavascript(HomeAssistant.SESSION_CLEAR, null);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(w::destroy, 400);
    }

    /** Lee los estados (hilo propio) y repinta. */
    private void load() {
        try {
            List<HomeAssistant.Entity> list = ha.chosen();
            host.post(() -> {
                if (!running) return;
                shown.clear();
                shown.addAll(list);
                status.setText("");
                render();
            });
        } catch (Exception e) {
            String why = HomeAssistant.reason(e);
            L.w("Casa: Home Assistant no responde (" + why + ")");
            host.post(() -> {
                if (running) status.setText(Str.get(R.string.hql_ha_error, why));
            });
        }
    }

    private void render() {
        Context c = host.context();
        grid.removeAllViews();
        // Solo había cosas del coche (Leapmotor Mate, que van en la sección Coche): como si no hubiera nada elegido.
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        int w = Math.max(200, (host.width() - 40) / COLUMNS - 16);
        for (HomeAssistant.Entity e : shown) {
            View tile = tile(c, e, w);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = 190;
            lp.setMargins(8, 8, 8, 8);
            grid.addView(tile, lp);
        }
    }

    private View tile(Context c, HomeAssistant.Entity e, int w) {
        boolean on = HomeAssistant.active(e);
        String[] act = HomeAssistant.action(e);
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(22, 18, 22, 18);
        int bg = on ? (e.domain.equals("light") ? 0xFF4A3B12 : CarStyle.ACCENT_BG) : CarStyle.CARD;
        t.setBackground(CarStyle.round(bg, 28));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = CarStyle.text(c, glyph(e), 40, 0xFFFFFFFF);
        top.addView(icon);
        TextView state = CarStyle.text(c, e.id.equals(busy) ? "…" : stateText(e), 22, on ? (e.domain.equals("light") ? 0xFFFFD27A
                : CarStyle.ACCENT) : CarStyle.TEXT_DIM);
        state.setGravity(Gravity.END);
        state.setSingleLine(true);
        top.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.addView(top);
        TextView name = CarStyle.text(c, e.name, 26, CarStyle.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setPadding(0, 14, 0, 0);
        t.addView(name);
        if (act != null) {
            t.setOnClickListener(v -> {
                if (HomeAssistant.needsConfirm(e)) {
                    confirm(area, Str.get("unlock".equals(act[1]) || "open_cover".equals(act[1]) ? R.string.hql_ha_confirm_open
                            : R.string.hql_ha_confirm_close, e.name), () -> run(e, act));
                } else {
                    run(e, act);
                }
            });
        }
        return t;
    }

    /** Pregunta antes de hacerlo (la cerradura de casa, abrir el coche…): un segundo toque en «Sí», sobre area. */
    static void confirm(FrameLayout area, String question, Runnable yesDo) {
        Context c = area.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xE60A0E14);
        box.setClickable(true);
        TextView q = CarStyle.text(c, question, 32, CarStyle.TEXT);
        q.setGravity(Gravity.CENTER);
        box.addView(q);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 30, 0, 0);
        TextView yes = CarStyle.pill(c, Str.get(R.string.hql_ha_yes));
        yes.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30);
        yes.setPadding(60, 22, 60, 22);
        yes.setBackground(CarStyle.accent(34));
        yes.setTextColor(CarStyle.ON_ACCENT);
        TextView no = CarStyle.pill(c, Str.get(R.string.hql_cancel));
        no.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30);
        no.setPadding(60, 22, 60, 22);
        row.addView(yes);
        row.addView(no);
        box.addView(row);
        area.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        yes.setOnClickListener(v -> {
            area.removeView(box);
            yesDo.run();
        });
        no.setOnClickListener(v -> area.removeView(box));
    }

    private void run(HomeAssistant.Entity e, String[] act) {
        busy = e.id;
        render();
        new Thread(() -> {
            try {
                ha.call(act[0], act[1], e.id);
                L.i("Casa: " + act[0] + "." + act[1]);
                // Home Assistant tarda un poco en cambiar el estado (el garaje, más): se relee enseguida y otra vez luego.
                Thread.sleep(700);
            } catch (Exception ex) {
                String why = HomeAssistant.reason(ex);
                L.w("Casa: no se pudo (" + why + ")");
                host.post(() -> status.setText(Str.get(R.string.hql_ha_error, why)));
            }
            busy = null;
            load();
        }, "hql-home-call").start();
    }

    static String stateText(HomeAssistant.Entity e) {
        String s = e.state == null ? "" : e.state;
        switch (s) {
            case "on":
                return Str.get(R.string.hql_ha_on);
            case "off":
                return Str.get(R.string.hql_ha_off);
            case "open":
                return Str.get(R.string.hql_ha_open);
            case "closed":
                return Str.get(R.string.hql_ha_closed);
            case "opening":
                return Str.get(R.string.hql_ha_opening);
            case "closing":
                return Str.get(R.string.hql_ha_closing);
            case "locked":
                return Str.get(R.string.hql_ha_locked);
            case "unlocked":
                return Str.get(R.string.hql_ha_unlocked);
            case "unavailable":
            case "unknown":
                return "—";
            default:
                break;
        }
        if ("scene".equals(e.domain) || "script".equals(e.domain) || e.domain.endsWith("button")) return "";
        return e.value();
    }

    /** Un dibujo por tipo (y por clase en garajes, puertas, temperatura…). */
    static String glyph(HomeAssistant.Entity e) {
        String dc = e.deviceClass == null ? "" : e.deviceClass;
        switch (e.domain) {
            case "light":
                return "💡";
            case "switch":
            case "input_boolean":
                return "🔌";
            case "cover":
                return dc.equals("garage") ? "🚗" : dc.equals("door") || dc.equals("gate") ? "🚪" : "🪟";
            case "lock":
                return "🔒";
            case "scene":
                return "🎬";
            case "script":
            case "automation":
                return "⚙️";
            case "button":
            case "input_button":
                return "🔘";
            case "fan":
                return "🌀";
            case "climate":
                return "🌡️";
            case "alarm_control_panel":
                return "🛡️";
            case "media_player":
                return "🎵";
            case "sensor":
                return dc.equals("temperature") ? "🌡️" : dc.equals("humidity") ? "💧" : dc.equals("power") || dc.equals("energy") ? "⚡"
                        : dc.equals("battery") ? "🔋" : "📈";
            case "binary_sensor":
                return dc.equals("door") || dc.equals("window") || dc.equals("opening") ? "🚪" : dc.equals("motion") ? "🏃" : "●";
            default:
                return "●";
        }
    }
}
