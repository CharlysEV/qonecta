package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.File;

/**
 * Pantalla de carga en el coche mientras Android Auto arranca. Desde el 28-10 (el «efecto WOW»), tu C10 de verdad en
 * tu color: la imagen que entrega el 3D de la pestaña Estado o de «Mi coche» ({@link #saveCarImage}), que entra
 * girando y creciendo sobre un resplandor del acento, con un barrido de luz que la recorre; debajo, la marca y el
 * estado. Sin imagen aún (primer uso), el coche de frente de siempre con el destello en su tira de luz.
 */
final class SplashView extends View {
    private static final String CAR_IMAGE = "car-splash.png";
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = System.nanoTime();
    private final int bg;
    private final Drawable logo;
    private final Bitmap car;
    private final RectF tmp = new RectF();
    private boolean stopped;

    SplashView(Context c, int bg) {
        super(c);
        this.bg = bg;
        this.logo = c.getDrawable(R.drawable.hql_logo);
        this.car = loadCarImage(c);
    }

    /** Deja de animar (AA ya se ve encima). */
    void stop() {
        stopped = true;
    }

    // ------------------------------------------------------------------ la imagen del coche

    /** La imagen del 3D («data:image/png;base64,…»), guardada en files/ para el arranque. En su hilo: pesa ~100 KB. */
    static void saveCarImage(Context c, String dataUrl) {
        if (dataUrl == null) return;
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.startsWith("data:image/png")) return;
        String b64 = dataUrl.substring(comma + 1);
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        new Thread(() -> {
            try {
                byte[] png = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                File f = new File(app.getFilesDir(), CAR_IMAGE);
                File tmpF = new File(app.getFilesDir(), CAR_IMAGE + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmpF)) {
                    out.write(png);
                }
                if (!tmpF.renameTo(f)) L.w("arranque: no se pudo guardar la imagen del coche");
                else L.i("arranque: imagen del coche guardada (" + png.length / 1024 + " KB)");
            } catch (RuntimeException | java.io.IOException e) {
                L.w("arranque: imagen del coche no guardada (" + e.getClass().getSimpleName() + ")");
            }
        }, "hql-car-image").start();
    }

    /** La imagen guardada, o null (primer uso, o ilegible). */
    static Bitmap loadCarImage(Context c) {
        File f = new File(c.getFilesDir(), CAR_IMAGE);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float t = (System.nanoTime() - t0) / 1e9f;
        cv.drawColor(bg);
        if (car != null) drawCar3d(cv, w, h, t);
        else drawCarFront(cv, w, h, t);

        // Marca: la Q con rayo y el nombre, centrados juntos; aparecen tras el coche.
        float fade = Math.max(0f, Math.min(1f, (t - 0.5f) / 0.6f));
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(withAlpha(CarStyle.TEXT, fade));
        p.setTextSize(h * 0.07f);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setLetterSpacing(0.14f);
        String name = "QONECTA+";
        float tw = p.measureText(name);
        float ls = h * 0.095f;
        float gap = ls * 0.32f;
        float x0 = w / 2 - (ls + gap + tw) / 2;
        float base = h * 0.80f;
        if (logo != null) {
            int top = Math.round(base - p.getTextSize() * 0.36f - ls / 2);
            logo.setBounds(Math.round(x0), top, Math.round(x0 + ls), Math.round(top + ls));
            logo.setAlpha(Math.round(255 * fade));
            logo.draw(cv);
        }
        cv.drawText(name, x0 + ls + gap, base, p);
        p.setLetterSpacing(0f);

        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(withAlpha(CarStyle.TEXT_DIM, fade));
        p.setTextSize(h * 0.034f);
        int dots = (int) (t * 2) % 4;
        cv.drawText(Str.get(R.string.hql_starting_auto) + "...".substring(0, dots) + "   ".substring(dots), w / 2, h * 0.88f, p);
        // De quién es la app y de qué parte, pequeño y en el color de la app.
        p.setColor(withAlpha(CarStyle.ACCENT, fade));
        p.setTextSize(h * 0.028f);
        cv.drawText(Str.get(R.string.hql_about_by, About.AUTHOR) + " · " + Str.get(R.string.hql_about_from, About.BASE_AUTHOR),
                w / 2, h * 0.95f, p);
        if (!stopped && isAttachedToWindow()) postInvalidateOnAnimation();
    }

    /**
     * El coche de verdad: un resplandor del acento detrás que respira, y la imagen que entra en 0,9 s girando desde
     * −12° y creciendo desde el 80 %, y después un barrido de luz de lado a lado cada 2,2 s.
     */
    private void drawCar3d(Canvas cv, float w, float h, float t) {
        float u = WeatherGlyph.ease(Math.min(1f, t / 0.9f));
        float cx = w / 2, cy = h * 0.42f;
        // Resplandor.
        float breathe = 0.85f + 0.15f * (float) Math.sin(t * 1.6);
        float gr = Math.min(w, h) * 0.42f * breathe;
        p.setShader(new RadialGradient(cx, cy, gr, new int[]{0x3C00E5C7, 0x1200E5C7, 0x0000E5C7}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, gr, p);
        p.setShader(null);
        // La imagen: que quepa en un 56 % del ancho o un 62 % del alto.
        float scaleFit = Math.min(w * 0.56f / car.getWidth(), h * 0.62f / car.getHeight());
        float scale = scaleFit * (0.8f + 0.2f * u);
        cv.save();
        cv.translate(cx, cy);
        cv.rotate(-12f * (1f - u));
        cv.scale(scale, scale);
        p.setAlpha(Math.round(255 * Math.min(1f, u * 1.5f)));
        cv.drawBitmap(car, -car.getWidth() / 2f, -car.getHeight() / 2f, p);
        p.setAlpha(255);
        cv.restore();
        // Barrido de luz sobre el coche, una banda inclinada que lo recorre.
        if (u >= 1f) {
            float period = 2.2f;
            float phase = ((t - 0.9f) % period) / period;
            float bw = car.getWidth() * scale, bh = car.getHeight() * scale;
            float x = cx - bw / 2 - bh * 0.3f + (bw + bh * 0.6f) * phase;
            cv.save();
            tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            cv.clipRect(tmp);
            p.setShader(new LinearGradient(x - bh * 0.18f, 0, x + bh * 0.18f, 0,
                    new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            cv.skew(-0.35f, 0f);
            cv.drawRect(x - bh * 0.4f, tmp.top, x + bh * 0.4f + bh * 0.35f, tmp.bottom, p);
            p.setShader(null);
            cv.restore();
        }
    }

    /** Sin imagen: el coche de frente de siempre con el destello cian → verde en su tira de luz. */
    private void drawCarFront(Canvas cv, float w, float h, float t) {
        float side = Math.min(w * 0.42f, h * 0.52f);
        RectF car = new RectF(w / 2 - side / 2, h * 0.40f - side / 2, w / 2 + side / 2, h * 0.40f + side / 2);
        CarArt.front(cv, car, p);
        RectF bar = CarArt.frontLightBar(car);
        float phase = (float) (0.5 - 0.5 * Math.cos(t * Math.PI / 1.1));
        float cx = bar.left + bar.width() * phase;
        float glow = bar.width() * 0.22f;
        p.setShader(new LinearGradient(cx - glow, 0, cx + glow, 0,
                new int[]{CarStyle.ACCENT & 0x00FFFFFF, CarStyle.ACCENT, CarStyle.ACCENT_2, CarStyle.ACCENT_2 & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        RectF lit = new RectF(bar.left, bar.top - bar.height() * 0.6f, bar.right, bar.bottom + bar.height() * 0.6f);
        cv.drawRoundRect(lit, lit.height() / 2, lit.height() / 2, p);
        p.setShader(null);
    }

    private static int withAlpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24);
    }
}
