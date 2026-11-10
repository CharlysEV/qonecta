package com.headqlink.link;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * Lo que comparten las pestañas del móvil (Viajes, Home Assistant, Mi coche y Ruta) para verse iguales (10-11): el
 * margen de la página, el título de arriba y los estados vacíos (icono suave, una frase corta en negrita y la
 * explicación en gris), entre ellos los de la cuenta de Leapmotor: sin cuenta, sesión cerrada o esperando el primer dato.
 */
final class PhoneUi {
    private PhoneUi() {
    }

    /** Margen lateral de todas las pestañas, en dp (el mismo que el de «Conexión» en hql_activity_home.xml). */
    static final int SIDE = 16;

    /** El relleno de la columna de una pestaña. */
    static void pad(LinearLayout col) {
        col.setPadding(dp(col.getContext(), SIDE), dp(col.getContext(), 16), dp(col.getContext(), SIDE), dp(col.getContext(), 28));
    }

    /** El título de arriba de una pestaña; `below` es el hueco hasta lo siguiente, en dp. */
    static TextView title(Context c, String s, int below) {
        TextView t = new TextView(c);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        t.setText(s);
        t.setTextColor(PhoneCharts.TEXT);
        t.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, below));
        return t;
    }

    // ------------------------------------------------------------------ estados vacíos

    /**
     * Un estado vacío centrado: el icono (un vector de la app, teñido) en su círculo, el título en negrita, la explicación
     * en gris y más pequeña y, si lo hay, el botón.
     */
    static LinearLayout empty(Context c, int icon, String title, String body, String button, Runnable tap) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 6));
        ImageView img = new ImageView(c);
        img.setImageResource(icon);
        img.setImageTintList(ColorStateList.valueOf(PhoneCharts.FAINT));
        img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xFF16202B);
        img.setBackground(bg);
        int p = dp(c, 12);
        img.setPadding(p, p, p, p);
        v.addView(img, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
        TextView t = new TextView(c);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setText(title);
        t.setTextColor(PhoneCharts.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 10), 0, 0);
        v.addView(t);
        if (body != null && !body.isEmpty()) {
            TextView b = new TextView(c);
            b.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            b.setText(body);
            b.setTextColor(PhoneCharts.DIM);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(c, 4), 0, 0);
            v.addView(b);
        }
        if (button != null) {
            MaterialButton go = new MaterialButton(c);
            go.setText(button);
            go.setOnClickListener(x -> tap.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(c, 12);
            v.addView(go, lp);
        }
        return v;
    }

    /** Un texto de «aún no hay…» de los de siempre, como estado vacío: su primera frase de título y el resto debajo. */
    static LinearLayout empty(Context c, int icon, String text) {
        String[] parts = split(text);
        return empty(c, icon, parts[0], parts[1], null, null);
    }

    /**
     * Puro: «Aún no hay recorridos: aquí se guardan los viajes…» en {«Aún no hay recorridos», «Aquí se guardan los
     * viajes…»}. Se corta en los primeros dos puntos o punto y seguido (también los del francés, « : », y los del chino);
     * sin ellos, todo es título.
     */
    static String[] split(String text) {
        String s = text == null ? "" : text.trim();
        int at = -1;
        int len = 0;
        for (String sep : new String[]{" : ", ": ", ". ", "：", "。"}) {
            int i = s.indexOf(sep);
            if (i > 0 && i + sep.length() < s.length() && (at < 0 || i < at)) {
                at = i;
                len = sep.length();
            }
        }
        if (at < 0) return new String[]{s, ""};
        String body = s.substring(at + len).trim();
        return new String[]{s.substring(0, at).trim(), body.substring(0, 1).toUpperCase(Locale.getDefault()) + body.substring(1)};
    }

    // ------------------------------------------------------------------ la cuenta de Leapmotor

    /** Lo que hay de la nube de Leapmotor para pintar: datos o por qué no los hay. */
    enum Cloud {
        DATA,
        /** Sin cuenta configurada (o apagada en el móvil). */
        NO_ACCOUNT,
        /** La sesión caducó o no se puede abrir: hasta volver a entrar no llega nada. */
        CLOSED,
        /** Con cuenta y aún sin el primer dato. */
        WAITING
    }

    /**
     * Puro: qué enseñar. `account` es PhoneInsights.hasAccount y `relogin` que la sesión guardada pide volver a entrar
     * (CarCloudStore.reloginReason, como en CarCloudActivity). Con la sesión cerrada la lectura no llega nunca: no se
     * dice «Leyendo…» para siempre (visto en el móvil).
     */
    static Cloud cloud(boolean account, CarCloud.Snapshot s, boolean relogin) {
        if (!account) return Cloud.NO_ACCOUNT;
        if (s != null && s.hasData()) return Cloud.DATA;
        if (relogin || (s != null && s.state == CarCloud.State.EXPIRED)) return Cloud.CLOSED;
        return Cloud.WAITING;
    }

    static Cloud cloud(Context c, CarCloud.Snapshot s) {
        boolean account = PhoneInsights.hasAccount(c);
        return cloud(account, s, account && new CarCloudStore(c).reloginReason() != null);
    }

    /**
     * El estado vacío de la cuenta: sin cuenta (con `intro`, lo que se gana con ella, y «Conectar la cuenta»), sesión
     * cerrada (con «Volver a entrar») o esperando el primer dato. Los dos botones abren CarCloudActivity.
     */
    static LinearLayout cloudEmpty(Activity act, Cloud st, String intro) {
        Runnable open = () -> act.startActivity(new Intent(act, CarCloudActivity.class));
        switch (st) {
            case NO_ACCOUNT:
                return empty(act, R.drawable.hql_ln_car, Str.get(R.string.hql_cloud_none_title), intro, Str.get(R.string.hql_cloud_connect), open);
            case CLOSED:
                return empty(act, R.drawable.hql_ic_cw_lock, Str.get(R.string.hql_cloud_closed_title), Str.get(R.string.hql_cloud_closed_body),
                        Str.get(R.string.hql_cloud_reenter), open);
            default:
                return empty(act, R.drawable.hql_ln_car, Str.get(R.string.hql_ins_cloud_wait), Str.get(R.string.hql_cloud_waiting_body), null, null);
        }
    }

    private static int dp(Context c, float d) {
        return Math.round(d * c.getResources().getDisplayMetrics().density);
    }
}
