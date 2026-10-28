package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * El tiempo del pie del panel dibujado por nosotros, con una animación corta cada vez que se refresca (28-10, el usuario:
 * «ponle un sol o alguna animación»): sol con rayos que giran, luna con estrellas que parpadean, nube que entra, gotas o
 * copos que caen, rayo en tormenta, niebla que se desplaza. Nunca continua: ANIM_MS y se queda quieta (en el coche todo lo
 * que se mueve va por el vídeo y cuesta bitrate). Puro en {@link #kind}: se prueba en el PC. El color es el del texto
 * del panel (CarTheme.navTextDim), para que vaya bien sobre cualquier color de panel.
 */
final class WeatherGlyph extends View {
    static final long ANIM_MS = 1100;

    /** Lo que se dibuja, por el código WMO de Open-Meteo. */
    enum Kind { NONE, SUN, MOON, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

    /** Puro: el dibujo para un código WMO, de día o de noche (como RouteTab.weatherEmoji). */
    static Kind kind(int code, boolean day) {
        if (code < 0) return Kind.NONE;
        if (code == 0) return day ? Kind.SUN : Kind.MOON;
        if (code <= 2) return day ? Kind.PARTLY : Kind.MOON;
        if (code == 3) return Kind.CLOUD;
        if (code <= 48) return Kind.FOG;
        if (code <= 67) return Kind.RAIN;
        if (code <= 77) return Kind.SNOW;
        if (code <= 82) return Kind.RAIN;
        if (code <= 86) return Kind.SNOW;
        return Kind.STORM;
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private Kind kind = Kind.NONE;
    private int color = 0xFFA3B3C2;
    private long animStart;
    private boolean animate = true;

    WeatherGlyph(Context c) {
        super(c);
    }

    /** El tiempo nuevo (y el color del texto del panel): si cambia, vuelve a animar; si no, se queda como está. */
    void set(int code, boolean day, int color, boolean animate) {
        Kind k = kind(code, day);
        this.animate = animate;
        boolean changed = k != kind || color != this.color;
        kind = k;
        this.color = color;
        setVisibility(k == Kind.NONE ? GONE : VISIBLE);
        if (changed || animate) animStart = System.currentTimeMillis();
        invalidate();
    }

    /** 0..1 de la animación, 1 cuando terminó (o si no se anima). */
    private float phase() {
        if (!animate) return 1f;
        long t = System.currentTimeMillis() - animStart;
        return t >= ANIM_MS ? 1f : Math.max(0f, t / (float) ANIM_MS);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2, cy = h / 2;
        float u = phase();
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (kind) {
            case SUN:
                sun(cv, cx, cy, s * 0.22f, u);
                break;
            case MOON:
                moon(cv, cx, cy, s * 0.3f, u);
                break;
            case PARTLY:
                sun(cv, cx - s * 0.14f, cy - s * 0.12f, s * 0.16f, u);
                cloud(cv, cx + s * 0.06f, cy + s * 0.1f, s * 0.2f, u);
                break;
            case CLOUD:
                cloud(cv, cx, cy, s * 0.26f, u);
                break;
            case FOG:
                fog(cv, cx, cy, s, u);
                break;
            case RAIN:
                cloud(cv, cx, cy - s * 0.12f, s * 0.22f, 1f);
                drops(cv, cx, cy + s * 0.12f, s, u, false);
                break;
            case SNOW:
                cloud(cv, cx, cy - s * 0.12f, s * 0.22f, 1f);
                drops(cv, cx, cy + s * 0.12f, s, u, true);
                break;
            case STORM:
                cloud(cv, cx, cy - s * 0.12f, s * 0.22f, 1f);
                bolt(cv, cx, cy + s * 0.1f, s, u);
                break;
            default:
                break;
        }
        if (u < 1f) postInvalidateOnAnimation();
    }

    /** Disco con ocho rayos que giran un cuarto de vuelta (con freno) y crecen. */
    private void sun(Canvas cv, float cx, float cy, float r, float u) {
        float e = ease(u);
        cv.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, r * 0.22f));
        float rot = (1f - e) * 90f;
        float len = r * (0.45f + 0.35f * e);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(rot + i * 45);
            float x0 = cx + (float) Math.cos(a) * r * 1.35f, y0 = cy + (float) Math.sin(a) * r * 1.35f;
            cv.drawLine(x0, y0, x0 + (float) Math.cos(a) * len, y0 + (float) Math.sin(a) * len, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Luna en cuarto con dos estrellas que parpadean una vez. */
    private void moon(Canvas cv, float cx, float cy, float r, float u) {
        cv.save();
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        cv.clipRect(tmp);
        cv.drawCircle(cx, cy, r, p);
        int keep = p.getColor();
        p.setColor(0x00000000);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        cv.drawCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, p);
        p.setXfermode(null);
        p.setColor(keep);
        cv.restore();
        float tw = (float) Math.sin(u * Math.PI);
        float st = r * (0.18f + 0.12f * tw);
        star(cv, cx + r * 1.15f, cy - r * 0.9f, st);
        star(cv, cx + r * 1.45f, cy - r * 0.2f, st * 0.7f);
    }

    private void star(Canvas cv, float x, float y, float r) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, r * 0.35f));
        cv.drawLine(x - r, y, x + r, y, p);
        cv.drawLine(x, y - r, x, y + r, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Nube que entra deslizándose desde la izquierda y se asienta. */
    private void cloud(Canvas cv, float cx, float cy, float r, float u) {
        float e = ease(u);
        float dx = (1f - e) * -r * 1.2f;
        int a = p.getAlpha();
        p.setAlpha(Math.round(a * (0.3f + 0.7f * e)));
        cv.drawCircle(cx + dx - r * 0.55f, cy + r * 0.15f, r * 0.62f, p);
        cv.drawCircle(cx + dx + r * 0.1f, cy - r * 0.25f, r * 0.8f, p);
        cv.drawCircle(cx + dx + r * 0.75f, cy + r * 0.15f, r * 0.62f, p);
        tmp.set(cx + dx - r * 0.55f, cy + r * 0.1f, cx + dx + r * 0.75f, cy + r * 0.77f);
        cv.drawRoundRect(tmp, r * 0.3f, r * 0.3f, p);
        p.setAlpha(a);
    }

    /** Tres franjas que se desplazan en sentidos alternos y se asientan. */
    private void fog(Canvas cv, float cx, float cy, float s, float u) {
        float e = ease(u);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.09f));
        for (int i = -1; i <= 1; i++) {
            float dir = i % 2 == 0 ? 1 : -1;
            float dx = (1f - e) * dir * s * 0.25f;
            float y = cy + i * s * 0.24f;
            float half = s * (0.3f - Math.abs(i) * 0.05f);
            cv.drawLine(cx - half + dx, y, cx + half + dx, y, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Tres gotas (o copos) que caen desde la nube durante la animación y se quedan abajo. */
    private void drops(Canvas cv, float cx, float top, float s, float u, boolean snow) {
        float fall = s * 0.26f;
        for (int i = -1; i <= 1; i++) {
            float lag = (i + 1) * 0.15f;
            float f = Math.max(0f, Math.min(1f, (u - lag) / 0.7f));
            float x = cx + i * s * 0.2f;
            float y = top + f * fall;
            if (f <= 0f) continue;
            if (snow) {
                star(cv, x, y, s * 0.06f);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, s * 0.07f));
                cv.drawLine(x + s * 0.03f, y - s * 0.07f, x - s * 0.03f, y + s * 0.07f, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    /** Un rayo que parpadea dos veces y se queda encendido. */
    private void bolt(Canvas cv, float cx, float top, float s, float u) {
        boolean on = u >= 0.85f || (u > 0.15f && u < 0.3f) || (u > 0.45f && u < 0.6f);
        if (!on) return;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx + s * 0.06f, top - s * 0.02f);
        path.lineTo(cx - s * 0.08f, top + s * 0.14f);
        path.lineTo(cx + s * 0.01f, top + s * 0.14f);
        path.lineTo(cx - s * 0.06f, top + s * 0.32f);
        path.lineTo(cx + s * 0.1f, top + s * 0.1f);
        path.lineTo(cx + s * 0.01f, top + s * 0.1f);
        path.close();
        int keep = p.getColor();
        p.setColor(CarKit.AMBER);
        cv.drawPath(path, p);
        p.setColor(keep);
    }

    /** Puro: una curva de freno suave (el final se nota más lento). */
    static float ease(float u) {
        return 1f - (1f - u) * (1f - u) * (1f - u);
    }
}
