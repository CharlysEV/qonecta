package com.headqlink.link;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * La novedad estrella de la 0.2.71, una vez al entrar en la app (antes de las novedades de la versión): un cartel que
 * brilla con lo de «Precisión de la ubicación de Google». Activada, Google busca redes Wi-Fi cada ~9 s mientras se usa
 * Android Auto y cada búsqueda corta un momento la conexión con el coche; apagada, el GPS sigue igual. El estado se lee
 * como en Comprobación (Checklist.googleAccuracy) y el botón abre la pantalla de Google.
 */
final class StarFeature {
    /** Ya enseñada (una vez por móvil). */
    static final String SEEN = "star_seen_google_accuracy";

    private StarFeature() {
    }

    /** Enseña el cartel si no se ha visto y, al cerrarlo (o si no toca), sigue con next. */
    static void show(Activity a, Runnable next) {
        Config cfg = new Config(a);
        if (cfg.getBool(SEEN)) {
            next.run();
            return;
        }
        cfg.putBool(SEEN, true);
        int state = Checklist.googleAccuracy(a);
        L.i("novedad estrella: precisión de la ubicación de Google " + (state == 1 ? "activada" : state == 0 ? "apagada" : "sin saber"));
        // Desplazable: en un móvil pequeño, o con letra grande, el cartel no cabe entero.
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(view(a, state));
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(a)
                .setView(sv)
                .setOnDismissListener(d -> next.run());
        if (state == 0) {
            b.setPositiveButton(Str.get(R.string.hql_star_great), null);
        } else {
            b.setPositiveButton(Str.get(R.string.hql_star_turn_off), (d, w) -> {
                L.i("novedad estrella: abro la precisión de la ubicación de Google");
                Checklist.openGoogleAccuracy(a);
            });
            b.setNegativeButton(Str.get(R.string.hql_star_later), null);
        }
        b.show();
    }

    /** El cartel: la franja que brilla, el título, lo que pasa, lo del GPS y cómo la tienes ahora (state: 1, 0 o -1). */
    static View view(Context c, int state) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * dp);
        col.setPadding(pad, pad, pad, Math.round(8 * dp));

        col.addView(badge(c, Str.get(R.string.hql_star_badge)),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(c);
        title.setText(Str.get(R.string.hql_star_title));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ContextCompat.getColor(c, R.color.hql_text));
        title.setPadding(0, Math.round(18 * dp), 0, Math.round(8 * dp));
        col.addView(title);

        col.addView(text(c, Str.get(R.string.hql_star_body), R.color.hql_text, 15));
        TextView gps = text(c, Str.get(R.string.hql_star_gps), R.color.hql_ok, 15);
        gps.setPadding(0, Math.round(12 * dp), 0, 0);
        col.addView(gps);
        if (state >= 0) {
            TextView now = text(c, Str.get(state == 0 ? R.string.hql_star_state_off : R.string.hql_star_state_on),
                    state == 0 ? R.color.hql_ok : R.color.hql_warn, 16);
            now.setTypeface(Typeface.DEFAULT_BOLD);
            now.setPadding(0, Math.round(14 * dp), 0, 0);
            col.addView(now);
        }
        TextView also = text(c, Str.get(R.string.hql_star_also), R.color.hql_text_dim, 13);
        also.setPadding(0, Math.round(10 * dp), 0, 0);
        col.addView(also);
        return col;
    }

    /** La franja dorada que brilla y late (también la de las novedades de cada versión, ReleaseNotesView). */
    static TextView badge(Context c, String s) {
        float dp = c.getResources().getDisplayMetrics().density;
        TextView badge = new TextView(c);
        badge.setText(s);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setLetterSpacing(0.12f);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(0xFF06231F);
        badge.setPadding(0, Math.round(12 * dp), 0, Math.round(12 * dp));
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFFD54F, ContextCompat.getColor(c, R.color.hql_accent), 0xFFFFD54F});
        glow.setCornerRadius(14 * dp);
        glow.setStroke(Math.round(2 * dp), 0xFFFFF3B0);
        badge.setBackground(glow);
        badge.setShadowLayer(10 * dp, 0, 0, 0xCCFFFFFF);
        pulse(badge);
        return badge;
    }

    private static TextView text(Context c, String s, int color, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(c, color));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** El brillo: la franja late despacio (escala y luz) mientras está a la vista. */
    private static void pulse(View v) {
        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(1100);
        an.setRepeatMode(ValueAnimator.REVERSE);
        an.setRepeatCount(ValueAnimator.INFINITE);
        an.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            v.setScaleX(1f + 0.03f * f);
            v.setScaleY(1f + 0.03f * f);
            v.setAlpha(0.85f + 0.15f * f);
        });
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                an.start();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                an.cancel();
            }
        });
    }
}
