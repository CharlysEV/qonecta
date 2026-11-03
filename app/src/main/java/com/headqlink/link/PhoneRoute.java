package com.headqlink.link;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pestaña «Ruta» de la app del móvil (3-11, pedida en el foro LPMFF, #339/#340): planificar en el móvil, antes de subir
 * al coche, lo que hasta ahora solo estaba en el panel del coche (RouteTab): el destino (con el teclado del móvil, Casa,
 * Trabajo, los guardados y los recientes), paradas intermedias e ida y vuelta, la batería al salir (la de la nube de
 * Leapmotor con su antigüedad, o la indicada) y la hora de salida («Ahora», «Hoy a las…», «Mañana a las…»: el tiempo y el
 * viento de cada tramo son los de esa hora). Debajo, el plan: el resumen, el consejo de velocidad, las paradas de carga
 * (con «Cambiar», como en el coche) y el mapa. «Ir con las paradas» abre Google Maps ya; «Usar en el próximo viaje» la
 * guarda (PlannedTrip) y, al conectar, el coche pregunta si se usa.
 *
 * Calcula un borrador aparte (RoutePlanner.draft): no toca el viaje en curso. Los servicios abiertos se piden al pulsar
 * («Buscar», «Calcular»), no con cada letra. La nube se lee solo con la pestaña a la vista y basta con una lectura.
 */
final class PhoneRoute {
    private static final long TICK_MS = 3_000;
    private static final String CLOUD_OWNER = "phone-route";
    /** La nube se suelta con la primera lectura nueva o pasado esto (basta con una al entrar). */
    private static final long CLOUD_READ_MS = 90_000;
    /** Los % que se proponen con un toque. */
    private static final int[] SOC_CHOICES = {80, 90, 100};
    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScrollView root;
    private final LinearLayout col;
    private boolean visible;
    private boolean cloudHeld;
    private long shownAtMs;
    /** El borrador (se crea al calcular la primera vez). */
    private RoutePlanner draft;

    // Lo elegido.
    private RoutePlanner.Place dest;
    private final List<RoutePlanner.Place> vias = new ArrayList<>();
    private boolean round;
    private boolean fromHome;
    /** % al salir elegido a mano (NaN: el propuesto, el de la nube o el indicado). */
    private double socChosen = Double.NaN;
    /** Hora de salida (de pared; 0: ahora). */
    private long departAt;
    /** Lo que se busca es una parada intermedia (si no, el destino). */
    private boolean searchingVia;
    private List<RoutePlanner.Place> results;
    private boolean searching;
    private String searchNote;

    // El cálculo.
    private boolean busy;
    private String error;
    private RoutePlanner.Plan plan;
    private ChargePlanner.Result charge;
    /** Con qué % y capacidad se hizo el plan de carga (para rehacerlo si cambian). */
    private double planSoc = Double.NaN;
    private boolean ticking;
    private int paintedVersion = -1;
    private ChargePlanner.Result paintedCharge;
    private String mapKey = "";
    private WebView map;
    /** La línea de la batería propuesta tal y como se pintó (para no repintar la salida si no cambia). */
    private String departShown = "";

    // Vistas.
    private TextInputEditText field;
    private LinearLayout whereBody;
    private LinearLayout departBody;
    private LinearLayout resultBody;
    private MaterialButton calcButton;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            tick();
            main.postDelayed(this, TICK_MS);
        }
    };

    PhoneRoute(Activity act) {
        this.act = act;
        root = new ScrollView(act);
        root.setFillViewport(true);
        root.setBackgroundColor(0x00000000);
        col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(28));
        root.addView(col);
        build();
    }

    View view() {
        return root;
    }

    void show() {
        visible = true;
        shownAtMs = System.currentTimeMillis();
        // La batería de la nube: una lectura al entrar (se suelta con ella, en tick).
        if (PhoneInsights.hasAccount(act)) {
            CarCloud.acquire(act, CLOUD_OWNER);
            cloudHeld = true;
        }
        paintDepart();
        paintResult();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    void hide() {
        visible = false;
        main.removeCallbacks(ticker);
        releaseCloud();
        hideKeyboard();
    }

    private void releaseCloud() {
        if (!cloudHeld) return;
        cloudHeld = false;
        CarCloud.release(CLOUD_OWNER);
    }

    /**
     * Cada TICK_MS con la pestaña a la vista: la batería propuesta al día (y la nube suelta con su primera lectura), y,
     * con un plan, el repaso pequeño del borrador y su plan de carga (en otro hilo: puede pedir cargadores).
     */
    private void tick() {
        CarCloud.Snapshot cs = CarCloud.car();
        if (cloudHeld && (cs.fetchedAtMs >= shownAtMs || System.currentTimeMillis() - shownAtMs > CLOUD_READ_MS)) releaseCloud();
        // La batería propuesta (y su antigüedad) se repinta si cambia lo que se lee.
        if (!proposedLine().equals(departShown)) paintDepart();
        RoutePlanner d = draft;
        RoutePlanner.Plan p = plan;
        if (d == null || p == null || busy || ticking) return;
        ticking = true;
        double soc = socNow();
        new Thread(() -> {
            ChargePlanner.Result cp = null;
            try {
                d.draftTick();
                cp = chargeFor(d, soc);
            } catch (RuntimeException e) {
                L.w("ruta del móvil: " + e.getClass().getSimpleName());
            }
            ChargePlanner.Result got = cp;
            main.post(() -> {
                ticking = false;
                if (plan != p) return;
                charge = got;
                planSoc = soc;
                if (got != paintedCharge || p.chargerVersion != paintedVersion) paintResult();
            });
        }, "hql-phone-route").start();
    }

    // ------------------------------------------------------------------ pantalla

    private void build() {
        TextView title = text(Str.get(R.string.hql_pr_title), com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
                PhoneCharts.TEXT);
        title.setPadding(dp(4), 0, 0, dp(4));
        col.addView(title);
        TextView intro = text(Str.get(R.string.hql_pr_intro), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                PhoneCharts.DIM);
        intro.setPadding(dp(4), 0, dp(4), dp(12));
        col.addView(intro);

        // ¿A dónde vas?
        MaterialCardView where = card();
        LinearLayout in = inner();
        in.addView(cardTitle(Str.get(R.string.hql_pr_where)));
        field = M3.field(act);
        field.setHint(Str.get(R.string.hql_pr_search_hint));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                search();
                return true;
            }
            return false;
        });
        in.addView(M3.outlined(field));
        whereBody = new LinearLayout(act);
        whereBody.setOrientation(LinearLayout.VERTICAL);
        in.addView(whereBody);
        where.addView(in);
        col.addView(where);

        // Salida: desde dónde, con cuánta batería y cuándo.
        MaterialCardView depart = card();
        LinearLayout din = inner();
        din.addView(cardTitle(Str.get(R.string.hql_pr_depart)));
        departBody = new LinearLayout(act);
        departBody.setOrientation(LinearLayout.VERTICAL);
        din.addView(departBody);
        calcButton = M3.button(act, Str.get(R.string.hql_pr_calc), true);
        calcButton.setOnClickListener(v -> calculate());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        din.addView(calcButton, clp);
        depart.addView(din);
        col.addView(spaced(depart));

        resultBody = new LinearLayout(act);
        resultBody.setOrientation(LinearLayout.VERTICAL);
        col.addView(resultBody);
        paintWhere();
        paintDepart();
        paintResult();
    }

    // ------------------------------------------------------------------ destino y paradas

    private void paintWhere() {
        whereBody.removeAllViews();
        if (dest != null) {
            whereBody.addView(placeRow("📍", dest, () -> {
                dest = null;
                vias.clear();
                round = false;
                dropPlan();
                paintWhere();
            }));
            for (int i = 0; i < vias.size(); i++) {
                final int idx = i;
                whereBody.addView(placeRow(Str.get(R.string.hql_pr_via_n, i + 1), vias.get(i), () -> {
                    vias.remove(idx);
                    dropPlan();
                    paintWhere();
                }));
            }
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            if (vias.size() < SavedPlaces.MAX_VIA) {
                MaterialButton add = M3.button(act, (searchingVia ? "✓ " : "+ ") + Str.get(R.string.hql_pl_add_via), false);
                add.setOnClickListener(v -> {
                    searchingVia = !searchingVia;
                    results = null;
                    searchNote = null;
                    paintWhere();
                    if (searchingVia) focusField();
                });
                row.addView(add);
            }
            whereBody.addView(row);
            MaterialSwitch rt = new MaterialSwitch(act);
            rt.setText(Str.get(R.string.hql_pl_round_q));
            rt.setTextColor(PhoneCharts.DIM);
            rt.setChecked(round);
            rt.setOnCheckedChangeListener((b, on) -> {
                round = on;
                dropPlan();
            });
            whereBody.addView(rt);
            if (searchingVia) {
                TextView hint = small(Str.get(R.string.hql_pl_via_hint));
                hint.setTextColor(PhoneCharts.ACCENT);
                whereBody.addView(hint);
            }
        }
        // Los atajos (Casa, Trabajo, guardados y recientes) con la caja vacía y sin resultados.
        if (results == null && !searching) {
            ChipGroup chips = new ChipGroup(act);
            chips.setPadding(0, dp(8), 0, 0);
            Config c = new Config(act);
            List<SavedPlaces.Saved> saved = SavedPlaces.parse(c.savedPlaces());
            for (SavedPlaces.Saved s : saved) {
                String label = SavedPlaces.HOME.equals(s.tag) ? "🏠 " + Str.get(R.string.hql_pl_home)
                        : SavedPlaces.WORK.equals(s.tag) ? "💼 " + Str.get(R.string.hql_pl_work) : "★ " + s.name;
                chips.addView(chip(label, () -> chooseSaved(s)));
            }
            int n = 0;
            for (RoutePlanner.Place p : PlaceSearch.recents(c.recentPlaces())) {
                if (isSaved(saved, p)) continue; // Casa ya sale con su atajo: no otra vez como reciente
                if (n++ >= 5) break;
                chips.addView(chip("🕓 " + p.name, () -> choose(p)));
            }
            if (chips.getChildCount() > 0) whereBody.addView(chips);
        }
        if (searching) whereBody.addView(small(Str.get(R.string.hql_pr_searching)));
        if (searchNote != null) whereBody.addView(small(searchNote));
        if (results != null) {
            for (RoutePlanner.Place p : results) {
                View r = row(p.name, p.detail == null ? "" : p.detail);
                r.setClickable(true);
                r.setBackgroundResource(android.R.drawable.list_selector_background);
                r.setOnClickListener(v -> choose(p));
                whereBody.addView(r);
            }
        }
    }

    /** Un lugar elegido: el icono o «Parada 1», el nombre, su dirección y una ✕ para quitarlo. */
    private View placeRow(String lead, RoutePlanner.Place p, Runnable remove) {
        LinearLayout v = new LinearLayout(act);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(0, dp(8), 0, 0);
        TextView l = text(lead, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, PhoneCharts.ACCENT);
        l.setPadding(0, 0, dp(10), 0);
        v.addView(l);
        LinearLayout t = new LinearLayout(act);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(p.name, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name);
        if (p.detail != null && !p.detail.isEmpty() && !p.detail.equals(p.name)) {
            TextView d = text(p.detail, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.FAINT);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(d);
        }
        v.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = text("✕", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.FAINT);
        x.setPadding(dp(14), dp(6), dp(6), dp(6));
        x.setClickable(true);
        x.setBackgroundResource(android.R.drawable.list_selector_background);
        x.setOnClickListener(vv -> remove.run());
        v.addView(x);
        return v;
    }

    /** Buscar lo escrito (al pulsar «Buscar» en el teclado, no con cada letra: normas de uso de los buscadores). */
    private void search() {
        String q = field.getText() == null ? "" : field.getText().toString().trim();
        if (!PlaceSearch.worthTyping(q)) return;
        hideKeyboard();
        searching = true;
        results = null;
        searchNote = null;
        paintWhere();
        double[] me = RoutePlanner.lastKnown(act);
        new Thread(() -> {
            List<RoutePlanner.Place> found = PlaceSearch.search(act, q, me == null ? Double.NaN : me[0], me == null ? Double.NaN : me[1]);
            main.post(() -> {
                searching = false;
                results = found;
                searchNote = found.isEmpty() ? Str.get(R.string.hql_pr_no_results) : null;
                paintWhere();
            });
        }, "hql-phone-search").start();
    }

    /** Un reciente que es un sitio guardado: a menos de SavedPlaces.SAME_M, o con su mismo nombre. */
    static boolean isSaved(List<SavedPlaces.Saved> saved, RoutePlanner.Place p) {
        for (SavedPlaces.Saved s : saved) {
            if (RoadInfo.dist(s.lat, s.lon, p.lat, p.lon) < SavedPlaces.SAME_M) return true;
            String name = SavedPlaces.HOME.equals(s.tag) ? Str.get(R.string.hql_pl_home)
                    : SavedPlaces.WORK.equals(s.tag) ? Str.get(R.string.hql_pl_work) : s.name;
            if (name != null && name.equalsIgnoreCase(p.name)) return true;
        }
        return false;
    }

    private void chooseSaved(SavedPlaces.Saved s) {
        // Un viaje guardado (con paradas o de ida y vuelta) se pone entero, como en el coche.
        if (s.isTrip() && !searchingVia) {
            dest = s.place();
            vias.clear();
            vias.addAll(s.via);
            round = s.roundTrip;
            afterChoose();
            return;
        }
        RoutePlanner.Place p = s.place();
        if (SavedPlaces.HOME.equals(s.tag)) p.name = Str.get(R.string.hql_pl_home);
        else if (SavedPlaces.WORK.equals(s.tag)) p.name = Str.get(R.string.hql_pl_work);
        choose(p);
    }

    private void choose(RoutePlanner.Place p) {
        if (searchingVia && dest != null) {
            if (vias.size() < SavedPlaces.MAX_VIA) vias.add(p);
        } else {
            dest = p;
            Config c = new Config(act);
            c.setRecentPlaces(PlaceSearch.remember(c.recentPlaces(), p));
        }
        afterChoose();
    }

    private void afterChoose() {
        searchingVia = false;
        results = null;
        searchNote = null;
        field.setText("");
        hideKeyboard();
        dropPlan();
        paintWhere();
    }

    /** Lo elegido ha cambiado: el plan de antes ya no vale. */
    private void dropPlan() {
        if (plan == null && error == null) return;
        plan = null;
        charge = null;
        error = null;
        paintResult();
    }

    // ------------------------------------------------------------------ salida

    private void paintDepart() {
        if (departBody == null) return;
        departBody.removeAllViews();
        // Desde dónde: aquí (la última posición del móvil) o Casa.
        SavedPlaces.Saved home = SavedPlaces.tagged(SavedPlaces.parse(new Config(act).savedPlaces()), SavedPlaces.HOME);
        if (home == null) fromHome = false;
        if (home != null) {
            ChipGroup from = new ChipGroup(act);
            from.setSingleSelection(true);
            from.addView(choice(Str.get(R.string.hql_pr_from_here), !fromHome, () -> setFromHome(false)));
            from.addView(choice(Str.get(R.string.hql_pr_from_home), fromHome, () -> setFromHome(true)));
            departBody.addView(from);
        }
        // La batería al salir.
        departBody.addView(subhead(Str.get(R.string.hql_pr_battery)));
        double proposed = proposedSoc();
        departShown = proposedLine();
        departBody.addView(small(departShown));
        ChipGroup socs = new ChipGroup(act);
        socs.setSingleSelection(true);
        double now = socNow();
        if (!Double.isNaN(proposed)) {
            long p = Math.round(proposed);
            socs.addView(choice(p + " %", Double.isNaN(socChosen), () -> setSoc(Double.NaN)));
        }
        boolean other = !Double.isNaN(socChosen);
        for (int v : SOC_CHOICES) {
            // Sin nube ni % indicado, el plan sale con el 80 %: marcado.
            boolean on = Double.isNaN(socChosen) ? Double.isNaN(proposed) && v == 80 : Math.round(socChosen) == v;
            if (on) other = false;
            socs.addView(choice(v + " %", on, () -> setSoc(v)));
        }
        String otherLabel = other ? Math.round(now) + " %" : Str.get(R.string.hql_pr_battery_other);
        socs.addView(choice(otherLabel, other, this::askSoc));
        departBody.addView(socs);
        // Cuándo.
        departBody.addView(subhead(Str.get(R.string.hql_pr_when)));
        ChipGroup when = new ChipGroup(act);
        when.setSingleSelection(true);
        boolean tomorrow = departAt > 0 && !sameDay(departAt, System.currentTimeMillis());
        when.addView(choice(Str.get(R.string.hql_pr_now), departAt == 0, () -> {
            departAt = 0;
            dropPlan();
            paintDepart();
        }));
        when.addView(choice(departAt > 0 && !tomorrow ? Str.get(R.string.hql_pr_today_at, hhmm(departAt)) : Str.get(R.string.hql_pr_today_pick),
                departAt > 0 && !tomorrow, () -> pickTime(false)));
        when.addView(choice(tomorrow ? Str.get(R.string.hql_pr_tomorrow_at, hhmm(departAt)) : Str.get(R.string.hql_pr_tomorrow_pick),
                tomorrow, () -> pickTime(true)));
        departBody.addView(when);
        if (calcButton != null) calcButton.setEnabled(!busy);
    }

    private void setFromHome(boolean home) {
        if (fromHome == home) return;
        fromHome = home;
        dropPlan();
        paintDepart();
    }

    private void setSoc(double v) {
        socChosen = v;
        paintDepart();
        // El plan de carga se rehace con el % nuevo en el siguiente tick (la ruta es la misma).
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    private void askSoc() {
        TextInputEditText e = M3.field(act);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(Str.get(R.string.hql_pr_battery_other_title));
        double now = socNow();
        if (!Double.isNaN(now)) e.setText(String.valueOf(Math.round(now)));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        box.addView(M3.outlined(e));
        new MaterialAlertDialogBuilder(act)
                .setTitle(Str.get(R.string.hql_pr_battery))
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        double v = Double.parseDouble(String.valueOf(e.getText()).trim());
                        setSoc(Math.max(1, Math.min(100, v)));
                    } catch (NumberFormatException ignored) {
                        // Vacío o no es un número: como estaba.
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** «Hoy a las…» o «Mañana a las…»: la hora en el reloj del móvil. Hoy, una hora ya pasada es «ahora». */
    private void pickTime(boolean tomorrow) {
        Calendar c = Calendar.getInstance();
        if (departAt > 0) c.setTimeInMillis(departAt);
        else c.add(Calendar.HOUR_OF_DAY, 1);
        new TimePickerDialog(act, (v, h, m) -> {
            long at = departAt(System.currentTimeMillis(), tomorrow, h, m);
            departAt = at;
            dropPlan();
            paintDepart();
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(act)).show();
    }

    /** Puro: la hora de salida (de pared) hoy o mañana a h:m en la zona del móvil; una hora de hoy ya pasada, 0 (ahora). */
    static long departAt(long nowMs, boolean tomorrow, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        if (tomorrow) c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long at = c.getTimeInMillis();
        return at <= nowMs ? 0 : at;
    }

    /** La batería que se propone: la de la nube (si hay cuenta y dato) o la indicada en el coche (Config.socPct). */
    private double proposedSoc() {
        CarCloud.Snapshot cs = CarCloud.car();
        if (cs.hasData() && !Double.isNaN(cs.status.socBest())) return cs.status.socBest();
        return new Config(act).socPct();
    }

    /** «Nube de Leapmotor, hace 2 h: 74 %», «Leyendo la nube…» o «La que indicaste: 80 %». */
    private String proposedLine() {
        CarCloud.Snapshot cs = CarCloud.car();
        long now = System.currentTimeMillis();
        if (cs.hasData() && !Double.isNaN(cs.status.socBest())) {
            return Str.get(R.string.hql_pr_battery_cloud, CarCloud.ago(cs.ageMs(now)), cs.status.socBest());
        }
        if (cloudHeld) return Str.get(R.string.hql_pr_battery_cloud_wait);
        double set = new Config(act).socPct();
        return Double.isNaN(set) ? Str.get(R.string.hql_pr_battery_none) : Str.get(R.string.hql_pr_battery_manual, set);
    }

    /** El % con el que se planifica: el elegido o el propuesto (sin ninguno, el 80 %). */
    private double socNow() {
        if (!Double.isNaN(socChosen)) return socChosen;
        double p = proposedSoc();
        return Double.isNaN(p) ? 80 : p;
    }

    // ------------------------------------------------------------------ cálculo

    /** «Calcular ruta»: el borrador desde aquí (o Casa) al destino, en otro hilo. */
    private void calculate() {
        if (busy) return;
        if (dest == null) {
            error = Str.get(R.string.hql_pr_no_dest);
            paintResult();
            return;
        }
        if (draft == null) draft = RoutePlanner.draft(act);
        RoutePlanner d = draft;
        busy = true;
        error = null;
        plan = null;
        charge = null;
        paintDepart();
        paintResult();
        RoutePlanner.Place to = dest;
        List<RoutePlanner.Place> via = new ArrayList<>(vias);
        boolean rt = round;
        long at = departAt;
        double soc = socNow();
        boolean home = fromHome;
        new Thread(() -> {
            RoutePlanner.Plan p = null;
            ChargePlanner.Result cp = null;
            String fail = null;
            try {
                RoutePlanner.Place origin = origin(home);
                if (origin == null) throw new NoOriginException();
                p = d.planDraft(origin, to, via, rt, at);
                cp = chargeFor(d, soc);
            } catch (NoOriginException e) {
                fail = "";
            } catch (Exception e) {
                L.w("ruta del móvil: " + Http.safeError(e));
                fail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (RoutePlanner.noNetwork(e)) fail = Str.get(R.string.hql_pr_no_network);
            }
            RoutePlanner.Plan got = p;
            ChargePlanner.Result gotCp = cp;
            String why = fail;
            main.post(() -> {
                busy = false;
                plan = got;
                charge = gotCp;
                planSoc = soc;
                error = why == null ? null : why.isEmpty() ? Str.get(R.string.hql_pr_no_origin) : Str.get(R.string.hql_pr_failed, why);
                paintDepart();
                paintResult();
                // El resultado sale debajo del botón, fuera de la vista: se baja hasta él.
                root.post(() -> root.smoothScrollTo(0, resultBody.getTop()));
            });
        }, "hql-phone-plan").start();
    }

    /** No se sabe desde dónde se sale (sin posición y sin Casa). */
    private static final class NoOriginException extends Exception {
    }

    /** Lo que se espera a una posición nueva del móvil si no tiene ninguna reciente. */
    private static final long FIX_WAIT_MS = 12_000;

    /**
     * La salida (fuera del hilo principal): la última posición conocida del móvil o, si no tiene ninguna reciente, una
     * nueva (como mucho FIX_WAIT_MS); si no, o elegida, Casa. null si nada.
     */
    private RoutePlanner.Place origin(boolean fromHome) {
        SavedPlaces.Saved home = SavedPlaces.tagged(SavedPlaces.parse(new Config(act).savedPlaces()), SavedPlaces.HOME);
        double[] me = fromHome ? null : RoutePlanner.lastKnown(act);
        if (me == null && !fromHome) me = freshFix(act, FIX_WAIT_MS);
        if (me != null) {
            RoutePlanner.Place o = new RoutePlanner.Place();
            o.name = Str.get(R.string.hql_pr_from_here);
            o.detail = "";
            o.lat = me[0];
            o.lon = me[1];
            return o;
        }
        return home == null ? null : home.place();
    }

    /** Una posición nueva del móvil (red o GPS), esperando como mucho waitMs: {lat, lon}, o null (sin permiso o sin fix). */
    @android.annotation.SuppressLint("MissingPermission")
    static double[] freshFix(android.content.Context c, long waitMs) {
        android.location.LocationManager lm = c.getSystemService(android.location.LocationManager.class);
        if (lm == null || android.os.Build.VERSION.SDK_INT < 30) return null;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        android.location.Location[] got = new android.location.Location[1];
        android.os.CancellationSignal cancel = new android.os.CancellationSignal();
        try {
            // A todos los que estén encendidos a la vez: vale la primera que llegue.
            for (String prov : new String[]{"fused", android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER}) {
                try {
                    if (!lm.isProviderEnabled(prov)) continue;
                    lm.getCurrentLocation(prov, cancel, c.getMainExecutor(), l -> {
                        if (l == null || got[0] != null) return;
                        got[0] = l;
                        done.countDown();
                    });
                } catch (IllegalArgumentException e) {
                    // Ese proveedor no existe en este móvil.
                }
            }
            done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            cancel.cancel();
        } catch (SecurityException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        android.location.Location l = got[0];
        return l == null ? null : new double[]{l.getLatitude(), l.getLongitude()};
    }

    /** El plan de carga del borrador con el % elegido y la capacidad del coche (la de la nube, si la hay). */
    private static ChargePlanner.Result chargeFor(RoutePlanner d, double soc) {
        CarCloud.Snapshot cs = CarCloud.car();
        boolean data = cs.hasData();
        double cap = data && cs.capacityKwh > 0 ? cs.capacityKwh : EnergyModel.usableKwh();
        boolean reev = (data && cs.status.reev()) || CarSpec.current().reev;
        return d.chargePlan(soc, cap, false, reev, false);
    }

    // ------------------------------------------------------------------ el plan

    private void paintResult() {
        if (resultBody == null) return;
        RoutePlanner.Plan p = plan;
        ChargePlanner.Result cp = charge;
        paintedCharge = cp;
        paintedVersion = p == null ? -1 : p.chargerVersion;
        resultBody.removeAllViews();
        PlannedTrip saved = PlannedTrip.peek(act);
        if (saved != null) resultBody.addView(spaced(savedCard(saved)));
        if (busy) {
            resultBody.addView(spaced(message(Str.get(R.string.hql_pr_calculating), PhoneCharts.DIM)));
            dropMap();
            return;
        }
        if (error != null) {
            resultBody.addView(spaced(message(error, PhoneCharts.WARN)));
            dropMap();
            return;
        }
        if (p == null) {
            dropMap();
            return;
        }
        MaterialCardView card = card();
        LinearLayout in = inner();
        TextView title = text(p.destination, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, PhoneCharts.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        in.addView(title);
        String drive = DriveTab.duration(p.totalSeconds);
        boolean stops = cp != null && !cp.stops.isEmpty();
        // «Sin paradas» solo si de verdad no hacen falta (sin cargador alcanzable, o sin plan en un REEV, no se dice).
        String sum = stops ? Str.get(R.string.hql_pr_summary, p.totalKm, drive, DriveTab.duration(Math.round(cp.chargeMinutes * 60)))
                : cp != null && cp.outcome == ChargePlanner.Outcome.NO_STOPS ? Str.get(R.string.hql_pr_summary_nostop, p.totalKm, drive)
                : Str.get(R.string.hql_pr_summary_drive, p.totalKm, drive);
        in.addView(text(sum, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, PhoneCharts.TEXT));
        if (cp != null && cp.outcome != ChargePlanner.Outcome.NO_CHARGER) {
            TextView arrive = text(Str.get(R.string.hql_pr_arrive, cp.arrivalPct), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                    cp.arrivalPct < new Config(act).planArrivePct() - 0.5 ? PhoneCharts.WARN : PhoneCharts.OK);
            arrive.setPadding(0, dp(4), 0, 0);
            in.addView(arrive);
        }
        // La salida y el tiempo en el destino a la hora de llegada.
        String when = p.departAtMs > 0 ? Str.get(sameDay(p.departAtMs, System.currentTimeMillis()) ? R.string.hql_pr_today_at
                : R.string.hql_pr_tomorrow_at, hhmm(p.departAtMs)) : Str.get(R.string.hql_pr_now);
        String wx = p.destCode >= 0 && !Double.isNaN(p.destTemp)
                ? " · " + Str.get(R.string.hql_pr_weather, RouteTab.weatherText(p.destCode), p.destTemp) : "";
        in.addView(small(Str.get(R.string.hql_pr_depart) + ": " + when + wx));
        TextView note = small(Str.get(R.string.hql_pr_osrm_note));
        note.setPadding(0, dp(8), 0, 0);
        in.addView(note);
        if (p.flatElevation) in.addView(small(Str.get(R.string.hql_pr_flat)));

        if (cp == null) {
            boolean reev = CarSpec.current().reev || (CarCloud.car().hasData() && CarCloud.car().status.reev());
            if (reev) in.addView(warn(Str.get(R.string.hql_pr_reev), PhoneCharts.DIM));
        } else {
            paintPlan(in, p, cp);
        }
        // El mapa: el trazado y las paradas (sin perfil de alturas).
        in.addView(mapView(p, cp));
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);
        MaterialButton use = M3.button(act, Str.get(R.string.hql_pr_use_next), true);
        use.setOnClickListener(v -> saveForNext());
        actions.addView(use, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Sin paradas de carga, «Ir con las paradas» no tiene sentido: solo «Ir».
        MaterialButton go = M3.button(act, Str.get(stops ? R.string.hql_plan_go : R.string.hql_go), false);
        go.setOnClickListener(v -> NavApps.goWithStops(act, p, charge));
        actions.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        in.addView(actions);
        card.addView(in);
        resultBody.addView(spaced(card));
    }

    /**
     * «Usar en el próximo viaje»: la ruta (destino, paradas, ida y vuelta, las paradas fijadas a mano y la velocidad
     * elegida) y lo que salió aquí, para el coche (PlannedTrip). Ya en el coche, la pregunta sale en su pantalla ahora.
     */
    private void saveForNext() {
        RoutePlanner.Plan p = plan;
        RoutePlanner d = draft;
        if (p == null || d == null || dest == null) return;
        ChargePlanner.Result cp = charge;
        PlannedTrip t = new PlannedTrip();
        t.dest = dest;
        t.vias = new ArrayList<>(vias);
        t.round = round;
        t.pins = d.pinsNow();
        t.speedCap = cp != null ? cp.speedCap : 0;
        t.departAtMs = p.departAtMs;
        t.savedAtMs = System.currentTimeMillis();
        t.stops = PlannedTrip.stopsOf(cp);
        t.socPct = planSoc;
        t.arrivalPct = cp != null ? cp.arrivalPct : Double.NaN;
        t.totalKm = p.totalKm;
        t.route = NavApps.points(p, cp);
        PlannedTrip.save(act, t);
        if (RoutePlanner.get() != null) RoutePlanner.offerPlanned(act, 0);
        paintResult();
        root.post(() -> root.smoothScrollTo(0, resultBody.getTop()));
    }

    /** La ruta guardada para el próximo viaje: a dónde, con cuántas paradas, hasta cuándo vale y «Quitar». */
    private View savedCard(PlannedTrip t) {
        MaterialCardView card = card();
        card.setStrokeColor(0xFF1E5A4E);
        LinearLayout in = inner();
        in.addView(text("✓ " + Str.get(R.string.hql_pr_saved_title), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                PhoneCharts.OK));
        String until = android.text.format.DateFormat.format(
                android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEjmm"), t.expiresAtMs()).toString();
        TextView body = text(Str.get(R.string.hql_pr_saved_text, t.dest.name, PlannedTrip.stopsText(t.stops.size()), until),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, PhoneCharts.DIM);
        body.setPadding(0, dp(4), 0, 0);
        in.addView(body);
        if (RoutePlanner.get() != null) in.addView(small(Str.get(R.string.hql_pr_saved_now)));
        in.addView(textButton(Str.get(R.string.hql_pr_remove), () -> {
            PlannedTrip.clear(act);
            L.i("ruta del móvil: quitada la guardada para el próximo viaje");
            paintResult();
        }));
        card.addView(in);
        return card;
    }

    /** El plan de carga: el consejo de velocidad, los tramos del viaje y cada parada con «Cambiar» e «Ir». */
    private void paintPlan(LinearLayout in, RoutePlanner.Plan p, ChargePlanner.Result cp) {
        // Sin paradas no se repite «llegas con X %»: ya lo dicen el resumen y la línea verde de arriba.
        if (cp.outcome == ChargePlanner.Outcome.NO_CHARGER) {
            in.addView(warn(cp.stops.isEmpty() ? Str.get(R.string.hql_plan_impossible)
                    : Str.get(R.string.hql_plan_summary_stuck, cp.stops.size(), DriveTab.duration(Math.round(cp.chargeMinutes * 60)),
                    cp.kmMark(cp.stops.get(cp.stops.size() - 1).km)), PhoneCharts.WARN));
        }
        double cost = ChargePlanner.cost(cp, new Config(act).fastChargePrice());
        if (!cp.stops.isEmpty() && cost >= 0.5) in.addView(small(Str.get(R.string.hql_pr_cost, Money.format(cost, 0))));
        // Consejo de velocidad, o la elegida con su «Quitar».
        if (cp.speedCap > 0) {
            in.addView(warn(Str.get(R.string.hql_speed_capped, cp.speedCap), PhoneCharts.OK));
            in.addView(textButton(Str.get(R.string.hql_speed_uncap), () -> setCap(0)));
        } else {
            String t = RouteTab.speedText(cp.speed);
            if (t != null) {
                in.addView(warn(t, cp.speed.rescue ? PhoneCharts.WARN : PhoneCharts.OK));
                if (cp.speed.rescue || cp.speed.stopsAfter < cp.speed.stopsBefore) {
                    int kmh = cp.speed.capKmh;
                    in.addView(textButton(Str.get(R.string.hql_speed_go, kmh), () -> setCap(kmh)));
                }
            }
        }
        // Viaje de varios tramos: con cuánto se llega a cada punto.
        if (p.multi() || p.roundTrip) {
            double[] at = ChargePlanner.arrivals(cp, p.waypointKm);
            for (int i = 0; i < p.waypoints.size() && i < at.length; i++) {
                RoutePlanner.Place w = p.waypoints.get(i);
                boolean last = i == p.waypoints.size() - 1;
                String what = i == p.destIndex ? w.name : last && p.roundTrip ? Str.get(R.string.hql_pl_back) : w.name;
                TextView t = small(Str.get(R.string.hql_pl_leg_line, what, p.waypointKm[i], Math.max(0, at[i])));
                t.setTextColor(at[i] < 10 ? PhoneCharts.WARN : PhoneCharts.DIM);
                in.addView(t);
            }
        }
        if (cp.stops.isEmpty()) return;
        in.addView(subhead(Str.get(R.string.hql_plan_title)));
        int n = 1;
        for (ChargePlanner.Stop st : cp.stops) {
            final int idx = n - 1;
            LinearLayout block = new LinearLayout(act);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(10), 0, 0);
            TextView name = text(n++ + ". " + (draft != null && draft.isPinned(st.charger) ? "📌 " : "") + RoutePlanner.chargerName(st.charger),
                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            block.addView(name);
            boolean low = ChargePlanner.lowVolts(st.charger, cp.carVolts);
            String line = Str.get(R.string.hql_plan_stop_line, cp.kmMark(st.km), st.arrivePct, st.departPct,
                    DriveTab.duration(Math.round(st.minutes * 60))) + " · " + RouteTab.kwText(st.charger)
                    + (low ? " · " + Str.get(R.string.hql_charger_low_volts, st.charger.maxVolts) : "");
            block.addView(text(line, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, low ? PhoneCharts.WARN : PhoneCharts.ACCENT));
            double kwh = Math.max(0, st.departPct - st.arrivePct) / 100.0 * cp.capacityKwh;
            double stopCost = RouteTab.stopCost(st.charger, kwh, st.minutes);
            if (!Double.isNaN(st.charger.adHocKwh)) {
                String price = RouteTab.adHocText(st.charger)
                        + (Double.isNaN(stopCost) ? "" : " · " + Str.get(R.string.hql_adhoc_stop_cost, Money.formatEur(stopCost)));
                block.addView(text(price, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.DIM));
            }
            LinearLayout btns = new LinearLayout(act);
            btns.setGravity(Gravity.END);
            MaterialButton change = M3.button(act, Str.get(R.string.hql_plan_change), false);
            change.setOnClickListener(v -> openStopChange(idx));
            btns.addView(change);
            MaterialButton go = M3.button(act, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> NavApps.go(act, st.charger.lat, st.charger.lon));
            btns.addView(go);
            block.addView(btns);
            in.addView(block);
        }
    }

    /** La velocidad máxima del borrador (0: quitarla): el plan de carga se rehace con ella. */
    private void setCap(int kmh) {
        RoutePlanner d = draft;
        if (d == null) return;
        d.setDraftSpeedCap(kmh);
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    /**
     * «Cambiar» una parada, como en el coche (RouteTab.openStopChange): los cargadores de ese tramo a los que se llega, con
     * cuánto y lo que cambia el tiempo parado; «Usar esta» la fija para este viaje y viaja con la ruta al coche.
     */
    private void openStopChange(int idx) {
        RoutePlanner d = draft;
        ChargePlanner.Result cp = charge;
        if (d == null || cp == null || idx >= cp.stops.size()) return;
        ChargePlanner.Stop st = cp.stops.get(idx);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(4), dp(22), dp(8));
        body.addView(small(Str.get(R.string.hql_plan_change_now, RoutePlanner.chargerName(st.charger), cp.kmMark(st.km), st.arrivePct)));
        androidx.appcompat.app.AlertDialog[] dlg = new androidx.appcompat.app.AlertDialog[1];
        if (d.isPinned(st.charger)) {
            body.addView(textButton(Str.get(R.string.hql_plan_change_unpin), () -> {
                d.unpin(st.charger);
                if (dlg[0] != null) dlg[0].dismiss();
                refreshCharge();
            }));
        }
        List<ChargePlanner.Alternative> alts = d.stopAlternatives(idx);
        boolean any = false;
        for (ChargePlanner.Alternative a : alts) any |= !a.current;
        if (!any) body.addView(small(Str.get(R.string.hql_plan_change_none)));
        for (ChargePlanner.Alternative a : alts) {
            RoutePlanner.Charger c = a.charger;
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(10), 0, dp(4));
            item.addView(text(RoutePlanner.chargerName(c), com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                    a.current ? PhoneCharts.ACCENT : PhoneCharts.TEXT));
            item.addView(text(Str.get(R.string.hql_plan_change_line, cp.kmMark(c.kmAlong), a.arrivePct, RouteTab.kwText(c)),
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.DIM));
            if (a.current) {
                item.addView(text(Str.get(R.string.hql_plan_change_in_plan), com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
                        PhoneCharts.ACCENT));
            } else {
                item.addView(text(RouteTab.deltaText(a, cp), com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                        !a.works() ? PhoneCharts.BAD : a.deltaMin > 0.5 ? PhoneCharts.WARN : PhoneCharts.OK));
                if (a.works()) {
                    item.addView(textButton(Str.get(R.string.hql_plan_change_use), () -> {
                        d.pinStop(st.charger, c);
                        if (dlg[0] != null) dlg[0].dismiss();
                        refreshCharge();
                    }));
                }
            }
            body.addView(item);
        }
        body.addView(small(Str.get(R.string.hql_plan_change_note)));
        ScrollView sv = new ScrollView(act);
        sv.addView(body);
        dlg[0] = new MaterialAlertDialogBuilder(act)
                .setTitle(Str.get(R.string.hql_plan_change_title, idx + 1))
                .setView(sv)
                .setNegativeButton(Str.get(R.string.hql_close), null)
                .show();
    }

    /** El plan de carga con lo elegido ahora (una parada fijada o quitada), ya. */
    private void refreshCharge() {
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    // ------------------------------------------------------------------ mapa

    /** El mapa de la ruta (RouteMap.html, el del coche) en una vista; se rehace solo si cambian la ruta o las paradas. */
    private View mapView(RoutePlanner.Plan p, ChargePlanner.Result cp) {
        StringBuilder k = new StringBuilder(String.valueOf(System.identityHashCode(p)));
        if (cp != null) for (ChargePlanner.Stop s : cp.stops) k.append('/').append(Math.round(s.km)).append(':').append(Math.round(s.departPct));
        String key = k.toString();
        if (map != null && key.equals(mapKey)) {
            ViewGroup parent = (ViewGroup) map.getParent();
            if (parent != null) parent.removeView(map);
        } else {
            dropMap();
            try {
                // Dentro de la lista: deslizar arriba y abajo mueve la lista (el mapa ocupa casi la pantalla y, si se quedara
                // el gesto, no se llegaría a los botones de debajo); de lado y con dos dedos, el mapa.
                map = RouteMap.web(act, RouteMap.html(p, cp, draft));
                mapKey = key;
            } catch (RuntimeException e) {
                L.w("ruta del móvil: sin WebView para el mapa (" + e.getClass().getSimpleName() + ")");
                map = null;
                mapKey = "";
                return new View(act);
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        lp.topMargin = dp(12);
        map.setLayoutParams(lp);
        return map;
    }

    private void dropMap() {
        if (map == null) return;
        ViewGroup parent = (ViewGroup) map.getParent();
        if (parent != null) parent.removeView(map);
        map.destroy();
        map = null;
        mapKey = "";
    }

    // ------------------------------------------------------------------ piezas

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance();
        x.setTimeInMillis(a);
        Calendar y = Calendar.getInstance();
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private static String hhmm(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(new Date(ms));
    }

    private void focusField() {
        field.requestFocus();
        InputMethodManager imm = act.getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = act.getSystemService(InputMethodManager.class);
        if (imm != null && field != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private Chip chip(String label, Runnable tap) {
        Chip c = new Chip(act, null, com.google.android.material.R.attr.chipStyle);
        c.setText(label);
        c.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.setMaxWidth(dp(220));
        c.setOnClickListener(v -> tap.run());
        return c;
    }

    /** Una opción de una fila de opciones (la elegida, marcada). */
    private Chip choice(String label, boolean on, Runnable tap) {
        Chip c = new Chip(act, null, com.google.android.material.R.attr.chipStyle);
        c.setText(label);
        c.setCheckable(true);
        c.setChecked(on);
        c.setOnClickListener(v -> tap.run());
        return c;
    }

    private MaterialButton textButton(String label, Runnable tap) {
        MaterialButton b = M3.button(act, label, false);
        b.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        b.setLayoutParams(lp);
        return b;
    }

    private View message(String s, int color) {
        MaterialCardView card = card();
        LinearLayout in = inner();
        in.addView(text(s, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, color));
        card.addView(in);
        return card;
    }

    private TextView warn(String s, int color) {
        TextView t = text(s, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, color);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private TextView cardTitle(String s) {
        return text(s, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, PhoneCharts.TEXT);
    }

    /** Título pequeño dentro de una tarjeta, con una raya encima (como en «Mi coche»). */
    private View subhead(String s) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        View line = new View(act);
        line.setBackgroundColor(PhoneCharts.GRID);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.topMargin = dp(14);
        llp.bottomMargin = dp(8);
        v.addView(line, llp);
        v.addView(text(s, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, PhoneCharts.DIM));
        return v;
    }

    private View row(String title, String line) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(8), 0, dp(4));
        TextView t = text(title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, PhoneCharts.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(t);
        if (!line.isEmpty()) {
            TextView l = text(line, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.DIM);
            l.setMaxLines(2);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.addView(l);
        }
        return v;
    }

    private TextView small(String s) {
        TextView t = text(s, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, PhoneCharts.FAINT);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private LinearLayout inner() {
        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(18), dp(16), dp(18), dp(16));
        return in;
    }

    private View spaced(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
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
