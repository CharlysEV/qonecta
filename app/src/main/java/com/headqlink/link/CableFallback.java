package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Paso solo del cable USB a Wi-Fi Direct cuando el puerto USB del coche se cuelga a mitad de viaje (puro: lo prueban los
 * tests). Visto en dos C10 con «Auto extendido» por cable (30-10 y 01-11): tras una hora sin un corte, el coche deja de
 * leer y de mandar HEARTBEAT («CABLE: el coche no lee»), la sesión cae por el WATCHDOG, el coche reinicia su puerto (el
 * accesorio se va y vuelve en ~2 s) y su QDLink se queda colgado minutos: sesiones mudas de 10 s una tras otra, hasta
 * que vence «Esperar al coche» y se cierra todo. Por Wi-Fi Direct esos viajes aguantan, y el cambio a mano en marcha
 * (widget) conectó en ~4 s.
 *
 * Se pasa a Wi-Fi Direct, durante el resto del viaje, solo si:
 * - la conexión elegida es el cable y el enlace va por él, y en este viaje ya hubo vídeo (CAR_INFO);
 * - se abrió un atasco del cable ({@link #hung}): una sesión acabó con el coche callado y el cable puesto (WATCHDOG o
 *   WRITE_STALL) o con un atasco «el coche no lee» de al menos STALL_MIN_MS al cerrarse (aunque luego el coche cortara
 *   el puerto: EIO/ENODEV), o una sesión sin CAR_INFO acabó con el cable puesto. Quitar el cable a propósito no lo abre
 *   (el corte es inmediato, sin atasco antes): se sigue esperando como siempre;
 * - después, (a) el accesorio no vuelve en ACCESSORY_BACK_MS, o (b) los FAILED_ATTEMPTS intentos siguientes por el
 *   cable fallan: mudos (sin CAR_INFO) o atascados otra vez antes de RECOVERED_MS (el QDLink colgado puede contestar al
 *   saludo y no leer el vídeo, como la S2 del 01-11);
 * - el Bluetooth del coche sigue conectado (el coche sigue encendido) y el ajuste está puesto.
 * Mientras se confirma, sin el aviso «la app del coche se ha colgado» ({@link #handlesHang}: por voz pedía desenchufar
 * el cable, justo antes de pasar solo a Wi-Fi Direct).
 *
 * Es temporal: la conexión elegida no cambia. Se vuelve al cable en el viaje siguiente (cada arranque del servicio
 * empieza de cero: {@link #startTrip}) o al elegirlo a mano; tras una elección a mano con el cable caído ya no se cambia
 * solo en ese viaje ({@link #onManualChoice}): manda lo que elige el usuario. Una sesión con CAR_INFO de al menos
 * RECOVERED_MS sin atascarse olvida el atasco.
 *
 * Instantes en ms de un reloj monótono. Lo usa LinkService, solo en el hilo principal.
 */
final class CableFallback {
    /** Tras el atasco, el accesorio fuera más de esto: el puerto USB del coche se ha quedado colgado. */
    static final long ACCESSORY_BACK_MS = 10_000;
    /** Intentos por el cable que fallan tras el atasco (mudos o atascados otra vez) para pasar a Wi-Fi Direct. */
    static final int FAILED_ATTEMPTS = 2;
    /** Un atasco «el coche no lee» al cerrarse la sesión cuenta desde esto (quitar el cable corta en el acto). */
    static final long STALL_MIN_MS = 3_000;
    /** Una sesión con CAR_INFO que dura esto sin atascarse: el cable se ha recuperado y el atasco se olvida. */
    static final long RECOVERED_MS = 60_000;
    /** Un atasco sin decidir se olvida pasado esto (los intentos llegan cada 16-70 s: se decide mucho antes). */
    static final long EPISODE_MS = 10 * 60_000L;
    /** BluetoothProfile.STATE_CONNECTED (aquí sin Android). */
    static final int BT_STATE_CONNECTED = 2;
    /** AudioDeviceInfo.TYPE_BLUETOOTH_SCO y TYPE_BLUETOOTH_A2DP (aquí sin Android). */
    static final int AUDIO_BT_SCO = 7;
    static final int AUDIO_BT_A2DP = 8;

    /** El Bluetooth del coche: conectado, fuera o no se sabe (Android no contesta). */
    enum Bt { CONNECTED, GONE, UNKNOWN }

    /** Cómo terminó una sesión por cable (UsbLink). */
    static final class End {
        final int sid;
        /** El coche mandó su CAR_INFO (hubo vídeo). */
        final boolean carInfo;
        final long livedMs;
        /** Tipo de cierre (CloseReason.Kind: WATCHDOG, WRITE_STALL, READ_ERROR, LOCAL…). */
        final String closeKind;
        /** Atasco «el coche no lee» en curso al cerrarse la sesión, en ms (0: ninguno). */
        final long stallMs;
        /** El descriptor del accesorio dio ENODEV/EIO: el cable se fue o el coche cortó el puerto. */
        final boolean gone;

        End(int sid, boolean carInfo, long livedMs, String closeKind, long stallMs, boolean gone) {
            this.sid = sid;
            this.carInfo = carInfo;
            this.livedMs = livedMs;
            this.closeKind = closeKind == null ? "" : closeKind;
            this.stallMs = stallMs;
            this.gone = gone;
        }

        /** El coche, callado con el cable puesto: el watchdog o un write() bloqueado cerraron la sesión. */
        boolean carSilent() {
            return "WATCHDOG".equals(closeKind) || "WRITE_STALL".equals(closeKind);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("S").append(sid).append(" (").append(LinkLifecycle.dur(livedMs))
                    .append(carInfo ? ", con vídeo, " : ", sin CAR_INFO, ").append(closeKind.isEmpty() ? "?" : closeKind);
            if (stallMs > 0) sb.append(", «el coche no lee» ").append(secs(stallMs));
            if (gone) sb.append(", accesorio fuera");
            return sb.append(')').toString();
        }
    }

    /** Lo que se ve del móvil al decidir. */
    static final class Env {
        /** El ajuste «Pasar a Wi-Fi Direct si el cable se cae» (Config.CABLE_WIFI_FALLBACK). */
        final boolean enabled;
        /** La conexión elegida es el cable y el enlace va por él (no el cable por delante de una Wi-Fi elegida). */
        final boolean cableChosen;
        final Bt bt;

        Env(boolean enabled, boolean cableChosen, Bt bt) {
            this.enabled = enabled;
            this.cableChosen = cableChosen;
            this.bt = bt == null ? Bt.UNKNOWN : bt;
        }
    }

    /** Si hay que pasar ya a Wi-Fi Direct, y los motivos para el log. El temporizador, en {@link #timerAtMs()}. */
    static final class Decision {
        private boolean toWifi;
        private final List<String> reasons = new ArrayList<>();

        boolean toWifi() {
            return toWifi;
        }

        List<String> reasons() {
            return Collections.unmodifiableList(reasons);
        }

        private Decision why(String reason) {
            reasons.add(reason);
            return this;
        }

        @Override
        public String toString() {
            return (toWifi ? "Wi-Fi Direct " : "") + reasons;
        }
    }

    /** Hubo vídeo (CAR_INFO) en este viaje. */
    private boolean video;
    /** Atasco del cable abierto desde este instante (-1: ninguno) e intentos fallidos desde entonces. */
    private long hungAt = -1;
    private int failed;
    /** El accesorio se fue en este instante (-1: está, o no se sabe). */
    private long goneAt = -1;
    /** Cuándo llamar a {@link #timer} (-1: nada pendiente). */
    private long timerAt = -1;
    /** Wi-Fi Direct en lugar del cable hasta el final del viaje. */
    private boolean active;
    /** Conexión elegida a mano con el cable caído: ya no se cambia solo en este viaje. */
    private boolean manual;
    /** El accesorio del cable retenido (no se abre): desde el paso a Wi-Fi Direct o una Wi-Fi elegida a mano con el cable caído. */
    private boolean holdUsb;

    /** Arranque del servicio (viaje nuevo): de cero, y el enlace vuelve a ser la conexión elegida. */
    void startTrip() {
        video = false;
        hungAt = -1;
        failed = 0;
        goneAt = -1;
        timerAt = -1;
        active = false;
        manual = false;
        holdUsb = false;
    }

    /** Se sigue por Wi-Fi Direct en lugar del cable (hasta el final del viaje o una elección a mano). */
    boolean active() {
        return active;
    }

    /**
     * El accesorio del coche no se abre (UsbLink retenido): el cable se cayó en este viaje y se sigue por una Wi-Fi. Hasta
     * el viaje siguiente o hasta elegir «Cable USB» a mano.
     */
    boolean holdsUsb() {
        return holdUsb;
    }

    /** Hay un atasco del cable abierto (el puerto o el QDLink del coche colgados) y aún sin decidir. */
    boolean cableHung(long now) {
        return hungAt >= 0 && now - hungAt <= EPISODE_MS;
    }

    /** Cuándo llamar a {@link #timer} (-1: nada pendiente). */
    long timerAtMs() {
        return timerAt;
    }

    /** La conexión con la que va el enlace: Wi-Fi Direct en lugar del cable mientras dura el paso. */
    String linkFor(String chosen) {
        return active && Config.LINK_USB.equals(chosen) ? Config.LINK_P2P : chosen;
    }

    /** Puro: la sesión acabó atascada (el coche callado con el cable puesto, o «el coche no lee» un buen rato). */
    static boolean hung(End e) {
        return e.carSilent() || e.stallMs >= STALL_MIN_MS;
    }

    /** Puro: el Bluetooth del coche por los perfiles de audio del móvil (A2DP y manos libres: BluetoothProfile.STATE_*). */
    static Bt btFrom(boolean adapterOn, int a2dpState, int hfpState) {
        if (!adapterOn) return Bt.GONE;
        return a2dpState == BT_STATE_CONNECTED || hfpState == BT_STATE_CONNECTED ? Bt.CONNECTED : Bt.GONE;
    }

    /**
     * Puro: lo mismo por las salidas de audio del móvil (AudioDeviceInfo.TYPE_*), que no piden el permiso «Dispositivos
     * cercanos»: con A2DP o manos libres, el coche (el audio de Qonecta+ va por su Bluetooth).
     */
    static Bt btFromAudio(int[] outputTypes) {
        if (outputTypes == null) return Bt.UNKNOWN;
        for (int t : outputTypes) if (t == AUDIO_BT_A2DP || t == AUDIO_BT_SCO) return Bt.CONNECTED;
        return Bt.GONE;
    }

    /**
     * Hay un atasco del cable abierto y, si se confirma, se pasará a Wi-Fi Direct (nada lo impide ahora): el aviso «la app
     * del coche se ha colgado» (UsbDiagnosis, que por voz dice que se desenchufe el cable) sobra.
     */
    boolean handlesHang(long now, Env env) {
        return !active && cableHung(now) && blocker(env) == null;
    }

    /** Fin de una sesión por cable con el cable como enlace. */
    Decision onSessionEnd(long now, End e, Env env) {
        Decision d = new Decision();
        if (e.carInfo) video = true;
        if (active) return d;
        expire(now, d);
        boolean hung = hung(e);
        if (hungAt < 0) {
            // Sin atasco abierto: lo abre una sesión atascada o una muda con el cable puesto (un fin normal o un cable quitado,
            // no); y sin vídeo en el viaje, aún no (al empezar, el coche a veces tarda en contestar por el cable).
            if (video && (hung || (!e.carInfo && !e.gone))) open(now, e, d);
            return d;
        }
        if (e.carInfo && e.livedMs >= RECOVERED_MS) {
            if (hung) {
                // Se recuperó y se ha vuelto a atascar: atasco nuevo.
                open(now, e, d);
            } else {
                close();
                d.why(e + ": el cable se ha recuperado; olvido el atasco");
            }
            return d;
        }
        if (e.carInfo && !hung) return d.why(e + ": con vídeo, pero corta; sigo vigilando el cable");
        failed++;
        d.why(e + ": intento " + failed + " de " + FAILED_ATTEMPTS + " fallido tras el atasco ("
                + (e.carInfo ? "se atascó otra vez" : "mudo") + ")");
        if (failed >= FAILED_ATTEMPTS) decide(env, d, failed + " intentos fallidos por el cable tras el atasco");
        return d;
    }

    /** El accesorio del coche se ha ido (cable quitado, sin corriente o EIO/ENODEV). */
    Decision onAccessoryGone(long now) {
        Decision d = new Decision();
        if (goneAt < 0) goneAt = now;
        if (active) return d;
        expire(now, d);
        if (hungAt < 0) return d;
        timerAt = goneAt + ACCESSORY_BACK_MS;
        return d.why("accesorio fuera tras el atasco: si no vuelve en " + secs(ACCESSORY_BACK_MS) + ", sigo por Wi-Fi Direct");
    }

    /** El accesorio ha vuelto: se abrió una sesión nueva por el cable. */
    Decision onAccessoryBack(long now) {
        Decision d = new Decision();
        long was = goneAt;
        goneAt = -1;
        if (timerAt < 0) return d;
        timerAt = -1;
        if (!active && hungAt >= 0 && was >= 0) {
            d.why("el accesorio ha vuelto en " + secs(now - was) + ": ahora cuentan los intentos (" + FAILED_ATTEMPTS
                    + " fallidos: Wi-Fi Direct)");
        }
        return d;
    }

    /** Vencimiento del temporizador ({@link #timerAtMs()}): el accesorio sigue fuera tras el atasco. */
    Decision timer(long now, Env env) {
        Decision d = new Decision();
        if (timerAt < 0 || now < timerAt) return d;
        timerAt = -1;
        if (active) return d;
        expire(now, d);
        if (hungAt >= 0 && goneAt >= 0) decide(env, d, "el accesorio no ha vuelto en " + secs(now - goneAt) + " tras el atasco");
        return d;
    }

    /**
     * Conexión elegida a mano (widget o «Cambiar»). Con el paso a Wi-Fi Direct hecho o con el cable atascado: manda lo
     * elegido y ya no se cambia solo hasta el viaje siguiente (true; si no, false). El accesorio ({@link #holdsUsb}): con
     * «Cable USB» se suelta siempre, también cuando lo retuvo otra elección a mano de antes en este viaje (entonces el paso
     * ya no está hecho ni hay atasco abierto, y esto da false); con una Wi-Fi y el cable caído o atascado, se retiene.
     */
    boolean onManualChoice(long now, boolean toUsb) {
        if (toUsb) holdUsb = false;
        if (!active && !cableHung(now)) return false;
        manual = true;
        active = false;
        close();
        if (!toUsb) holdUsb = true;
        return true;
    }

    /**
     * Puro: el aviso «el cable se ha caído» en el coche va por voz (el audio del móvil sale por el Bluetooth del coche, que
     * sigue conectado) salvo en «Auto extendido», que lo enseña en su pantalla (CarUi) con la primera sesión por Wi-Fi
     * Direct. En «Auto» la pantalla es toda de Android Auto: no hay dónde ponerlo.
     */
    static boolean carNoticeByVoice(String mode) {
        return !Config.MODE_AA_EXT.equals(mode);
    }

    private void open(long now, End e, Decision d) {
        hungAt = now;
        failed = 0;
        // El coche ya cortó el puerto antes de cerrarse la sesión: los 10 s, desde entonces.
        if (goneAt >= 0) timerAt = goneAt + ACCESSORY_BACK_MS;
        d.why(e + ": atasco del cable con el cable puesto (el coche no lee o no contesta); si el accesorio no vuelve en "
                + secs(ACCESSORY_BACK_MS) + " o fallan los " + FAILED_ATTEMPTS + " intentos siguientes, sigo por Wi-Fi Direct");
    }

    private void close() {
        hungAt = -1;
        failed = 0;
        timerAt = -1;
    }

    private void expire(long now, Decision d) {
        if (hungAt < 0 || now - hungAt <= EPISODE_MS) return;
        close();
        d.why("el atasco del cable fue hace más de " + LinkLifecycle.dur(EPISODE_MS) + " sin decidir nada: lo olvido");
    }

    /** Lo que impide pasar a Wi-Fi Direct ahora, o null si nada. */
    private String blocker(Env env) {
        return manual ? "la conexión se eligió a mano en este viaje"
                : !env.enabled ? "el ajuste «Pasar a Wi-Fi Direct si el cable se cae» está apagado"
                : !env.cableChosen ? "la conexión elegida no es el cable"
                : !video ? "no hubo vídeo en este viaje"
                : env.bt == Bt.GONE ? "el Bluetooth del coche ya no está (el coche se ha apagado)"
                : env.bt != Bt.CONNECTED ? "no se sabe si el Bluetooth del coche sigue (Android no lo dice)"
                : null;
    }

    private void decide(Env env, Decision d, String why) {
        String no = blocker(env);
        if (no != null) {
            d.why(why + ", pero " + no + ": sigo con el cable");
            return;
        }
        active = true;
        holdUsb = true;
        close();
        d.toWifi = true;
        d.why(why + " y el Bluetooth del coche sigue conectado: el cable se ha caído, sigo por Wi-Fi Direct hasta el final"
                + " del viaje (la conexión elegida sigue siendo el cable: el viaje siguiente vuelve a él)");
    }

    /** «10 s» o «8,8 s». */
    static String secs(long ms) {
        if (ms % 1000 == 0) return ms / 1000 + " s";
        return String.format(Locale.ROOT, "%.1f s", ms / 1000.0).replace('.', ',');
    }
}
