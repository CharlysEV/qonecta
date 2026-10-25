package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.health.HealthStats;
import android.os.health.SystemHealthManager;
import android.os.health.TimerStat;
import android.os.health.UidHealthStats;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Lo que gasta Qonecta+ de batería, también cerrada: Android le da a cada app sus propias estadísticas (SystemHealthManager:
 * procesador, GPS, vídeo, radio y datos desde la última carga) y con ellas se estima el gasto en mAh con un modelo
 * sencillo, ajustado con el informe de batería del S25 Ultra (24-10: ~130 mA por segundo de procesador, ~60 mA de GPS).
 * Se reparte en dos bolsas por día: en el coche (de que empieza un viaje a que acaba) y en reposo (lo demás, con la app
 * cerrada o en segundo plano). Se apunta en cada viaje (inicio y fin), en la comprobación de fondo (cada ~15 min) y al
 * abrir la app. No cuenta la pantalla del móvil mientras la app está a la vista (eso lo eliges tú) ni lo que gastan
 * Android Auto, Maps o la música, que Android apunta a ellos.
 */
final class AppEnergy {
    /** mA de media por segundo de procesador (usuario + sistema), de GPS y de codificador de vídeo. */
    static final double CPU_MA = 130;
    static final double GPS_MA = 60;
    static final double VIDEO_MA = 25;
    /** mAh por MB de datos móviles y de Wi-Fi, cuando el móvil no da la energía de la radio. */
    static final double MOBILE_MAH_PER_MB = 2.0;
    static final double WIFI_MAH_PER_MB = 0.003;
    /** Días que se guardan. */
    static final int KEEP_DAYS = 14;
    private static final String PREFS = "hql_energy";
    /** Clave del sensor GPS en las estadísticas de Android (BatteryStats.Uid.Sensor.GPS). */
    private static final String GPS_SENSOR = "-10000";

    private AppEnergy() {
    }

    /** Lo que se cuenta de una foto de las estadísticas (acumulado desde la última carga). */
    static final class Snap {
        double cpuMs;
        double gpsMs;
        double videoMs;
        /** Energía de la radio que da el móvil (mA·ms); 0 si no la da. */
        double radioMams;
        double mobileBytes;
        double wifiBytes;

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("cpu", cpuMs).put("gps", gpsMs).put("video", videoMs).put("radio", radioMams)
                    .put("mob", mobileBytes).put("wifi", wifiBytes);
        }

        static Snap fromJson(JSONObject o) {
            Snap s = new Snap();
            s.cpuMs = o.optDouble("cpu", 0);
            s.gpsMs = o.optDouble("gps", 0);
            s.videoMs = o.optDouble("video", 0);
            s.radioMams = o.optDouble("radio", 0);
            s.mobileBytes = o.optDouble("mob", 0);
            s.wifiBytes = o.optDouble("wifi", 0);
            return s;
        }
    }

    // ------------------------------------------------------------------ puro

    /**
     * Puro: lo gastado (mAh) entre dos fotos. Si alguna cifra bajó, Android las puso a cero al cargar: lo de ahora es lo
     * gastado desde entonces.
     */
    static double mahBetween(Snap a, Snap b) {
        boolean reset = a == null || b.cpuMs < a.cpuMs || b.gpsMs < a.gpsMs || b.videoMs < a.videoMs || b.mobileBytes < a.mobileBytes
                || b.wifiBytes < a.wifiBytes || b.radioMams < a.radioMams;
        Snap base = reset ? new Snap() : a;
        double cpu = (b.cpuMs - base.cpuMs) / 3_600_000.0 * CPU_MA;
        double gps = (b.gpsMs - base.gpsMs) / 3_600_000.0 * GPS_MA;
        double video = (b.videoMs - base.videoMs) / 3_600_000.0 * VIDEO_MA;
        double radioMams = b.radioMams - base.radioMams;
        double radio = radioMams > 0 ? radioMams / 3_600_000.0
                : (b.mobileBytes - base.mobileBytes) / 1e6 * MOBILE_MAH_PER_MB + (b.wifiBytes - base.wifiBytes) / 1e6 * WIFI_MAH_PER_MB;
        return Math.max(0, cpu + gps + video + radio);
    }

    /** Puro: suma mah (y minutos) a la bolsa de un día («idle» o «car») en el diario, y quita los días viejos. */
    static JSONObject add(JSONObject days, String day, boolean car, double mah, double minutes) throws org.json.JSONException {
        JSONObject d = days.optJSONObject(day);
        if (d == null) days.put(day, d = new JSONObject());
        String k = car ? "car" : "idle";
        d.put(k, d.optDouble(k, 0) + mah);
        d.put(k + "Min", d.optDouble(k + "Min", 0) + minutes);
        // Solo los últimos KEEP_DAYS (las claves son fechas «2026-10-24»: se ordenan solas).
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Iterator<String> it = days.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        for (int i = 0; i < keys.size() - KEEP_DAYS; i++) days.remove(keys.get(i));
        return days;
    }

    /** Puro: lo gastado en los días que empiezan por los de la lista: {reposo mAh, coche mAh, minutos de coche}. */
    static double[] total(JSONObject days, java.util.Collection<String> wanted) {
        double idle = 0;
        double car = 0;
        double carMin = 0;
        for (String k : wanted) {
            JSONObject d = days.optJSONObject(k);
            if (d == null) continue;
            idle += d.optDouble("idle", 0);
            car += d.optDouble("car", 0);
            carMin += d.optDouble("carMin", 0);
        }
        return new double[]{idle, car, carMin};
    }

    // ------------------------------------------------------------------ Android

    /** La foto de ahora de las estadísticas de esta app, o null si Android no las da. */
    static Snap snapshot(Context c) {
        try {
            SystemHealthManager shm = (SystemHealthManager) c.getSystemService(Context.SYSTEM_HEALTH_SERVICE);
            if (shm == null) return null;
            HealthStats h = shm.takeMyUidSnapshot();
            Snap s = new Snap();
            s.cpuMs = m(h, UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS) + m(h, UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS);
            if (h.hasTimers(UidHealthStats.TIMERS_SENSORS)) {
                TimerStat gps = h.getTimers(UidHealthStats.TIMERS_SENSORS).get(GPS_SENSOR);
                if (gps != null) s.gpsMs = gps.getTime();
            }
            if (h.hasTimer(UidHealthStats.TIMER_VIDEO)) s.videoMs = h.getTimerTime(UidHealthStats.TIMER_VIDEO);
            s.radioMams = m(h, UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS) + m(h, UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS);
            s.mobileBytes = m(h, UidHealthStats.MEASUREMENT_MOBILE_RX_BYTES) + m(h, UidHealthStats.MEASUREMENT_MOBILE_TX_BYTES);
            s.wifiBytes = m(h, UidHealthStats.MEASUREMENT_WIFI_RX_BYTES) + m(h, UidHealthStats.MEASUREMENT_WIFI_TX_BYTES);
            return s;
        } catch (RuntimeException e) {
            L.w("consumo de la app: Android no da sus estadísticas (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static double m(HealthStats h, int key) {
        return h.hasMeasurement(key) ? h.getMeasurement(key) : 0;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String today(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /**
     * Cierra el tramo desde la última foto: lo gastado va a la bolsa del coche (car, el viaje que acaba) o a la de reposo.
     * Barato; se puede llamar desde cualquier hilo.
     */
    static synchronized void checkpoint(Context c, boolean car) {
        Snap now = snapshot(c);
        if (now == null) return;
        SharedPreferences p = prefs(c);
        long t = System.currentTimeMillis();
        try {
            String last = p.getString("snap", null);
            long lastAt = p.getLong("snapAt", 0);
            if (last != null && lastAt > 0) {
                double mah = mahBetween(Snap.fromJson(new JSONObject(last)), now);
                double minutes = Math.max(0, (t - lastAt) / 60_000.0);
                JSONObject days = new JSONObject(p.getString("days", "{}"));
                add(days, today(t), car, mah, minutes);
                p.edit().putString("days", days.toString()).apply();
                if (car) L.i(String.format(Locale.US, "consumo de la app: %.1f mAh en este viaje (%.0f min)", mah, minutes));
            }
            p.edit().putString("snap", now.toJson().toString()).putLong("snapAt", t).apply();
        } catch (org.json.JSONException e) {
            p.edit().remove("snap").remove("days").apply();
        }
    }

    /** Lo gastado en los últimos n días (hoy incluido): {reposo mAh, coche mAh, minutos de coche}. */
    static double[] lastDays(Context c, int n) {
        try {
            JSONObject days = new JSONObject(prefs(c).getString("days", "{}"));
            java.util.List<String> want = new java.util.ArrayList<>();
            long t = System.currentTimeMillis();
            for (int i = 0; i < n; i++) want.add(today(t - i * 86_400_000L));
            return total(days, want);
        } catch (org.json.JSONException e) {
            return new double[]{0, 0, 0};
        }
    }

    /** Días con datos (para decir desde cuándo se mide). */
    static int daysMeasured(Context c) {
        try {
            return new JSONObject(prefs(c).getString("days", "{}")).length();
        } catch (org.json.JSONException e) {
            return 0;
        }
    }
}
