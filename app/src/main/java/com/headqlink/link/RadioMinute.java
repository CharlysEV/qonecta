package com.headqlink.link;

import java.util.Arrays;
import java.util.Locale;

/**
 * Resumen por minuto del enlace por Wi-Fi (zona Wi-Fi o Wi-Fi Direct), con las muestras de TCP_INFO que toma el monitor
 * cada 100 ms: retransmisiones, rtt (mediana, p95 y máximo), picos de rtt por encima de SPIKE_MS (cada pico cuenta una
 * vez, no cada muestra), la cola máxima y cuánto rato estuvo encendida la pantalla del móvil. Para ver de dónde salen los
 * saltos de imagen por Wi-Fi Direct (16-10: ráfagas de retransmisiones cada ~10 s con la pantalla apagada; el modo Wi-Fi
 * de baja latencia que pide la app solo vale con la pantalla encendida). Puro, salvo el log que hace quien lo usa.
 * <p>
 * 19-10: los picos subían con el vídeo mandado (11 por minuto con 0,6 Mbit/s, 18 con 1,8), pero aun con poco quedaban
 * unos 11, con el móvil sin red Wi-Fi (Android busca redes cada poco, y mientras busca la radio deja el canal del coche) y
 * con audio por el Bluetooth del coche. Por eso también: en qué segundo empieza cada pico y cuánto dura el más largo,
 * cuándo acaba Android una búsqueda de redes y cuántos picos caen junto a una, y cuánto rato suena audio.
 */
final class RadioMinute {
    /** Un rtt por encima de esto es un pico (lo normal por Wi-Fi Direct son ~5 ms). */
    static final int SPIKE_MS = 50;
    /** Un pico va «junto a una búsqueda» si empieza entre esto antes de que acabe (lo que tarda en recorrer los canales)… */
    static final int SCAN_BEFORE_MS = 6000;
    /** …y esto después. */
    static final int SCAN_AFTER_MS = 1000;
    /** Muestras de un minuto a 100 ms (con margen). */
    private static final int CAP = 700;
    /** Cuántos picos y búsquedas por minuto se apuntan con su hora. */
    private static final int MAX_MARKS = 60;

    private final int[] rtts = new int[CAP];
    private int n;
    private int spikes;
    private boolean inSpike;
    private int maxOutq;
    private long firstRetrans = -1;
    private long lastRetrans;
    private int screenOn;
    private int screenSamples;
    private final int[] spikeAt = new int[MAX_MARKS];
    private int marks;
    private long spikeStart = -1;
    private long lastSample = -1;
    private int longestMs;
    private boolean scansWatched;
    private final int[] scanAt = new int[MAX_MARKS];
    private int scans;
    private int audioOn;
    private int audioSamples;

    /** Una muestra del socket sin hora (no se apunta en qué segundo empieza cada pico). */
    void add(int rttMs, long retransTotal, int outq) {
        add(-1, rttMs, retransTotal, outq);
    }

    /**
     * Una muestra del socket: ms desde el principio del minuto, rtt en ms, retransmisiones totales de la conexión y bytes
     * por enviar.
     */
    void add(long atMs, int rttMs, long retransTotal, int outq) {
        if (rttMs >= 0 && n < CAP) rtts[n++] = rttMs;
        boolean spike = rttMs > SPIKE_MS;
        if (spike && !inSpike) {
            spikes++;
            spikeStart = atMs;
            if (atMs >= 0 && marks < MAX_MARKS) spikeAt[marks++] = (int) atMs;
        } else if (!spike && inSpike && spikeStart >= 0 && atMs >= 0) {
            longestMs = (int) Math.max(longestMs, atMs - spikeStart);
        }
        inSpike = spike;
        if (atMs >= 0) lastSample = atMs;
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

    /** Si suena audio ahora (una vez por segundo basta). */
    void audio(boolean playing) {
        audioSamples++;
        if (playing) audioOn++;
    }

    /** Se están mirando las búsquedas de redes Wi-Fi de Android (sin ninguna, el resumen lo dice). */
    void watchScans() {
        scansWatched = true;
    }

    /** Android acabó una búsqueda de redes Wi-Fi (ms desde el principio del minuto). */
    void wifiScan(long atMs) {
        if (atMs >= 0 && scans < MAX_MARKS) scanAt[scans++] = (int) atMs;
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

    /** Lo que duró el pico más largo (ms), contando el que sigue abierto. */
    int longestSpikeMs() {
        int l = longestMs;
        if (inSpike && spikeStart >= 0 && lastSample >= 0) l = (int) Math.max(l, lastSample - spikeStart + 100);
        return l;
    }

    /** Cuántos de los picos apuntados empiezan junto a una búsqueda de redes (SCAN_BEFORE_MS antes, SCAN_AFTER_MS después). */
    int spikesNearScan() {
        int c = 0;
        for (int i = 0; i < marks; i++) {
            for (int j = 0; j < scans; j++) {
                if (spikeAt[i] >= scanAt[j] - SCAN_BEFORE_MS && spikeAt[i] <= scanAt[j] + SCAN_AFTER_MS) {
                    c++;
                    break;
                }
            }
        }
        return c;
    }

    /**
     * «radio, minuto 3: retrans +42 · rtt 5 ms (p95 61, máx. 136) · 7 picos de más de 50 ms · cola máx. 71 KB · pantalla
     * del móvil apagada · picos a los 3, 13, 24 s, el más largo 300 ms · Android buscó redes Wi-Fi 2 veces (a los 12, 44 s),
     * 2 picos junto a una búsqueda · audio sonando el 80 %».
     */
    String line(int minute) {
        String screen;
        if (screenSamples == 0) screen = "";
        else if (screenOn == 0) screen = " · pantalla del móvil apagada";
        else if (screenOn == screenSamples) screen = " · pantalla del móvil encendida";
        else screen = String.format(Locale.US, " · pantalla del móvil encendida el %d %%", Math.round(100f * screenOn / screenSamples));
        StringBuilder b = new StringBuilder(String.format(Locale.US,
                "radio, minuto %d: retrans +%d · rtt %d ms (p95 %d, máx. %d) · %d picos de más de %d ms · cola máx. %d KB%s",
                minute, retrans(), rttPercentile(50), rttPercentile(95), rttPercentile(100), spikes, SPIKE_MS,
                maxOutq / 1024, screen));
        if (marks > 0) {
            b.append(" · picos a los ").append(seconds(spikeAt, marks)).append(" s");
            int l = longestSpikeMs();
            if (l > 0) b.append(", el más largo ").append(l).append(" ms");
        }
        if (scansWatched) {
            if (scans == 0) {
                b.append(" · Android no buscó redes Wi-Fi");
            } else {
                b.append(" · Android buscó redes Wi-Fi ").append(scans).append(scans == 1 ? " vez" : " veces")
                        .append(" (a los ").append(seconds(scanAt, scans)).append(" s)");
                if (marks > 0) {
                    int near = spikesNearScan();
                    b.append(", ").append(near).append(near == 1 ? " pico" : " picos").append(" junto a una búsqueda");
                }
            }
        }
        if (audioSamples > 0) {
            if (audioOn == 0) b.append(" · sin audio");
            else if (audioOn == audioSamples) b.append(" · audio sonando");
            else b.append(String.format(Locale.US, " · audio sonando el %d %%", Math.round(100f * audioOn / audioSamples)));
        }
        return b.toString();
    }

    private static String seconds(int[] ms, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) b.append(", ");
            b.append(ms[i] / 1000);
        }
        return b.toString();
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
        marks = 0;
        spikeStart = -1;
        lastSample = -1;
        longestMs = 0;
        scans = 0;
        audioOn = 0;
        audioSamples = 0;
    }
}
