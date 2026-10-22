package com.headqlink.link;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lo que se ve del informe del mes (MonthlyReport): la tarjeta grande con el mes y sus cifras frente al mes de antes,
 * lo que te ahorras frente a gasolina, las cargas, el coche parado y los destacados; y la imagen para compartir (solo
 * cifras, sin lugares ni rutas). Lo usan MonthlyReportActivity y las capturas del PC.
 */
final class MonthlyReportView {
    private static final int BG = 0xFF0A0E14;
    private static final int CARD = 0xFF111821;
    private static final int STROKE = 0xFF243140;
    private static final int GOLD = 0xFFFFD54F;

    private MonthlyReportView() {
    }

    // ------------------------------------------------------------------ textos

    /** «octubre» (como va en una frase) en el idioma de la app. */
    static String monthName(int year, int month) {
        SimpleDateFormat f = new SimpleDateFormat("LLLL", Str.locale());
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(MonthlyReport.monthStart(year, month, TimeZone.getDefault()) + 12 * 3_600_000L));
    }

    /** «Octubre 2026». */
    static String monthTitle(int year, int month) {
        String m = monthName(year, month);
        return m.substring(0, 1).toUpperCase(Str.locale()) + m.substring(1) + " " + year;
    }

    /** «Tu octubre con el C10» (o con el T03). */
    static String heading(MonthlyReport.Summary s) {
        return Str.carText(Str.get(R.string.hql_report_heading, monthName(s.year, s.month)), CarSpec.current());
    }

    static String day(long ms) {
        return new SimpleDateFormat("d MMM", Str.locale()).format(new Date(ms));
    }

    static String num(double v, int decimals) {
        return Double.isNaN(v) ? "—" : String.format(Str.locale(), "%,." + decimals + "f", v);
    }

    /** «↑ 12 %», «↓ 5 %» o «=»; vacío si no se puede comparar. */
    static String delta(double pct) {
        if (Double.isNaN(pct) || Double.isInfinite(pct)) return "";
        if (Math.abs(pct) < 0.5) return "=";
        return (pct > 0 ? "↑ " : "↓ ") + num(Math.abs(pct), 0) + " %";
    }

    // ------------------------------------------------------------------ el informe

    /**
     * El informe de s, comparado con prev si se puede (MonthlyReport.comparable). current: es el mes de ahora (a
     * medias). Con el botón «Compartir» que llama a onShare.
     */
    static View build(Context c, MonthlyReport.Summary s, MonthlyReport.Summary prev, boolean current, Runnable onShare) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean cmp = MonthlyReport.comparable(s, prev);
        col.addView(hero(c, s, cmp ? prev : null, current), lp(c, 0));
        if (s.trips == 0) {
            col.addView(note(c, Str.get(R.string.hql_report_empty)), lp(c, 14));
            return col;
        }
        double saved = s.savedEur();
        if (!Double.isNaN(saved) && saved > 0) {
            LinearLayout k = card(c, Str.get(R.string.hql_report_saved_title));
            TextView big = text(c, "~" + Money.format(saved, 0), 0xFF4CFF9F, 34, true);
            k.addView(big);
            Config cfg = new Config(c);
            k.addView(note(c, Str.get(R.string.hql_report_saved, Money.format(s.petrolEur, 0), num(cfg.fuelLitersPer100(), 1),
                    Money.per(cfg.fuelPrice(), "l"))), lp(c, 4));
            col.addView(k, lp(c, 14));
        }
        LinearLayout ch = card(c, Str.get(R.string.hql_report_charges_title));
        if (s.charges > 0) {
            LinearLayout row = row(c);
            row.addView(stat(c, String.valueOf(s.charges), Str.get(R.string.hql_report_charges_unit), ""), weight());
            row.addView(stat(c, num(s.chargedKwh, 0), "kWh", ""), weight());
            row.addView(stat(c, Money.format(s.chargeCostEur, 0), Str.get(R.string.hql_report_cost), ""), weight());
            ch.addView(row);
            ch.addView(note(c, Str.get(R.string.hql_report_charges_split, num(s.chargedKwh - s.fastKwh, 0), num(s.fastKwh, 0))), lp(c, 8));
            if (!Double.isNaN(s.fastestKw)) ch.addView(note(c, Str.get(R.string.hql_report_fastest, num(s.fastestKw, 0))), lp(c, 2));
        } else {
            ch.addView(note(c, Str.get(R.string.hql_report_charges_none)));
        }
        col.addView(ch, lp(c, 14));
        if (s.parks > 0) {
            LinearLayout pk = card(c, Str.get(R.string.hql_report_parked_title));
            pk.addView(text(c, Str.get(R.string.hql_report_parked, num(s.parkedPct, 1), num(s.parkedKwh, 1),
                    s.parkedHours >= 48 ? num(s.parkedHours, 0) + " h" : PhoneInsights.duration(Math.round(s.parkedHours * 60))),
                    0xFFEAF2F7, 16, false));
            col.addView(pk, lp(c, 14));
        }
        LinearLayout hl = card(c, Str.get(R.string.hql_report_highlights));
        if (!Double.isNaN(s.bestPer100)) {
            hl.addView(highlight(c, Str.get(R.string.hql_report_best), day(s.bestMs) + " · " + num(s.bestPer100, 1) + " kWh/100 km · "
                    + num(s.bestKm, 0) + " km"));
        }
        if (!Double.isNaN(s.longestKm)) {
            hl.addView(highlight(c, Str.get(R.string.hql_report_longest), day(s.longestMs) + " · " + num(s.longestKm, 0) + " km"), lp(c, 10));
        }
        if (!Double.isNaN(s.topDayKm)) {
            hl.addView(highlight(c, Str.get(R.string.hql_report_top_day), day(s.topDayMs) + " · " + num(s.topDayKm, 0) + " km"), lp(c, 10));
        }
        col.addView(hl, lp(c, 14));
        String foot = Str.get(s.fromCar ? R.string.hql_report_source_car : R.string.hql_report_source_app);
        if (!current) foot += "\n" + (cmp ? Str.get(R.string.hql_report_compared, monthName(prev.year, prev.month))
                : Str.get(R.string.hql_report_no_compare));
        TextView f = note(c, foot);
        f.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        col.addView(f, lp(c, 14));
        com.google.android.material.button.MaterialButton share = new com.google.android.material.button.MaterialButton(c);
        share.setText(Str.get(R.string.hql_report_share));
        share.setOnClickListener(v -> onShare.run());
        col.addView(share, lp(c, 16));
        return col;
    }

    /** La tarjeta grande: el mes, los km, el consumo, la energía y el dinero, cada uno con su cambio. */
    private static View hero(Context c, MonthlyReport.Summary s, MonthlyReport.Summary prev, boolean current) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 20);
        k.setPadding(p, dp(c, 18), p, dp(c, 18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0E3B37, 0xFF111821});
        bg.setCornerRadius(dp(c, 22));
        bg.setStroke(dp(c, 1), 0xFF1F5A52);
        k.setBackground(bg);
        TextView month = text(c, monthTitle(s.year, s.month) + (current ? " · " + Str.get(R.string.hql_report_so_far) : ""),
                0xFF00E5C7, 13, true);
        month.setLetterSpacing(0.08f);
        k.addView(month);
        k.addView(text(c, heading(s), 0xFFEAF2F7, 23, true), lp(c, 4));
        if (!s.complete && !current) k.addView(note(c, Str.get(R.string.hql_report_partial)), lp(c, 4));
        LinearLayout r1 = row(c);
        r1.addView(stat(c, num(s.km, 0), "km", prev == null ? "" : delta(MonthlyReport.changePct(s.km, prev.km)), 0), weight());
        r1.addView(stat(c, num(s.per100(), 1), "kWh/100 km", prev == null ? "" : delta(MonthlyReport.changePct(s.per100(), prev.per100())), -1),
                weight());
        k.addView(r1, lp(c, 16));
        LinearLayout r2 = row(c);
        r2.addView(stat(c, num(s.kwh, 0), "kWh", prev == null ? "" : delta(MonthlyReport.changePct(s.kwh, prev.kwh)), 0), weight());
        r2.addView(stat(c, Double.isNaN(s.costEur) ? "—" : Money.format(s.costEur, 0), Str.get(R.string.hql_report_spent),
                prev == null ? "" : delta(MonthlyReport.changePct(s.costEur, prev.costEur)), -1), weight());
        k.addView(r2, lp(c, 12));
        String trips = Str.plural(R.plurals.hql_report_trips, s.trips, s.trips);
        k.addView(note(c, trips), lp(c, 10));
        return k;
    }

    // ------------------------------------------------------------------ la imagen para compartir

    /** La imagen de 1080x1350 px: el mes y sus cifras, sin lugares. */
    static View shareCard(Context c, MonthlyReport.Summary s) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding(84, 84, 84, 72);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0E3B37, BG, BG});
        k.setBackground(bg);
        TextView brand = px(c, "Qonecta+", 0xFF00E5C7, 40, true);
        brand.setLetterSpacing(0.12f);
        k.addView(brand);
        k.addView(px(c, Str.carText(Str.get(R.string.hql_report_share_heading, monthName(s.year, s.month)), CarSpec.current()),
                0xFFEAF2F7, 64, true), pxLp(28));
        k.addView(px(c, String.valueOf(s.year), 0xFFA3B3C2, 36, false), pxLp(6));
        LinearLayout big = new LinearLayout(c);
        big.setGravity(Gravity.BOTTOM);
        big.addView(px(c, num(s.km, 0), 0xFFEAF2F7, 190, true));
        TextView km = px(c, " km", 0xFFA3B3C2, 64, false);
        km.setPadding(0, 0, 0, 34);
        big.addView(km);
        k.addView(big, pxLp(36));
        LinearLayout r1 = new LinearLayout(c);
        r1.addView(pxStat(c, num(s.per100(), 1), "kWh/100 km"), weight());
        r1.addView(pxStat(c, Double.isNaN(s.costEur) ? "—" : Money.format(s.costEur, 0), Str.get(R.string.hql_report_spent)), weight());
        k.addView(r1, pxLp(40));
        LinearLayout r2 = new LinearLayout(c);
        r2.addView(pxStat(c, String.valueOf(s.charges), Str.get(R.string.hql_report_charges_unit)), weight());
        r2.addView(pxStat(c, String.valueOf(s.trips), Str.plural(R.plurals.hql_report_trips_unit, s.trips)), weight());
        k.addView(r2, pxLp(40));
        double saved = s.savedEur();
        if (!Double.isNaN(saved) && saved > 0) {
            TextView sv = px(c, Str.get(R.string.hql_report_share_saved, Money.format(saved, 0)), 0xFF4CFF9F, 46, true);
            k.addView(sv, pxLp(56));
        }
        View spacer = new View(c);
        k.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        k.addView(px(c, Str.get(R.string.hql_report_share_footer), 0xFF8296A9, 30, false));
        return k;
    }

    private static View pxStat(Context c, String value, String label) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(px(c, value, 0xFFEAF2F7, 84, true));
        l.addView(px(c, label, 0xFFA3B3C2, 34, false));
        return l;
    }

    private static TextView px(Context c, String s, int color, int sizePx, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private static LinearLayout.LayoutParams pxLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    // ------------------------------------------------------------------ piezas

    private static LinearLayout card(Context c, String title) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 18);
        k.setPadding(p, dp(c, 16), p, dp(c, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(c, 22));
        bg.setStroke(dp(c, 1), STROKE);
        k.setBackground(bg);
        TextView t = text(c, title, 0xFFEAF2F7, 17, true);
        t.setPadding(0, 0, 0, dp(c, 10));
        k.addView(t);
        return k;
    }

    /** Una cifra con su unidad y, si hay, su cambio. better: -1 si bajar es bueno (consumo, dinero), 0 si da igual. */
    private static View stat(Context c, String value, String unit, String delta, int better) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.BOTTOM);
        top.addView(text(c, value, 0xFFEAF2F7, 28, true));
        if (!delta.isEmpty()) {
            int color = better == 0 || delta.equals("=") ? 0xFFA3B3C2 : (delta.startsWith("↓") == (better < 0)) ? 0xFF4CFF9F : 0xFFFFB547;
            TextView d = text(c, "  " + delta, color, 14, true);
            d.setPadding(0, 0, 0, dp(c, 4));
            top.addView(d);
        }
        l.addView(top);
        l.addView(text(c, unit, 0xFFA3B3C2, 13, false));
        return l;
    }

    private static View stat(Context c, String value, String unit, String delta) {
        return stat(c, value, unit, delta, 0);
    }

    private static View highlight(Context c, String label, String value) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, "★ " + label, GOLD, 13, true);
        l.addView(t);
        l.addView(text(c, value, 0xFFEAF2F7, 16, false));
        return l;
    }

    private static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private static TextView note(Context c, String s) {
        return text(c, s, 0xFFA3B3C2, 14, false);
    }

    private static TextView text(Context c, String s, int color, int sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.12f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams lp(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static int dp(Context c, float d) {
        return Math.round(d * c.getResources().getDisplayMetrics().density);
    }

    static int background() {
        return BG;
    }
}
