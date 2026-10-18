package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.aap.NavTap;

/**
 * Tarjeta flotante sobre Android Auto con la próxima parada del plan de carga (o, sin paradas, con qué % se llega al
 * destino). CarUi la coloca encima de la zona de AA y GlFrameRelay vuelve a pintar su rectángulo sobre la imagen de AA.
 * Un toque abre la ruta; mantenerla pulsada y arrastrar la mueve (la posición se guarda). La ✕ de la derecha termina la
 * ruta (aparcado algo antes del destino: RoutePlanner.endRoute).
 */
final class FloatCard extends LinearLayout {
    static final int W = 470;
    static final int H = 112;
    private static final long LONG_PRESS_MS = 450;
    private static final float SLOP = 22;

    interface Listener {
        void onTap();

        /** Arrastrando: desplazamiento desde donde empezó (px de la pantalla del coche). */
        void onDrag(float dx, float dy);

        void onDrop();

        /** La ✕: terminar la ruta. */
        void onEnd();
    }

    private final Listener listener;
    private final Icon icon;
    private final TextView title;
    private final TextView sub;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean dragging;
    private final Runnable longPress = () -> {
        dragging = true;
        paintBackground();
    };

    FloatCard(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(22, 14, 26, 14);
        icon = new Icon(c);
        addView(icon, new LayoutParams(64, 64));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.setPadding(18, 0, 0, 0);
        title = CarStyle.text(c, "", 27, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Una línea, con la letra más pequeña si el nombre es largo («Iberdrola · Guadalajara», 18-10) y solo si ni así
        // cabe, cortado. Con setSingleLine el ajuste de tamaño no funciona: maxLines 1.
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, CarStyle.textPx(19), CarStyle.textPx(27), 1,
                android.util.TypedValue.COMPLEX_UNIT_PX);
        texts.addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sub = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);
        addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Terminar la ruta: su propio botón (un toque en el resto de la tarjeta sigue abriendo la ruta).
        TextView end = CarStyle.text(c, "✕", 28, CarStyle.TEXT_DIM);
        end.setGravity(Gravity.CENTER);
        end.setBackground(CarStyle.round(CarKit.alpha(CarStyle.TEXT_DIM, 0.16f), 30));
        end.setContentDescription(Str.get(R.string.hql_route_end));
        end.setOnClickListener(v -> listener.onEnd());
        LayoutParams elp = new LayoutParams(60, 60);
        elp.leftMargin = 10;
        addView(end, elp);
        paintBackground();
    }

    /** Lee el plan de carga. false si no hay nada que enseñar (sin ruta o sin plan). */
    boolean update() {
        RoutePlanner r = RoutePlanner.get();
        // Solo con ruta en el planificador (al quitar el destino se va) y con Google Maps guiando (al terminar o quitar la
        // navegación, también): antes bastaba el último plan de carga, que se quedaba puesto.
        if (r == null || r.plan() == null || !navigating()) return false;
        ChargePlanner.Result cp = r.lastChargePlan();
        if (cp == null) return false;
        ChargePlanner.Stop st = cp.next();
        boolean stuck = cp.outcome == ChargePlanner.Outcome.NO_CHARGER;
        if (st != null) {
            // Con paradas, pero tras la última ya no se llega a ningún cargador (con los filtros): en ámbar, sin un «llegas
            // con» que haga pensar que está todo bien (17-10, un viaje largo).
            icon.set(Icon.BOLT, stuck ? CarKit.AMBER : CarKit.ACCENT);
            title.setText(RoutePlanner.chargerName(st.charger));
            sub.setText(stuck ? Str.get(R.string.hql_float_stop_stuck, cp.kmTo(st))
                    : withKmPerPct(Str.get(R.string.hql_float_stop_sub, cp.kmTo(st), st.arrivePct), r, cp));
        } else if (stuck) {
            // «Sin cargador» y debajo si se llega: nunca «llegas con −1 %» (la pestaña Ruta dice «No llegas sin cargar»).
            icon.set(Icon.BOLT, CarKit.AMBER);
            title.setText(Str.get(R.string.hql_float_no_charger));
            sub.setText(cp.arrivalPct < 0.5 ? Str.get(R.string.hql_float_no_charger_none) : Str.get(R.string.hql_float_no_charger_pct, cp.arrivalPct));
        } else {
            icon.set(Icon.FLAG, CarKit.ACCENT);
            title.setText(Str.get(R.string.hql_float_dest, cp.arrivalPct));
            NavTap.Info nav = DemoMode.navInfo();
            sub.setText(withKmPerPct(cp.speedCap > 0 ? Str.get(R.string.hql_float_at_speed, cp.speedCap)
                    : nav != null && nav.eta != null ? Str.get(R.string.hql_drive_arrival) + " " + nav.eta
                    : Str.get(R.string.hql_float_no_stop), r, cp));
        }
        return true;
    }

    /** La segunda línea con los km por cada 1 % detrás («a 46 km · 85 % · 4,8 km/%»), si se saben. */
    private static String withKmPerPct(String base, RoutePlanner r, ChargePlanner.Result cp) {
        String k = kmPerPctText(r.liveKmPerPct(), cp.kmPerPctPlanned());
        return k == null ? base : base + " · " + k;
    }

    /**
     * Puro: «4,8 km/%» con lo real del viaje (18-10, idea de una usuaria: cuántos km se hacen con cada punto de batería)
     * o, hasta tenerlo, «≈ 4,8 km/%» con lo previsto del plan; null sin nada.
     */
    static String kmPerPctText(double real, double planned) {
        double v = !Double.isNaN(real) ? real : planned;
        if (Double.isNaN(v) || v <= 0) return null;
        String n = String.format(java.util.Locale.getDefault(), "%.1f", v);
        return (Double.isNaN(real) ? "≈ " : "") + Str.get(R.string.hql_float_km_pct, n);
    }

    /**
     * ¿Guía Google Maps? Lo dice Android Auto (su estado de navegación) o la notificación de Maps en el móvil mientras
     * siga puesta: con current(), que caduca a los 2 min sin cambios, la tarjeta se iba con el coche parado (un atasco, un
     * semáforo largo; 18-10).
     */
    static boolean navigating() {
        if (DemoMode.active()) return true;
        NavTap.Info nav = NavTap.getInfo();
        return (nav != null && nav.active) || MapsNavListener.ongoing() != null;
    }

    private void paintBackground() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(24);
        d.setColor(CarKit.alpha(CarKit.SURFACE, 1f));
        d.setStroke(dragging ? 4 : 2, dragging ? CarStyle.ACCENT : CarKit.OUTLINE);
        setBackground(d);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                moved = false;
                dragging = false;
                postDelayed(longPress, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (dragging) {
                    listener.onDrag(dx, dy);
                } else if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) {
                    // Arrastre sin mantener pulsado: ni toque ni mover (no se mueve por un roce).
                    moved = true;
                    removeCallbacks(longPress);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    paintBackground();
                    listener.onDrop();
                } else if (!moved) {
                    listener.onTap();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    paintBackground();
                    listener.onDrop();
                }
                return true;
            default:
                return true;
        }
    }

    /** Rayo (próxima parada) o bandera (destino), en un círculo. */
    private static final class Icon extends View {
        static final int BOLT = 0;
        static final int FLAG = 1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int kind = BOLT;
        private int color = CarKit.ACCENT;

        Icon(Context c) {
            super(c);
        }

        void set(int kind, int color) {
            if (kind == this.kind && color == this.color) return;
            this.kind = kind;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(CarKit.alpha(color, 0.22f));
            cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            if (kind == FLAG) CarIcons.flag(cv, getWidth() / 2f, getHeight() / 2f, r * 1.1f, color, p);
            else CarIcons.bolt(cv, getWidth() / 2f, getHeight() / 2f, r * 1.2f, color, p);
        }
    }
}
