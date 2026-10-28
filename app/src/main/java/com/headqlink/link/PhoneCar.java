package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Pestaña «Mi coche» de la app del móvil (28-10, pedida en el foro LPMFF por dominik): lo de la app de Leapmotor, a
 * nuestra manera. Arriba la autonomía, la batería y el cierre; en medio nuestro C10 en 3D (assets/car3d.html, el mismo
 * de la pestaña Estado del coche: se gira con el dedo, con las puertas y el maletero abiertos); debajo, la fila de
 * órdenes del widget «Coche» (abrir, cerrar, maletero, clima, localizar: CarControlActivity, con su confirmación y su
 * PIN) y dos tarjetas que se despliegan: «Clima» (la última orden) y «Salud» (presiones, puertas, temperaturas). Todo
 * sale de la nube de Leapmotor (CarCloud): sin cuenta, explica qué es y lleva a configurarla. Solo se lee mientras se
 * ve (CarCloud.acquire/release).
 */
final class PhoneCar {
    private static final long TICK_MS = 2_000;
    private static final String CLOUD_OWNER = "phone-car";
    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScrollView root;
    private final LinearLayout col;
    private boolean visible;
    private boolean healthOpen;
    private boolean climateOpen;
    private WebView car3d;
    private boolean ready3d;
    private String sent3d = "";
    private CarCloud.Snapshot snap = CarCloud.snapshot();
    /** Lo que se repinta sin reconstruir la pantalla (el 3D no se destruye en cada tick). */
    private TextView rangeText;
    private TextView socText;
    /** Barra fina del % de batería bajo la cifra, como en la app de Leapmotor; su ancho y color siguen al dato. */
    private View socBar;
    private FrameLayout socTrack;
    private TextView lockText;
    private TextView ageText;
    private TextView stateText;
    /** Los cinco colores de Leapmotor España del C10 (los mismos que assets/car3d.html), o los tres del T03. */
    private static final int[] C10_PAINTS = {0xFF123C29, 0xFFE6E8E6, 0xFF121316, 0xFF34393F, 0xFF8C8A82};
    private LinearLayout colorRow;
    private LinearLayout healthBody;
    private LinearLayout climateBody;
    private String lastShape = "";
    /** «Carga» y «Consumo» (28-10): lo leído de ficheros y de la nube guardada, en otro hilo al entrar y cada minuto. */
    private static final long LOAD_MS = 60_000;
    private List<CloudHistory.Trip> history = new ArrayList<>();
    private List<TripLog.Trip> appTrips = new ArrayList<>();
    private Map<String, MonthlyReport.Summary> months = new HashMap<>();
    private List<ChargeSession> charges = new ArrayList<>();
    /** Cuándo se pidió por última vez a la nube lo de cada periodo. */
    private final Map<CarUsage.Period, Long> windowTriedAt = new HashMap<>();
    private long loadedAt;
    private boolean loading;
    private CarUsage.Period period = CarUsage.Period.WEEK;
    private TextView chargePct;
    private Battery battery;
    private TextView chargeState;
    private LinearLayout chargeBody;
    private TextView odoText;
    private LinearLayout usageBody;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            tick();
            main.postDelayed(this, TICK_MS);
        }
    };

    PhoneCar(Activity act) {
        this.act = act;
        root = new ScrollView(act);
        root.setFillViewport(true);
        root.setBackgroundColor(0x00000000);
        col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(28));
        root.addView(col);
    }

    View view() {
        return root;
    }

    void show() {
        visible = true;
        if (PhoneInsights.hasAccount(act)) CarCloud.acquire(act, CLOUD_OWNER);
        snap = CarCloud.snapshot();
        build();
        load();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    void hide() {
        visible = false;
        main.removeCallbacks(ticker);
        CarCloud.release(CLOUD_OWNER);
        drop3d();
    }

    /** Cada tick: si cambia lo que decide la forma de la pantalla (cuenta, datos), se reconstruye; si no, se repinta. */
    private void tick() {
        snap = CarCloud.snapshot();
        if (!shape().equals(lastShape)) build();
        else repaint();
        if (System.currentTimeMillis() - loadedAt > LOAD_MS) load();
    }

    private String shape() {
        return (PhoneInsights.hasAccount(act) ? "a" : "-") + (snap.hasData() ? "d" : "-") + (CarOrders.enabled(act) ? "o" : "-");
    }

    private void build() {
        lastShape = shape();
        drop3d();
        col.removeAllViews();
        rangeText = null;
        chargePct = null;
        chargeBody = null;
        odoText = null;
        usageBody = null;
        TextView title = text(Str.get(R.string.hql_car_tab), com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
                PhoneCharts.TEXT);
        title.setPadding(dp(4), 0, 0, dp(12));
        col.addView(title);
        if (!PhoneInsights.hasAccount(act)) {
            intro(Str.get(R.string.hql_car_intro), Str.get(R.string.hql_cloud_menu));
            return;
        }
        if (!snap.hasData()) {
            intro(Str.get(R.string.hql_cloud_waiting_body), null);
            return;
        }
        col.addView(hero());
        col.addView(orders());
        col.addView(chargeCard());
        col.addView(usageCard());
        if (loadedAt > 0) {
            paintRecords();
            paintUsage();
        }
        col.addView(section(Str.get(R.string.hql_cw_climate), climateOpen, () -> climateOpen = !climateOpen, b -> climateBody = b));
        col.addView(section(Str.get(R.string.hql_car_health), healthOpen, () -> healthOpen = !healthOpen, b -> healthBody = b));
        TextView note = text(Str.get(R.string.hql_car_note), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                PhoneCharts.FAINT);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        col.addView(note);
        repaint();
    }

    /** Sin cuenta o sin datos: qué es y, si procede, el botón para configurarla. */
    private void intro(String body, String button) {
        MaterialCardView card = card();
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(18), dp(18), dp(18), dp(18));
        in.addView(text(body, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM));
        if (button != null) {
            MaterialButton go = new MaterialButton(act);
            go.setText(button);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            go.setOnClickListener(v -> act.startActivity(new Intent(act, CarCloudActivity.class)));
            in.addView(go, lp);
        }
        card.addView(in);
        col.addView(card);
    }

    // ------------------------------------------------------------------ cabecera con el coche

    /** Autonomía y batería a la izquierda, el cierre a la derecha, el coche en 3D y el estado debajo. */
    private View hero() {
        MaterialCardView card = card();
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(18), dp(16), dp(18), dp(14));
        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.TOP);
        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        rangeText = text("", com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall, PhoneCharts.TEXT);
        rangeText.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        left.addView(rangeText);
        socTrack = new FrameLayout(act);
        socTrack.setBackgroundColor(0xFF1C2631);
        socBar = new View(act);
        socTrack.addView(socBar, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(150), dp(5));
        tlp.topMargin = dp(6);
        tlp.bottomMargin = dp(6);
        left.addView(socTrack, tlp);
        socText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        left.addView(socText);
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lockText = text("", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, PhoneCharts.DIM);
        lockText.setGravity(Gravity.END);
        lockText.setPadding(dp(8), dp(6), 0, 0);
        top.addView(lockText);
        in.addView(top);
        FrameLayout stage = new FrameLayout(act);
        setup3d(stage);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        slp.topMargin = dp(6);
        in.addView(stage, slp);
        in.addView(colors());
        stateText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        stateText.setGravity(Gravity.CENTER);
        in.addView(stateText);
        ageText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.FAINT);
        ageText.setGravity(Gravity.CENTER);
        in.addView(ageText);
        card.addView(in);
        return card;
    }

    /**
     * El color de la carrocería (28-10: el selector del 3D no cabe en el móvil, así que va aquí fuera): un círculo por
     * color, el elegido con el aro del acento. Se guarda en Config.carColor, el mismo que usa la pestaña Estado del coche.
     */
    private View colors() {
        colorRow = new LinearLayout(act);
        colorRow.setGravity(Gravity.CENTER);
        colorRow.setPadding(0, dp(4), 0, dp(10));
        int[] paints = CarSpec.current().isT03() ? CarArt.T03_PAINTS : C10_PAINTS;
        for (int c : paints) {
            String hex = CarArt.hex(c);
            View dot = new View(act);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(c);
            dot.setBackground(g);
            dot.setTag(hex);
            dot.setContentDescription(hex);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.setMargins(dp(7), 0, dp(7), 0);
            dot.setOnClickListener(v -> {
                new Config(act).setCarColor(hex);
                L.i("mi coche: color " + hex);
                paintColors();
                push3d();
            });
            colorRow.addView(dot, lp);
        }
        paintColors();
        return colorRow;
    }

    /** El aro en el color elegido (sin elegir, el primero). */
    private void paintColors() {
        if (colorRow == null) return;
        String chosen = new Config(act).carColor();
        for (int i = 0; i < colorRow.getChildCount(); i++) {
            View dot = colorRow.getChildAt(i);
            boolean on = chosen.isEmpty() ? i == 0 : chosen.equalsIgnoreCase((String) dot.getTag());
            android.graphics.drawable.GradientDrawable g = (android.graphics.drawable.GradientDrawable) dot.getBackground();
            g.setStroke(dp(on ? 3 : 1), on ? PhoneCharts.ACCENT : 0x59EAF2F7);
            dot.setScaleX(on ? 1.15f : 1f);
            dot.setScaleY(on ? 1.15f : 1f);
        }
    }

    /** El C10 en 3D (o el T03): la misma página que la pestaña Estado del coche. Sin WebView, el hueco queda vacío. */
    private void setup3d(FrameLayout stage) {
        try {
            WebView w = new WebView(act);
            w.setBackgroundColor(Color.TRANSPARENT);
            w.setVerticalScrollBarEnabled(false);
            w.setHorizontalScrollBarEnabled(false);
            android.webkit.WebSettings ws = w.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            w.addJavascriptInterface(new Bridge3d(), "HQL");
            w.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) L.w("3d (móvil): " + m.message());
                    return true;
                }
            });
            // Dentro de un ScrollView: el giro con el dedo es del coche, no de la lista.
            w.setOnTouchListener((v, e) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            w.loadUrl("file:///android_asset/car3d.html?mode=phone" + (CarSpec.current().isT03() ? "&model=t03" : ""));
            stage.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            car3d = w;
            ready3d = false;
            sent3d = "";
        } catch (RuntimeException e) {
            L.w("3d (móvil): sin WebView (" + e.getClass().getSimpleName() + ")");
            car3d = null;
        }
    }

    private void drop3d() {
        if (car3d == null) return;
        ViewGroup p = (ViewGroup) car3d.getParent();
        if (p != null) p.removeView(car3d);
        car3d.stopLoading();
        car3d.destroy();
        car3d = null;
        ready3d = false;
    }

    private final class Bridge3d {
        @android.webkit.JavascriptInterface
        public void ready() {
            main.post(() -> {
                ready3d = true;
                sent3d = "";
                push3d();
                // Entrada (28-10): el coche aparece girando desde −10° y creciendo desde el 85 %.
                if (car3d != null) {
                    car3d.setAlpha(0f);
                    car3d.setRotation(-10f);
                    car3d.setScaleX(0.85f);
                    car3d.setScaleY(0.85f);
                    car3d.animate().alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setDuration(700)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void color(String hex) {
            main.post(() -> new Config(act).setCarColor(hex));
        }


        /** La imagen del coche pintado (PNG en base64) para la pantalla de arranque (SplashView.carImage). */
        @android.webkit.JavascriptInterface
        public void snapshot(String dataUrl) {
            SplashView.saveCarImage(act, dataUrl);
        }

        @android.webkit.JavascriptInterface
        public void failed(String why) {
            main.post(() -> {
                L.i("3d (móvil): " + why);
                drop3d();
            });
        }
    }

    /** Las presiones, las puertas y el maletero al coche en 3D (solo si cambian), como en la pestaña Estado. */
    private void push3d() {
        if (car3d == null || !ready3d || snap.status == null) return;
        LeapStatus s = snap.status;
        try {
            JSONObject d = new JSONObject();
            JSONArray t = new JSONArray();
            JSONArray doors = new JSONArray();
            for (int i = 0; i < 4; i++) {
                double bar = s.tyreBar(i);
                JSONObject o = new JSONObject();
                o.put("text", num(bar, 2));
                o.put("unit", Double.isNaN(bar) ? "" : "bar");
                o.put("warn", s.tyreWarning(i));
                o.put("tag", s.tyreStateAlert(i) ? Str.get(R.string.hql_cloud_tyre_tpms_short) : "");
                t.put(o);
                doors.put(Boolean.TRUE.equals(s.doorOpen(i)));
            }
            d.put("tyres", t);
            d.put("doors", doors);
            d.put("boot", Boolean.TRUE.equals(s.bootOpen));
            d.put("hint", "");
            String color = new Config(act).carColor();
            if (!color.isEmpty()) d.put("color", color);
            String js = d.toString();
            if (js.equals(sent3d)) return;
            sent3d = js;
            car3d.evaluateJavascript("update(" + js + ")", null);
        } catch (org.json.JSONException e) {
            L.w("3d (móvil): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ órdenes

    /** La fila de la app de Leapmotor: Abrir · Cerrar · Maletero · Clima · Localizar, cada una por CarControlActivity. */
    private View orders() {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(14), 0, dp(4));
        int[][] items = {
                {R.drawable.hql_ic_cw_unlock, R.string.hql_cw_unlock},
                {R.drawable.hql_ic_cw_lock, R.string.hql_cw_lock},
                {R.drawable.hql_ic_cw_trunk, R.string.hql_cw_trunk},
                {R.drawable.hql_ic_cw_climate, R.string.hql_cw_climate},
                {R.drawable.hql_ic_cw_find, R.string.hql_cw_find},
        };
        String[] what = {"unlock", "lock", "trunk", "climate", "find"};
        boolean on = CarOrders.enabled(act);
        for (int i = 0; i < items.length; i++) {
            String w = what[i];
            row.addView(orderButton(items[i][0], Str.get(items[i][1]), on, v -> order(w)),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row);
        if (!on) {
            TextView hint = text(Str.get(R.string.hql_cw_off), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                    PhoneCharts.WARN);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, 0, 0, dp(6));
            hint.setOnClickListener(v -> act.startActivity(new Intent(act, CarCloudActivity.class)));
            box.addView(hint);
        }
        return box;
    }

    /** Un botón redondo con su icono y el nombre debajo, como los de la app de Leapmotor. */
    private View orderButton(int icon, String label, boolean on, View.OnClickListener tap) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(0, dp(4), 0, dp(4));
        MaterialCardView circle = new MaterialCardView(act);
        circle.setRadius(dp(30));
        circle.setCardElevation(0);
        circle.setCardBackgroundColor(on ? 0xFF16202B : 0xFF111821);
        circle.setStrokeColor(on ? 0xFF2A3A4C : 0xFF1C2631);
        circle.setStrokeWidth(dp(1));
        circle.setRippleColor(ColorStateList.valueOf(0x3300E5C7));
        circle.setClickable(true);
        circle.setOnClickListener(tap);
        ImageView img = new ImageView(act);
        img.setImageResource(icon);
        img.setImageTintList(ColorStateList.valueOf(on ? PhoneCharts.TEXT : PhoneCharts.FAINT));
        int pad = dp(16);
        img.setPadding(pad, pad, pad, pad);
        circle.addView(img, new FrameLayout.LayoutParams(dp(60), dp(60)));
        v.addView(circle, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView t = text(label, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium, on ? PhoneCharts.DIM : PhoneCharts.FAINT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, 0);
        t.setSingleLine(true);
        // «Localizar» y «Maletero» no caben a 12 sp en 1/5 del ancho: un poco más pequeño y, si aun así no cabe, encoge.
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        t.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        v.addView(t);
        return v;
    }

    /** Una orden: la ventanita del widget (confirma lo que abre, pide el clima, enseña el resultado). */
    private void order(String what) {
        act.startActivity(new Intent(act, CarControlActivity.class).putExtra(CarControlActivity.EXTRA_WHAT, what));
    }

    // ------------------------------------------------------------------ secciones desplegables

    private interface BodyHolder {
        void set(LinearLayout body);
    }

    /** Una tarjeta con título y flecha; al tocarla se despliega su cuerpo (lo rellena repaint). */
    private View section(String title, boolean open, Runnable toggle, BodyHolder holder) {
        MaterialCardView card = card();
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(14), dp(14), dp(14));
        head.setClickable(true);
        head.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView t = text(title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = text(open ? "⌃" : "⌄", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.FAINT);
        head.addView(arrow);
        in.addView(head);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), 0, dp(18), dp(16));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        in.addView(body);
        holder.set(body);
        head.setOnClickListener(v -> {
            toggle.run();
            boolean now = body.getVisibility() != View.VISIBLE;
            body.setVisibility(now ? View.VISIBLE : View.GONE);
            arrow.setText(now ? "⌃" : "⌄");
            if (now) repaint();
        });
        card.addView(in);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    // ------------------------------------------------------------------ repintado con cada dato

    private void repaint() {
        LeapStatus s = snap.status;
        if (s == null || rangeText == null) return;
        long now = System.currentTimeMillis();
        double range = s.evRangeKm();
        rangeText.setText(Double.isNaN(range) ? "—" : num(range, 0) + " km");
        String soc = Double.isNaN(s.socBest()) ? "—" : num(s.socBest(), 0) + " %";
        String socLine = Str.get(R.string.hql_cloud_battery) + " " + soc;
        if (s.reev() && !Double.isNaN(s.combinedRangeKm)) socLine += " · " + Str.get(R.string.hql_cloud_total_range, num(s.combinedRangeKm, 0));
        socText.setText(socLine);
        double pct = Double.isNaN(s.socBest()) ? 0 : Math.max(0, Math.min(100, s.socBest()));
        int level = pct >= 20 ? PhoneCharts.ACCENT_2 : pct >= 8 ? PhoneCharts.WARN : PhoneCharts.BAD;
        socBar.setBackgroundColor(level);
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) socBar.getLayoutParams();
        blp.width = (int) Math.round(dp(150) * pct / 100.0);
        socBar.setLayoutParams(blp);
        Boolean locked = s.locked;
        lockText.setText(locked == null ? "—" : (locked ? "🔒 " : "🔓 ")
                + Str.get(locked ? R.string.hql_cloud_locked : R.string.hql_cloud_unlocked));
        lockText.setTextColor(locked == null ? PhoneCharts.FAINT : locked ? PhoneCharts.ACCENT_2 : PhoneCharts.WARN);
        stateText.setText(stateLine(s, now));
        ageText.setText(Str.get(R.string.hql_cloud_car_data) + " · " + CarCloud.ago(snap.ageMs(now)));
        push3d();
        paintChargeNow(s, now);
        if (odoText != null) {
            odoText.setVisibility(Double.isNaN(s.odometerKm) ? View.GONE : View.VISIBLE);
            odoText.setText(Str.get(R.string.hql_car_use_odo, CarStatusTab.odometer(s.odometerKm), CarSpec.current().name));
        }
        if (climateBody != null && climateBody.getVisibility() == View.VISIBLE) paintClimate(s);
        if (healthBody != null && healthBody.getVisibility() == View.VISIBLE) paintHealth(s);
    }

    /** «Aparcado», «En marcha · 87 km/h», «Cargando · carga rápida (CC) · 85 kW · completa en 25 min». */
    private String stateLine(LeapStatus s, long now) {
        boolean moving = !Double.isNaN(s.speedKmh) && s.speedKmh > 1 && snap.ageMs(now) < 15 * 60_000L;
        if (s.charging()) {
            String t = Str.get(s.dcPlugged() ? R.string.hql_cloud_charging_dc : R.string.hql_cloud_charging_ac);
            double kw = s.powerKw();
            if (!Double.isNaN(kw)) t += " · " + num(Math.abs(kw), 1) + " kW";
            String left = CarStatusTab.minutes(s.chargeRemainMin);
            if (!left.isEmpty()) t += " · " + Str.get(R.string.hql_cloud_full_in, left);
            return t;
        }
        if (Boolean.TRUE.equals(s.chargeCompleted) && s.pluggedIn()) return Str.get(R.string.hql_cloud_charge_done);
        if (s.pluggedIn()) return Str.get(R.string.hql_cloud_plugged);
        if (moving) return Str.get(R.string.hql_cloud_driving, num(s.speedKmh, 0));
        return Str.get(Boolean.TRUE.equals(s.powerOn) ? R.string.hql_cloud_on : R.string.hql_car_parked);
    }

    /** Clima: el habitáculo, el clima encendido desde aquí (si lo está) y el botón que abre el diálogo del clima. */
    private void paintClimate(LeapStatus s) {
        climateBody.removeAllViews();
        String cabin = Double.isNaN(s.interiorTempC) ? "—" : num(s.interiorTempC, 0) + " °C";
        climateBody.addView(kv(Str.get(R.string.hql_cloud_cabin), cabin, PhoneCharts.TEXT));
        CarCommand.Mode mode = CarOrders.climate(act);
        String st = mode == null ? Str.get(R.string.hql_cloud_off)
                : Str.get(mode == CarCommand.Mode.COLD ? R.string.hql_cc_mode_cold : mode == CarCommand.Mode.HEAT ? R.string.hql_cc_mode_heat
                : R.string.hql_cc_mode_auto);
        int color = mode == null ? PhoneCharts.DIM : mode == CarCommand.Mode.COLD ? 0xFF64B5F6 : mode == CarCommand.Mode.HEAT ? 0xFFF28B82 : 0xFFFDD663;
        climateBody.addView(kv(Str.get(R.string.hql_cw_climate), st, color));
        String last = new Config(act).getString(CarOrders.K_LAST);
        if (last != null && !last.isEmpty()) {
            climateBody.addView(kv(Str.get(R.string.hql_car_last_order), last, PhoneCharts.FAINT));
        }
        MaterialButton b = new MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(Str.get(R.string.hql_cw_climate) + " ›");
        b.setEnabled(CarOrders.enabled(act));
        b.setOnClickListener(v -> order("climate"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        climateBody.addView(b, lp);
    }

    private static final int[] PLACES = {R.string.hql_cloud_pos_fl, R.string.hql_cloud_pos_fr, R.string.hql_cloud_pos_rl, R.string.hql_cloud_pos_rr};

    /** Salud: las cuatro presiones (en ámbar la rueda baja o con aviso), puertas, temperaturas y cuentakilómetros. */
    private void paintHealth(LeapStatus s) {
        healthBody.removeAllViews();
        GridLayout grid = new GridLayout(act);
        grid.setColumnCount(2);
        for (int i = 0; i < 4; i++) {
            double bar = s.tyreBar(i);
            String v = Double.isNaN(bar) ? "—" : num(bar, 2) + " bar";
            if (s.tyreStateAlert(i)) v += " · " + Str.get(R.string.hql_cloud_tyre_tpms_short);
            View cell = kv(capital(Str.get(PLACES[i])), v, s.tyreWarning(i) ? PhoneCharts.WARN : PhoneCharts.TEXT);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            grid.addView(cell, lp);
        }
        healthBody.addView(grid);
        String open = openList(s);
        healthBody.addView(kv(Str.get(R.string.hql_car_doors),
                open == null ? Str.get(R.string.hql_cloud_doors_closed) : Str.get(R.string.hql_cloud_open_list, open),
                open == null ? PhoneCharts.TEXT : PhoneCharts.WARN));
        if (!Double.isNaN(s.minBatteryTempC)) {
            healthBody.addView(kv(Str.get(R.string.hql_cloud_batt_temp, "").trim(), num(s.minBatteryTempC, 0) + " °C",
                    s.coldBattery() ? PhoneCharts.WARN : PhoneCharts.TEXT));
        }
        healthBody.addView(kv(Str.get(R.string.hql_cloud_odometer), CarStatusTab.odometer(s.odometerKm) + " km", PhoneCharts.TEXT));
        TextView note = text(Str.get(R.string.hql_cloud_tyres_note), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                PhoneCharts.FAINT);
        note.setPadding(0, dp(8), 0, 0);
        healthBody.addView(note);
    }

    /** «delantera izquierda» (la etiqueta de las presiones, en minúscula para las frases) como título: «Delantera izquierda». */
    private static String capital(String t) {
        return t == null || t.isEmpty() ? "" : t.substring(0, 1).toUpperCase(Locale.getDefault()) + t.substring(1);
    }

    private static String openList(LeapStatus s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (Boolean.TRUE.equals(s.doorOpen(i))) b.append(b.length() > 0 ? ", " : "").append(Str.get(PLACES[i]));
        }
        if (Boolean.TRUE.equals(s.bootOpen)) b.append(b.length() > 0 ? ", " : "").append(Str.get(R.string.hql_cloud_pos_boot));
        return b.length() == 0 ? null : b.toString();
    }

    // ------------------------------------------------------------------ carga y consumo (como la app de Leapmotor)

    /** Lee en otro hilo lo de «Carga» y «Consumo»: el historial de la nube, los viajes, los meses y las cargas. */
    private void load() {
        if (loading) return;
        loading = true;
        loadedAt = System.currentTimeMillis();
        // El periodo que se ve se pide aquí: que el repintado no lo pida otra vez a la vez.
        if (PhoneInsights.hasAccount(act)) windowTriedAt.put(period, loadedAt);
        Activity a = act;
        new Thread(() -> {
            List<CloudHistory.Trip> h = new ArrayList<>(CarCloud.history());
            if (h.isEmpty() && !DemoMode.active()) {
                JSONObject o = new CarCloudStore(a).history();
                if (o != null) h = CloudHistory.tripsFromJson(o);
            }
            List<TripLog.Trip> t = TripLog.recent(a, 600);
            Map<String, MonthlyReport.Summary> m;
            try {
                m = MonthlyReport.refresh(a);
            } catch (RuntimeException e) {
                m = new HashMap<>();
            }
            List<ChargeSession> c;
            try {
                c = ChargeLog.load(a);
            } catch (RuntimeException e) {
                c = new ArrayList<>();
            }
            // Con la cuenta de Leapmotor, lo del coche para el periodo que se ve (km y reparto).
            boolean account = PhoneInsights.hasAccount(a);
            CarUsage.Period p = period;
            if (account) {
                long now = System.currentTimeMillis();
                CarCloud.usage(a, CarUsage.key(p), CarUsage.fromMs(p, now, TimeZone.getDefault()), now);
            }
            List<CloudHistory.Trip> fh = h;
            Map<String, MonthlyReport.Summary> fm = m;
            List<ChargeSession> fc = c;
            main.post(() -> {
                loading = false;
                history = fh;
                appTrips = t;
                months = fm;
                charges = fc;
                if (!visible) return;
                paintRecords();
                paintUsage();
            });
        }, "hql-phone-car").start();
    }

    /** Pide a la nube lo de un periodo (al cambiar de Semana / Mes / Año) y repinta; una vez cada 30 min por periodo. */
    private void fetchWindow(CarUsage.Period p) {
        if (!PhoneInsights.hasAccount(act)) return;
        long now = System.currentTimeMillis();
        Long tried = windowTriedAt.get(p);
        if (tried != null && now - tried < CarCloud.USAGE_MS) return;
        windowTriedAt.put(p, now);
        Activity a = act;
        new Thread(() -> {
            long t = System.currentTimeMillis();
            CarCloud.usage(a, CarUsage.key(p), CarUsage.fromMs(p, t, TimeZone.getDefault()), t);
            main.post(() -> {
                if (visible && period == p) paintUsage();
            });
        }, "hql-phone-usage").start();
    }

    /** «Carga», como el «Centro de carga» de Leapmotor: el % grande, la batería dibujada, el estado y los registros. */
    private View chargeCard() {
        MaterialCardView card = card();
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(18), dp(16), dp(18), dp(16));
        in.addView(text(Str.get(R.string.hql_car_charge_title), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                PhoneCharts.TEXT));
        chargePct = text("", com.google.android.material.R.style.TextAppearance_Material3_DisplayMedium, PhoneCharts.TEXT);
        chargePct.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        chargePct.setGravity(Gravity.CENTER);
        in.addView(chargePct, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        battery = new Battery(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        blp.topMargin = dp(8);
        blp.leftMargin = dp(16);
        blp.rightMargin = dp(16);
        in.addView(battery, blp);
        chargeState = text("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        chargeState.setGravity(Gravity.CENTER);
        chargeState.setPadding(0, dp(10), 0, 0);
        in.addView(chargeState);
        chargeBody = new LinearLayout(act);
        chargeBody.setOrientation(LinearLayout.VERTICAL);
        in.addView(chargeBody);
        card.addView(in);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    /** Con cada dato: el %, la batería y el estado («Cargando · CC · 85 kW…», y en la carga en directo, el objetivo). */
    private void paintChargeNow(LeapStatus s, long now) {
        if (chargePct == null) return;
        double soc = s.socBest();
        chargePct.setText(Double.isNaN(soc) ? "—" : num(soc, 0) + " %");
        battery.set(Double.isNaN(soc) ? -1 : soc, s.charging());
        boolean plugged = s.charging() || s.pluggedIn();
        String st = plugged ? stateLine(s, now) : Str.get(R.string.hql_car_unplugged);
        ChargeSession live = s.charging() ? ChargeWatch.session() : null;
        if (live != null) {
            double target = ChargeWatch.barTarget(live);
            double at = live.atMs(target);
            if (live.minutesUntil(target) == 0) st += "\n" + Str.get(R.string.hql_live_ready_title);
            else if (!Double.isNaN(at)) st += "\n" + Str.get(R.string.hql_live_now_at, target) + " · " + ChargeWatch.time((long) at);
        }
        chargeState.setText(st);
        chargeState.setTextColor(s.charging() ? PhoneCharts.ACCENT_2 : PhoneCharts.DIM);
    }

    /**
     * Los últimos registros de carga: los que vio Qonecta+ (ChargeLog, con el % y el cargador; el historial de cargas de
     * la nube la rechaza, 28-10). El enlace lleva a Viajes › Cargas (con sus curvas).
     */
    private void paintRecords() {
        if (chargeBody == null) return;
        chargeBody.removeAllViews();
        chargeBody.addView(subhead(Str.get(R.string.hql_car_charge_records)));
        List<ChargeSession> list = PhoneInsights.realCharges(charges);
        if (list.isEmpty()) {
            chargeBody.addView(small(Str.get(R.string.hql_ins_no_charges)));
            return;
        }
        for (int i = 0; i < list.size() && i < 3; i++) {
            ChargeSession s = list.get(i);
            ChargeSession.Sample last = s.last();
            double endSoc = last != null ? last.soc : s.startSoc;
            double kwh = Math.max(0, endSoc - s.startSoc) * s.capacityKwh / 100;
            long min = Math.max(0, ((s.endMs > 0 ? s.endMs : (last != null ? last.atMs : s.startMs)) - s.startMs) / 60_000);
            double peak = 0;
            for (ChargeSession.Sample sm : s.samples) if (!Double.isNaN(sm.kw)) peak = Math.max(peak, sm.kw);
            String where = s.charger != null ? RoutePlanner.chargerName(s.charger) : Str.get(s.dc ? R.string.hql_ins_dc : R.string.hql_ins_ac);
            chargeBody.addView(row(day(s.startMs) + " · " + where, Str.get(R.string.hql_ins_charge_line, num(s.startSoc, 0), num(endSoc, 0),
                    num(kwh, 1), PhoneInsights.duration(min), peak > 0 ? num(peak, 0) : "—")));
        }
        chargeBody.addView(link(Str.get(R.string.hql_car_charge_all)));
    }

    /** «Consumo», como el de la app de Leapmotor: los km del coche, Semana / Mes / Año, las cifras y los últimos viajes. */
    private View usageCard() {
        MaterialCardView card = card();
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(18), dp(16), dp(18), dp(16));
        in.addView(text(Str.get(R.string.hql_car_use_title), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                PhoneCharts.TEXT));
        odoText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        odoText.setPadding(0, dp(2), 0, 0);
        in.addView(odoText);
        com.google.android.material.button.MaterialButtonToggleGroup g = new com.google.android.material.button.MaterialButtonToggleGroup(act);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        int[] labels = {R.string.hql_car_use_week, R.string.hql_car_use_month, R.string.hql_car_use_year};
        CarUsage.Period[] values = CarUsage.Period.values();
        for (int i = 0; i < values.length; i++) {
            MaterialButton b = new MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(Str.get(labels[i]));
            // Sin el relleno ancho de serie: «Semana» cabe entero en un tercio del ancho.
            b.setPaddingRelative(dp(4), b.getPaddingTop(), dp(4), b.getPaddingBottom());
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setSingleLine(true);
            CarUsage.Period p = values[i];
            g.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (p == period) g.check(b.getId());
            b.addOnCheckedChangeListener((btn, on) -> {
                if (!on || period == p) return;
                period = p;
                paintUsage();
            });
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(12);
        in.addView(g, glp);
        usageBody = new LinearLayout(act);
        usageBody.setOrientation(LinearLayout.VERTICAL);
        in.addView(usageBody);
        card.addView(in);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    /** Las cifras del periodo elegido y sus últimos viajes. */
    private void paintUsage() {
        if (usageBody == null) return;
        usageBody.removeAllViews();
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        CarUsage.Totals t = CarUsage.compute(period, now, tz, history, appTrips, months);
        // Con la cuenta: los km y la energía que da el coche para la ventana (los mismos que su app); si no, lo nuestro.
        CloudUsage.Window w = CarCloud.usageCached(CarUsage.key(period));
        if (w == null || now - w.atMs >= CarCloud.USAGE_MS || !CarUsage.matches(w, t.fromMs)) fetchWindow(period);
        if (!CarUsage.matches(w, t.fromMs)) w = null;
        boolean carKm = w != null && !Double.isNaN(w.km);
        boolean carKwh = w != null && w.split != null;
        double km = carKm ? w.km : t.km;
        double kwh = carKwh ? w.kwh() : t.kwh;
        double per100 = km >= 1 && kwh > 0 ? kwh / km * 100 : Double.NaN;
        CloudUsage.Split split = carKwh ? w.split : null;
        // La demostración enseña también el reparto (inventado, con la energía de sus viajes).
        if (split == null && DemoMode.active() && t.kwh > 0) split = new CloudUsage.Split(t.kwh * 0.931, t.kwh * 0.032, t.kwh * 0.037);
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.BOTTOM);
        TextView kmText = text(String.format(Locale.getDefault(), "%,.0f km", km), com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium,
                PhoneCharts.TEXT);
        head.addView(kmText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(text(periodLabel(t, carKm || carKwh), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                PhoneCharts.FAINT));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        usageBody.addView(head, hlp);
        if (t.trips == 0 && !(km >= 0.5)) {
            usageBody.addView(small(Str.get(R.string.hql_car_use_none)));
            return;
        }
        LinearLayout two = new LinearLayout(act);
        two.addView(stat(num(kwh, 1), "kWh", Str.get(R.string.hql_car_use_energy)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        two.addView(stat(num(per100, 1), "kWh/100", Str.get(R.string.hql_car_use_per100)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        usageBody.addView(two);
        if (split != null && split.percents() != null) usageBody.addView(splitBar(split.percents()));
        // Los viajes se cuentan si nuestros datos cubren la ventana (el año del coche puede ir más atrás que la app).
        String extra = "";
        if (!(carKm && period == CarUsage.Period.YEAR)) extra = t.trips + " " + Str.plural(R.plurals.hql_ins_trips, t.trips);
        double price = new Config(act).electricityPrice();
        if (kwh > 0 && price > 0) extra += (extra.isEmpty() ? "" : " · ") + "≈ " + Money.format(kwh * price);
        boolean fromCar = carKm || carKwh || t.fromCar;
        extra += (extra.isEmpty() ? "" : " · ") + Str.get(fromCar ? R.string.hql_ins_from_car : R.string.hql_ins_from_app);
        TextView ex = small(extra);
        ex.setPadding(0, dp(10), 0, 0);
        usageBody.addView(ex);
        if (t.rows.isEmpty()) return;
        usageBody.addView(subhead(Str.get(R.string.hql_car_use_trips)));
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (CarUsage.Row r : t.rows) {
            String when = day(r.startMs) + " · " + hm.format(new Date(r.startMs)) + "–" + hm.format(new Date(r.endMs));
            String what = num(r.km, 1) + " km";
            if (!Double.isNaN(r.kwh)) what += " · " + num(r.kwh, 1) + " kWh";
            if (r.km >= 1 && r.kwh > 0) what += " · " + num(r.kwh / r.km * 100, 1) + " kWh/100";
            usageBody.addView(row(when, what));
        }
        usageBody.addView(link(Str.get(R.string.hql_car_use_all)));
    }

    /** Colores del reparto: conducción (verde), clima (azul) y otros (ámbar), como en la app de Leapmotor. */
    private static final int[] SPLIT_COLORS = {PhoneCharts.ACCENT_2, 0xFF4FA3FF, PhoneCharts.WARN};
    private static final int[] SPLIT_LABELS = {R.string.hql_car_use_drive, R.string.hql_car_use_ac, R.string.hql_car_use_other};

    /** La barra del reparto (conducción, clima, otros) y su leyenda con el % de cada uno. */
    private View splitBar(double[] pct) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(14), 0, 0);
        LinearLayout bar = new LinearLayout(act);
        for (int i = 0; i < 3; i++) {
            if (pct[i] <= 0) continue;
            View seg = new View(act);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(SPLIT_COLORS[i]);
            g.setCornerRadius(dp(4));
            seg.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    (float) Math.max(pct[i], 0.8));
            lp.rightMargin = dp(2);
            bar.addView(seg, lp);
        }
        v.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        LinearLayout legend = new LinearLayout(act);
        legend.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView name = text("● " + Str.get(SPLIT_LABELS[i]), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                    PhoneCharts.DIM);
            android.text.SpannableString dot = new android.text.SpannableString(name.getText());
            dot.setSpan(new android.text.style.ForegroundColorSpan(SPLIT_COLORS[i]), 0, 1, 0);
            name.setText(dot);
            cell.addView(name);
            cell.addView(text(num(pct[i], 1) + " %", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.TEXT));
            legend.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        v.addView(legend);
        return v;
    }

    /** «últimos 7 días», «octubre» o «2026 · desde agosto» (con los datos del coche, el año entero: sin «desde»). */
    private static String periodLabel(CarUsage.Totals t, boolean fromCar) {
        switch (t.period) {
            case WEEK:
                return Str.get(R.string.hql_car_use_last7);
            case MONTH:
                return new SimpleDateFormat("LLLL", Locale.getDefault()).format(new Date(t.fromMs));
            default: {
                String y = new SimpleDateFormat("yyyy", Locale.getDefault()).format(new Date(t.fromMs));
                if (t.sinceMs <= 0 || fromCar) return y;
                return y + " · " + Str.get(R.string.hql_car_use_since, new SimpleDateFormat("LLLL", Locale.getDefault()).format(new Date(t.sinceMs)));
            }
        }
    }

    /** Cifra grande con su unidad y la etiqueta debajo. */
    private View stat(String value, String unit, String label) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(10), dp(8), 0);
        LinearLayout line = new LinearLayout(act);
        line.setGravity(Gravity.BOTTOM);
        line.addView(text(value, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, PhoneCharts.TEXT));
        TextView u = text(" " + unit, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        u.setPadding(0, 0, 0, dp(3));
        line.addView(u);
        v.addView(line);
        v.addView(text(label, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.FAINT));
        return v;
    }

    /** Título pequeño de una lista dentro de una tarjeta, con una raya encima. */
    private View subhead(String s) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        View line = new View(act);
        line.setBackgroundColor(PhoneCharts.GRID);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.topMargin = dp(14);
        llp.bottomMargin = dp(10);
        v.addView(line, llp);
        v.addView(text(s, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, PhoneCharts.DIM));
        return v;
    }

    /** Una fila de lista: el título y una línea pequeña debajo. */
    private View row(String title, String line) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(8), 0, 0);
        v.addView(text(title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, PhoneCharts.TEXT));
        v.addView(text(line, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.DIM));
        return v;
    }

    private TextView small(String s) {
        TextView t = text(s, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.FAINT);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    /** «Todos los viajes ›» / «Todas las cargas ›»: lleva a la pestaña Viajes, donde está todo con sus mapas y curvas. */
    private View link(String s) {
        TextView b = text(s, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, PhoneCharts.ACCENT);
        b.setPadding(0, dp(10), dp(12), dp(4));
        b.setClickable(true);
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        b.setOnClickListener(v -> {
            if (act instanceof HomeActivity) ((HomeActivity) act).showTab(1);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.gravity = Gravity.START;
        b.setLayoutParams(lp);
        return b;
    }

    private static String day(long ms) {
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new Date(ms));
    }

    /**
     * La batería del «Centro de carga»: el cuerpo redondeado con la parte llena en degradado verde (ámbar por debajo del
     * 20 %, rojo del 8 %), el borne a la derecha y, cargando, el rayo encima.
     */
    static final class Battery extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF body = new android.graphics.RectF();
        private final android.graphics.RectF fill = new android.graphics.RectF();
        private double pct = -1;
        private boolean charging;

        Battery(android.content.Context c) {
            super(c);
        }

        void set(double pct, boolean charging) {
            if (pct == this.pct && charging == this.charging) return;
            this.pct = pct;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float nub = 5 * d, stroke = 2 * d, gap = 2 * d, inset = 4 * d;
            body.set(stroke / 2, stroke / 2, w - nub - gap - stroke / 2, h - stroke / 2);
            float rad = h * 0.28f;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(PhoneCharts.TRACK);
            cv.drawRoundRect(body, rad, rad, p);
            if (pct >= 0) {
                float left = body.left + inset, right = body.right - inset;
                float to = left + (right - left) * (float) Math.max(0, Math.min(100, pct)) / 100f;
                if (to > left + 1) {
                    int c0 = pct < 8 ? PhoneCharts.BAD : pct < 20 ? PhoneCharts.WARN : 0xFF00A86B;
                    int c1 = pct < 8 ? PhoneCharts.BAD : pct < 20 ? PhoneCharts.WARN : PhoneCharts.ACCENT_2;
                    p.setShader(new android.graphics.LinearGradient(left, 0, right, 0, c0, c1, android.graphics.Shader.TileMode.CLAMP));
                    fill.set(left, body.top + inset, to, body.bottom - inset);
                    float fr = Math.max(2 * d, rad - inset);
                    cv.drawRoundRect(fill, fr, fr, p);
                    p.setShader(null);
                }
            }
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(PhoneCharts.GRID);
            cv.drawRoundRect(body, rad, rad, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            fill.set(body.right + gap, h * 0.32f, body.right + gap + nub, h * 0.68f);
            cv.drawRoundRect(fill, 2 * d, 2 * d, p);
            if (charging) CarIcons.bolt(cv, (body.left + body.right) / 2, h / 2, h * 0.62f, PhoneCharts.TEXT, p);
        }
    }

    // ------------------------------------------------------------------ piezas

    /** Etiqueta pequeña arriba y el valor debajo. */
    private View kv(String label, String value, int color) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(8), dp(8), 0);
        v.addView(text(label, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium, PhoneCharts.FAINT));
        v.addView(text(value, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, color));
        return v;
    }

    private static String num(double v, int decimals) {
        if (Double.isNaN(v)) return "—";
        return String.format(Locale.getDefault(), decimals == 0 ? "%.0f" : decimals == 1 ? "%.1f" : "%.2f", v);
    }

    private MaterialCardView card() {
        MaterialCardView c = new MaterialCardView(act, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle);
        c.setCardBackgroundColor(0xFF111821);
        c.setStrokeColor(0xFF243140);
        c.setRadius(dp(20));
        c.setRippleColor(ColorStateList.valueOf(0x3300E5C7));
        return c;
    }

    private TextView text(String s, int appearance, int color) {
        TextView t = new TextView(act);
        t.setTextAppearance(appearance);
        t.setText(s);
        t.setTextColor(color);
        return t;
    }

    private int dp(float d) {
        return Math.round(d * act.getResources().getDisplayMetrics().density);
    }
}
