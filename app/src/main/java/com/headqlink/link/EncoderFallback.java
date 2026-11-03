package com.headqlink.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Escalones del codificador cuando falla al arrancar (pura, la prueban los tests). El c2.qti.avc.encoder del Galaxy S23
 * Ultra (Snapdragon 8 Gen 2, Android 17, 0.3.12) con Máximo (1920x882@60, 12 Mbit/s, High, baja latencia del fabricante
 * y relojes al máximo) da «err 0x80000000» unos 45 ms después de arrancar y no saca ni un frame; con Coche (1280x588,
 * Baseline, sin baja latencia) va bien. Antes el vídeo se quedaba a 0 fps, el coche cortaba (vigilante USB 10 s, EOF
 * Wi-Fi 20 s), reconectaba con lo mismo y vuelta a empezar.
 *
 * Ahora un error antes del primer frame (o en los primeros EARLY_MS) reinicia el codificador con el escalón siguiente
 * que cambie algo (VideoHub, sin soltar la sesión con el coche). Los escalones se suman:
 * 0 tal cual → 1 sin baja latencia del fabricante ni relojes al máximo → 2 perfil Main → 3 perfil Baseline → 4 tamaño
 * (1280 de ancho) y bitrate de «Coche».
 * El escalón que queda se recuerda por móvil y codificador (Build.MODEL + nombre del códec, VideoEncoder.fallbackKey) y
 * perfil de imagen: la vez siguiente se empieza en él. Un error tardío (ya con frames) no baja: es otra cosa (calor,
 * sistema) y se arregla como siempre, al reconectar. Con otro perfil se empieza desde arriba, y elegir uno a mano borra
 * lo recordado (Config.setVideoProfile): se vuelve a probar desde arriba una vez.
 */
final class EncoderFallback {
    static final int AS_IS = 0;
    static final int NO_BOOST = 1;
    static final int MAIN = 2;
    static final int BASELINE = 3;
    static final int CAR_SIZE = 4;
    static final int LAST = CAR_SIZE;
    /** Un error en este tiempo desde que arrancó el codificador cuenta como «al arrancar», aunque ya diera frames. */
    static final long EARLY_MS = 5_000;
    /** Ancho del escalón CAR_SIZE: el 720p de «Coche» (VideoProfile.videoSize). */
    static final int CAR_WIDTH = 1280;
    /** Móviles y códecs que se recuerdan (una copia de seguridad puede traer los de otro móvil). */
    static final int KEEP = 4;

    private EncoderFallback() {
    }

    /** Lo que pide el perfil antes de los escalones: para saltar los que no cambiarían nada. */
    static final class Wanted {
        /** Baja latencia del fabricante o relojes al máximo. */
        final boolean boost;
        final String h264;
        final int width;
        /** Lo más que puede llegar a pedir (con bitrate adaptable, su techo). */
        final int bitrate;
        /** El de «Coche» con lo que pide el coche (el tope del escalón CAR_SIZE). */
        final int carBitrate;

        Wanted(boolean boost, String h264, int width, int bitrate, int carBitrate) {
            this.boost = boost;
            this.h264 = h264 == null ? "baseline" : h264.toLowerCase(Locale.ROOT);
            this.width = width;
            this.bitrate = bitrate;
            this.carBitrate = carBitrate;
        }
    }

    /** ¿Cambia algo este escalón respecto al anterior, para lo que pide el perfil? */
    static boolean changes(int step, Wanted w) {
        switch (step) {
            case NO_BOOST:
                return w.boost;
            case MAIN:
                return "high".equals(w.h264);
            case BASELINE:
                return "high".equals(w.h264) || "main".equals(w.h264);
            case CAR_SIZE:
                return w.width > CAR_WIDTH || (w.carBitrate > 0 && w.bitrate > w.carBitrate);
            default:
                return false;
        }
    }

    /** El escalón siguiente que cambie algo, o -1 si ya no quedan. */
    static int next(int step, Wanted w) {
        for (int s = Math.max(AS_IS, step) + 1; s <= LAST; s++) if (changes(s, w)) return s;
        return -1;
    }

    /** ¿Un error «al arrancar»? Sin ningún frame, o en los primeros EARLY_MS. */
    static boolean early(int frames, long sinceStartMs) {
        return frames <= 0 || sinceStartMs < EARLY_MS;
    }

    /** Error del codificador: el escalón con que seguir, o -1 si no se baja (error tardío o sin escalones). */
    static int afterFailure(int step, Wanted w, int frames, long sinceStartMs) {
        if (!early(frames, sinceStartMs)) return -1;
        return next(step, w);
    }

    // ---------------------------------------------------------------- aplicar un escalón

    /** Baja latencia, relojes y perfil H.264 del escalón (lo demás de Params no se toca). */
    static void apply(int step, VideoEncoder.Params vp) {
        if (step >= NO_BOOST) {
            vp.lowLatency = false;
            vp.maxClocks = false;
        }
        vp.profile = h264For(step, vp.profile);
    }

    /** Perfil H.264 con el escalón: High pasa a Main en MAIN; todo a Baseline desde BASELINE. */
    static String h264For(int step, String wanted) {
        String w = wanted == null ? "baseline" : wanted;
        if (step >= BASELINE) return "baseline";
        if (step >= MAIN && "high".equalsIgnoreCase(w)) return "main";
        return w;
    }

    /** Bitrate con el escalón: desde CAR_SIZE, como mucho el de «Coche». */
    static int bitrate(int step, int bps, int carBps) {
        return step >= CAR_SIZE && carBps > 0 ? Math.min(bps, carBps) : bps;
    }

    /** Tamaño con el escalón: desde CAR_SIZE, 1280 de ancho con la misma proporción (1920x882 → 1280x588). */
    static int[] size(int step, int w, int h) {
        if (step < CAR_SIZE || w <= CAR_WIDTH || w <= 0) return new int[]{w, h};
        return new int[]{CAR_WIDTH, Math.round((float) CAR_WIDTH * h / w / 2) * 2};
    }

    /** Para el log. */
    static String describe(int step) {
        switch (step) {
            case AS_IS:
                return "tal cual";
            case NO_BOOST:
                return "sin baja latencia del fabricante ni relojes al máximo";
            case MAIN:
                return "sin baja latencia ni relojes al máximo, perfil Main";
            case BASELINE:
                return "sin baja latencia ni relojes al máximo, perfil Baseline";
            default:
                return "sin baja latencia ni relojes al máximo, Baseline, tamaño y bitrate de «Coche»";
        }
    }

    /**
     * La línea del log al bajar: «codificador: falló con X, sigo con Y». params: lo que llevaba el códec
     * (VideoEncoder.Params y la baja latencia); why: el error; next -1 si no se baja.
     */
    static String failLine(String key, int step, String params, String why, int frames, long sinceStartMs, int next) {
        String head = String.format(Locale.US, "codificador: falló con escalón %d «%s» (%s) en %s: %s, %d frames a los %d ms",
                step, describe(step), params, key, why, frames, sinceStartMs);
        if (next >= 0) return head + "; sigo con escalón " + next + " «" + describe(next) + "» (queda recordado para este móvil)";
        if (!early(frames, sinceStartMs)) return head + "; ya había dado frames: no bajo de escalón";
        return head + "; no quedan escalones más seguros";
    }

    // ---------------------------------------------------------------- lo recordado (Config.encFallback)

    /** El escalón recordado para un móvil y códec, con el perfil con que se aprendió. */
    static final class Entry {
        final String key;
        final String profile;
        final int step;

        Entry(String key, String profile, int step) {
            this.key = clean(key);
            this.profile = clean(profile);
            this.step = Math.max(AS_IS, Math.min(LAST, step));
        }
    }

    /** Escalón con que empezar: el recordado si es el mismo móvil, códec y perfil; si no, desde arriba (0). */
    static int startStep(List<Entry> memory, String key, String profile) {
        String k = clean(key);
        String p = clean(profile);
        for (Entry e : memory) if (e.key.equals(k)) return e.profile.equals(p) ? e.step : AS_IS;
        return AS_IS;
    }

    /** La lista con el escalón de key (sustituye el que hubiera; como mucho KEEP, el más reciente al final). */
    static List<Entry> remember(List<Entry> memory, String key, String profile, int step) {
        List<Entry> out = new ArrayList<>();
        String k = clean(key);
        for (Entry e : memory) if (!e.key.equals(k)) out.add(e);
        out.add(new Entry(k, profile, step));
        while (out.size() > KEEP) out.remove(0);
        return out;
    }

    /** Una línea por móvil: «modelo · códec», perfil y escalón separados por tabuladores. */
    static String encode(List<Entry> memory) {
        StringBuilder b = new StringBuilder();
        for (Entry e : memory) {
            if (b.length() > 0) b.append('\n');
            b.append(e.key).append('\t').append(e.profile).append('\t').append(e.step);
        }
        return b.toString();
    }

    static List<Entry> decode(String s) {
        List<Entry> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length != 3 || f[0].isEmpty()) continue;
            try {
                out.add(new Entry(f[0], f[1], Integer.parseInt(f[2].trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
    }
}
