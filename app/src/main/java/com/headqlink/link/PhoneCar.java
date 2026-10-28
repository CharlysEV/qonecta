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

import java.util.Locale;

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
    private TextView lockText;
    private TextView ageText;
    private TextView stateText;
    private LinearLayout healthBody;
    private LinearLayout climateBody;
    private String lastShape = "";
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
        root.setBackgroundColor(0xFF0A0E14);
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
    }

    private String shape() {
        return (PhoneInsights.hasAccount(act) ? "a" : "-") + (snap.hasData() ? "d" : "-") + (CarOrders.enabled(act) ? "o" : "-");
    }

    private void build() {
        lastShape = shape();
        drop3d();
        col.removeAllViews();
        rangeText = null;
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
        col.addView(section(Str.get(R.string.hql_cw_climate), climateOpen, () -> climateOpen = !climateOpen, b -> climateBody = b));
        col.addView(section(Str.get(R.string.hql_car_health), healthOpen, () -> healthOpen = !healthOpen, b -> healthBody = b));
        TextView note = text(Str.get(R.string.hql_cloud_readonly_note), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
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
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        slp.topMargin = dp(6);
        in.addView(stage, slp);
        stateText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        stateText.setGravity(Gravity.CENTER);
        in.addView(stateText);
        ageText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.FAINT);
        ageText.setGravity(Gravity.CENTER);
        in.addView(ageText);
        card.addView(in);
        return card;
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
            w.loadUrl("file:///android_asset/car3d.html" + (CarSpec.current().isT03() ? "?model=t03" : ""));
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
            });
        }

        @android.webkit.JavascriptInterface
        public void color(String hex) {
            main.post(() -> new Config(act).setCarColor(hex));
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
        row.setPadding(0, dp(14), 0, dp(6));
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
        v.setPadding(dp(2), dp(4), dp(2), dp(4));
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
        Boolean locked = s.locked;
        lockText.setText(locked == null ? "—" : (locked ? "🔒 " : "🔓 ")
                + Str.get(locked ? R.string.hql_cloud_locked : R.string.hql_cloud_unlocked));
        lockText.setTextColor(locked == null ? PhoneCharts.FAINT : locked ? PhoneCharts.ACCENT_2 : PhoneCharts.WARN);
        stateText.setText(stateLine(s, now));
        ageText.setText(Str.get(R.string.hql_cloud_car_data) + " · " + CarCloud.ago(snap.ageMs(now)));
        push3d();
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
            View cell = kv(Str.get(PLACES[i]), v, s.tyreWarning(i) ? PhoneCharts.WARN : PhoneCharts.TEXT);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            grid.addView(cell, lp);
        }
        healthBody.addView(grid);
        String open = openList(s);
        healthBody.addView(kv(Str.get(R.string.hql_cloud_tyres_doors),
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

    private static String openList(LeapStatus s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (Boolean.TRUE.equals(s.doorOpen(i))) b.append(b.length() > 0 ? ", " : "").append(Str.get(PLACES[i]));
        }
        if (Boolean.TRUE.equals(s.bootOpen)) b.append(b.length() > 0 ? ", " : "").append(Str.get(R.string.hql_cloud_pos_boot));
        return b.length() == 0 ? null : b.toString();
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
