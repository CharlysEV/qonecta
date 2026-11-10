package com.headqlink.link;

import org.json.JSONObject;

import java.util.Locale;

/**
 * El plan de carga del coche (orden 190 de la nube de Leapmotor): el límite de carga y una sola ventana con hora de
 * inicio y de fin. La orden sustituye el plan entero, así que lo que no se quiere cambiar (los días, «circulation»,
 * «recharge») se devuelve tal como se leyó. Los nombres y el cuerpo son los de leapmotor-api (markoceri, AGPL-3.0,
 * RemoteActionCtlChargePlan y getAppointment). Puro.
 */
final class ChargePlan {
    static final int SOC_MIN = 50;
    static final int SOC_MAX = 100;
    /** Lo que pone leapmotor-api cuando la nube no manda un campo (un plan solo con hora de inicio no trae el resto). */
    static final String ALL_DAYS = "1,2,3,4,5,6,7";
    static final String DEFAULT_START = "00:00";
    static final String DEFAULT_END = "08:00";

    /** La ventana está activa (si no, el coche carga al enchufar). */
    final boolean enabled;
    /** Límite de carga, en %. */
    final int soc;
    /** «HH:MM», hora local del coche. */
    final String start;
    final String end;
    /** Días («1,2,3,4,5,6,7»). */
    final String cycles;
    /** Repetir (0 una vez, 1 cada semana; un C10 real tenía 2): se devuelve como se leyó. */
    final int circulation;
    /** Recarga automática: se devuelve como se leyó. */
    final int recharge;

    ChargePlan(boolean enabled, int soc, String start, String end, String cycles, int circulation, int recharge) {
        this.enabled = enabled;
        this.soc = Math.max(SOC_MIN, Math.min(SOC_MAX, soc));
        this.start = time(start, DEFAULT_START);
        this.end = time(end, DEFAULT_END);
        this.cycles = cycles == null || !cycles.matches("[0-9](,[0-9]){0,6}") ? ALL_DAYS : cycles;
        this.circulation = circulation;
        this.recharge = recharge;
    }

    /**
     * Lo que contesta getAppointment (cmdId 190): un objeto plano con los mismos nombres que la orden. null si no hay
     * plan (objeto vacío) o no trae el límite: sin saber el límite actual no se manda nada.
     */
    static ChargePlan parse(JSONObject o) {
        if (o == null || !o.has("chargesoc")) return null;
        int soc = o.optInt("chargesoc", -1);
        if (soc < 1 || soc > 100) return null;
        return new ChargePlan(o.optInt("chargeEnable", 0) == 1, soc, o.optString("starttime", ""), o.optString("endtime", ""),
                o.optString("cycles", ""), o.optInt("circulation", 0), o.optInt("recharge", 0));
    }

    /** El mismo plan con otra ventana y otro límite, activo; los días y lo demás, como estaban. */
    ChargePlan window(String newStart, String newEnd, int newSoc) {
        return new ChargePlan(true, newSoc, newStart, newEnd, cycles, circulation, recharge);
    }

    /** El mismo plan sin ventana: el coche carga al enchufar («Cargar ya»). */
    ChargePlan disabled() {
        return new ChargePlan(false, soc, start, end, cycles, circulation, recharge);
    }

    /** El cuerpo de la orden 190, con los campos en el orden de leapmotor-api y sin espacios. */
    String content() {
        return String.format(Locale.ROOT, "{\"chargeEnable\":%d,\"chargesoc\":%d,\"circulation\":%d,\"cycles\":\"%s\","
                + "\"endtime\":\"%s\",\"recharge\":%d,\"starttime\":\"%s\"}", enabled ? 1 : 0, soc, circulation, cycles, end,
                recharge, start);
    }

    /** true si los dos planes mandarían lo mismo al coche. */
    boolean same(ChargePlan other) {
        return other != null && content().equals(other.content());
    }

    /** «H:MM» o «HH:MM» válida, con dos cifras en la hora; si no, la de reserva. */
    static String time(String s, String fallback) {
        if (s == null) return fallback;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9]{1,2}):([0-9]{2})(:[0-9]{2})?$").matcher(s.trim());
        if (!m.matches()) return fallback;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return fallback;
        return String.format(Locale.ROOT, "%02d:%02d", h, min);
    }

    /** Para el log: no lleva nada personal. */
    @Override
    public String toString() {
        return (enabled ? "ventana " + start + "–" + end : "sin ventana (" + start + "–" + end + ")") + ", límite " + soc
                + " %, días " + cycles + ", circulation " + circulation + ", recharge " + recharge;
    }
}
