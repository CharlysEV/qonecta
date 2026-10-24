package com.headqlink.link;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * «Acerca de y créditos»: Qonecta+ es de CharlysEV (bien a la vista), la versión y la licencia; de qué parte (HeadQLink
 * de ryazrm, Open Headunit, Michael Reid), quién hizo lo de la nube de Leapmotor (LMB10, leapmotor-api,
 * leapmotor-mate), los datos y servicios en línea (OpenStreetMap, Open-Meteo, DGT…) y las bibliotecas. Lo mismo que
 * NOTICE y el README, para quien no los lee. Desde el menú › App y tocando la versión en la pantalla principal.
 */
final class About {
    static final String AUTHOR = "CharlysEV";
    static final String AUTHOR_URL = "https://github.com/CharlysEV";
    static final String REPO_URL = "https://github.com/CharlysEV/qonecta";
    /** El HeadQLink original del que parte Qonecta+. */
    static final String BASE_AUTHOR = "ryazrm";
    static final String BASE_URL = "https://github.com/ryazrm/headqlink";

    private About() {
    }

    /** Bajo el nombre de la pantalla principal: «v0.3.2» y, debajo, «por CharlysEV» en el color de la app. */
    static CharSequence versionByline(Context c) {
        SpannableStringBuilder b = new SpannableStringBuilder(Ui.appVersionLabel(c));
        b.append('\n');
        int from = b.length();
        b.append(Str.get(R.string.hql_about_by, AUTHOR));
        b.setSpan(new ForegroundColorSpan(ContextCompat.getColor(c, R.color.hql_accent)), from, b.length(), 0);
        b.setSpan(new StyleSpan(Typeface.BOLD), from, b.length(), 0);
        return b;
    }

    /**
     * La versión y «por CharlysEV» en la cabecera, sin partir ninguna palabra: con el móvil en vertical la columna es
     * estrecha y «CharlysEV» se partía («Charl / ysEV»). Se busca la letra más grande (de 13 a 9 sp) con la que no se
     * parte ninguna, aunque «por» y el nombre queden en líneas distintas.
     */
    static void fitByline(TextView t) {
        t.post(() -> {
            int w = t.getWidth();
            if (w <= 0) return;
            for (float sp = 13f; sp >= 9f; sp -= 0.5f) {
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
                t.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                if (t.getLayout() != null && wholeWords(t.getLayout(), t.getText())) return;
            }
        });
    }

    /** Puro: ¿ninguna línea corta una palabra por la mitad (letra justo antes y justo después del corte)? */
    static boolean wholeWords(android.text.Layout l, CharSequence s) {
        for (int i = 0; i < l.getLineCount() - 1; i++) {
            int end = l.getLineEnd(i);
            if (end > 0 && end < s.length() && Character.isLetterOrDigit(s.charAt(end - 1)) && Character.isLetterOrDigit(s.charAt(end))) {
                return false;
            }
        }
        return true;
    }

    static void show(Activity a) {
        ScrollView sv = new ScrollView(a);
        sv.addView(view(a));
        new MaterialAlertDialogBuilder(a)
                .setView(sv)
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .setNeutralButton(Str.get(R.string.hql_update_on_github), (d, w) -> open(a, REPO_URL))
                .show();
    }

    static View view(Context c) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * dp);
        col.setPadding(pad, pad, pad, Math.round(8 * dp));

        // Cabecera: el logo, el nombre y, bien grande, de quién es.
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.hql_logo);
        head.addView(logo, new LinearLayout.LayoutParams(Math.round(52 * dp), Math.round(52 * dp)));
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Math.round(14 * dp), 0, 0, 0);
        TextView app = text(c, "Qonecta+ " + Ui.appVersionLabel(c), R.color.hql_text, 22);
        app.setTypeface(Typeface.DEFAULT_BOLD);
        names.addView(app);
        TextView by = text(c, Str.get(R.string.hql_about_by, AUTHOR), R.color.hql_accent, 19);
        by.setTypeface(Typeface.DEFAULT_BOLD);
        by.setOnClickListener(v -> open(c, AUTHOR_URL));
        names.addView(by);
        TextView from = text(c, Str.get(R.string.hql_about_from, BASE_AUTHOR), R.color.hql_accent, 15);
        from.setOnClickListener(v -> open(c, BASE_URL));
        names.addView(from);
        head.addView(names);
        col.addView(head);
        col.addView(text(c, Str.get(R.string.hql_about_tagline), R.color.hql_text_dim, 14), gap(c, 10));

        section(c, col, R.string.hql_about_based,
                "HeadQLink · " + BASE_AUTHOR,
                "Open Headunit · " + Str.get(R.string.hql_about_and_contributors, "André Rinas"),
                "Headunit for Android Auto · Michael A. Reid",
                Str.get(R.string.hql_about_qdauto, AUTHOR));
        section(c, col, R.string.hql_about_cloud,
                "LMB10 · txurtxil (GPL-3.0) — " + Str.get(R.string.hql_about_role_protocol),
                "leapmotor-api · markoceri (AGPL-3.0) — " + Str.get(R.string.hql_about_role_commands),
                "leapmotor-mate · ProtossBlaster (AGPL-3.0) — " + Str.get(R.string.hql_about_role_reev));
        section(c, col, R.string.hql_about_data,
                Str.get(R.string.hql_about_osm),
                Str.get(R.string.hql_about_weather),
                Str.get(R.string.hql_about_dgt),
                Str.get(R.string.hql_about_bnetza),
                Str.get(R.string.hql_about_radio),
                Str.get(R.string.hql_about_adblock),
                Str.get(R.string.hql_about_wikipedia),
                Str.get(R.string.hql_about_lrclib));
        section(c, col, R.string.hql_about_libs,
                Str.get(R.string.hql_about_3d),
                Str.get(R.string.hql_about_leaflet),
                Str.get(R.string.hql_about_emulators));
        col.addView(text(c, Str.get(R.string.hql_about_license), R.color.hql_text_dim, 13), gap(c, 18));
        col.addView(text(c, Str.get(R.string.hql_about_independent), R.color.hql_text_faint, 12), gap(c, 6));
        return col;
    }

    private static void section(Context c, LinearLayout col, int title, String... lines) {
        TextView t = text(c, Str.get(title).toUpperCase(Str.locale()), R.color.hql_text_dim, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.1f);
        col.addView(t, gap(c, 18));
        for (String l : lines) col.addView(text(c, "• " + l, R.color.hql_text, 14), gap(c, 5));
    }

    private static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            L.w("acerca de: no se pudo abrir el enlace (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static TextView text(Context c, String s, int color, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(c, color));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private static LinearLayout.LayoutParams gap(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topDp * c.getResources().getDisplayMetrics().density);
        return lp;
    }
}
