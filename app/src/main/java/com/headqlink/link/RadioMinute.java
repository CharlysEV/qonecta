package com.headqlink.link;

import java.util.Arrays;
import java.util.Locale;

/**
 * Resumen por minuto del enlace por Wi-Fi (zona Wi-Fi o Wi-Fi Direct), con las muestras de TCP_INFO que toma el monitor
 * cada 100 ms: retransmisiones, rtt (mediana, p95 y máximo), picos de rtt por encima de SPIKE_MS (cada pico cuenta una
 * vez, no cada muestra), la cola máxima y cuánto rato estuvo encendida la pantalla del móvil. Para ver de dónde salen los
 * saltos de imagen por Wi-Fi Direct (16-10: ráfagas de retransmisiones cada ~10 s con la pantalla apagada; el modo Wi-Fi
 * de baja latencia que pide la app solo vale con la pantalla encendida). Puro, salvo el log que hace quien lo usa.
 */
final class RadioMinute {
    /** Un rtt por encima de esto es un pico (lo normal por Wi-Fi Direct son ~5 ms). */
    static final int SPIKE_MS = 50;
    /** Muestras de un minuto a 100 ms (con margen). */
    private static final int CAP = 700;

    private final int[] rtts = new int[CAP];
    private int n;
    private int spikes;
    private boolean inSpike;
    private int maxOutq;
    private long firstRetrans = -1;
    private long lastRetrans;
    private int screenOn;
    private int screenSamples;

    /** Una muestra del socket: rtt en ms, retransmisiones totales de la conexión y bytes por enviar. */
    void add(int rttMs, long retransTotal, int outq) {
        if (rttMs >= 0 && n < CAP) rtts[n++] = rttMs;
        boolean spike = rttMs > SPIKE_MS;
        if (spike && !inSpike) spikes++;
        inSpike = spike;
        maxOutq = Math.max(maxOutq, outq);
        if (retransTotal >= 0) {
            if (firstRetrans < 0) firstRetrans = retransTotal;
            lastRetrans = retransTotal;
        }
    }

    /** Si la pantalla del móvil está encendida ahora (una vez por segundo basta). */
    void screen(boolean on) {
        screenSamples++;
        if (on) screenOn++;
    }

    boolean isEmpty() {
        return n == 0;
    }

    int retrans() {
        return firstRetrans < 0 ? 0 : (int) Math.max(0, lastRetrans - firstRetrans);
    }

    int spikes() {
        return spikes;
    }

    /** El rtt del percentil p (0-100) de las muestras, o -1 si no hay. */
    int rttPercentile(int p) {
        if (n == 0) return -1;
        int[] s = Arrays.copyOf(rtts, n);
        Arrays.sort(s);
        return s[Math.min(n - 1, (int) Math.floor((n - 1) * p / 100.0))];
    }

    /** «radio, minuto 3: retrans +42 · rtt 5 ms (p95 61, máx. 136) · 7 picos de más de 50 ms · cola máx. 71 KB · pantalla del móvil apagada». */
    String line(int minute) {
        String screen;
        if (screenSamples == 0) screen = "";
        else if (screenOn == 0) screen = " · pantalla del móvil apagada";
        else if (screenOn == screenSamples) screen = " · pantalla del móvil encendida";
        else screen = String.format(Locale.US, " · pantalla del móvil encendida el %d %%", Math.round(100f * screenOn / screenSamples));
        return String.format(Locale.US, "radio, minuto %d: retrans +%d · rtt %d ms (p95 %d, máx. %d) · %d picos de más de %d ms · cola máx. %d KB%s",
                minute, retrans(), rttPercentile(50), rttPercentile(95), rttPercentile(100), spikes, SPIKE_MS,
                maxOutq / 1024, screen);
    }

    /** Empieza otro minuto; las retransmisiones siguen desde el último total visto. */
    void reset() {
        n = 0;
        spikes = 0;
        inSpike = false;
        maxOutq = 0;
        firstRetrans = firstRetrans < 0 ? -1 : lastRetrans;
        screenOn = 0;
        screenSamples = 0;
    }
}
