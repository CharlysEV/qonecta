package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Memoria del cable por coche (pura salvo {@link #load} y {@link #save}: la prueban los tests). Hay coches en los que el
 * cable no aguanta nunca (un T03, del 06-11 al 10-11: más de cien sesiones por cable en 4 días, ninguna de más de 11 s,
 * y por Wi-Fi Direct todas bien): cada viaje volvía al cable, repetía el fallo y tardaba en pasar solo a Wi-Fi Direct
 * (CableFallback). Con esto, si en TRIPS viajes seguidos con el mismo coche el cable acabó en ese paso sin una sola sesión
 * buena por cable (RECOVERED_MS con vídeo), los viajes siguientes empiezan ya por Wi-Fi Direct, con un aviso una sola
 * vez. La conexión elegida no se toca.
 *
 * Se vuelve a probar el cable:
 * - al elegirlo a mano (widget o «Cambiar»): se olvida todo;
 * - a los RETRY_MS, o al actualizar la app: un viaje por el cable y, si vuelve a fallar, el siguiente ya empieza por
 *   Wi-Fi Direct (sin repetir el aviso);
 * - con otro coche: la cuenta es del coche que la hizo (el del último viaje decide).
 * Una sesión buena por cable lo olvida todo. Con el ajuste «Pasar a Wi-Fi Direct si el cable se cae» apagado no hace
 * nada (tampoco hay paso que contar).
 *
 * El coche, por una huella de su CarUUID y su ProjectID (CAR_INFO), que no se puede deshacer ni se escribe en el log. Un
 * «viaje» es un arranque del servicio. Lo usa LinkService, solo en el hilo principal; los textos que devuelve son para
 * el log (null: nada que decir).
 */
final class CableMemory {
    /** Viajes seguidos con el cable caído y sin sesión buena para empezar por Wi-Fi Direct. */
    static final int TRIPS = 2;
    /** Pasado esto, se vuelve a probar el cable. */
    static final long RETRY_MS = 7 * 24 * 3_600_000L;

    private static final String PREFS = "hql_cable_memory";
    private static final String KEY = "memory";

    /** Qué hacer al empezar el viaje. */
    static final class Start {
        /** El viaje empieza sin el cable (el accesorio retenido; con el cable elegido, por Wi-Fi Direct). */
        final boolean skip;
        final String why;

        Start(boolean skip, String why) {
            this.skip = skip;
            this.why = why;
        }
    }

    // Lo guardado.
    /** Huella del coche de la cuenta ("": ninguno). */
    private String car = "";
    private int trips;
    /** Cuándo llegó la cuenta a TRIPS (reloj de pared) y con qué versión de la app. */
    private long sinceMs;
    private long version;
    /** El aviso «El cable no funciona con este coche» ya salió. */
    private boolean noticed;
    /** Huella del coche del último viaje en el que se identificó. */
    private String lastCar = "";

    // Este viaje.
    private long now;
    private long appVersion;
    private boolean enabled;
    private String tripCar;
    private boolean tripFell;
    private boolean tripGood;
    private boolean tripCounted;
    private boolean skipping;
    private boolean dirty;

    /** Puro: la huella del coche. Sin identificadores (CAR_INFO sin ellos), la misma para todos. */
    static String carKey(String carUuid, String projectId) {
        String id = "qonecta+ cable|" + (carUuid == null ? "" : carUuid) + "|" + (projectId == null ? "" : projectId);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(java.util.Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(id.hashCode());
        }
    }

    /** Viaje nuevo: la hora (reloj de pared), el versionCode de la app y el ajuste «Pasar a Wi-Fi Direct si el cable se cae». */
    Start startTrip(long nowMs, long appVersion, boolean enabled) {
        this.now = nowMs;
        this.appVersion = appVersion;
        this.enabled = enabled;
        tripCar = null;
        tripFell = false;
        tripGood = false;
        tripCounted = false;
        skipping = false;
        if (trips < TRIPS) return new Start(false, null);
        if (!enabled) {
            return new Start(false, "el cable no funciona con este coche, pero el ajuste «Pasar a Wi-Fi Direct si el cable se"
                    + " cae» está apagado: empiezo por el cable");
        }
        if (version != appVersion) return retry("la app se ha actualizado");
        if (nowMs - sinceMs >= RETRY_MS || nowMs < sinceMs) return retry("han pasado 7 días");
        if (!lastCar.isEmpty() && !lastCar.equals(car)) {
            return new Start(false, "el cable no funciona con otro coche, pero el último viaje fue con uno distinto: empiezo por"
                    + " el cable");
        }
        skipping = true;
        return new Start(true, "el cable no funcionó en los " + TRIPS + " últimos viajes con este coche (ninguna sesión buena):"
                + " este empieza sin él (para volver a probarlo, elige «Cable USB» en el widget o en «Cambiar»)");
    }

    private Start retry(String why) {
        // Un viaje de prueba: si el cable vuelve a acabar en Wi-Fi Direct, el siguiente ya empieza sin él.
        trips = TRIPS - 1;
        dirty = true;
        return new Start(false, "el cable no funcionaba con este coche, pero " + why + ": lo vuelvo a probar en este viaje");
    }

    /** No se pudo empezar sin el cable (el Wi-Fi del móvil apagado): este viaje va por él. */
    void notSkipping() {
        skipping = false;
    }

    /** Con el viaje empezado por Wi-Fi Direct en lugar del cable elegido: true la primera vez (toca el aviso). */
    boolean noticeDue() {
        if (!skipping || noticed) return false;
        noticed = true;
        dirty = true;
        return true;
    }

    /** El coche se ha identificado (CAR_INFO, por el cable o por Wi-Fi) con esta huella ({@link #carKey}). */
    String onCar(String key) {
        if (key == null || key.isEmpty()) return null;
        boolean first = tripCar == null;
        tripCar = key;
        if (!key.equals(lastCar)) {
            lastCar = key;
            dirty = true;
        }
        if (tripFell && !tripCounted) return count();
        if (first && skipping && !key.equals(car)) {
            return "este no es el coche con el que el cable no funciona: este viaje sigue sin el cable y el siguiente con este"
                    + " coche vuelve a él";
        }
        return null;
    }

    /** En este viaje el cable ha acabado en el paso a Wi-Fi (CableFallback). Cuenta si no hubo antes una sesión buena. */
    String onCableFallback() {
        if (!enabled || tripGood || tripFell) return null;
        tripFell = true;
        if (tripCar == null) return "el cable ha acabado en Wi-Fi sin una sesión buena: lo apunto cuando el coche se identifique";
        return count();
    }

    private String count() {
        tripCounted = true;
        dirty = true;
        if (tripCar.equals(car)) {
            trips = Math.min(TRIPS, trips + 1);
        } else {
            car = tripCar;
            trips = 1;
            noticed = false;
        }
        if (trips < TRIPS) {
            return "el cable ha acabado en Wi-Fi sin una sesión buena en este viaje (" + trips + " de " + TRIPS + "): si se repite"
                    + " en el siguiente con este coche, los demás empezarán por Wi-Fi Direct";
        }
        sinceMs = now;
        version = appVersion;
        return "el cable ha acabado en Wi-Fi sin una sesión buena en " + TRIPS + " viajes seguidos con este coche: los siguientes"
                + " empiezan por Wi-Fi Direct (hasta elegir el cable a mano, 7 días o una actualización de la app)";
    }

    /** Una sesión buena por cable (RECOVERED_MS con vídeo) en este viaje: con este coche el cable funciona. */
    String onGoodCable() {
        if (tripGood) return null;
        tripGood = true;
        if (car.isEmpty() || (tripCar != null && !tripCar.equals(car))) return null;
        clear();
        return "una sesión buena por cable: olvido que el cable fallaba con este coche";
    }

    /** «Cable USB» elegido a mano (widget o «Cambiar»): se vuelve a probar, desde cero. */
    String onManualCable() {
        skipping = false;
        if (car.isEmpty() && trips == 0 && !noticed) return null;
        clear();
        return "«Cable USB» elegido a mano: olvido que el cable fallaba con este coche y lo vuelvo a probar";
    }

    private void clear() {
        car = "";
        trips = 0;
        sinceMs = 0;
        version = 0;
        noticed = false;
        dirty = true;
    }

    /** Los viajes siguientes empiezan sin el cable (para los tests y el log). */
    boolean armed() {
        return trips >= TRIPS;
    }

    // ---------------------------------------------------------------- lo guardado

    /** Puro: lo guardado, en una línea. */
    String encode() {
        return "1|" + car + "|" + trips + "|" + sinceMs + "|" + version + "|" + (noticed ? 1 : 0) + "|" + lastCar;
    }

    /** Puro: de {@link #encode}; con algo que no se entiende, vacía. */
    static CableMemory decode(String s) {
        CableMemory m = new CableMemory();
        if (s == null) return m;
        String[] p = s.split("\\|", -1);
        if (p.length != 7 || !"1".equals(p[0])) return m;
        try {
            int trips = Integer.parseInt(p[2]);
            long since = Long.parseLong(p[3]);
            long version = Long.parseLong(p[4]);
            m.car = p[1];
            m.trips = Math.max(0, Math.min(TRIPS, trips));
            m.sinceMs = since;
            m.version = version;
            m.noticed = "1".equals(p[5]);
            m.lastCar = p[6];
        } catch (NumberFormatException e) {
            return new CableMemory();
        }
        return m;
    }

    static CableMemory load(Context c) {
        try {
            return decode(prefs(c).getString(KEY, null));
        } catch (RuntimeException e) {
            return new CableMemory();
        }
    }

    /** Guarda si algo cambió. */
    void save(Context c) {
        if (!dirty) return;
        dirty = false;
        try {
            prefs(c).edit().putString(KEY, encode()).apply();
        } catch (RuntimeException e) {
            L.w("cable → Wi-Fi Direct: no se pudo guardar la memoria del cable: " + e);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
