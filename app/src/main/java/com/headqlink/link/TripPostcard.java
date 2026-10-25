package com.headqlink.link;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.core.content.FileProvider;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * El diario del viaje: una postal (1080x1350) con el trazado, las cifras, las fotos que hiciste con el móvil durante el
 * viaje y los sitios que te contó la Guía por el camino, para guardarla o compartirla. No sale del móvil salvo que la
 * compartas tú; antes se ve, y se puede quitar las fotos. Tras un viaje largo, un aviso discreto la ofrece.
 */
final class TripPostcard {
    static final int W = 1080;
    static final int H = 1350;
    /** Viajes de esto o más: aviso al terminar. */
    static final double LONG_KM = 50;
    /** Fotos de la postal, como mucho. */
    static final int MAX_PHOTOS = 3;
    private static final String CHANNEL = "hql_diary";
    private static final int NOTIF_ID = 31;
    private static final String PREFS = "hql_diary";
    private static final String K_LAST = "last_notified_start";

    private TripPostcard() {
    }

    // ------------------------------------------------------------------ el dibujo

    /** La postal. photos puede ir vacía. */
    static Bitmap render(Context c, TripLog.Trip t, List<CloudHistory.Trip> history, List<Bitmap> photos) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, H, 0xFF0A0E14, 0xFF0F2430, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, p);
        p.setShader(null);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        float x = 64;
        // Cabecera, con «Qonecta+» a la derecha.
        text(cv, p, Str.get(R.string.hql_diary_title).toUpperCase(Locale.getDefault()), x, 104, 32, 0xFF00E5C7, medium, 0.16f);
        p.setTextAlign(Paint.Align.RIGHT);
        text(cv, p, "Qonecta+", W - x, 104, 30, 0xFF5B6B7C, medium, 0);
        p.setTextAlign(Paint.Align.LEFT);
        String when = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, Str.locale()).format(new Date(t.startMs))
                + " · " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Str.locale()).format(new Date(t.startMs));
        text(cv, p, cap(when), x, 156, 34, 0xFFA3B3C2, regular, 0);
        float w = text(cv, p, String.format(Locale.getDefault(), "%.1f", t.km), x, 286, 112, 0xFFEAF2F7, medium, 0);
        w += text(cv, p, " km", x + w, 286, 48, 0xFFA3B3C2, regular, 0);
        text(cv, p, "  ·  " + PhoneInsights.duration(t.minutes), x + w, 286, 48, 0xFFA3B3C2, regular, 0);

        // De abajo arriba: los sitios, las fotos y las cifras; el trazado ocupa lo que queda.
        String[] places = t.places;
        List<String> placeLines = new ArrayList<>();
        if (places.length > 0) {
            p.setTypeface(regular);
            p.setTextSize(30);
            placeLines = wrap(p, Str.get(R.string.hql_diary_places, TextUtils.join(" · ", places)), W - 2 * x, photos.isEmpty() ? 4 : 2);
        }
        int n = Math.min(MAX_PHOTOS, photos.size());
        float gap = 20;
        float pw = n == 0 ? 0 : (W - 2 * x - gap * (n - 1)) / n;
        float ph = n == 0 ? 0 : Math.min(220, pw);
        float bottom = H - 56;
        float placesTop = bottom - placeLines.size() * 42;
        float photosTop = placesTop - (n == 0 ? 0 : ph + 34);
        float statsTop = photosTop - 150;
        RectF box = new RectF(48, 330, W - 48, statsTop - 20);
        p.setColor(0xFF111821);
        cv.drawRoundRect(box, 36, 36, p);
        List<TripStops.Stop> stops = TripStops.find(t.track);
        drawRoute(cv, p, t.track, stops, new RectF(box.left + 50, box.top + 50, box.right - 50, box.bottom - 50));

        // Cifras: consumo (o litros en un REEV en modo combustible), coste, desnivel y paradas; la cifra y debajo qué es.
        Config cfg = new Config(c);
        TripStats.Energy en = TripStats.energy(t, history);
        double per100 = TripStats.bestKwhPer100(t, history);
        boolean fuel = en != null && en.drive() == TripStats.Drive.FUEL;
        String use = fuel ? String.format(Locale.getDefault(), "%.1f", en.litersPer100())
                : Double.isNaN(per100) || per100 <= 0.05 ? "—" : String.format(Locale.getDefault(), "%.1f", per100);
        double cost = TripStats.cost(t, history, cfg.electricityPrice(), cfg.fuelPrice());
        String[][] stats = {
                {use, fuel ? "L/100 km" : "kWh/100 km"},
                {Double.isNaN(cost) || cost <= 0 ? "—" : Money.format(cost), Str.get(R.string.hql_diary_cost)},
                {String.format(Locale.getDefault(), "↑%.0f m", t.climb), Str.get(R.string.hql_diary_climb)},
                {String.valueOf(stops.size()), Str.get(R.string.hql_diary_stops)}};
        float colW = (W - 2 * x) / stats.length;
        for (int i = 0; i < stats.length; i++) {
            float sx = x + i * colW;
            text(cv, p, ellipsize(p, stats[i][0], colW - 24, 48, medium), sx, statsTop + 70, 48, 0xFFEAF2F7, medium, 0);
            text(cv, p, ellipsize(p, stats[i][1], colW - 16, 26, regular), sx, statsTop + 110, 26, 0xFF8296A9, regular, 0);
        }
        for (int i = 0; i < n; i++) {
            RectF r = new RectF(x + i * (pw + gap), photosTop, x + i * (pw + gap) + pw, photosTop + ph);
            drawPhoto(cv, photos.get(i), r);
        }
        float y = placesTop + 30;
        for (String l : placeLines) {
            text(cv, p, l, x, y, 30, 0xFFA3B3C2, regular, 0);
            y += 42;
        }
        return bmp;
    }

    private static void drawRoute(Canvas cv, Paint p, double[][] tr, List<TripStops.Stop> stops, RectF b) {
        if (tr == null || tr.length < 2) return;
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (double[] q : tr) {
            minLa = Math.min(minLa, q[0]);
            maxLa = Math.max(maxLa, q[0]);
            minLo = Math.min(minLo, q[1]);
            maxLo = Math.max(maxLo, q[1]);
        }
        double cos = Math.cos(Math.toRadians((minLa + maxLa) / 2));
        double spanX = Math.max(1e-6, (maxLo - minLo) * cos);
        double spanY = Math.max(1e-6, maxLa - minLa);
        double scale = Math.min(b.width() / spanX, b.height() / spanY);
        float offX = (float) (b.left + (b.width() - spanX * scale) / 2);
        float offY = (float) (b.top + (b.height() - spanY * scale) / 2);
        Path path = new Path();
        float[] xy = new float[2];
        for (int i = 0; i < tr.length; i++) {
            project(tr[i], minLa, minLo, cos, scale, offX, offY, spanY, xy);
            if (i == 0) path.moveTo(xy[0], xy[1]);
            else path.lineTo(xy[0], xy[1]);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(26);
        p.setColor(0x3300E5C7);
        cv.drawPath(path, p);
        p.setStrokeWidth(10);
        p.setColor(0xFF00E5C7);
        cv.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        for (TripStops.Stop s : stops) {
            project(new double[]{s.lat, s.lon}, minLa, minLo, cos, scale, offX, offY, spanY, xy);
            p.setColor(0xFFFFB547);
            cv.drawCircle(xy[0], xy[1], 14, p);
        }
        project(tr[0], minLa, minLo, cos, scale, offX, offY, spanY, xy);
        p.setColor(0xFFEAF2F7);
        cv.drawCircle(xy[0], xy[1], 16, p);
        project(tr[tr.length - 1], minLa, minLo, cos, scale, offX, offY, spanY, xy);
        p.setColor(0xFF4CFF9F);
        cv.drawCircle(xy[0], xy[1], 18, p);
    }

    private static void project(double[] q, double minLa, double minLo, double cos, double scale, float offX, float offY, double spanY,
                                float[] out) {
        out[0] = (float) (offX + (q[1] - minLo) * cos * scale);
        out[1] = (float) (offY + spanY * scale - (q[0] - minLa) * scale);
    }

    /** Una foto recortada al centro, con las esquinas redondeadas. */
    private static void drawPhoto(Canvas cv, Bitmap b, RectF r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(r.left + (r.width() - b.getWidth() * s) / 2, r.top + (r.height() - b.getHeight() * s) / 2);
        sh.setLocalMatrix(m);
        p.setShader(sh);
        cv.drawRoundRect(r, 24, 24, p);
    }

    private static float text(Canvas cv, Paint p, String s, float x, float y, float size, int color, Typeface tf, float spacing) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setLetterSpacing(spacing);
        cv.drawText(s, x, y, p);
        float w = p.measureText(s);
        p.setLetterSpacing(0);
        return w;
    }

    private static String ellipsize(Paint p, String s, float max, float size, Typeface tf) {
        p.setTypeface(tf);
        p.setTextSize(size);
        if (p.measureText(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && p.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    /** Parte el texto en líneas que quepan en max (como mucho maxLines; la última con «…» si no cabe). */
    static List<String> wrap(Paint p, String s, float max, int maxLines) {
        List<String> out = new ArrayList<>();
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String cand = line.length() == 0 ? words[i] : line + " " + words[i];
            if (p.measureText(cand) <= max || line.length() == 0) {
                line.setLength(0);
                line.append(cand);
                continue;
            }
            out.add(line.toString());
            line.setLength(0);
            line.append(words[i]);
            if (out.size() == maxLines) {
                String last = out.remove(maxLines - 1);
                StringBuilder rest = new StringBuilder(last);
                for (int k = i; k < words.length; k++) rest.append(' ').append(words[k]);
                String r = rest.toString();
                while (r.length() > 1 && p.measureText(r + "…") > max) r = r.substring(0, r.length() - 1);
                out.add(r + "…");
                return out;
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    // ------------------------------------------------------------------ fotos del viaje

    /** Las fotos hechas con el móvil durante el viaje (hasta MAX_PHOTOS, repartidas), sin permiso ninguna. */
    static List<Bitmap> photos(Context c, TripLog.Trip t) {
        List<Bitmap> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            return out;
        }
        List<Uri> uris = new ArrayList<>();
        long from = t.startMs;
        long to = t.endMs() + 10 * 60_000L;
        String[] cols = {MediaStore.Images.Media._ID};
        String where = MediaStore.Images.Media.DATE_TAKEN + " >= ? AND " + MediaStore.Images.Media.DATE_TAKEN + " <= ?";
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, where,
                new String[]{String.valueOf(from), String.valueOf(to)}, MediaStore.Images.Media.DATE_TAKEN + " ASC")) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)));
                }
            }
        } catch (RuntimeException e) {
            L.w("diario: no se pudieron leer las fotos (" + e.getClass().getSimpleName() + ")");
            return out;
        }
        for (int i : spread(uris.size(), MAX_PHOTOS)) {
            try {
                out.add(c.getContentResolver().loadThumbnail(uris.get(i), new android.util.Size(640, 640), null));
            } catch (Exception e) {
                L.w("diario: una foto no se pudo leer (" + e.getClass().getSimpleName() + ")");
            }
        }
        return out;
    }

    /** Puro: hasta max índices de 0..n-1 repartidos (primero, medio, último…). */
    static int[] spread(int n, int max) {
        int k = Math.min(n, max);
        int[] out = new int[k];
        for (int i = 0; i < k; i++) out[i] = k == 1 ? 0 : Math.round(i * (n - 1) / (float) (k - 1));
        return out;
    }

    // ------------------------------------------------------------------ ver y compartir

    /** La postal en grande, con «Compartir» y, si hay fotos, quitarlas o volver a ponerlas. Fuera del hilo principal la carga. */
    static void show(Activity a, TripLog.Trip t) {
        List<CloudHistory.Trip> history = CarCloud.history();
        new Thread(() -> {
            List<Bitmap> photos = photos(a, t);
            Bitmap with = render(a, t, history, photos);
            Bitmap without = photos.isEmpty() ? with : render(a, t, history, new ArrayList<>());
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                boolean[] withPhotos = {!photos.isEmpty()};
                ImageView iv = new ImageView(a);
                iv.setAdjustViewBounds(true);
                iv.setImageBitmap(with);
                int pad = Math.round(16 * a.getResources().getDisplayMetrics().density);
                iv.setPadding(pad, pad, pad, 0);
                com.google.android.material.dialog.MaterialAlertDialogBuilder d = new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                        .setView(iv)
                        .setPositiveButton(Str.get(R.string.hql_diary_share), (x, w) -> share(a, withPhotos[0] ? with : without, t))
                        .setNegativeButton(Str.get(R.string.hql_close), null);
                if (!photos.isEmpty()) d.setNeutralButton(Str.get(R.string.hql_diary_no_photos), null);
                androidx.appcompat.app.AlertDialog dlg = d.show();
                if (!photos.isEmpty()) {
                    android.widget.Button nb = dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
                    nb.setOnClickListener(v -> {
                        withPhotos[0] = !withPhotos[0];
                        iv.setImageBitmap(withPhotos[0] ? with : without);
                        nb.setText(Str.get(withPhotos[0] ? R.string.hql_diary_no_photos : R.string.hql_diary_with_photos));
                    });
                }
                L.i("diario: postal del viaje (" + photos.size() + " fotos, " + t.places.length + " sitios)");
            });
        }, "hql-diary").start();
    }

    private static void share(Activity a, Bitmap bmp, TripLog.Trip t) {
        try {
            File dir = new File(a.getCacheDir(), "export");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("sin carpeta");
            File out = new File(dir, "qonecta-viaje-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(t.startMs)) + ".png");
            try (FileOutputStream f = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(out.getName(), uri));
            a.startActivity(Intent.createChooser(send, Str.get(R.string.hql_diary_share)));
            L.i("diario: postal compartida");
        } catch (Exception e) {
            L.w("diario: no se pudo compartir (" + e.getClass().getSimpleName() + ")");
            android.widget.Toast.makeText(a, Str.get(R.string.hql_report_share_failed), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ aviso tras un viaje largo

    /**
     * Al terminar la sesión con el coche: si no vuelve en el margen de seguir el mismo viaje (TripCarry) y fue largo, un
     * aviso discreto «Tu viaje de 312 km: toca para ver la postal». Una vez por viaje.
     */
    static void afterTrip(Context ctx, long startMs) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            android.os.SystemClock.sleep(TripCarry.CONTINUE_MS + 60_000L);
            if (TripLog.liveStartMs() != 0) return; // otra sesión: el viaje sigue
            TripLog.Trip t = null;
            for (TripLog.Trip x : TripLog.recent(app, 5)) {
                if (x.startMs == startMs) t = x;
            }
            if (t == null || t.km < LONG_KM) return;
            android.content.SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (sp.getLong(K_LAST, 0) == startMs) return;
            sp.edit().putLong(K_LAST, startMs).apply();
            notifyReady(app, t);
        }, "hql-diary-after").start();
    }

    private static void notifyReady(Context app, TripLog.Trip t) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_diary_title), NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(app, TripMapActivity.class).putExtra(TripMapActivity.EXTRA_START, t.startMs)
                .putExtra(TripMapActivity.EXTRA_POSTCARD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(app, 31, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIF_ID, new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(app.getColor(R.color.hql_accent))
                .setContentTitle(Str.get(R.string.hql_diary_title))
                .setContentText(Str.get(R.string.hql_diary_ready, t.km))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
        L.i("diario: aviso de postal tras un viaje largo");
    }
}
