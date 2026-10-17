package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una orden al coche por la nube de Leapmotor (widget «Coche» y voz): cerrar, abrir, maletero, clima, desempañar,
 * apagar el clima y localizar. Los cmdId y cuerpos son los documentados por leapmotor-api (markoceri, AGPL-3.0,
 * docs/api.md «Remote Commands»). Abrir el coche y el maletero piden confirmación (por si la voz entiende mal).
 */
final class CarCommand {
    enum Kind { LOCK, UNLOCK, TRUNK_OPEN, TRUNK_CLOSE, CLIMATE_ON, CLIMATE_OFF, DEFROST, FIND }

    static final int TEMP_MIN = 18;
    static final int TEMP_MAX = 32;
    static final int TEMP_DEFAULT = 22;

    final Kind kind;
    /** Clima: la temperatura pedida (°C). */
    final int tempC;

    CarCommand(Kind kind) {
        this(kind, TEMP_DEFAULT);
    }

    CarCommand(Kind kind, int tempC) {
        this.kind = kind;
        this.tempC = Math.max(TEMP_MIN, Math.min(TEMP_MAX, tempC));
    }

    /** Lo que se abre pide un toque de confirmación. */
    boolean needsConfirm() {
        return kind == Kind.UNLOCK || kind == Kind.TRUNK_OPEN;
    }

    String cmdId() {
        switch (kind) {
            case LOCK:
            case UNLOCK:
                return LeapApi.CMD_LOCK;
            case TRUNK_OPEN:
            case TRUNK_CLOSE:
                return LeapApi.CMD_TRUNK;
            case FIND:
                return LeapApi.CMD_FIND;
            default:
                return LeapApi.CMD_CLIMATE;
        }
    }

    /**
     * El cuerpo de la orden. interiorC (NaN sin dato): con el clima a una temperatura, calor si dentro hace menos y frío
     * si hace más. El apagado del clima depende del modelo (el T03 quiere el cuerpo entero).
     */
    String content(String carType, double interiorC) {
        switch (kind) {
            case LOCK:
                return "{\"value\":\"lock\"}";
            case UNLOCK:
                return "{\"value\":\"unlock\"}";
            case TRUNK_OPEN:
            case FIND:
                return "{\"value\":\"true\"}";
            case TRUNK_CLOSE:
                return "{\"value\":\"false\"}";
            case DEFROST:
                return climate("in", "hot", 32, 7, 1);
            case CLIMATE_OFF:
                return CarCloudStore.isT03(carType)
                        ? "{\"circle\":\"out\",\"mode\":\"wind\",\"operate\":\"off\",\"position\":\"all\",\"temperature\":\"26\",\"windlevel\":\"3\",\"wshld\":\"0\"}"
                        : "{\"operate\":\"off\"}";
            default: {
                boolean hot = Double.isNaN(interiorC) ? tempC >= TEMP_DEFAULT : interiorC < tempC;
                return climate("out", hot ? "hot" : "cold", tempC, 4, 0);
            }
        }
    }

    private static String climate(String circle, String mode, int temp, int wind, int wshld) {
        return String.format(Locale.ROOT, "{\"circle\":\"%s\",\"mode\":\"%s\",\"operate\":\"manual\",\"position\":\"all\","
                + "\"temperature\":\"%d\",\"windlevel\":\"%d\",\"wshld\":\"%d\"}", circle, mode, temp, wind, wshld);
    }

    /** Lo que se hace, para la pantalla («Abrir el maletero», «Clima a 21 °C»). */
    String label() {
        switch (kind) {
            case LOCK:
                return Str.get(R.string.hql_cc_lock);
            case UNLOCK:
                return Str.get(R.string.hql_cc_unlock);
            case TRUNK_OPEN:
                return Str.get(R.string.hql_cc_trunk_open);
            case TRUNK_CLOSE:
                return Str.get(R.string.hql_cc_trunk_close);
            case CLIMATE_OFF:
                return Str.get(R.string.hql_cc_climate_off);
            case DEFROST:
                return Str.get(R.string.hql_cc_defrost);
            case FIND:
                return Str.get(R.string.hql_cc_find);
            default:
                return Str.get(R.string.hql_cc_climate_on, tempC);
        }
    }

    /** Para el log (sin traducir). */
    @Override
    public String toString() {
        return kind == Kind.CLIMATE_ON ? "CLIMATE_ON " + tempC + " °C" : kind.name();
    }

    /** Los botones del widget (extra de CarControlActivity), o null. */
    static CarCommand fromButton(String name) {
        if (name == null) return null;
        switch (name) {
            case "lock":
                return new CarCommand(Kind.LOCK);
            case "unlock":
                return new CarCommand(Kind.UNLOCK);
            case "trunk":
                return new CarCommand(Kind.TRUNK_OPEN);
            case "climate":
                return new CarCommand(Kind.CLIMATE_ON);
            case "find":
                return new CarCommand(Kind.FIND);
            default:
                return null;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{2})\\b");

    /**
     * Puro: la orden de una frase dicha («cierra el coche», «abre el maletero», «pon el clima a 21», «desempaña», «apaga
     * el aire», «dónde está el coche»), o null si no se entiende. En castellano y lo básico en inglés.
     */
    static CarCommand parse(String spoken) {
        if (spoken == null) return null;
        String t = Normalizer.normalize(spoken.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = " " + t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
        boolean close = has(t, " cierra", " cerrar", " cierre", " bloquea", " close", " lock ");
        boolean off = has(t, " apaga", " apagar", " quita", " para el", " parar", " desconecta", " off ", " stop ");
        if (has(t, " maleter", " porton", " trunk", " boot ", " tailgate")) {
            return new CarCommand(close ? Kind.TRUNK_CLOSE : Kind.TRUNK_OPEN);
        }
        if (has(t, " desempan", " desempen", " luna", " parabrisas", " defrost", " windshield")) {
            return off ? new CarCommand(Kind.CLIMATE_OFF) : new CarCommand(Kind.DEFROST);
        }
        boolean warm = has(t, " calienta", " calentar", " calefac", " warm", " heat");
        boolean cool = has(t, " enfria", " enfriar", " refresca", " cool");
        if (warm || cool || has(t, " clima", " aire", " temperatura", " grados", " climate", " air ", " ac ")) {
            if (off || close) return new CarCommand(Kind.CLIMATE_OFF);
            Matcher m = NUMBER.matcher(t);
            int temp = m.find() ? Integer.parseInt(m.group(1)) : warm ? 26 : cool ? 19 : TEMP_DEFAULT;
            return new CarCommand(Kind.CLIMATE_ON, temp);
        }
        if (has(t, " localiza", " donde esta", " encuentra", " busca", " pita", " pite", " pitar", " claxon", " bocina", " find", " where ")) {
            return new CarCommand(Kind.FIND);
        }
        if (close) return new CarCommand(Kind.LOCK);
        if (has(t, " abre", " abrir", " desbloquea", " unlock", " open")) return new CarCommand(Kind.UNLOCK);
        return null;
    }

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }
}
