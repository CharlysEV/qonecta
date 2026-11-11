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
 * Órdenes (una por petición, en minúsculas): estado · msg TEXTO · fps N|auto · kbps N|auto · cable N|auto · qp N|auto ·
 * cola N|auto · boost on|off|auto · termica normal|suave|apagada · noche on|off|auto · hud on|off · perfil ID · reconectar · auto ·
 * alemania on|off (el registro de cargadores de Alemania como si una ruta pasara por allí; off lo borra) · precios [LAT,LON]
 * (los precios ad hoc de Alemania alrededor de un punto; sin nada, Berlín) · carga demo|off
 * (la notificación de una carga inventada, para ver el Live Update) · ffmpeg (si el audio por FFmpeg está listo) · ia [hola]
 * (el SDK de Anthropic con una clave falsa; «hola»: una pregunta de verdad con la clave guardada) · radioaa
 * [fuente si|no · enlace normal|apartado] (la radio en Android Auto: Qonecta+ app de música de Android Auto o no, desde su
 * próxima sesión; la sesión del enlace con Android Auto apartada solo mientras la radio tiene la música o siempre; sin
 * nada, cómo está) · frente · fondo (CAR_APP_FOREGROUND o CAR_APP_BACKGROUND al coche: ¿vuelve a QDLink si se le pide?) ·
 * plan [reenviar] (el plan de carga del coche) · barata («Carga barata»: el estado y el cálculo que haría ahora, sin
 * mandar nada) · calentar [on|off] («Batería caliente para el cargador»: on/off mandan ya la orden 160; sin nada, el
 * estado y lo que haría ahora).
 */
final class LabControl {
    /** Tope de fps de Claude (0: ninguno). */
    static volatile int fpsCap;
    /** Tope de bitrate de Claude en kbps (0: ninguno). */
    static volatile int maxKbps;
    /**
     * Techo del bitrate adaptable POR CABLE en kbps, por encima del del perfil (0: el del perfil, 16 Mbit/s en Máximo).
     * Para probar en el coche hasta dónde llega el USB; el adaptable sube solo hasta él si el enlace aguanta.
     */
    static volatile int cableKbps;
    /** Salto a 60 fps al tocar: null, lo del perfil; si no, forzado. */
    static volatile Boolean touchBoost;
    /**
     * Suelo de calidad del codificador (QP) para probar la nitidez: el QP-I de partida y el mínimo de los P-frames al
     * configurar (0: los de siempre, 24). Más bajo = más nítido y frames más grandes; el c2.qti solo lo coge al
     * configurar, así que vale desde la siguiente conexión («reconectar»).
     */
    static volatile int qpFloor;
    /**
     * Puerta «último frame» en KB (0: la de serie, VideoPipeline.gateOutqFor). Más alta aguanta los microcortes de radio
     * del Wi-Fi Direct sin tirar frames (a ~4 Mbit/s, 200 ms son ~100 KB) a cambio de imagen algo retrasada tras cada
     * corte. Vale al momento.
     */
    static volatile int gateKb;

    private LabControl() {
    }

    static boolean enabled() {
        return com.andrerinas.openheadunit.BuildConfig.LAB;
    }

    /**
     * El texto del marcador (VideoHud) y, en la variante de pruebas, lo que Claude tenga forzado: «41 °C · calor 1 ·
     * 30 fps · 1,2 Mbit/s · rtt 5/61 ms · tope 30 fps».
     */
    static String hudText(Context ctx) {
        String t = VideoHud.text(ctx);
        String o = enabled() ? overrides() : "";
        return o.isEmpty() ? t : t.isEmpty() ? o : t + " · " + o;
    }

    /** Lo que Claude tiene forzado ahora («tope 30 fps, máx. 6 Mbit/s, sin salto a 60»), o "". */
    static String overrides() {
        StringBuilder b = new StringBuilder();
        if (fpsCap > 0) b.append("tope ").append(fpsCap).append(" fps");
        if (maxKbps > 0) b.append(b.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "máx. %.1f Mbit/s", maxKbps / 1000.0));
        if (cableKbps > 0) {
            b.append(b.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "techo por cable %.0f Mbit/s", cableKbps / 1000.0));
        }
        if (touchBoost != null) b.append(b.length() > 0 ? ", " : "").append(touchBoost ? "salto a 60 al tocar" : "sin salto a 60");
        if (qpFloor > 0) b.append(b.length() > 0 ? ", " : "").append("QP ").append(qpFloor);
        if (gateKb > 0) b.append(b.length() > 0 ? ", " : "").append("cola ").append(gateKb).append(" KB");
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
                return "variante de pruebas · " + hudText(ctx) + (new Config(ctx).videoHud() ? " · marcador encendido" : "");
            case "msg":
                if (arg.isEmpty()) return "no: falta el texto";
                L.i("laboratorio: mensaje en el coche");
                CarUi.videoInfoText("Claude · " + arg);
                return "mensaje enseñado";
            case "wifi":
                // Vigilancia de las Wi-Fi del servidor encendido entre viajes (OpenWifiGuard), sin ir al coche: «wifi on»
                // la arma (Android avisa enseguida de la Wi-Fi de ahora) y «wifi» dice qué se sabe.
                if ("on".equals(a)) OpenWifiGuard.arm(ctx);
                if ("off".equals(a)) OpenWifiGuard.disarm(ctx);
                return "servidor encendido (último dato): " + AaServerStarter.serverLikelyOn(ctx) + " · encendido entre viajes: "
                        + new Config(ctx).aaServerKeepOn() + " · ahora en una Wi-Fi sin contraseña: " + OpenWifiGuard.onOpenWifi(ctx);
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
            case "cable":
                cableKbps = "auto".equals(a) ? 0 : clamp(parse(a), 8000, 40000);
                done = cableKbps == 0 ? "techo por cable: el del perfil"
                        : String.format(Locale.getDefault(), "techo por cable %.0f Mbit/s (perfil adaptable, coche a 60 fps por cable)",
                        cableKbps / 1000.0);
                VideoPipeline.labApply();
                break;
            case "alemania":
                if ("off".equals(a)) {
                    BnetzaChargers.forget(ctx);
                    done = "registro de Alemania borrado y sin pedir";
                } else {
                    BnetzaChargers.want(ctx);
                    done = "registro de Alemania pedido (se baja con Wi-Fi)";
                }
                break;
            case "precios": {
                // Precios ad hoc de Alemania sin ir allí: «precios 52.52,13.40» (sin nada, Berlín) pide las zonas de
                // alrededor; la segunda vez dice cuántas estaciones tienen precio y la más barata en continua.
                double lat = 52.52;
                double lon = 13.40;
                if (!a.isEmpty() && a.contains(",")) {
                    try {
                        lat = Double.parseDouble(a.substring(0, a.indexOf(',')).trim());
                        lon = Double.parseDouble(a.substring(a.indexOf(',') + 1).trim());
                    } catch (NumberFormatException e) {
                        return "no: «precios LAT,LON»";
                    }
                }
                AdHocPrices.want(ctx, AdHocPrices.tilesAround(lat, lon, 5));
                return AdHocPrices.labStatus(lat, lon, 5);
            }
            case "suiza": {
                // Cargadores de Suiza sin ir a Suiza: baja los puntos (si hace falta) y el estado, y dice cuántos.
                if (SwissChargers.sites(ctx) == null || "on".equals(a)) SwissChargers.refresh(ctx);
                java.util.List<DgtChargers.Site> all = SwissChargers.sites(ctx);
                if (all == null) return "no: no se pudieron bajar (mira el log)";
                SwissChargers.refreshStatus();
                int free = 0;
                int out = 0;
                int known = 0;
                for (DgtChargers.Site s : all) {
                    int[] c = SwissChargers.live(s);
                    if (c == null) continue;
                    known++;
                    if (c[SwissChargers.FREE] > 0) free++;
                    if (SwissChargers.allOut(s)) out++;
                }
                return all.size() + " sitios de carga rápida en Suiza · con estado " + known + " · con algún punto libre " + free
                        + " · fuera de servicio " + out;
            }
            case "qp":
                qpFloor = "auto".equals(a) ? 0 : clamp(parse(a), 12, 30);
                done = (qpFloor == 0 ? "calidad mínima: la de siempre (QP 24)" : "calidad mínima QP " + qpFloor)
                        + " · vale al reconectar (orden «reconectar»)";
                break;
            case "cola":
                gateKb = "auto".equals(a) ? 0 : clamp(parse(a), 16, 256);
                done = gateKb == 0 ? "cola del kernel: la de serie (" + VideoPipeline.GATE_OUTQ_WIFI / 1024 + " KB por Wi-Fi, "
                        + VideoPipeline.GATE_OUTQ / 1024 + " por cable)"
                        : "cola del kernel hasta " + gateKb + " KB antes de tirar frames";
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
            case "hud": {
                boolean on = "on".equals(a);
                new Config(ctx).putBool(Config.VIDEO_HUD, on);
                CarUi.refreshHud();
                L.i("laboratorio: marcador " + (on ? "encendido" : "apagado"));
                return "marcador " + (on ? "encendido" : "apagado");
            }
            case "perfil": {
                boolean known = false;
                for (String id : VideoProfile.ALL) if (id.equals(a)) known = true;
                if (!known) return "no: perfil " + String.join("|", VideoProfile.ALL);
                new Config(ctx).setVideoProfile(a);
                done = "perfil de vídeo «" + a + "»: reconecto (unos segundos sin imagen)";
                reconnect(ctx);
                break;
            }
            case "ia":
                if (a.equals("hola")) {
                    // Una pregunta de verdad con la clave guardada (cuesta una fracción de céntimo): la respuesta entera.
                    ClaudeCompanion.Reply r = ClaudeCompanion.ask(ctx, "Hola, ¿me oyes? Preséntate en una frase.", ClaudeCompanion.context(ctx));
                    done = "compañero: " + r.outcome + " · " + r.text;
                } else {
                    done = "compañero: " + labCompanion(ctx);
                }
                break;
            case "ffmpeg":
                done = "FFmpeg para el audio: " + VideoPlayer.ffmpegInfo();
                break;
            case "carga":
                done = ChargeWatch.labNotify(ctx, !a.equals("off"));
                break;
            case "radioaa": {
                // La radio en Android Auto, para probar en el coche virtual sin compilar: «fuente no» quita Qonecta+ de las
                // apps de música de Android Auto (como en 0.3.13; Android Auto lo ve en su próxima sesión, y al reiniciar la
                // app vuelve a «sí»); «enlace apartado» deja inactiva siempre la sesión del enlace con Android Auto, por si
                // Android Auto salta a Qonecta+ con Spotify sonando (de serie, «normal»: solo mientras la radio tiene la música).
                String[] w = a.trim().split("\\s+");
                String v = w.length > 1 ? w[1] : "";
                if ("fuente".equals(w[0]) && ("si".equals(v) || "sí".equals(v) || "no".equals(v))) {
                    RadioSession.labSource(!"no".equals(v));
                    done = "no".equals(v) ? "Qonecta+ deja de ser app de música de Android Auto (desde su próxima sesión)"
                            : "Qonecta+ app de música de Android Auto (desde su próxima sesión)";
                } else if ("enlace".equals(w[0]) && ("normal".equals(v) || "apartado".equals(v))) {
                    RadioSession.labLinkApart("apartado".equals(v));
                    done = "apartado".equals(v) ? "sesión del enlace con Android Auto apartada siempre"
                            : "sesión del enlace con Android Auto apartada solo mientras la radio tiene la música";
                } else {
                    return "radioaa fuente si|no · enlace normal|apartado (ahora: " + RadioSession.labStatus() + ")";
                }
                break;
            }
            case "frente":
            case "fondo": {
                // Maggus #324: la cámara tapa QDLink y, al quitarse, el coche no siempre vuelve a él; el coche no avisa de
                // nada. «fondo» manda CAR_APP_BACKGROUND, lo mismo que el botón de salir de QDLink (el coche lo atiende), y
                // «frente» CAR_APP_FOREGROUND, que QDLink define pero nunca envía: si con él el coche vuelve a QDLink, se
                // puede pensar cuándo pedirlo de verdad. Probar en parado: «fondo», esperar el menú del coche y «frente».
                SessionPort port = VideoPipeline.activePort();
                if (port == null) return "no: no hay sesión con el coche";
                boolean front = cmd.equals("frente");
                boolean sent = front ? port.getSession().sendCarAppForeground() : port.getSession().sendCarAppBackground();
                if (!sent) return "no: la sesión no lo aceptó (cerrándose)";
                done = (front ? "CAR_APP_FOREGROUND" : "CAR_APP_BACKGROUND") + " mandado a S" + port.getId();
                break;
            }
            case "plan": {
                // «Carga barata» (10-11): «plan» lee el plan de carga del coche; «plan reenviar» lo lee, manda el mismo sin
                // cambiar nada (para ver si la nube acepta la orden 190) y lo vuelve a leer. Con red: en un hilo aparte, el
                // resultado va al log.
                boolean resend = a.equals("reenviar");
                if (!a.isEmpty() && !resend) return "plan · plan reenviar";
                Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    try {
                        ChargePlan p = CarCloudSession.readChargePlan(app);
                        if (!resend) return;
                        if (p == null) {
                            L.i("pruebas: plan de carga: el coche no tiene plan; no se manda nada");
                            return;
                        }
                        boolean ok = CarCloudSession.sendChargePlan(app, p);
                        ChargePlan after = CarCloudSession.readChargePlan(app);
                        L.i("pruebas: plan de carga reenviado sin cambios: " + (ok ? "confirmado" : "sin confirmar")
                                + "; después " + (p.same(after) ? "sigue igual" : "ha CAMBIADO"));
                    } catch (Exception e) {
                        L.w("pruebas: plan de carga: " + Http.safeError(e));
                    }
                }, "lab-plan").start();
                done = resend ? "leo el plan de carga y lo reenvío igual (resultado en el log)" : "leo el plan de carga (resultado en el log)";
                break;
            }
            case "barata": {
                // «Carga barata»: el estado guardado y el cálculo que haría ahora con la última lectura del coche. No manda
                // nada al coche ni pregunta su posición; con el PVPC puede pedir los precios (red): en otro hilo, al log.
                Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    try {
                        L.i("pruebas: carga barata: " + CheapCharge.lab(app));
                    } catch (RuntimeException e) {
                        L.w("pruebas: carga barata: " + e.getClass().getSimpleName());
                    }
                }, "lab-barata").start();
                done = "carga barata: estado y cálculo de ahora, sin mandar nada (resultado en el log)";
                break;
            }
            case "calentar": {
                // «Batería caliente para el cargador» (11-11): «calentar on|off» manda ya la orden 160 (para probarla
                // aparcado; el resultado, en el log); «calentar» a secas dice el estado y lo que haría ahora, sin mandar.
                if (a.isEmpty()) return "calentar: " + BatteryHeat.lab(ctx);
                if (!a.equals("on") && !a.equals("off")) return "calentar · calentar on · calentar off";
                boolean on = a.equals("on");
                Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    try {
                        boolean ok = BatteryHeat.cloud(app).send(on);
                        L.i("pruebas: calentar " + (on ? "on" : "off") + ": " + (ok ? "confirmado por el coche" : "sin confirmar")
                                + "; la señal 1186 se verá en las próximas lecturas («calentar»)");
                    } catch (Exception e) {
                        L.w("pruebas: calentar " + (on ? "on" : "off") + ": "
                                + (BatteryHeat.unsupported(e) ? "el coche no tiene la función (" : "fallo (") + Http.safeError(e) + ")");
                    }
                }, "lab-calentar").start();
                done = "calentar la batería: mando «" + (on ? "ptcon" : "ptcoff") + "» al coche (resultado en el log)";
                break;
            }
            case "reconectar":
                done = "reconecto (unos segundos sin imagen)";
                reconnect(ctx);
                break;
            case "auto":
                fpsCap = 0;
                maxKbps = 0;
                cableKbps = 0;
                touchBoost = null;
                qpFloor = 0;
                gateKb = 0;
                VideoPipeline.labApply();
                done = "todo como lo deja la app (sin topes de Claude)";
                break;
            default:
                return "no: orden desconocida (estado, msg, fps, kbps, cable, qp, cola, alemania, precios, carga, plan, barata, calentar, ffmpeg, ia, radioaa, boost, termica, noche, hud, perfil, frente, fondo, reconectar, auto)";
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

    /**
     * «ia»: una pregunta de verdad con el SDK de Anthropic y una clave falsa, para ver en el móvil que el SDK funciona
     * (crea el cliente, manda la petición y entiende el error 401). No toca la clave guardada.
     */
    private static String labCompanion(Context ctx) {
        if (!ClaudeCompanion.supported()) return "Android antiguo";
        try {
            com.anthropic.client.AnthropicClient c = com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()
                    .apiKey("sk-ant-prueba-falsa-0000000000").maxRetries(0).build();
            c.beta().messages().create(com.anthropic.models.beta.messages.MessageCreateParams.builder()
                    .model(ClaudeCompanion.MODEL).maxTokens(64L).system(ClaudeCompanion.systemPrompt())
                    .addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
                    .addMessage(com.anthropic.models.beta.messages.BetaMessageParam.ofUser("Hola")).build());
            return "respuesta inesperada con una clave falsa";
        } catch (com.anthropic.errors.UnauthorizedException e) {
            return "SDK bien: la API contesta 401 a la clave falsa";
        } catch (Throwable t) {
            return "fallo " + t.getClass().getName() + ": " + t.getMessage();
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
