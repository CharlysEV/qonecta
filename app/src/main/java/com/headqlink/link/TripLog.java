package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Registro de viajes: cada sesión con el coche (modo ampliado) graba el recorrido GPS (con el segundo de cada punto:
 * de ahí salen las paradas, TripStops), km, tiempo, desnivel, energía estimada (EnergyModel) y velocidad máxima, y lo
 * guarda en files/trips/ al terminar si se han recorrido más de 300 m. Con la cuenta de Leapmotor (CarCloud), además el
 * % de batería, el cuentakilómetros y, en un REEV, los litros del depósito al empezar y al terminar: de ahí salen el
 * consumo real y la gasolina gastada.
 */
final class TripLog {
    static final class Trip {
        long startMs;
        long minutes;
        double km;
        double kwh;
        double climb;
        double descent;
        double maxKmh;
        double[][] track = new double[0][];
        /** Los sitios que contó la Guía del camino en este viaje (sus títulos), para el diario del viaje. */
        String[] places = new String[0];
        // Datos reales del coche (nube de Leapmotor) al empezar y al terminar; NaN si no los hubo.
        double socStart = Double.NaN;
        double socEnd = Double.NaN;
        double odoStart = Double.NaN;
        double odoEnd = Double.NaN;
        /** Capacidad de la batería del perfil (kWh) con la que se mide. */
        double capKwh = Double.NaN;
        /** Se vio cargando (o subir la batería) por el camino: la bajada no es el consumo. */
        boolean charged;
        /** REEV: litros del depósito al empezar y al terminar (NaN si no es REEV o no se supo). */
        double fuelStartL = Double.NaN;
        double fuelEndL = Double.NaN;
        /** Batería del MÓVIL al empezar y al terminar (%; -1 si no se supo), lo gastado (mAh; NaN) y si iba enchufado. */
        int phonePct0 = -1;
        int phonePct1 = -1;
        double phoneMah = Double.NaN;
        boolean phonePlugged;

        /** Lo que bajó la batería del móvil (puntos de %), o -1 si no se sabe o iba enchufado. */
        int phoneUsedPct() {
            if (phonePlugged || phonePct0 < 0 || phonePct1 < 0) return -1;
            return Math.max(0, phonePct0 - phonePct1);
        }

        /** Fin del viaje (aproximado: el inicio más la duración). */
        long endMs() {
            return startMs + minutes * 60_000L;
        }

        /** Litros de gasolina gastados según el depósito (REEV), o NaN. */
        double fuelUsedL() {
            if (Double.isNaN(fuelStartL) || Double.isNaN(fuelEndL)) return Double.NaN;
            double d = fuelStartL - fuelEndL;
            // Más de 0,3 L de subida es que se repostó por el camino: no es un consumo.
            return d < -0.3 ? Double.NaN : Math.max(0, d);
        }
    }

    /** % de batería y cuentakilómetros del coche en un momento (de la nube). */
    static final class CloudMark {
        final double soc;
        final double odo;
        final long timeMs;
        /** REEV: litros del depósito (NaN si no es REEV). */
        final double fuelL;

        CloudMark(double soc, double odo, long timeMs) {
            this(soc, odo, timeMs, Double.NaN);
        }

        CloudMark(double soc, double odo, long timeMs, double fuelL) {
            this.soc = soc;
            this.odo = odo;
            this.timeMs = timeMs;
            this.fuelL = fuelL;
        }
    }

    private static volatile TripLog instance;
    private final Context ctx;
    private final CarSensors sensors;
    private volatile boolean running;
    private final List<double[]> track = new ArrayList<>();
    /** Los sitios que va contando la Guía (títulos, sin repetir), como mucho MAX_PLACES. */
    private final java.util.LinkedHashSet<String> places = new java.util.LinkedHashSet<>();
    static final int MAX_PLACES = 12;
    private long startMs = System.currentTimeMillis();
    private long startElapsed = SystemClock.elapsedRealtime();
    /** El viaje que se retoma (TripCarry): sus km, energía y desnivel ya hechos, para las bases del primer paso. */
    private JSONObject resumed;
    private double startKwh = Double.NaN;
    private double startKm;
    private double startClimb;
    private double startDescent;
    private double maxKmh;
    // Nube de Leapmotor: la primera y la última lectura de este viaje (leídas después de empezar).
    private volatile CloudMark cloudStart;
    private volatile CloudMark cloudEnd;
    private volatile boolean cloudCharged;
    private double cloudCap = Double.NaN;
    private long cloudSeenFetch;
    /** Modo demostración: no se graba nada (los viajes que se ven son los de DemoMode). */
    private final boolean demo;

    private TripLog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sensors = CarSensors.start(ctx);
        demo = DemoMode.active();
        phoneStart = demo ? null : PhoneBattery.read(this.ctx);
        if (!demo && CarSensors.continuedTrip()) resume();
    }

    /** La batería del móvil al empezar este viaje (PhoneBattery.read), o null. */
    private long[] phoneStart;

    /**
     * La conexión anterior se cortó hace poco y los sensores siguen su viaje: este también, en el mismo fichero (su
     * inicio, recorrido, máximos y los datos del coche del principio).
     */
    private void resume() {
        File[] files = new File(ctx.getExternalFilesDir(null), "trips").listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8));
            long end = o.optLong("start") + o.optLong("minutes") * 60_000L;
            if (System.currentTimeMillis() - end > TripCarry.CONTINUE_MS + 60_000L) return;
            startMs = o.optLong("start");
            startElapsed = SystemClock.elapsedRealtime() - o.optLong("minutes") * 60_000L;
            maxKmh = o.optDouble("maxKmh", 0);
            JSONArray t = o.optJSONArray("track");
            if (t != null) {
                synchronized (track) {
                    for (int i = 0; i < t.length(); i++) {
                        JSONArray p = t.optJSONArray(i);
                        if (p != null && p.length() >= 3) track.add(new double[]{p.optDouble(0), p.optDouble(1), p.optDouble(2)});
                    }
                }
            }
            JSONArray pl = o.optJSONArray("places");
            if (pl != null) {
                synchronized (places) {
                    for (int i = 0; i < pl.length(); i++) places.add(pl.optString(i));
                }
            }
            JSONObject c = o.optJSONObject("cloud");
            if (c != null) {
                cloudStart = new CloudMark(c.optDouble("socStart", Double.NaN), c.optDouble("odoStart", Double.NaN), startMs,
                        c.optDouble("fuelStart", Double.NaN));
                cloudCharged = c.optBoolean("charged");
            }
            // La batería del móvil, desde el principio del viaje (no desde la reconexión).
            JSONObject ph = o.optJSONObject("phone");
            if (ph != null && ph.has("pct0")) {
                phoneStart = new long[]{ph.optLong("pct0"), ph.optLong("uah0", Long.MIN_VALUE), ph.optBoolean("plugged") ? 1 : 0,
                        startMs};
            }
            resumed = o;
            L.i("viaje: sigo el guardado de la conexión anterior (mismo fichero)");
        } catch (Exception e) {
            L.w("viaje: no se pudo seguir el anterior: " + e.getClass().getSimpleName());
        }
    }

    static synchronized void start(Context ctx) {
        if (instance != null) return;
        instance = new TripLog(ctx);
        instance.running = true;
        if (!instance.demo) new Thread(instance::loop, "trip-log").start();
    }

    static synchronized void stop() {
        if (instance == null) return;
        TripLog t = instance;
        instance = null;
        t.running = false;
        t.save();
        // Si fue largo y no sigue con la próxima conexión, el diario del viaje lo ofrece.
        if (!t.demo) TripPostcard.afterTrip(t.ctx, t.startMs);
        CarSensors.stop();
    }

    private void loop() {
        while (running) {
            CarSensors.Snapshot s = sensors.snapshot();
            if (Double.isNaN(startKwh)) {
                // Si se retoma el viaje anterior, lo ya hecho cuenta: la base queda tanto más atrás.
                JSONObject r = resumed;
                startKwh = s.kwhTotal - (r == null ? 0 : r.optDouble("kwh", 0));
                startKm = s.kmTotal - (r == null ? 0 : r.optDouble("km", 0));
                startClimb = s.climbM - (r == null ? 0 : r.optDouble("climb", 0));
                startDescent = s.descentM - (r == null ? 0 : r.optDouble("descent", 0));
            }
            if (s.gpsLive()) maxKmh = Math.max(maxKmh, s.speedKmh);
            noteCloud(CarCloud.car());
            if (!Double.isNaN(s.lat)) {
                synchronized (track) {
                    double[] last = track.isEmpty() ? null : track.get(track.size() - 1);
                    if (last == null || RoadInfo.dist(last[0], last[1], s.lat, s.lon) > 30) {
                        // Con el segundo del viaje: un hueco largo en el mismo sitio es una parada (TripStops).
                        track.add(new double[]{s.lat, s.lon, (SystemClock.elapsedRealtime() - startElapsed) / 1000});
                    }
                }
            }
            SystemClock.sleep(5000);
        }
    }

    /**
     * Apunta el % y los km del coche de cada lectura nueva de la nube hecha después de empezar el viaje: la primera es
     * el inicio y la última, el final. Si se ve cargando o la batería sube más de un punto, el viaje no da consumo real.
     */
    private void noteCloud(CarCloud.Snapshot cs) {
        if (cs == null || !cs.hasData() || cs.demo || cs.fetchedAtMs < startMs || cs.fetchedAtMs == cloudSeenFetch) return;
        cloudSeenFetch = cs.fetchedAtMs;
        double soc = cs.status.socBest();
        double odo = cs.status.odometerKm;
        if (Double.isNaN(soc) || Double.isNaN(odo)) return;
        CloudMark m = new CloudMark(soc, odo, cs.dataTimeMs(), cs.status.fuelLiters);
        CloudMark prev = cloudEnd;
        // REEV sin enchufar: la batería sube en marcha porque el generador gasta gasolina; eso no es una carga.
        boolean generator = cs.status.reev() && !cs.status.pluggedIn();
        if (cloudStart == null) {
            cloudStart = m;
            L.i(String.format(Locale.US, "viaje: inicio con datos del coche (%.1f %%%s)", soc,
                    Double.isNaN(m.fuelL) ? "" : String.format(Locale.US, ", %.2f L de gasolina", m.fuelL)));
            // Lo que bajó la batería con el coche parado desde el viaje anterior (Coche › Estado; aviso si es raro).
            ParkedNotice.check(ctx, startMs, soc, odo, m.timeMs, cs.capacityKwh);
        } else if (cs.status.charging() || cs.status.pluggedIn() || (!generator && prev != null && soc > prev.soc + 1.0)) {
            if (!cloudCharged) L.i("viaje: el coche ha cargado por el camino (el consumo real del viaje no se mide)");
            cloudCharged = true;
        }
        cloudEnd = m;
        cloudCap = cs.capacityKwh;
    }

    /** La Guía del camino acaba de contar este sitio: va al diario del viaje en curso. */
    static void notePlace(String title) {
        TripLog t = instance;
        if (t == null || t.demo || title == null || title.isEmpty()) return;
        synchronized (t.places) {
            if (t.places.size() < MAX_PLACES) t.places.add(title);
        }
    }

    /** El recorrido del viaje en curso hasta ahora ([lat, lon, segundo]; copia), o null sin viaje o en la demostración. */
    static java.util.List<double[]> liveTrack() {
        TripLog t = instance;
        if (t == null || t.demo) return null;
        synchronized (t.track) {
            return new ArrayList<>(t.track);
        }
    }

    /** Inicio del viaje en curso (ms), o 0. */
    static long liveStartMs() {
        TripLog t = instance;
        return t == null ? 0 : t.startMs;
    }

    /** Inicio del viaje en curso con datos de la nube, o null (en la demostración, el suyo). */
    static CloudMark cloudStart() {
        if (DemoMode.active()) return CarCloud.car().demo ? DemoMode.cloudTripStart() : null;
        TripLog t = instance;
        return t == null ? null : t.cloudStart;
    }

    /** Consumo real del viaje en curso hasta la lectura now (de la nube). */
    static CloudEnergy.Result liveReal(CarCloud.Snapshot now) {
        CloudMark st = cloudStart();
        if (st == null || now == null || !now.hasData()) return CloudEnergy.NONE;
        TripLog t = instance;
        boolean charged = t != null && !DemoMode.active() && t.cloudCharged;
        return CloudEnergy.between(st.soc, st.odo, now.status.socBest(), now.status.odometerKm, now.capacityKwh, charged);
    }

    private void save() {
        if (demo) return;
        CarSensors.Snapshot s = sensors.snapshot();
        if (Double.isNaN(startKwh)) return;
        double km = s.kmTotal - startKm;
        if (km < 0.3) return;
        try {
            JSONObject o = new JSONObject();
            o.put("start", startMs);
            o.put("minutes", (SystemClock.elapsedRealtime() - startElapsed) / 60000);
            o.put("km", km);
            o.put("kwh", s.kwhTotal - startKwh);
            o.put("climb", s.climbM - startClimb);
            o.put("descent", s.descentM - startDescent);
            o.put("maxKmh", maxKmh);
            // Tiempo sin GPS (p. ej. con el móvil bloqueado): esos tramos cuentan en línea recta (CarSensors.bridgeGap).
            if (s.gpsGapSec > 0) o.put("gpsGapSec", s.gpsGapSec);
            JSONArray t = new JSONArray();
            synchronized (track) {
                for (double[] p : track) t.put(new JSONArray().put(round(p[0])).put(round(p[1])).put(Math.round(p[2])));
            }
            o.put("track", t);
            synchronized (places) {
                if (!places.isEmpty()) o.put("places", new JSONArray(places));
            }
            CloudMark c0 = cloudStart;
            CloudMark c1 = cloudEnd;
            if (c0 != null && c1 != null && c1 != c0) {
                JSONObject c = new JSONObject();
                c.put("socStart", c0.soc);
                c.put("socEnd", c1.soc);
                c.put("odoStart", c0.odo);
                c.put("odoEnd", c1.odo);
                c.put("cap", cloudCap);
                c.put("charged", cloudCharged);
                if (!Double.isNaN(c0.fuelL) && !Double.isNaN(c1.fuelL)) {
                    c.put("fuelStart", c0.fuelL);
                    c.put("fuelEnd", c1.fuelL);
                    L.i(String.format(Locale.US, "viaje: gasolina %.2f → %.2f L (%.2f L gastados)", c0.fuelL, c1.fuelL,
                            Math.max(0, c0.fuelL - c1.fuelL)));
                }
                o.put("cloud", c);
                CloudEnergy.Result r = CloudEnergy.between(c0.soc, c0.odo, c1.soc, c1.odo, cloudCap, cloudCharged);
                L.i(String.format(Locale.US, "viaje: datos del coche %.1f → %.1f %%, %.0f km de cuentakilómetros%s", c0.soc, c1.soc,
                        c1.odo - c0.odo, r.ok() ? String.format(Locale.US, ", %.1f kWh/100 km reales", r.kwhPer100)
                                : " (" + r.kind.name().toLowerCase(Locale.ROOT) + ")"));
            }
            // La batería del móvil: al empezar y ahora (lo que gastó el móvil entero en el viaje).
            long[] p0 = phoneStart;
            long[] p1 = PhoneBattery.read(ctx);
            if (p0 != null && p1 != null) {
                JSONObject ph = new JSONObject();
                ph.put("pct0", p0[PhoneBattery.PCT]);
                ph.put("pct1", p1[PhoneBattery.PCT]);
                if (p0[PhoneBattery.UAH] > 0) ph.put("uah0", p0[PhoneBattery.UAH]);
                boolean plugged = p0[PhoneBattery.PLUGGED] != 0 || p1[PhoneBattery.PLUGGED] != 0;
                ph.put("plugged", plugged);
                if (!plugged && p0[PhoneBattery.UAH] > 0 && p1[PhoneBattery.UAH] > 0 && p0[PhoneBattery.UAH] >= p1[PhoneBattery.UAH]) {
                    ph.put("mah", Math.round((p0[PhoneBattery.UAH] - p1[PhoneBattery.UAH]) / 1000.0));
                }
                o.put("phone", ph);
            }
            File dir = new File(ctx.getExternalFilesDir(null), "trips");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name = "trip-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startMs)) + ".json";
            Files.write(new File(dir, name).toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            L.i(String.format(Locale.US, "viaje guardado: %.1f km, %.1f kWh estimados%s", km, s.kwhTotal - startKwh,
                    s.gpsGapSec > 0 ? String.format(Locale.US, " (%d s sin GPS en %d huecos, sumados en línea recta)", s.gpsGapSec, s.gpsGaps) : ""));
        } catch (Exception e) {
            L.w("viaje: no se pudo guardar: " + e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }

    /** Últimos viajes guardados, del más reciente al más antiguo. */
    static List<Trip> recent(Context ctx, int max) {
        if (DemoMode.active()) return DemoMode.trips(max);
        List<Trip> out = new ArrayList<>();
        File[] files = new File(ctx.getExternalFilesDir(null), "trips").listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = 0; i < files.length && out.size() < max; i++) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(files[i].toPath()), StandardCharsets.UTF_8));
                Trip t = new Trip();
                t.startMs = o.getLong("start");
                t.minutes = o.optLong("minutes");
                t.km = o.optDouble("km");
                t.kwh = o.optDouble("kwh");
                t.climb = o.optDouble("climb");
                t.descent = o.optDouble("descent");
                t.maxKmh = o.optDouble("maxKmh");
                JSONArray tr = o.optJSONArray("track");
                if (tr != null) {
                    t.track = new double[tr.length()][];
                    for (int k = 0; k < tr.length(); k++) {
                        JSONArray q = tr.getJSONArray(k);
                        // Los viajes de antes de la 0.2.7 no llevan el segundo de cada punto (NaN: sin paradas).
                        t.track[k] = new double[]{q.getDouble(0), q.getDouble(1), q.length() > 2 ? q.getDouble(2) : Double.NaN};
                    }
                }
                JSONArray pl = o.optJSONArray("places");
                if (pl != null) {
                    t.places = new String[pl.length()];
                    for (int k = 0; k < pl.length(); k++) t.places[k] = pl.optString(k);
                }
                JSONObject c = o.optJSONObject("cloud");
                if (c != null) {
                    t.socStart = c.optDouble("socStart");
                    t.socEnd = c.optDouble("socEnd");
                    t.odoStart = c.optDouble("odoStart");
                    t.odoEnd = c.optDouble("odoEnd");
                    t.capKwh = c.optDouble("cap");
                    t.charged = c.optBoolean("charged");
                    t.fuelStartL = c.optDouble("fuelStart");
                    t.fuelEndL = c.optDouble("fuelEnd");
                }
                JSONObject ph = o.optJSONObject("phone");
                if (ph != null) {
                    t.phonePct0 = ph.optInt("pct0", -1);
                    t.phonePct1 = ph.optInt("pct1", -1);
                    t.phoneMah = ph.optDouble("mah", Double.NaN);
                    t.phonePlugged = ph.optBoolean("plugged");
                }
                out.add(t);
            } catch (Exception e) {
                L.w("viaje ilegible " + files[i].getName() + ": " + e);
            }
        }
        return out;
    }
}
