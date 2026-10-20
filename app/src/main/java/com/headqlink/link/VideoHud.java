package com.headqlink.link;

import android.content.Context;

import com.andrerinas.openheadunit.R;

import java.util.Locale;

/**
 * El marcador de la imagen, arriba a la derecha de la pantalla del coche (CarUi): temperatura de la batería, calor de
 * Android, fps y Mbit/s que salen hacia el coche y, por Wi-Fi, el rtt del enlace (el último y el máximo desde el
 * marcador anterior): «35 °C · calor 0 · 53 fps · 0,7 Mbit/s · rtt 4/12 ms». Nació en la variante de pruebas; desde el
 * 20-10 lo ve todo el mundo (Ajustes › Imagen › Marcador de la imagen, encendido de serie).
 */
final class VideoHud {
    private static volatile double txFps = -1;
    private static volatile double txKbps = -1;
    private static volatile int rttLast = -1;
    private static volatile int rttMax = -1;

    private VideoHud() {
    }

    /** Sesión nueva: sin cifras de la anterior (por el cable no hay rtt; no se arrastra el de una sesión por Wi-Fi). */
    static void reset() {
        txFps = -1;
        txKbps = -1;
        rttLast = -1;
        rttMax = -1;
    }

    /** Lo enviado en la última ventana de 5 s (QdSessionBridge.logStats). */
    static void noteTx(double fps, double kbps) {
        txFps = fps;
        txKbps = kbps;
    }

    /** Un rtt del enlace por Wi-Fi (cada 100 ms); el marcador enseña el último y el máximo desde que se pintó. */
    static void noteRtt(int ms) {
        rttLast = ms;
        if (ms > rttMax) rttMax = ms;
    }

    /** El texto del marcador, o "" si aún no hay nada que enseñar. */
    static String text(Context ctx) {
        int max = rttMax;
        rttMax = rttLast;
        return text(ThermalGuard.batteryTempC(ctx), ThermalGuard.status(), txFps, txKbps, rttLast, max,
                ctx.getString(R.string.hql_hud_heat));
    }

    /** Puro: el texto con estas cifras (NaN o negativas: no se ponen). heat: «calor» en el idioma de la app. */
    static String text(double tempC, int status, double fps, double kbps, int rtt, int rttMax, String heat) {
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(tempC)) b.append(Math.round(tempC)).append(" °C");
        if (status >= 0) b.append(b.length() > 0 ? " · " : "").append(heat).append(' ').append(status);
        if (fps >= 0) {
            b.append(b.length() > 0 ? " · " : "")
                    .append(String.format(Locale.getDefault(), "%.0f fps · %.1f Mbit/s", fps, kbps / 1000));
        }
        if (rtt >= 0) b.append(b.length() > 0 ? " · " : "").append("rtt ").append(rtt).append('/').append(Math.max(rtt, rttMax)).append(" ms");
        return b.toString();
    }
}
