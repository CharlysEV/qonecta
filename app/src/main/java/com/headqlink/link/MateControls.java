package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Pestaña «Mandos» de Casa con Leapmotor Mate, como su página de comandos: accesos (cierre, maletero, ventanillas,
 * pitar y luces), clima, carga (precalentar la batería, soltar el cable y el límite de carga) y confort (asientos,
 * volante y retrovisores). Cada botón llama a su elemento de Home Assistant (los botones de Mate, el cierre, el
 * maletero, el límite); solo salen los que publica Mate para ese coche. Abrir el coche, el maletero y las ventanillas
 * piden confirmar (lo hace quien escucha, HomeScreen).
 */
final class MateControls {
    interface Listener {
        /** Llamar a domain.service sobre entityId (con data si hace falta); confirm: la pregunta antes, o null. */
        void act(String domain, String service, String entityId, JSONObject data, String confirm);
    }

    private static final int TILE_H = 168;

    private final Context ctx;
    private final Listener listener;
    private final int width;
    private final LinearLayout list;
    private final ScrollView root;
    /** Elemento con una orden en curso (se pinta «…» hasta la siguiente lectura). */
    String busy;

    MateControls(Context c, int width, Listener l) {
        ctx = c;
        this.width = width;
        listener = l;
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(20, 0, 20, 24);
        root = new ScrollView(c);
        root.setVerticalScrollBarEnabled(false);
        root.addView(list);
    }

    View view() {
        return root;
    }

    /** Repinta con el estado de ahora (conserva el desplazamiento). */
    void update(MateHa.Car car) {
        list.removeAllViews();
        if (car == null) return;
        int cols = width >= 1300 ? 5 : width >= 900 ? 4 : 3;
        int tw = Math.max(170, (width - 40) / cols - 16);

        // Accesos.
        GridLayout g = section(Str.get(R.string.hql_mate_access), null, cols);
        String lock = car.state("lock.door_lock");
        if (lock != null) {
            boolean locked = "locked".equals(lock);
            String label = Str.get(locked ? R.string.hql_cc_unlock : R.string.hql_cc_lock);
            add(g, tw, locked ? "🔒" : "🔓", Str.get(locked ? R.string.hql_cloud_locked : R.string.hql_cloud_unlocked), label, !locked,
                    car.id("lock.door_lock"), v -> listener.act("lock", locked ? "unlock" : "lock", car.id("lock.door_lock"), null,
                            locked ? Str.get(R.string.hql_mate_confirm, label) : null));
        }
        Boolean trunk = car.has("switch.trunk") ? (Boolean) "on".equals(car.state("switch.trunk")) : car.on("binary_sensor.trunk");
        if (car.has("switch.trunk") || car.has("button.open_trunk")) {
            boolean open = Boolean.TRUE.equals(trunk);
            String label = Str.get(open ? R.string.hql_cc_trunk_close : R.string.hql_cc_trunk_open);
            String key = open ? "button.close_trunk" : "button.open_trunk";
            add(g, tw, "🧳", trunk == null ? "—" : Str.get(open ? R.string.hql_ha_open : R.string.hql_ha_closed), label, open,
                    car.has(key) ? car.id(key) : car.id("switch.trunk"), v -> {
                        if (car.has(key)) listener.act("button", "press", car.id(key), null, open ? null : Str.get(R.string.hql_mate_confirm, label));
                        else listener.act("switch", open ? "turn_off" : "turn_on", car.id("switch.trunk"), null,
                                open ? null : Str.get(R.string.hql_mate_confirm, label));
                    });
        }
        if (car.has("button.close_windows") || car.has("button.open_windows")) {
            Boolean any = car.on("binary_sensor.any_window");
            boolean open = Boolean.TRUE.equals(any);
            String key = open || !car.has("button.open_windows") ? "button.close_windows" : "button.open_windows";
            boolean closing = key.equals("button.close_windows");
            String label = Str.get(closing ? R.string.hql_mate_windows_close : R.string.hql_mate_windows_open);
            add(g, tw, "🪟", any == null ? "—" : Str.get(open ? R.string.hql_ha_open : R.string.hql_ha_closed), label, open,
                    car.id(key), v -> listener.act("button", "press", car.id(key), null, closing ? null : Str.get(R.string.hql_mate_confirm, label)));
        }
        press(g, tw, car, "button.find_car", "📣", R.string.hql_cc_find, null);

        // Clima: arriba, si está puesto y la temperatura de dentro.
        Boolean climate = car.on("binary_sensor.climate");
        double inside = MateHa.celsius(car.num("sensor.inside_temp"), car.unit("sensor.inside_temp"));
        StringBuilder sub = new StringBuilder();
        if (climate != null) sub.append(Str.get(climate ? R.string.hql_cloud_on : R.string.hql_cloud_off));
        if (!Double.isNaN(inside)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(Str.get(R.string.hql_mate_climate_inside, String.format(Locale.getDefault(), "%.0f °C", inside)));
        }
        g = section(Str.get(R.string.hql_mate_climate), sub.toString(), cols);
        press(g, tw, car, "button.quick_cool", "❄️", R.string.hql_mate_quick_cool, null);
        press(g, tw, car, "button.quick_heat", "🔥", R.string.hql_mate_quick_heat, null);
        press(g, tw, car, "button.quick_ventilation", "🌀", R.string.hql_mate_ventilate, null);
        press(g, tw, car, "button.defrost", "🌫️", R.string.hql_cc_defrost, null);
        press(g, tw, car, "button.a_c_off", "⏹️", R.string.hql_cc_climate_off, null);

        // Carga.
        Boolean plug = car.on("binary_sensor.plug_connected");
        Boolean charging = car.on("binary_sensor.charging");
        String cs = Boolean.TRUE.equals(charging) ? Str.get(R.string.hql_mate_k_charging)
                : Boolean.TRUE.equals(plug) ? Str.get(R.string.hql_mate_k_plug) : plug == null ? null : Str.get(R.string.hql_cloud_unplugged);
        g = section(Str.get(R.string.hql_mate_charge), cs, cols);
        press(g, tw, car, "button.preheat_battery", "🔋", R.string.hql_mate_preheat, null);
        press(g, tw, car, "button.unlock_charge_cable", "🔌", R.string.hql_mate_unlock_cable, null);
        if (car.has("number.charge_limit")) limit(g, tw, car);

        // Confort: cada uno se enciende o se apaga según su nivel de ahora.
        g = section(Str.get(R.string.hql_mate_comfort), null, cols);
        toggle(g, tw, car, "sensor.seat_heating_driver", "button.driver_seat_heat_on", "button.driver_seat_heat_off", "💺", R.string.hql_mate_seat_heat_d);
        toggle(g, tw, car, "sensor.seat_heating_passenger", "button.passenger_seat_heat_on", "button.passenger_seat_heat_off", "💺", R.string.hql_mate_seat_heat_p);
        toggle(g, tw, car, "sensor.seat_ventilation_driver", "button.driver_seat_vent_on", "button.driver_seat_vent_off", "💨", R.string.hql_mate_seat_vent_d);
        toggle(g, tw, car, "sensor.seat_ventilation_passenger", "button.passenger_seat_vent_on", "button.passenger_seat_vent_off", "💨", R.string.hql_mate_seat_vent_p);
        toggle(g, tw, car, "sensor.steering_wheel_heat", "button.steering_heat_on", "button.steering_heat_off", "☸️", R.string.hql_mate_wheel_heat);
        toggle(g, tw, car, "sensor.mirror_heating_left", "button.mirror_heat_on", "button.mirror_heat_off", "🪞", R.string.hql_mate_mirror_heat);
        // Secciones que se quedaron sin botones (el coche no los tiene): fuera.
        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            View v = list.getChildAt(i);
            if (v.getTag() instanceof GridLayout && ((GridLayout) v.getTag()).getChildCount() == 0) list.removeViewAt(i);
        }
    }

    /** Título de sección (con una línea de estado al lado) y su rejilla. */
    private GridLayout section(String title, String state, int cols) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(8, 22, 8, 6);
        TextView t = CarStyle.text(ctx, title.toUpperCase(Locale.getDefault()), 21, CarKit.FAINT);
        t.setTypeface(CarKit.MEDIUM);
        t.setLetterSpacing(0.08f);
        head.addView(t);
        if (state != null && !state.isEmpty()) {
            TextView s = CarStyle.text(ctx, "  ·  " + state, 21, CarKit.DIM);
            head.addView(s);
        }
        box.addView(head);
        GridLayout g = new GridLayout(ctx);
        g.setColumnCount(cols);
        box.addView(g);
        box.setTag(g);
        list.addView(box);
        return g;
    }

    /** Un botón de Mate que se pulsa (si el coche lo tiene). */
    private void press(GridLayout g, int tw, MateHa.Car car, String key, String glyph, int label, String state) {
        if (!car.has(key)) return;
        String id = car.id(key);
        add(g, tw, glyph, state, Str.get(label), false, id, v -> listener.act("button", "press", id, null, null));
    }

    /** Encender o apagar según el nivel de ahora (Mate publica el nivel como sensor y un botón para cada cosa). */
    private void toggle(GridLayout g, int tw, MateHa.Car car, String level, String onKey, String offKey, String glyph, int label) {
        if (!car.has(onKey) || !car.has(offKey)) return;
        double v = car.num(level);
        boolean on = !Double.isNaN(v) && v > 0;
        String key = on ? offKey : onKey;
        String id = car.id(key);
        add(g, tw, glyph, Double.isNaN(v) ? "—" : Str.get(on ? R.string.hql_cloud_on : R.string.hql_cloud_off), Str.get(label), on,
                id, x -> listener.act("button", "press", id, null, null));
    }

    /** Límite de carga: el % de ahora con − y + (el paso, el mínimo y el máximo, los de Mate). */
    private void limit(GridLayout g, int tw, MateHa.Car car) {
        String id = car.id("number.charge_limit");
        double v = car.num("number.charge_limit");
        JSONObject a = car.attrs("number.charge_limit");
        double min = a.optDouble("min", 50);
        double max = a.optDouble("max", 100);
        double step = Math.max(1, a.optDouble("step", 5));
        LinearLayout t = tileBox(false);
        TextView name = CarStyle.text(ctx, Str.get(R.string.hql_mate_limit), 24, CarStyle.TEXT);
        name.setTypeface(CarKit.MEDIUM);
        name.setSingleLine(true);
        t.addView(name);
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 14, 0, 0);
        TextView minus = stepper("−");
        TextView val = CarStyle.text(ctx, id.equals(busy) ? "…" : Double.isNaN(v) ? "—" : String.format(Locale.getDefault(), "%.0f %%", v), 34, CarStyle.TEXT);
        val.setGravity(Gravity.CENTER);
        TextView plus = stepper("+");
        row.addView(minus);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus);
        t.addView(row);
        if (!Double.isNaN(v)) {
            minus.setOnClickListener(x -> setLimit(id, Math.max(min, v - step)));
            plus.setOnClickListener(x -> setLimit(id, Math.min(max, v + step)));
        }
        place(g, t, tw);
    }

    private void setLimit(String id, double value) {
        try {
            listener.act("number", "set_value", id, new JSONObject().put("value", value), null);
        } catch (JSONException ignored) {
        }
    }

    private TextView stepper(String s) {
        TextView b = CarStyle.text(ctx, s, 34, CarStyle.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(CarKit.outlined(CarKit.SURFACE_TOP, CarKit.EDGE, 30));
        b.setLayoutParams(new LinearLayout.LayoutParams(76, 64));
        return b;
    }

    /** Botón de la rejilla: dibujo y estado arriba, nombre abajo; encendido, con el fondo del acento. */
    private void add(GridLayout g, int tw, String glyph, String state, String label, boolean on, String id, View.OnClickListener click) {
        LinearLayout t = tileBox(on);
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(CarStyle.text(ctx, glyph, 38, 0xFFFFFFFF));
        TextView st = CarStyle.text(ctx, id != null && id.equals(busy) ? "…" : state == null ? "" : state, 22,
                on ? CarStyle.ACCENT : CarStyle.TEXT_DIM);
        st.setGravity(Gravity.END);
        st.setSingleLine(true);
        top.addView(st, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.addView(top);
        TextView name = CarStyle.text(ctx, label, 24, CarStyle.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setPadding(0, 12, 0, 0);
        t.addView(name);
        t.setOnClickListener(click);
        place(g, t, tw);
    }

    private LinearLayout tileBox(boolean on) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(22, 16, 22, 16);
        t.setBackground(CarStyle.round(on ? CarStyle.ACCENT_BG : CarStyle.CARD, 28));
        return t;
    }

    private static void place(GridLayout g, View t, int tw) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = tw;
        lp.height = TILE_H;
        lp.setMargins(8, 8, 8, 8);
        g.addView(t, lp);
    }
}
