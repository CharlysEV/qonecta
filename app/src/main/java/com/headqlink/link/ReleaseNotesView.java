package com.headqlink.link;

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

import java.util.List;

/**
 * Las notas de una versión, por apartados (UpdateCheck.sections): arriba, la franja que brilla y cada novedad en su
 * tarjeta con su ★; debajo, las mejoras en una lista discreta; y los arreglos plegados en «🔧 N arreglos ›», que se
 * despliegan al tocarlos (si no hay nada más, se ven ya abiertos). Para «Versión nueva» y para las novedades al
 * estrenar una versión.
 */
final class ReleaseNotesView {
    private ReleaseNotesView() {
    }

    static View view(Context c, String version, UpdateCheck.Notes n) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * dp);
        col.setPadding(pad, pad, pad, Math.round(8 * dp));

        if (!n.added.isEmpty()) {
            col.addView(StarFeature.badge(c, Str.get(R.string.hql_notes_badge, version)),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            for (UpdateCheck.NoteItem it : n.added) col.addView(starCard(c, it), gap(c, 14));
        }
        if (!n.improved.isEmpty()) {
            col.addView(header(c, Str.get(R.string.hql_notes_improved)), gap(c, n.added.isEmpty() ? 0 : 22));
            for (UpdateCheck.NoteItem it : n.improved) col.addView(line(c, it), gap(c, 8));
        }
        if (!n.fixed.isEmpty()) {
            boolean alone = n.added.isEmpty() && n.improved.isEmpty();
            LinearLayout fixes = new LinearLayout(c);
            fixes.setOrientation(LinearLayout.VERTICAL);
            for (UpdateCheck.NoteItem it : n.fixed) fixes.addView(line(c, it), gap(c, 8));
            if (alone) {
                col.addView(header(c, Str.get(R.string.hql_notes_fixed)));
            } else {
                TextView toggle = text(c, "", R.color.hql_text_dim, 15);
                toggle.setTypeface(Typeface.DEFAULT_BOLD);
                toggle.setPadding(0, Math.round(10 * dp), 0, Math.round(10 * dp));
                int count = n.fixed.size();
                Runnable label = () -> toggle.setText(Str.plural(R.plurals.hql_notes_fixes, count, count)
                        + (fixes.getVisibility() == View.VISIBLE ? " ⌄" : " ›"));
                fixes.setVisibility(View.GONE);
                label.run();
                toggle.setOnClickListener(v -> {
                    fixes.setVisibility(fixes.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    label.run();
                });
                col.addView(toggle, gap(c, 16));
            }
            col.addView(fixes);
        }
        if (!n.other.isEmpty()) col.addView(text(c, n.other, R.color.hql_text_dim, 14), gap(c, 16));
        return col;
    }

    /** Una novedad: ★ y el título en grande, el texto y sus subpuntos, en una tarjeta con el borde del acento. */
    private static View starCard(Context c, UpdateCheck.NoteItem it) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * dp);
        card.setPadding(p, Math.round(14 * dp), p, Math.round(14 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(c, R.color.hql_surface_hi));
        bg.setCornerRadius(16 * dp);
        bg.setStroke(Math.round(1.5f * dp), ContextCompat.getColor(c, R.color.hql_accent_container));
        card.setBackground(bg);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView star = text(c, "★", 0, 22);
        star.setTextColor(0xFFFFD54F);
        star.setPadding(0, 0, Math.round(10 * dp), 0);
        row.addView(star);
        TextView title = text(c, it.title.isEmpty() ? it.body : it.title, R.color.hql_text, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        // En las notas el texto sigue al título tras «:» (en minúscula); aquí va en su línea.
        if (!it.title.isEmpty() && !it.body.isEmpty()) {
            String body = it.body.substring(0, 1).toUpperCase(java.util.Locale.getDefault()) + it.body.substring(1);
            card.addView(text(c, body, R.color.hql_text, 15), gap(c, 8));
        }
        for (String s : it.sub) card.addView(text(c, "– " + s, R.color.hql_text_dim, 14), gap(c, 6));
        return card;
    }

    /** Una mejora o un arreglo: «• Título: texto», con los subpuntos debajo, más pequeños. */
    private static View line(Context c, UpdateCheck.NoteItem it) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("• ");
        if (!it.title.isEmpty()) {
            int from = sb.length();
            sb.append(it.title);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), from, sb.length(), 0);
            if (!it.body.isEmpty()) sb.append(": ");
        }
        sb.append(it.body);
        TextView t = text(c, "", R.color.hql_text, 15);
        t.setText(sb);
        l.addView(t);
        List<String> sub = it.sub;
        for (String s : sub) {
            TextView st = text(c, "– " + s, R.color.hql_text_dim, 14);
            st.setPadding(Math.round(14 * c.getResources().getDisplayMetrics().density), 0, 0, 0);
            l.addView(st, gap(c, 4));
        }
        return l;
    }

    private static TextView header(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.getDefault()), R.color.hql_text_dim, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.1f);
        return t;
    }

    private static TextView text(Context c, String s, int color, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(ContextCompat.getColor(c, color));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private static LinearLayout.LayoutParams gap(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topDp * c.getResources().getDisplayMetrics().density);
        return lp;
    }
}
