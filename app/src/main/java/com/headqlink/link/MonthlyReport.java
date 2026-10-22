package com.headqlink.link;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Informe del mes: km, energía, consumo, dinero (y lo que costaría en gasolina), cargas, lo que perdió parado y los
 * destacados de cada mes. Viajes: los del coche (historial de la nube, sus kWh) si cubren el mes entero; si no, los
 * que grabó la app (TripLog, kWh estimados). La nube solo guarda 35 días: cada mes se guarda resumido en
 * files/monthly-reports.json (los últimos KEEP_MONTHS), y un mes completo no se pisa con uno a medias. Cálculo puro
 * (se prueba en el PC); load/refresh leen y escriben en el móvil.
 */
final class MonthlyReport {
    static final int KEEP_MONTHS = 24;
    private static final String FILE = "monthly-reports.json";

    /** Lo de un mes. NaN: no se sabe. */
    static final class Summary {
        int year;
        /** 1-12. */
        int month;
        /** Hay datos del mes entero (si no, los deltas no se comparan). */
        boolean complete;
        /** Viajes del coche (nube) o de la app. */
        boolean fromCar;
        int trips;
        double km;
        double kwh = Double.NaN;
        /** REEV: litros de gasolina del mes, o NaN. */
        double fuelL = Double.NaN;
        double costEur = Double.NaN;
        /** Lo que costaría en un coche de gasolina (Config.fuelLitersPer100 a Config.fuelPrice). */
        double petrolEur = Double.NaN;
        int charges;
        double chargedKwh;
        double fastKwh;
        double fastestKw = Double.NaN;
        double chargeCostEur;
        int parks;
        double parkedPct;
        double parkedKwh = Double.NaN;
        double parkedHours;
        double longestKm = Double.NaN;
        long longestMs;
        double bestPer100 = Double.NaN;
        double bestKm = Double.NaN;
        long bestMs;
        double topDayKm = Double.NaN;
        long topDayMs;

        String key() {
            return MonthlyReport.key(year, month);
        }

        double per100() {
            return km >= 1 && !Double.isNaN(kwh) ? kwh / km * 100 : Double.NaN;
        }

        /** Lo que te ahorras frente a gasolina, o NaN. */
        double savedEur() {
            return Double.isNaN(petrolEur) || Double.isNaN(costEur) ? Double.NaN : petrolEur - costEur;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("y", year).put("m", month).put("complete", complete).put("car", fromCar)
                    .put("trips", trips).put("km", km).put("kwh", nan(kwh)).put("fuel", nan(fuelL)).put("cost", nan(costEur))
                    .put("petrol", nan(petrolEur)).put("charges", charges).put("charged", chargedKwh).put("fast", fastKwh)
                    .put("fastest", nan(fastestKw)).put("chargeCost", chargeCostEur).put("parks", parks).put("parkedPct", parkedPct)
                    .put("parkedKwh", nan(parkedKwh)).put("parkedH", parkedHours).put("longKm", nan(longestKm)).put("longMs", longestMs)
                    .put("best", nan(bestPer100)).put("bestKm", nan(bestKm)).put("bestMs", bestMs).put("dayKm", nan(topDayKm))
                    .put("dayMs", topDayMs);
        }

        static Summary fromJson(JSONObject o) {
            Summary s = new Summary();
            s.year = o.optInt("y");
            s.month = o.optInt("m");
            s.complete = o.optBoolean("complete");
            s.fromCar = o.optBoolean("car");
            s.trips = o.optInt("trips");
            s.km = o.optDouble("km", 0);
            s.kwh = o.optDouble("kwh", Double.NaN);
            s.fuelL = o.optDouble("fuel", Double.NaN);
            s.costEur = o.optDouble("cost", Double.NaN);
            s.petrolEur = o.optDouble("petrol", Double.NaN);
            s.charges = o.optInt("charges");
            s.chargedKwh = o.optDouble("charged", 0);
            s.fastKwh = o.optDouble("fast", 0);
            s.fastestKw = o.optDouble("fastest", Double.NaN);
            s.chargeCostEur = o.optDouble("chargeCost", 0);
            s.parks = o.optInt("parks");
            s.parkedPct = o.optDouble("parkedPct", 0);
            s.parkedKwh = o.optDouble("parkedKwh", Double.NaN);
            s.parkedHours = o.optDouble("parkedH", 0);
            s.longestKm = o.optDouble("longKm", Double.NaN);
            s.longestMs = o.optLong("longMs");
            s.bestPer100 = o.optDouble("best", Double.NaN);
            s.bestKm = o.optDouble("bestKm", Double.NaN);
            s.bestMs = o.optLong("bestMs");
            s.topDayKm = o.optDouble("dayKm", Double.NaN);
            s.topDayMs = o.optLong("dayMs");
            return s;
        }
    }

    /** Precios para el dinero del mes (Config). */
    static final class Prices {
        final double home;
        final double fast;
        final double fuel;
        final double petrolLPer100;

        Prices(double home, double fast, double fuel, double petrolLPer100) {
            this.home = home;
            this.fast = fast;
            this.fuel = fuel;
            this.petrolLPer100 = petrolLPer100;
        }

        static Prices of(Config cfg) {
            return new Prices(cfg.electricityPrice(), cfg.fastChargePrice(), cfg.fuelPrice(), cfg.fuelLitersPer100());
        }
    }

    private MonthlyReport() {
    }

    // ------------------------------------------------------------------ fechas

    static String key(int year, int month) {
        return String.format(Locale.ROOT, "%04d-%02d", year, month);
    }

    /** Puro: inicio del mes (month 1-12) en la zona tz. */
    static long monthStart(int year, int month, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month - 1, 1, 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** Puro: inicio del mes siguiente. */
    static long monthEnd(int year, int month, TimeZone tz) {
        return month == 12 ? monthStart(year + 1, 1, tz) : monthStart(year, month + 1, tz);
    }

    /** Puro: {año, mes} de un instante. */
    static int[] monthOf(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1};
    }

    /** Puro: {año, mes} de antes (delta -1) o de después (+1). */
    static int[] shift(int year, int month, int delta) {
        int i = year * 12 + (month - 1) + delta;
        return new int[]{Math.floorDiv(i, 12), Math.floorMod(i, 12) + 1};
    }

    // ------------------------------------------------------------------ el cálculo

    /**
     * Puro: el resumen de un mes.
     *
     * @param history      viajes del coche (nube); historyAtMs, cuándo se leyó (cubre los 35 días de antes)
     * @param trips        viajes grabados por la app; appSinceMs, desde cuándo graba (la instalación)
     * @param charges      cargas de la carga en directo (ChargeLog)
     * @param defaultCap   capacidad de la batería para los aparcamientos sin la suya (kWh)
     */
    static Summary compute(int year, int month, TimeZone tz, List<CloudHistory.Trip> history, long historyAtMs,
                           List<TripLog.Trip> trips, long appSinceMs, List<ChargeSession> charges, double defaultCap, Prices p) {
        long from = monthStart(year, month, tz);
        long to = monthEnd(year, month, tz);
        Summary s = new Summary();
        s.year = year;
        s.month = month;
        List<CloudHistory.Trip> car = new ArrayList<>();
        for (CloudHistory.Trip t : history) if (t.startMs >= from && t.startMs < to) car.add(t);
        List<TripLog.Trip> app = new ArrayList<>();
        for (TripLog.Trip t : trips) if (t.startMs >= from && t.startMs < to && t.km > 0) app.add(t);
        // El coche, si su historial cubre el mes entero; si no, la app, si graba desde antes del mes y tiene viajes; si
        // tampoco, el coche a medias. Sin cuenta de Leapmotor, la app.
        boolean carComplete = historyAtMs > 0 && historyAtMs - CloudHistory.KEEP_MS <= from && historyAtMs >= to;
        boolean appComplete = appSinceMs > 0 && appSinceMs <= from;
        boolean useCar = historyAtMs > 0 && (carComplete || !appComplete || app.isEmpty());
        s.fromCar = useCar;
        s.complete = useCar ? carComplete : appComplete;

        Map<Long, Double> perDay = new HashMap<>();
        if (useCar) {
            double kwh = 0, fuel = 0;
            boolean anyKwh = false, anyFuel = false;
            for (CloudHistory.Trip t : car) {
                s.trips++;
                s.km += t.km;
                if (!Double.isNaN(t.kwh)) {
                    kwh += t.kwh;
                    anyKwh = true;
                }
                if (!Double.isNaN(t.fuelL)) {
                    fuel += t.fuelL;
                    anyFuel = true;
                }
                double per100 = t.km >= TripStats.MIN_KM_FOR_RECORD && t.kwh > 0 ? t.kwh / t.km * 100 : Double.NaN;
                highlight(s, t.startMs, t.km, per100, perDay, tz);
            }
            if (anyKwh) s.kwh = kwh;
            if (anyFuel) s.fuelL = fuel;
        } else {
            double kwh = 0, fuel = 0;
            boolean anyKwh = false, anyFuel = false;
            for (TripLog.Trip t : app) {
                s.trips++;
                s.km += t.km;
                double per100 = TripStats.bestKwhPer100(t, history);
                if (!Double.isNaN(per100)) {
                    kwh += per100 * t.km / 100;
                    anyKwh = true;
                }
                double l = TripStats.fuelL(t, history);
                if (!Double.isNaN(l)) {
                    fuel += l;
                    anyFuel = true;
                }
                highlight(s, t.startMs, t.km, t.km >= TripStats.MIN_KM_FOR_RECORD ? per100 : Double.NaN, perDay, tz);
            }
            if (anyKwh) s.kwh = kwh;
            if (anyFuel) s.fuelL = fuel;
        }
        for (Map.Entry<Long, Double> e : perDay.entrySet()) {
            if (Double.isNaN(s.topDayKm) || e.getValue() > s.topDayKm || (e.getValue() == s.topDayKm && e.getKey() < s.topDayMs)) {
                s.topDayKm = e.getValue();
                s.topDayMs = e.getKey();
            }
        }

        // Cargas del mes: en casa (CA) o rápidas (CC), con la potencia más alta que se vio.
        for (ChargeSession c : charges) {
            if (c.startMs < from || c.startMs >= to) continue;
            double k = c.kwhAdded();
            s.charges++;
            s.chargedKwh += k;
            if (c.dc) {
                s.fastKwh += k;
                s.chargeCostEur += k * p.fast;
                for (ChargeSession.Sample x : c.samples) {
                    if (!Double.isNaN(x.kw) && (Double.isNaN(s.fastestKw) || x.kw > s.fastestKw)) s.fastestKw = x.kw;
                }
            } else {
                s.chargeCostEur += k * p.home;
            }
        }

        // Dinero: lo cargado en rápida, al precio de la rápida; el resto, al de casa; más la gasolina (REEV).
        if (!Double.isNaN(s.kwh)) {
            double fast = Math.min(s.fastKwh, s.kwh);
            s.costEur = fast * p.fast + (s.kwh - fast) * p.home + (Double.isNaN(s.fuelL) ? 0 : s.fuelL * p.fuel);
        }
        if (p.fuel > 0 && p.petrolLPer100 > 0 && s.km > 0) s.petrolEur = s.km * p.petrolLPer100 / 100 * p.fuel;

        // Con el coche parado: los aparcamientos que acaban este mes (entre viajes grabados por la app).
        List<ParkedDrain.Park> parks = ParkedDrain.parks(trips, defaultCap);
        double parkedKwh = 0;
        boolean anyParkedKwh = false;
        for (ParkedDrain.Park pk : parks) {
            if (pk.toMs < from || pk.toMs >= to) continue;
            s.parks++;
            s.parkedPct += pk.dropPct();
            s.parkedHours += pk.hours();
            double k = pk.kwh();
            if (!Double.isNaN(k)) {
                parkedKwh += k;
                anyParkedKwh = true;
            }
        }
        if (anyParkedKwh) s.parkedKwh = parkedKwh;
        return s;
    }

    private static void highlight(Summary s, long startMs, double km, double per100, Map<Long, Double> perDay, TimeZone tz) {
        if (Double.isNaN(s.longestKm) || km > s.longestKm) {
            s.longestKm = km;
            s.longestMs = startMs;
        }
        if (!Double.isNaN(per100) && (Double.isNaN(s.bestPer100) || per100 < s.bestPer100)) {
            s.bestPer100 = per100;
            s.bestKm = km;
            s.bestMs = startMs;
        }
        long day = TripStats.startOfDay(startMs, tz);
        Double prev = perDay.get(day);
        perDay.put(day, (prev == null ? 0 : prev) + km);
    }

    /**
     * Puro: lo que se guarda de un mes, entre lo guardado y lo recién calculado: uno completo no se pisa con uno a
     * medias (la nube ya no tiene el principio del mes); entre dos completos, el nuevo; entre dos a medias, el de más km.
     */
    static Summary keep(Summary stored, Summary fresh) {
        if (stored == null) return fresh;
        if (fresh == null) return stored;
        if (stored.complete != fresh.complete) return stored.complete ? stored : fresh;
        if (fresh.complete) return fresh;
        return fresh.km >= stored.km ? fresh : stored;
    }

    /** Puro: ¿se puede comparar con el mes de antes? Los dos completos y con los viajes de la misma fuente. */
    static boolean comparable(Summary cur, Summary prev) {
        return cur != null && prev != null && cur.complete && prev.complete && cur.fromCar == prev.fromCar && prev.trips > 0;
    }

    /** Puro: el cambio en % (de prev a cur), o NaN si no tiene sentido. */
    static double changePct(double cur, double prev) {
        if (Double.isNaN(cur) || Double.isNaN(prev) || prev <= 0) return Double.NaN;
        return (cur - prev) / prev * 100;
    }

    // ------------------------------------------------------------------ guardar

    /** Puro: los resúmenes del JSON guardado, por clave («2026-10»); con el fichero roto, ninguno. */
    static Map<String, Summary> read(String json) {
        Map<String, Summary> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Summary s = Summary.fromJson(o);
                if (s.month >= 1 && s.month <= 12) out.put(s.key(), s);
            }
        } catch (JSONException e) {
            return new HashMap<>();
        }
        return out;
    }

    /** Puro: los resúmenes en JSON, los KEEP_MONTHS más recientes. */
    static String write(Map<String, Summary> all) {
        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys, Collections.reverseOrder());
        JSONArray a = new JSONArray();
        for (int i = 0; i < keys.size() && i < KEEP_MONTHS; i++) {
            try {
                a.put(all.get(keys.get(i)).toJson());
            } catch (JSONException ignored) {
            }
        }
        return a.toString();
    }

    static synchronized Map<String, Summary> load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile()) return new HashMap<>();
        try {
            return read(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static synchronized void save(Context ctx, Map<String, Summary> all) {
        try {
            Files.write(new File(ctx.getFilesDir(), FILE).toPath(), write(all).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            L.w("informe del mes: no se pudo guardar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Hilo de fondo: recalcula este mes y los dos de antes con lo que hay en el móvil y guarda lo que valga (keep).
     * Devuelve todos los meses guardados, con el de ahora (a medias) incluido.
     */
    static Map<String, Summary> refresh(Context ctx) {
        Context app = ctx.getApplicationContext();
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        List<CloudHistory.Trip> history = new ArrayList<>(CarCloud.history());
        long historyAt = CarCloud.historyAtMs();
        if (history.isEmpty() && !DemoMode.active()) {
            JSONObject o = new CarCloudStore(app).history();
            if (o != null) {
                history = CloudHistory.tripsFromJson(o);
                historyAt = o.optLong("tripsAt");
            }
        }
        List<TripLog.Trip> trips = TripLog.recent(app, 600);
        List<ChargeSession> charges;
        try {
            charges = ChargeLog.load(app);
        } catch (RuntimeException e) {
            charges = new ArrayList<>();
        }
        long since = appSince(app);
        Prices prices = Prices.of(new Config(app));
        double cap = CarSpec.current().usableKwh;
        Map<String, Summary> all = load(app);
        int[] cur = monthOf(now, tz);
        for (int d = -2; d <= 0; d++) {
            int[] ym = shift(cur[0], cur[1], d);
            Summary fresh = compute(ym[0], ym[1], tz, history, historyAt, trips, since, charges, cap, prices);
            all.put(fresh.key(), d == 0 ? fresh : keep(all.get(fresh.key()), fresh));
        }
        if (!DemoMode.active()) save(app, all);
        return all;
    }

    /** Desde cuándo graba viajes la app: la instalación. */
    private static long appSince(Context ctx) {
        if (DemoMode.active()) return 1;
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).firstInstallTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object nan(double v) {
        return Double.isNaN(v) ? JSONObject.NULL : v;
    }
}
