package com.headqlink.link;

import android.content.Context;
import android.content.Intent;

import java.util.Locale;

/**
 * Variante de pruebas (BuildConfig.LAB, compilada con -Plab): el apartado interno para Claude. Mientras sigue el log en
 * directo, Claude manda órdenes por el mismo canal (LiveLog: GET /cmd?k=CLAVE&amp;c=orden, solo desde Tailscale y con la
 * clave) y la app las ejecuta por dentro, sin tocar la interfaz: topes de fps y bitrate en marcha, el salto a 60 fps al
 * tocar, la protección térmica, día y noche, el perfil de vídeo (reconecta), mensajes arriba en la pantalla del coche y
 * un marcador en una esquina (temperatura, calor, fps, bitrate y radio). En la versión de siempre BuildConfig.LAB es
 * false: el canal no acepta órdenes y nada de esto se usa.
 *
 * Órdenes (una por petición, en minúsculas): estado · msg TEXTO · fps N|auto · kbps N|auto · boost on|off|auto ·
 * termica normal|suave|apagada · noche on|off|auto · hud on|off · perfil ID · reconectar · auto.
 */
final class LabControl {
    /** Tope de fps de Claude (0: ninguno). */
    static volatile int fpsCap;
    /** Tope de bitrate de Claude en kbps (0: ninguno). */
    static volatile int maxKbps;
    /** Salto a 60 fps al tocar: null, lo del perfil; si no, forzado. */
    static volatile Boolean touchBoost;
    /** Marcador en la esquina del coche. */
    static volatile boolean hud;

    private static volatile double txFps = -1;
    private static volatile double txKbps = -1;
    private static volatile int rttLast = -1;
    private static volatile int rttMax = -1;

    private LabControl() {
    }

    static boolean enabled() {
        return com.andrerinas.openheadunit.BuildConfig.LAB;
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

    /** El texto del marcador: «41 °C · calor 1 · 30 fps · 1,2 Mbit/s · rtt 5/61 ms · tope 30 fps». */
    static String hudText(Context ctx) {
        StringBuilder b = new StringBuilder();
        double t = ThermalGuard.batteryTempC(ctx);
        if (!Double.isNaN(t)) b.append(Math.round(t)).append(" °C");
        int heat = ThermalGuard.status();
        if (heat >= 0) b.append(b.length() > 0 ? " · " : "").append("calor ").append(heat);
        if (txFps >= 0) b.append(String.format(Locale.getDefault(), " · %.0f fps · %.1f Mbit/s", txFps, txKbps / 1000));
        int max = rttMax;
        rttMax = rttLast;
        if (rttLast >= 0) b.append(" · rtt ").append(rttLast).append('/').append(max).append(" ms");
        String o = overrides();
        if (!o.isEmpty()) b.append(" · ").append(o);
        return b.toString();
    }

    /** Lo que Claude tiene forzado ahora («tope 30 fps, máx. 6 Mbit/s, sin salto a 60»), o "". */
    static String overrides() {
        StringBuilder b = new StringBuilder();
        if (fpsCap > 0) b.append("tope ").append(fpsCap).append(" fps");
        if (maxKbps > 0) b.append(b.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "máx. %.1f Mbit/s", maxKbps / 1000.0));
        if (touchBoost != null) b.append(b.length() > 0 ? ", " : "").append(touchBoost ? "salto a 60 al tocar" : "sin salto a 60");
        return b.toString();
    }

    /** Una orden de Claude: la ejecuta y devuelve qué ha hecho (o por qué no). */
    static String handle(Context ctx, String raw) {
        if (!enabled()) return "no: esta no es la variante de pruebas";
        String line = raw == null ? "" : raw.trim();
        String cmd = line;
        String arg = "";
        int sp = line.indexOf(' ');
        if (sp > 0) {
            cmd = line.substring(0, sp);
            arg = line.substring(sp + 1).trim();
        }
        cmd = cmd.toLowerCase(Locale.ROOT);
        String a = arg.toLowerCase(Locale.ROOT);
        String done;
        switch (cmd) {
            case "estado":
                return "variante de pruebas · " + hudText(ctx) + (hud ? " · marcador encendido" : "");
            case "msg":
                if (arg.isEmpty()) return "no: falta el texto";
                L.i("laboratorio: mensaje en el coche");
                CarUi.videoInfoText("Claude · " + arg);
                return "mensaje enseñado";
            case "fps":
                fpsCap = "auto".equals(a) ? 0 : clamp(parse(a), 10, 60);
                done = fpsCap == 0 ? "fps sin tope (lo del perfil y el calor)" : "fps como mucho " + fpsCap;
                VideoPipeline.labApply();
                break;
            case "kbps":
                maxKbps = "auto".equals(a) ? 0 : clamp(parse(a), 1000, 20000);
                done = maxKbps == 0 ? "bitrate sin tope de Claude" : String.format(Locale.getDefault(), "bitrate como mucho %.1f Mbit/s", maxKbps / 1000.0);
                VideoPipeline.labApply();
                break;
            case "boost":
                touchBoost = "auto".equals(a) ? null : "on".equals(a) ? Boolean.TRUE : Boolean.FALSE;
                done = touchBoost == null ? "salto a 60 al tocar: lo del perfil" : touchBoost ? "salto a 60 al tocar: sí" : "salto a 60 al tocar: no (fps fijos)";
                VideoPipeline.labApply();
                break;
            case "termica":
                if (!a.equals(ThermalPolicy.MODE_NORMAL) && !a.equals(ThermalPolicy.MODE_SOFT) && !a.equals(ThermalPolicy.MODE_OFF)) {
                    return "no: termica normal|suave|apagada";
                }
                new Config(ctx).setThermalMode(a);
                ThermalGuard.onModeChanged();
                done = "protección térmica " + ThermalPolicy.modeName(a);
                break;
            case "noche":
                if ("auto".equals(a)) {
                    AaPassthroughSource.applyCarTheme(ctx);
                    done = "día y noche: como el ajuste «Tema»";
                } else {
                    AaPassthroughSource.simNight(ctx, "on".equals(a));
                    done = "on".equals(a) ? "modo noche forzado" : "modo día forzado";
                }
                break;
            case "hud":
                hud = "on".equals(a);
                CarUi.labHud(hud);
                L.i("laboratorio: marcador " + (hud ? "encendido" : "apagado"));
                return "marcador " + (hud ? "encendido" : "apagado");
            case "perfil": {
                boolean known = false;
                for (String id : VideoProfile.ALL) if (id.equals(a)) known = true;
                if (!known) return "no: perfil " + String.join("|", VideoProfile.ALL);
                new Config(ctx).setVideoProfile(a);
                done = "perfil de vídeo «" + a + "»: reconecto (unos segundos sin imagen)";
                reconnect(ctx);
                break;
            }
            case "reconectar":
                done = "reconecto (unos segundos sin imagen)";
                reconnect(ctx);
                break;
            case "auto":
                fpsCap = 0;
                maxKbps = 0;
                touchBoost = null;
                VideoPipeline.labApply();
                done = "todo como lo deja la app (sin topes de Claude)";
                break;
            default:
                return "no: orden desconocida (estado, msg, fps, kbps, boost, termica, noche, hud, perfil, reconectar, auto)";
        }
        L.i("laboratorio: " + done);
        CarUi.videoInfoText("Claude · " + done);
        return done;
    }

    private static void reconnect(Context ctx) {
        try {
            ctx.startForegroundService(LinkService.fromApp(new Intent(ctx, LinkService.class)
                    .setAction(LinkService.ACTION_APPLY).putExtra(LinkService.EXTRA_AA_RENEGOTIATE, true)));
        } catch (RuntimeException e) {
            L.w("laboratorio: no se pudo reconectar: " + e.getMessage());
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v <= 0 ? 0 : Math.max(lo, Math.min(hi, v));
    }
}
