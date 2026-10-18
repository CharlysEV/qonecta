package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * La curva de la carga en directo: kW que entran frente a %, lo que va dando de verdad (línea llena) y lo que se espera
 * del C10 en este cargador (discontinua: la curva del coche limitada por el cargador y, en uno de 400–500 V, a la
 * mitad), con una marca en el % para seguir (verde) y en el que ahorra la siguiente parada.
 *
 * La carga lenta (CA, en casa) va casi plana en kW durante horas: ahí se pinta el % frente a la hora, lo que lleva
 * (llena) y lo que le falta al ritmo de ahora hasta el objetivo o el 100 % (discontinua).
 */
final class ChargeCurve extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DashPathEffect dash = new DashPathEffect(new float[]{12, 10}, 0);
    private ChargeSession session;

    ChargeCurve(Context c) {
        super(c);
    }

    void setSession(ChargeSession s) {
        session = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        ChargeSession s = session;
        if (s == null) return;
        if (!s.dc) {
            drawSlow(cv, s);
            return;
        }
        float left = 64;
        float right = getWidth() - 12;
        float top = 12;
        float bottom = getHeight() - 34;
        if (right - left < 60 || bottom - top < 40) return;
        List<ChargeSession.Sample> pts;
        synchronized (s) {
            pts = new ArrayList<>(s.samples);
        }
        // Ejes: % desde un poco antes del inicio hasta el 100; kW hasta lo más alto que se espera o se ha visto.
        double x0 = Math.max(0, Math.floor((s.startSoc - 5) / 10) * 10);
        double x1 = 100;
        double maxKw = s.expectedKw(Math.max(x0, 1));
        for (ChargeSession.Sample q : pts) if (!Double.isNaN(q.kw)) maxKw = Math.max(maxKw, q.kw);
        maxKw = Math.max(10, Math.ceil(maxKw * 1.15 / 10) * 10);
        p.setStrokeWidth(1);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(CarKit.OUTLINE);
        for (int k = 0; k <= 4; k++) {
            float y = (float) (bottom - (bottom - top) * k / 4.0);
            cv.drawLine(left, y, right, y, p);
            String v = String.format(Locale.getDefault(), k == 4 ? "%.0f kW" : "%.0f", maxKw * k / 4);
            CarKit.text(cv, v, k == 4 ? left + 8 : left - 10, y + (k == 4 ? 22 : 7), 18, CarKit.FAINT, CarKit.REGULAR, p,
                    k == 4 ? Paint.Align.LEFT : Paint.Align.RIGHT);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(CarKit.OUTLINE);
        }
        for (double pct = Math.ceil(x0 / 20) * 20; pct <= x1; pct += 20) {
            float x = xOf(pct, x0, x1, left, right);
            // El último, pegado al borde: alineado a la derecha para que no se corte.
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f %%", pct), x, getHeight() - 6, 18, CarKit.FAINT, CarKit.REGULAR, p,
                    pct >= x1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
        }
        // Marcas: seguir (verde) y ahorrar parada (tenue).
        marker(cv, s.skipPct, x0, x1, left, right, top, bottom, CarKit.alpha(CarKit.DIM, 0.6f));
        marker(cv, s.targetPct, x0, x1, left, right, top, bottom, CarKit.GREEN);
        // Lo esperado (discontinua).
        path.reset();
        boolean first = true;
        for (double pct = x0; pct <= x1; pct += 1) {
            float x = xOf(pct, x0, x1, left, right);
            float y = yOf(s.expectedKw(pct), maxKw, top, bottom);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(CarKit.DIM);
        p.setPathEffect(dash);
        cv.drawPath(path, p);
        p.setPathEffect(null);
        // Lo real.
        path.reset();
        first = true;
        for (ChargeSession.Sample q : pts) {
            if (Double.isNaN(q.kw)) continue;
            float x = xOf(q.soc, x0, x1, left, right);
            float y = yOf(q.kw, maxKw, top, bottom);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        p.setStrokeWidth(5);
        p.setColor(s.slowFired ? CarKit.AMBER : CarKit.ACCENT);
        cv.drawPath(path, p);
        ChargeSession.Sample l = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        if (l != null && !Double.isNaN(l.kw)) {
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(xOf(l.soc, x0, x1, left, right), yOf(l.kw, maxKw, top, bottom), 8, p);
        }
    }

    /** Carga lenta: % (vertical) frente a la hora (horizontal), lo que lleva y lo que le falta al ritmo de ahora. */
    private void drawSlow(Canvas cv, ChargeSession s) {
        float left = 64;
        float right = getWidth() - 16;
        float top = 12;
        float bottom = getHeight() - 34;
        if (right - left < 60 || bottom - top < 40) return;
        List<ChargeSession.Sample> pts;
        synchronized (s) {
            pts = new ArrayList<>(s.samples);
        }
        if (pts.isEmpty()) return;
        ChargeSession.Sample l = pts.get(pts.size() - 1);
        double goal = Double.isNaN(s.targetPct) ? 100 : s.targetPct;
        double pace = s.pctPerHour();
        long t0 = Math.min(s.startMs, pts.get(0).atMs);
        long end = SlowAxis.endMs(l.atMs, l.soc, goal, pace, s.carRemainMin);
        long t1 = Math.max(end, t0 + 3600_000L);
        double y0 = Math.max(0, Math.floor((Math.min(s.startSoc, pts.get(0).soc) - 5) / 10) * 10);
        // Rejilla del %: cuatro líneas.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        for (int k = 0; k <= 4; k++) {
            double pct = y0 + (100 - y0) * k / 4.0;
            float y = yPct(pct, y0, top, bottom);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(CarKit.OUTLINE);
            cv.drawLine(left, y, right, y, p);
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f %%", pct), left - 10, y + 7, 18, CarKit.FAINT, CarKit.REGULAR, p,
                    Paint.Align.RIGHT);
        }
        // Horas abajo: cada 1, 2 o 4 h según lo larga que sea.
        long step = SlowAxis.stepMs(t1 - t0);
        for (long t = SlowAxis.firstTick(t0, step); t <= t1; t += step) {
            float x = xTime(t, t0, t1, left, right);
            CarKit.text(cv, ChargeWatch.time(t), x, getHeight() - 6, 18, CarKit.FAINT, CarKit.REGULAR, p,
                    x > right - 30 ? Paint.Align.RIGHT : x < left + 30 ? Paint.Align.LEFT : Paint.Align.CENTER);
        }
        // El objetivo (si no es el 100 %).
        if (goal < 99.5) {
            float y = yPct(goal, y0, top, bottom);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(CarKit.GREEN);
            cv.drawLine(left, y, right, y, p);
        }
        // Lo que le falta al ritmo de ahora (discontinua).
        if (end > l.atMs && l.soc < goal) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(CarKit.DIM);
            p.setPathEffect(dash);
            path.reset();
            path.moveTo(xTime(l.atMs, t0, t1, left, right), yPct(l.soc, y0, top, bottom));
            path.lineTo(xTime(end, t0, t1, left, right), yPct(goal, y0, top, bottom));
            cv.drawPath(path, p);
            p.setPathEffect(null);
        }
        // Lo que lleva.
        path.reset();
        boolean first = true;
        for (ChargeSession.Sample q : pts) {
            float x = xTime(q.atMs, t0, t1, left, right);
            float y = yPct(q.soc, y0, top, bottom);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5);
        p.setColor(CarKit.ACCENT);
        cv.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(xTime(l.atMs, t0, t1, left, right), yPct(l.soc, y0, top, bottom), 8, p);
    }

    private static float xTime(long t, long t0, long t1, float left, float right) {
        return (float) (left + (right - left) * Math.max(0, Math.min(1, (t - t0) / (double) (t1 - t0))));
    }

    private static float yPct(double pct, double y0, float top, float bottom) {
        return (float) (bottom - (bottom - top) * Math.max(0, Math.min(1, (pct - y0) / (100 - y0))));
    }

    /** Las cuentas del eje de la carga lenta. Puras: se prueban en el PC. */
    static final class SlowAxis {
        private SlowAxis() {
        }

        /**
         * Cuándo llegará al objetivo: al ritmo de ahora (%/h) o, si aún no se sabe, lo que dice el coche (minutos); sin
         * nada, una hora más. Como mucho 16 h.
         */
        static long endMs(long lastMs, double soc, double goal, double pctPerHour, double carRemainMin) {
            double h;
            if (soc >= goal) h = 0;
            else if (!Double.isNaN(pctPerHour) && pctPerHour > 0.1) h = (goal - soc) / pctPerHour;
            else if (!Double.isNaN(carRemainMin) && carRemainMin > 0) h = carRemainMin / 60;
            else h = 1;
            return lastMs + Math.round(Math.min(16, h) * 3600_000L);
        }

        /** Marcas de hora: cada hora hasta 6 h, cada 2 hasta 12 y cada 4 en adelante. */
        static long stepMs(long spanMs) {
            long h = 3600_000L;
            return spanMs <= 6 * h ? h : spanMs <= 12 * h ? 2 * h : 4 * h;
        }

        /** La primera marca en punto (en la hora local) a partir de t0. */
        static long firstTick(long t0, long step) {
            long off = java.util.TimeZone.getDefault().getOffset(t0);
            long local = t0 + off;
            return (local + step - 1) / step * step - off;
        }
    }

    private void marker(Canvas cv, double pct, double x0, double x1, float left, float right, float top, float bottom, int color) {
        if (Double.isNaN(pct) || pct < x0 || pct > x1) return;
        float x = xOf(pct, x0, x1, left, right);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(color);
        cv.drawLine(x, top, x, bottom, p);
    }

    private static float xOf(double pct, double x0, double x1, float left, float right) {
        return (float) (left + (right - left) * (Math.max(x0, Math.min(x1, pct)) - x0) / (x1 - x0));
    }

    private static float yOf(double kw, double maxKw, float top, float bottom) {
        return (float) (bottom - (bottom - top) * Math.max(0, Math.min(1, kw / maxKw)));
    }
}
