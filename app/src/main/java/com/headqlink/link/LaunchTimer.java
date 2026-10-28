package com.headqlink.link;

import java.util.Locale;

/**
 * Cronómetro 0-50 y 0-100 km/h con la velocidad del GPS del móvil. Puro: se prueba en el PC (LaunchTimerTest).
 *
 * El GPS da una posición cada ~0,5–1 s y antes se tomaba la primera que pasaba de 3 km/h como salida y la primera que
 * pasaba de 100 como llegada: cada extremo podía llegar hasta un segundo tarde (medio segundo de error de media, 28-10).
 * Ahora:
 * - la salida (0 km/h) sale de las dos primeras posiciones en marcha: la recta que las une, hacia atrás hasta 0 (al
 *   arrancar, un eléctrico acelera casi igual hasta 60–70 km/h). Entre la última posición parada y la primera en marcha
 *   no se sabe cuándo salió: interpolar ahí se iba hasta un segundo;
 * - las llegadas (50 y 100 km/h) se interpolan entre las dos posiciones de alrededor;
 * - las horas son las de cada posición del GPS (Location.getElapsedRealtimeNanos), no las de cuando llega a la app.
 * La velocidad del GPS va algo retrasada (el móvil la suaviza), pero igual en la salida y en la llegada: se anula.
 */
final class LaunchTimer {
    /** Por debajo, parado: se arma. */
    static final double ARM_KMH = 2;
    /** La primera posición por encima es la primera «en marcha». */
    static final double START_KMH = 3;
    /** Sin llegar a 100 en esto, no era un arranque: se olvida. */
    static final long MAX_MS = 30_000;
    /** Aceleraciones creíbles para llevar la salida a 0 km/h (m/s²); fuera, se interpola desde la posición parada. */
    static final double MIN_ACCEL = 0.3;
    static final double MAX_ACCEL = 15;

    private boolean armed;
    /** Última posición parada (ms), o NaN. */
    private double standMs = Double.NaN;
    /** Primera posición en marcha, a la espera de la segunda para saber la salida (NaN si no hay). */
    private double firstMs = Double.NaN;
    private double firstKmh;
    /** Instante (ms) de la salida, o NaN sin arranque en marcha. */
    private double startMs = Double.NaN;
    private boolean has50;
    private String text50 = "";
    private double prevKmh = -1;
    private double prevMs;

    /**
     * Una velocidad nueva del GPS en el instante nowMs (de la posición). Devuelve el texto nuevo del cronómetro
     * («0-50 km/h: 3,5 s», luego «0-50 km/h: 3,5 s · 0-100 km/h: 6,9 s»), o null si no cambia.
     */
    String fix(double nowMs, double kmh) {
        String out = null;
        if (kmh < ARM_KMH) {
            armed = true;
            standMs = nowMs;
            firstMs = Double.NaN;
            startMs = Double.NaN;
            has50 = false;
            text50 = "";
        } else if (prevKmh >= 0 && nowMs > prevMs) {
            if (armed && Double.isNaN(startMs)) {
                if (Double.isNaN(firstMs)) {
                    if (kmh > START_KMH) {
                        firstMs = nowMs;
                        firstKmh = kmh;
                        // Un hueco del GPS (ya muy deprisa): no da para esperar a la segunda.
                        if (kmh >= 50) startMs = fromStand(prevMs, prevKmh, nowMs, kmh);
                    }
                } else if (kmh > firstKmh) {
                    double z = zeroTime(firstMs, firstKmh, nowMs, kmh);
                    startMs = Double.isNaN(z) ? fromStand(standMs, 0, firstMs, firstKmh)
                            : Math.min(firstMs, Double.isNaN(standMs) ? z : Math.max(standMs, z));
                }
            }
            if (!Double.isNaN(startMs) && nowMs - startMs > MAX_MS) {
                armed = false;
                startMs = Double.NaN;
            }
            if (!Double.isNaN(startMs)) {
                if (!has50 && kmh >= 50) {
                    has50 = true;
                    text50 = String.format(Locale.getDefault(), "0-50 km/h: %.1f s", (cross(prevMs, prevKmh, nowMs, kmh, 50) - startMs) / 1000);
                    out = text50;
                }
                if (kmh >= 100) {
                    out = text50 + String.format(Locale.getDefault(), " · 0-100 km/h: %.1f s",
                            (cross(prevMs, prevKmh, nowMs, kmh, 100) - startMs) / 1000);
                    armed = false;
                    startMs = Double.NaN;
                }
            }
        }
        prevKmh = kmh;
        prevMs = nowMs;
        return out;
    }

    /** Como al empezar (un viaje nuevo). */
    void reset() {
        armed = false;
        standMs = Double.NaN;
        firstMs = Double.NaN;
        startMs = Double.NaN;
        has50 = false;
        text50 = "";
        prevKmh = -1;
    }

    /**
     * Puro: el instante (ms) en que la velocidad pasó por kmh entre dos posiciones (interpolando); si ya lo había pasado
     * en la primera, el de la primera.
     */
    static double cross(double t0, double v0, double t1, double v1, double kmh) {
        if (v0 >= kmh || v1 <= v0) return v0 >= kmh ? t0 : t1;
        return t0 + (kmh - v0) / (v1 - v0) * (t1 - t0);
    }

    /**
     * Puro: el instante (ms) de 0 km/h por la recta que une dos posiciones en marcha (hacia atrás), o NaN si la
     * aceleración entre ellas no es creíble (un salto del GPS, o casi sin acelerar).
     */
    static double zeroTime(double t0, double v0, double t1, double v1) {
        double accel = (v1 - v0) / 3.6 / ((t1 - t0) / 1000.0);
        if (!(accel >= MIN_ACCEL && accel <= MAX_ACCEL)) return Double.NaN;
        return t0 - v0 / 3.6 / accel * 1000;
    }

    /** Puro: sin dos posiciones en marcha, la salida interpolando a 0 km/h desde la anterior (parada o no). */
    static double fromStand(double t0, double v0, double t1, double v1) {
        if (Double.isNaN(t0)) return t1;
        double z = zeroTime(t0, v0, t1, v1);
        return Double.isNaN(z) ? cross(t0, v0, t1, v1, START_KMH) : Math.max(t0, z);
    }
}
