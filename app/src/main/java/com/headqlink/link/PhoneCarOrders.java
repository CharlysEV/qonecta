package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.card.MaterialCardView;

/**
 * La fila de órdenes de «Mi coche» (PhoneCar), como la de la app de Leapmotor: botones redondos con su icono y el
 * nombre debajo, cada uno por CarControlActivity (con su confirmación y su PIN). «Abrir» y «Cerrar» son un solo botón
 * cuando se sabe cómo está el coche (CarOrders.LockButton): cerrado, «Abrir» con el candado cerrado en verde tenue;
 * abierto, «Cerrar» con el candado abierto en rojo tenue; sin saberlo, los dos de siempre. Mientras se manda la orden,
 * su botón enseña el progreso; al confirmarla el coche, cambia (CarOrders avisa), y si falla, vuelve a como estaba.
 */
final class PhoneCarOrders {
    private final Activity act;
    private final LinearLayout row;
    /** Lo pintado (las órdenes activadas y CarOrders.LockButton.key): si no cambia, no se rehace la fila. */
    private String painted;
    private final Runnable onChange = this::refresh;

    PhoneCarOrders(Activity act) {
        this.act = act;
        row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(14), 0, dp(4));
        // Solo escucha mientras está en pantalla (la ventanita de la orden va encima: la fila sigue a la vista).
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                CarOrders.addListener(onChange);
                refresh();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                CarOrders.removeListener(onChange);
            }
        });
        refresh();
    }

    View view() {
        return row;
    }

    /** Repinta la fila si ha cambiado lo que enseña (PhoneCar, con cada dato; CarOrders, con cada orden). */
    void refresh() {
        boolean on = CarOrders.enabled(act);
        CarOrders.LockButton lock = CarOrders.lockButton(act);
        String key = (on ? "o" : "-") + lock.key();
        if (key.equals(painted)) return;
        painted = key;
        row.removeAllViews();
        if (lock.single()) {
            add(lock.icon(), Str.get(lock.label()), Str.get(lock.description()), on, lock.what(), lock.color(), lock.tint(), true,
                    lock.busy(lock.what()));
        } else {
            add(R.drawable.hql_ic_cw_unlock, R.string.hql_cw_unlock, on, "unlock", lock.busy("unlock"));
            add(R.drawable.hql_ic_cw_lock, R.string.hql_cw_lock, on, "lock", lock.busy("lock"));
        }
        add(R.drawable.hql_ic_cw_trunk, R.string.hql_cw_trunk, on, "trunk", false);
        add(R.drawable.hql_ic_cw_climate, R.string.hql_cw_climate, on, "climate", false);
        add(R.drawable.hql_ic_cw_find, R.string.hql_cw_find, on, "find", false);
    }

    /** Un botón sin color de estado. */
    private void add(int icon, int label, boolean on, String what, boolean busy) {
        String name = Str.get(label);
        add(icon, name, name, on, what, on ? PhoneCharts.TEXT : PhoneCharts.FAINT, on ? 0xFF16202B : 0xFF111821, false, busy);
    }

    /**
     * Un botón redondo con su icono (o el progreso, mientras se manda su orden) y el nombre debajo. color: el del icono
     * (y, con state, el del filo y el nombre: el botón de abrir/cerrar); fill: el fondo del círculo. Todos se reparten
     * el ancho por igual.
     */
    private void add(int icon, String label, String description, boolean on, String what, int color, int fill, boolean state,
                     boolean busy) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp(2), dp(4), dp(2), dp(4));
        MaterialCardView circle = new MaterialCardView(act);
        circle.setRadius(dp(30));
        circle.setCardElevation(0);
        circle.setCardBackgroundColor(fill);
        circle.setStrokeColor(state ? color : on ? 0xFF2A3A4C : 0xFF1C2631);
        circle.setStrokeWidth(dp(1));
        circle.setRippleColor(ColorStateList.valueOf(0x3300E5C7));
        circle.setContentDescription(description);
        // Mientras se manda, no se manda otra vez.
        circle.setClickable(!busy);
        if (!busy) circle.setOnClickListener(x -> order(what));
        if (busy) {
            ProgressBar p = new ProgressBar(act);
            p.setIndeterminate(true);
            p.setIndeterminateTintList(ColorStateList.valueOf(color));
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(28), dp(28));
            plp.gravity = Gravity.CENTER;
            circle.addView(p, plp);
        } else {
            ImageView img = new ImageView(act);
            img.setImageResource(icon);
            img.setImageTintList(ColorStateList.valueOf(color));
            int pad = dp(16);
            img.setPadding(pad, pad, pad, pad);
            circle.addView(img, new FrameLayout.LayoutParams(dp(60), dp(60)));
        }
        v.addView(circle, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView t = new TextView(act);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        t.setText(label);
        t.setTextColor(state ? color : on ? PhoneCharts.DIM : PhoneCharts.FAINT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, 0);
        // Una línea con setMaxLines: con setSingleLine el texto no encoge (Android no ajusta el tamaño de los que se desplazan).
        t.setMaxLines(1);
        // El círculo ya lo dice (con el estado, si lo hay).
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // «Localizar» y «Maletero» con la letra grande del sistema no caben a 11 sp: encogen hasta caber en su hueco
        // (el nombre ocupa todo el ancho del hueco: con el ancho justo del texto no encogía y salía cortado).
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setAutoSizeTextTypeUniformWithConfiguration(7, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        int line = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, act.getResources().getDisplayMetrics()));
        v.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, line + dp(6)));
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Una orden: la ventanita del widget (confirma lo que abre, pide el clima, enseña el resultado). */
    private void order(String what) {
        act.startActivity(new Intent(act, CarControlActivity.class).putExtra(CarControlActivity.EXTRA_WHAT, what));
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
