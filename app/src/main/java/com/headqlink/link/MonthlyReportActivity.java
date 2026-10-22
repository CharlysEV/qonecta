package com.headqlink.link;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * El informe del mes en el móvil: el mes elegido (‹ ›) entre los guardados y el de ahora, y «Compartir» con la imagen
 * de cifras (MonthlyReportView.shareCard). Se abre desde la pestaña Viajes y desde el aviso del día 1 (EXTRA_MONTH).
 */
public class MonthlyReportActivity extends Activity {
    /** «2026-10»: el mes que se abre (si no, el de antes los primeros días del mes y, si no, el de ahora). */
    static final String EXTRA_MONTH = "month";

    private Map<String, MonthlyReport.Summary> all;
    private final List<String> keys = new ArrayList<>();
    private int index;
    private String currentKey;
    private TextView title;
    private ImageButton prev;
    private ImageButton next;
    private FrameLayout body;

    static Intent intent(Context c, String monthKey) {
        Intent i = new Intent(c, MonthlyReportActivity.class);
        if (monthKey != null) i.putExtra(EXTRA_MONTH, monthKey);
        return i;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Str.init(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(MonthlyReportView.background());
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(12);
        bar.setPadding(p, dp(10), p, dp(4));
        ImageButton back = icon(R.drawable.ic_arrow_back_white, Str.get(R.string.hql_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView t = new TextView(this);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        t.setTextColor(0xFFEAF2F7);
        t.setText(Str.get(R.string.hql_report_title));
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(bar);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(p, 0, p, dp(6));
        prev = icon(R.drawable.ic_chevron_left_white, Str.get(R.string.hql_report_prev));
        prev.setOnClickListener(v -> go(index - 1));
        next = icon(R.drawable.ic_chevron_right, Str.get(R.string.hql_report_next));
        next.setOnClickListener(v -> go(index + 1));
        title = new TextView(this);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(0xFFEAF2F7);
        title.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(next);
        col.addView(nav);
        ScrollView sv = new ScrollView(this);
        body = new FrameLayout(this);
        int pad = dp(18);
        body.setPadding(pad, dp(6), pad, dp(28));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
        String wanted = getIntent().getStringExtra(EXTRA_MONTH);
        new Thread(() -> {
            Map<String, MonthlyReport.Summary> m = MonthlyReport.refresh(this);
            runOnUiThread(() -> {
                if (!isFinishing()) loaded(m, wanted);
            });
        }, "hql-monthly").start();
    }

    private void loaded(Map<String, MonthlyReport.Summary> m, String wanted) {
        all = m;
        TimeZone tz = TimeZone.getDefault();
        int[] now = MonthlyReport.monthOf(System.currentTimeMillis(), tz);
        currentKey = MonthlyReport.key(now[0], now[1]);
        keys.clear();
        for (Map.Entry<String, MonthlyReport.Summary> e : m.entrySet()) {
            if (e.getValue().trips > 0 || e.getKey().equals(currentKey)) keys.add(e.getKey());
        }
        Collections.sort(keys);
        int[] before = MonthlyReport.shift(now[0], now[1], -1);
        String prevKey = MonthlyReport.key(before[0], before[1]);
        String start = wanted != null && keys.contains(wanted) ? wanted
                : java.util.Calendar.getInstance(tz).get(java.util.Calendar.DAY_OF_MONTH) <= 10 && keys.contains(prevKey) ? prevKey : currentKey;
        go(Math.max(0, keys.indexOf(start)));
    }

    private void go(int i) {
        if (keys.isEmpty() || i < 0 || i >= keys.size()) return;
        index = i;
        String k = keys.get(i);
        MonthlyReport.Summary s = all.get(k);
        int[] before = MonthlyReport.shift(s.year, s.month, -1);
        MonthlyReport.Summary p = all.get(MonthlyReport.key(before[0], before[1]));
        boolean current = k.equals(currentKey);
        title.setText(MonthlyReportView.monthTitle(s.year, s.month));
        prev.setVisibility(i > 0 ? View.VISIBLE : View.INVISIBLE);
        next.setVisibility(i < keys.size() - 1 ? View.VISIBLE : View.INVISIBLE);
        body.removeAllViews();
        body.addView(MonthlyReportView.build(this, s, p, current, () -> share(s)));
        L.i("informe del mes: " + k + (current ? " (a medias, el de ahora)" : "") + " · " + s.trips + " viajes"
                + (s.complete ? "" : " · incompleto") + (MonthlyReport.comparable(s, p) ? " · con comparación" : ""));
    }

    /** La imagen de cifras del mes (sin lugares) a la hoja de compartir de Android. */
    private void share(MonthlyReport.Summary s) {
        try {
            View v = MonthlyReportView.shareCard(this, s);
            int w = 1080, h = 1350;
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bmp));
            File dir = new File(getCacheDir(), "export");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("sin carpeta");
            File out = new File(dir, "qonecta-" + s.key() + ".png");
            try (FileOutputStream f = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(out.getName(), uri));
            startActivity(Intent.createChooser(send, Str.get(R.string.hql_report_share)));
            L.i("informe del mes: compartido " + s.key());
        } catch (Exception e) {
            L.w("informe del mes: no se pudo compartir (" + e.getClass().getSimpleName() + ")");
            android.widget.Toast.makeText(this, Str.get(R.string.hql_report_share_failed), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private ImageButton icon(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(0xFFEAF2F7);
        b.setContentDescription(desc);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        int s = dp(44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private int dp(float d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }
}
