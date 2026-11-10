package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.andrerinas.openheadunit.R;

import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

/**
 * «Carga barata»: al enchufar el coche en casa, elige el tramo seguido más barato antes de la hora de salida
 * (CheapWindow, con la tarifa de los ajustes) y programa la ventana de carga del coche por la nube de Leapmotor (el plan
 * de carga, orden 190); avisa con una notificación y, al acabar, devuelve el plan del coche a como estaba (si la
 * ventana se quedara puesta, el coche no cargaría a mediodía en un cargador público).
 *
 * Lo mueven las lecturas del coche: la comprobación de fondo (ChargeCheckJob, cada ~15 min) y las de la app abierta
 * (CarCloud). En cada una, como mucho un envío al coche, y ninguno si no hay nada que cambiar. Lo que se ha hecho se
 * guarda (el proceso puede morir entre dos comprobaciones): la fase, el plan original del coche y el que se mandó.
 *
 * Fases: IDLE (nada en este enchufado, o desenchufado), PLANNED (ventana mandada), RESTORE (hay que devolver el plan
 * original; se reintenta en cada comprobación hasta lograrlo) y HOLD (ya está decidido para este enchufado y no hay
 * nada puesto en el coche: hasta verlo desenchufado no se hace nada más).
 *
 * Nada personal va al registro: ni la posición ni la distancia a casa.
 */
final class CheapCharge {
    static final String IDLE = "idle";
    static final String PLANNED = "planned";
    static final String RESTORE = "restore";
    static final String HOLD = "hold";

    // Por qué (State.why): lo que cuenta la tarjeta de «Mi coche».
    static final String WHY_NOW = "now";
    static final String WHY_NO_FIT = "nofit";
    static final String WHY_RESPECTED = "respected";
    static final String WHY_AWAY = "away";
    static final String WHY_NO_POSITION = "nopos";
    static final String WHY_FAILED = "failed";
    static final String WHY_USER = "user";
    static final String WHY_CHANGED = "changed";
    static final String WHY_TARGET = "target";
    static final String WHY_DEPART = "depart";
    static final String WHY_UNPLUGGED = "unplugged";
    static final String WHY_OFF = "off";
    static final String WHY_FLAT = "flat";

    // Lo último que falló (State.error).
    static final String ERR_PIN = "pin";
    static final String ERR_SESSION = "session";
    static final String ERR_NET = "net";
    static final String ERR_PRICES = "prices";

    /** El coche está en casa si su posición está a menos de esto de la casa guardada (m). */
    static final double HOME_M = 300;
    /** Lo que falló (o quedó sin confirmar) no se reintenta antes de esto, aunque la app lea el coche cada 2 min. */
    static final long RETRY_MS = 5 * 60_000L;
    /** Para planificar, el dato del coche no puede ser más viejo que esto. */
    static final long MAX_AGE_MS = CarCloud.SOC_MAX_AGE_MS;
    /** Intentos de mandar la ventana antes de dejarlo (el coche cargará al enchufar). */
    static final int MAX_TRIES = 4;
    /** Lecturas sin posición antes de dejarlo para este enchufado. */
    static final int MAX_POSITION_TRIES = 3;
    /** A menos de esto del objetivo, ya está (el coche para en el límite, y el % puede quedar unas décimas por debajo). */
    static final double TARGET_SLACK = 0.5;
    /** «Plan devuelto» se enseña en la tarjeta durante esto. */
    static final long RESTORED_SHOWN_MS = 12 * 3600_000L;
    private static final String PREFS = "hql_cheap_charge";
    static final int NOTIF_ID = 26;

    /** Lo que hace falta del coche (la nube de Leapmotor; de mentira en las pruebas). */
    interface Car {
        /** ¿Hay PIN guardado para mandar órdenes? */
        boolean hasPin();

        /** El plan de carga del coche, o null si no tiene. */
        ChargePlan readPlan() throws Exception;

        /** Manda el plan; true si el coche lo confirma. */
        boolean sendPlan(ChargePlan plan) throws Exception;

        /** {latitud, longitud} del coche, o null si no la da. */
        double[] position() throws Exception;
    }

    /** Lo guardado entre una comprobación y la siguiente. */
    static final class State {
        String phase = IDLE;
        String why = "";
        String error = "";
        /** El plan del coche antes de tocarlo y el que se mandó (ChargePlan.content()). */
        String original = "";
        String sent = "";
        long startMs;
        long endMs;
        long chargeEndMs;
        long departMs;
        /** La hora del dato del coche con el que se planificó: «desenchufado» solo vale con un dato posterior. */
        long plugDataMs;
        long lastTryMs;
        long restoredAtMs;
        double cost = Double.NaN;
        double costNow = Double.NaN;
        double startSoc = Double.NaN;
        int target;
        /** Calculada sin los precios de mañana (estimados): se recalcula cuando se publiquen. */
        boolean provisional;
        /** Cambiaron los ajustes con la ventana puesta: se recalcula en la próxima comprobación. */
        boolean recalc;
        /** El coche confirmó la ventana (o se leyó puesta). */
        boolean confirmed;
        int tries;
        int positionTries;
        /** Ya se vio que el coche está en casa en este enchufado (no se vuelve a preguntar la posición). */
        boolean atHome;
        /** Las ventanas mandadas en este enchufado («HH:MM-HH:MM;…»): otra distinta en el coche la puso el usuario. */
        String windows = "";

        static State load(Context ctx) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            State s = new State();
            s.phase = sp.getString("phase", IDLE);
            s.why = sp.getString("why", "");
            s.error = sp.getString("error", "");
            s.original = sp.getString("original", "");
            s.sent = sp.getString("sent", "");
            s.startMs = sp.getLong("start", 0);
            s.endMs = sp.getLong("end", 0);
            s.chargeEndMs = sp.getLong("charge_end", 0);
            s.departMs = sp.getLong("depart", 0);
            s.plugDataMs = sp.getLong("plug_data", 0);
            s.lastTryMs = sp.getLong("last_try", 0);
            s.restoredAtMs = sp.getLong("restored_at", 0);
            s.cost = sp.getFloat("cost", Float.NaN);
            s.costNow = sp.getFloat("cost_now", Float.NaN);
            s.startSoc = sp.getFloat("start_soc", Float.NaN);
            s.target = sp.getInt("target", 0);
            s.provisional = sp.getBoolean("provisional", false);
            s.recalc = sp.getBoolean("recalc", false);
            s.confirmed = sp.getBoolean("confirmed", false);
            s.tries = sp.getInt("tries", 0);
            s.positionTries = sp.getInt("position_tries", 0);
            s.atHome = sp.getBoolean("at_home", false);
            s.windows = sp.getString("windows", "");
            return s;
        }

        void save(Context ctx) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("phase", phase).putString("why", why)
                    .putString("error", error).putString("original", original).putString("sent", sent).putLong("start", startMs)
                    .putLong("end", endMs).putLong("charge_end", chargeEndMs).putLong("depart", departMs)
                    .putLong("plug_data", plugDataMs).putLong("last_try", lastTryMs).putLong("restored_at", restoredAtMs)
                    .putFloat("cost", (float) cost).putFloat("cost_now", (float) costNow).putFloat("start_soc", (float) startSoc)
                    .putInt("target", target).putBoolean("provisional", provisional).putBoolean("recalc", recalc)
                    .putBoolean("confirmed", confirmed).putInt("tries", tries).putInt("position_tries", positionTries)
                    .putBoolean("at_home", atHome).putString("windows", windows)
                    // commit: lo siguiente puede ser una orden al coche, y el proceso puede morir.
                    .commit();
        }

        /** Vuelve a IDLE (o a HOLD con su porqué) sin nada puesto en el coche. */
        void settle(String newPhase, String newWhy) {
            phase = newPhase;
            why = newWhy;
            error = "";
            lastTryMs = 0;
            tries = 0;
            positionTries = 0;
            atHome = false;
            provisional = false;
            recalc = false;
            confirmed = false;
        }

        /** Lo que se ahorra frente a cargar al enchufar (0 si no se sabe). */
        double saving() {
            return Double.isNaN(cost) || Double.isNaN(costNow) ? 0 : Math.max(0, costNow - cost);
        }
    }

    private CheapCharge() {
    }

    /** El plan del que se parte si el coche no tiene ninguno: sin ventana, todo el día, límite 100. */
    static ChargePlan defaultPlan() {
        return new ChargePlan(false, 100, "00:00", "23:59", null, 1, 0);
    }

    static ChargePlan planOf(String content) {
        if (content == null || content.isEmpty()) return null;
        try {
            return ChargePlan.parse(new JSONObject(content));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ entradas

    private static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Una lectura del coche con la app abierta (CarCloud, en su hilo): si hay algo que mirar, en otro hilo (las órdenes
     * tardan y no deben parar el sondeo).
     */
    static void onReading(Context ctx, CarCloud.Snapshot cs) {
        if (cs == null || !cs.hasData() || cs.demo) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (!new Config(app).cheapCharge() && IDLE.equals(State.load(app).phase)) return;
        if (!busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                check(app, cs, System.currentTimeMillis(), cloud(app));
            } catch (RuntimeException e) {
                L.w("carga barata: comprobación fallida (" + e.getClass().getSimpleName() + ")");
            } finally {
                busy.set(false);
            }
        }, "hql-cheap-charge").start();
    }

    /** La comprobación de fondo (ChargeCheckJob, ya en su hilo). */
    static void background(Context ctx, CarCloud.Snapshot cs) {
        try {
            check(ctx.getApplicationContext(), cs, System.currentTimeMillis(), cloud(ctx.getApplicationContext()));
        } catch (RuntimeException e) {
            L.w("carga barata: comprobación fallida (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** El coche de verdad: la nube de Leapmotor con la sesión guardada. */
    static Car cloud(Context app) {
        return new Car() {
            @Override
            public boolean hasPin() {
                return new CarCloudStore(app).hasPin();
            }

            @Override
            public ChargePlan readPlan() throws Exception {
                return CarCloudSession.readChargePlan(app);
            }

            @Override
            public boolean sendPlan(ChargePlan plan) throws Exception {
                return CarCloudSession.sendChargePlan(app, plan);
            }

            @Override
            public double[] position() throws Exception {
                // Una lectura más del estado: cuenta para el tope diario.
                if (!CarCloud.takeDailyQuota()) throw new java.io.IOException("tope diario de lecturas");
                CarCloudSession.Found f = CarCloudSession.readPosition(app);
                return f == null || f.position == null ? null : new double[]{f.position.lat, f.position.lon};
            }
        };
    }

    /** La casa guardada (SavedPlaces), o null. */
    static double[] home(Config cfg) {
        SavedPlaces.Saved h = SavedPlaces.tagged(SavedPlaces.parse(cfg.savedPlaces()), SavedPlaces.HOME);
        return h == null ? null : new double[]{h.lat, h.lon};
    }

    /** Desde la pantalla de ajustes: en otro hilo (una comprobación en marcha puede tardar lo que tarda una orden). */
    static void settingsChangedAsync(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> settingsChanged(app), "hql-cheap-settings").start();
    }

    /** Cambió un ajuste de «Carga barata»: lo decidido para este enchufado se vuelve a pensar en la próxima comprobación. */
    static synchronized void settingsChanged(Context ctx) {
        State st = State.load(ctx);
        if (PLANNED.equals(st.phase)) {
            st.recalc = true;
            st.lastTryMs = 0;
            st.save(ctx);
        } else if (HOLD.equals(st.phase) && (WHY_NOW.equals(st.why) || WHY_NO_FIT.equals(st.why) || WHY_AWAY.equals(st.why)
                || WHY_NO_POSITION.equals(st.why) || WHY_TARGET.equals(st.why))) {
            st.settle(IDLE, "");
            st.save(ctx);
        } else if (IDLE.equals(st.phase) && st.lastTryMs != 0) {
            st.lastTryMs = 0;
            st.error = "";
            st.save(ctx);
        }
    }

    // ------------------------------------------------------------------ el motor

    /**
     * Una comprobación con una lectura del coche: lo que toque según la fase. Como mucho un envío al coche. Con red:
     * fuera del hilo principal.
     */
    static synchronized void check(Context ctx, CarCloud.Snapshot cs, long now, Car car) {
        if (cs == null || !cs.hasData() || cs.demo) return;
        Config cfg = new Config(ctx);
        State st = State.load(ctx);
        LeapStatus s = cs.status;
        boolean on = cfg.cheapCharge();
        boolean plugged = s.pluggedIn();
        double soc = s.socBest();
        // «Desenchufado» solo vale si el dato es posterior al del enchufado con el que se planificó.
        boolean unplugged = !plugged && cs.dataTimeMs() > st.plugDataMs;
        TimeZone tz = TimeZone.getDefault();

        if (PLANNED.equals(st.phase)) {
            String why = !on ? WHY_OFF : unplugged ? WHY_UNPLUGGED : now >= st.departMs ? WHY_DEPART
                    : !Double.isNaN(soc) && soc >= st.target - TARGET_SLACK ? WHY_TARGET : null;
            if (why != null) {
                st.phase = RESTORE;
                st.why = why;
                st.lastTryMs = 0;
                st.save(ctx);
                L.i("carga barata: toca devolver el plan del coche (" + whyLog(why) + ")");
            } else {
                if (throttled(st, now)) return;
                if (!st.confirmed) confirm(ctx, st, now, car);
                else if (st.provisional || st.recalc) recalc(ctx, cfg, st, cs, now, car, tz);
                // Si de ahí sale que hay que devolver el plan (sin haber mandado nada), se sigue en esta misma comprobación.
                if (!RESTORE.equals(st.phase)) return;
            }
        }
        if (RESTORE.equals(st.phase)) {
            if (!throttled(st, now)) restore(ctx, st, now, car, plugged && !WHY_UNPLUGGED.equals(st.why), soc);
            return;
        }
        if (HOLD.equals(st.phase)) {
            if (unplugged) {
                st.settle(IDLE, "");
                st.save(ctx);
                L.i("carga barata: coche desenchufado; a la espera del próximo enchufado en casa");
            }
            return;
        }
        // IDLE: ¿se planifica?
        if (!on || !plugged || s.dcPlugged() || cs.ageMs(now) > MAX_AGE_MS || Double.isNaN(soc)) return;
        Tariff tariff = cfg.tariff();
        int target = cfg.cheapTarget();
        if (Tariff.FLAT.equals(tariff.mode) || soc >= target - TARGET_SLACK || !car.hasPin()) return;
        double[] home = home(cfg);
        if (home == null || throttled(st, now)) return;
        plan(ctx, cfg, st, cs, now, car, tz, tariff, target, home);
    }

    private static boolean throttled(State st, long now) {
        return st.lastTryMs > 0 && now >= st.lastTryMs && now - st.lastTryMs < RETRY_MS;
    }

    /** Los precios por hora de la tarifa (con el PVPC, lo publicado y, de lo que falte, la estimación). */
    private static CheapWindow.Prices prices(Context ctx, Tariff tariff, long now, long depart, TimeZone tz, boolean[] provisional) {
        provisional[0] = false;
        if (!Tariff.PVPC.equals(tariff.mode)) return tariff.prices(tz);
        PvpcPrices.Table t = PvpcPrices.get(ctx, now, depart);
        provisional[0] = !t.covers(now, depart);
        // El PVPC va en euros: en otra moneda, por su cambio aproximado (como los demás precios de la app).
        return t.times(Money.rate()).withEstimates();
    }

    /** Enchufado por alterna, con PIN y casa guardada: mira si está en casa, calcula y, si hay ventana, la manda. */
    private static void plan(Context ctx, Config cfg, State st, CarCloud.Snapshot cs, long now, Car car, TimeZone tz, Tariff tariff,
                             int target, double[] home) {
        double soc = cs.status.socBest();
        if (!st.atHome && !atHome(ctx, st, cs, now, car, home)) return;
        long depart = CheapWindow.departure(now, cfg.cheapDepartMin(), tz);
        boolean[] provisional = new boolean[1];
        CheapWindow.Prices prices = prices(ctx, tariff, now, depart, tz, provisional);
        double cap = cs.capacityKwh > 0 ? cs.capacityKwh : EnergyModel.usableKwh();
        CheapWindow.Result r = CheapWindow.plan(now, depart, soc, target, cap, cfg.cheapKw(), prices, tz);
        L.i("carga barata: enchufado en casa al " + Math.round(soc) + " %; " + describe(r, tz, target)
                + (provisional[0] ? " (provisional: sin los precios de mañana)" : ""));
        st.startSoc = soc;
        st.target = target;
        st.departMs = depart;
        st.cost = r.cost;
        st.costNow = r.costNow;
        st.startMs = r.startMs;
        st.endMs = r.endMs;
        st.chargeEndMs = r.chargeEndMs;
        switch (r.kind) {
            case NOT_NEEDED:
                st.lastTryMs = now;
                st.save(ctx);
                return;
            case NO_PRICES:
                st.error = ERR_PRICES;
                st.lastTryMs = now;
                st.save(ctx);
                return;
            case NO_FIT:
            case NOW:
                st.settle(HOLD, r.kind == CheapWindow.Kind.NOW ? WHY_NOW : WHY_NO_FIT);
                st.plugDataMs = cs.dataTimeMs();
                st.save(ctx);
                noticeNow(ctx, st, tz);
                return;
            default:
                break;
        }
        ChargePlan original;
        try {
            original = car.readPlan();
        } catch (Exception e) {
            failed(ctx, st, now, e, "no se pudo leer el plan del coche");
            return;
        }
        if (original != null && original.enabled) {
            st.settle(HOLD, WHY_RESPECTED);
            st.plugDataMs = cs.dataTimeMs();
            st.save(ctx);
            L.i("carga barata: el coche ya tiene una ventana de carga puesta por ti (" + original.start + "–" + original.end
                    + "); se respeta, no se toca nada");
            return;
        }
        ChargePlan base = original != null ? original : defaultPlan();
        ChargePlan plan = window(base, r, target, tz);
        // Guardado ANTES de mandar: si el proceso muere a medias, la siguiente comprobación sabe qué devolver.
        st.settle(PLANNED, "");
        st.atHome = true;
        st.windows = "";
        st.original = base.content();
        st.sent = plan.content();
        st.provisional = provisional[0];
        st.plugDataMs = cs.dataTimeMs();
        st.save(ctx);
        send(ctx, st, now, car, plan, tz);
    }

    /** ¿Está el coche en casa? Una lectura de su posición; si no lo está (o no la da), queda dicho para este enchufado. */
    private static boolean atHome(Context ctx, State st, CarCloud.Snapshot cs, long now, Car car, double[] home) {
        double[] pos;
        try {
            pos = car.position();
        } catch (Exception e) {
            failed(ctx, st, now, e, "no se pudo saber dónde está el coche");
            return false;
        }
        if (pos == null) {
            st.positionTries++;
            st.lastTryMs = now;
            if (st.positionTries >= MAX_POSITION_TRIES) {
                st.settle(HOLD, WHY_NO_POSITION);
                st.plugDataMs = cs.dataTimeMs();
            }
            st.save(ctx);
            L.i("carga barata: el coche no da su posición; no se sabe si está en casa");
            return false;
        }
        if (RoadInfo.dist(pos[0], pos[1], home[0], home[1]) > HOME_M) {
            st.settle(HOLD, WHY_AWAY);
            st.plugDataMs = cs.dataTimeMs();
            st.save(ctx);
            L.i("carga barata: enchufado fuera de casa; no se programa nada");
            return false;
        }
        st.atHome = true;
        st.save(ctx);
        return true;
    }

    /**
     * La ventana que se manda: el plan del coche con la ventana, el objetivo como límite y todos los días (un plan
     * apagado pudo quedar con días sueltos, y la ventana tiene que valer esta noche); lo demás, como estaba.
     */
    static ChargePlan window(ChargePlan base, CheapWindow.Result r, int target, TimeZone tz) {
        return new ChargePlan(true, target, CheapWindow.hhmm(r.startMs, tz), CheapWindow.hhmm(r.endMs, tz), ChargePlan.ALL_DAYS,
                base.circulation, base.recharge);
    }

    /** Manda la ventana (st.sent ya guardado). Confirmada: el aviso. Si no, se comprobará en la próxima. */
    private static void send(Context ctx, State st, long now, Car car, ChargePlan plan, TimeZone tz) {
        boolean ok = false;
        st.tries++;
        st.lastTryMs = now;
        String w = plan.start + "-" + plan.end + ";";
        if (!st.windows.contains(w)) st.windows += w;
        st.save(ctx);
        try {
            ok = car.sendPlan(plan);
            st.error = "";
        } catch (Exception e) {
            st.error = errorOf(e);
            L.w("carga barata: no se pudo mandar la ventana (" + Http.safeError(e) + "); se reintenta");
        }
        if (ok) {
            st.confirmed = true;
            st.lastTryMs = 0;
            L.i("carga barata: ventana " + plan.start + "–" + plan.end + " hasta el " + plan.soc + " % programada en el coche");
        } else if (st.error.isEmpty()) {
            L.i("carga barata: ventana mandada sin confirmar; se comprobará en la próxima lectura");
        }
        st.save(ctx);
        if (ok) noticePlanned(ctx, st, tz);
    }

    /** La ventana se mandó sin confirmar (o falló): se lee el plan; si está puesta, vale; si no, otro intento. */
    private static void confirm(Context ctx, State st, long now, Car car) {
        ChargePlan sent = planOf(st.sent);
        ChargePlan cur;
        try {
            cur = car.readPlan();
        } catch (Exception e) {
            failed(ctx, st, now, e, "no se pudo leer el plan del coche");
            return;
        }
        if (sent != null && ours(cur, sent)) {
            st.confirmed = true;
            st.lastTryMs = 0;
            st.error = "";
            st.save(ctx);
            L.i("carga barata: la ventana " + sent.start + "–" + sent.end + " está puesta en el coche");
            noticePlanned(ctx, st, TimeZone.getDefault());
            return;
        }
        if (sent == null || st.tries >= MAX_TRIES) {
            st.phase = RESTORE;
            st.why = WHY_FAILED;
            st.lastTryMs = 0;
            st.save(ctx);
            L.w("carga barata: el coche no acepta la ventana tras " + st.tries + " intentos; se deja el plan como estaba");
            return;
        }
        send(ctx, st, now, car, sent, TimeZone.getDefault());
    }

    /** ¿El plan leído lleva la ventana que mandamos? */
    private static boolean ours(ChargePlan cur, ChargePlan sent) {
        return cur != null && cur.enabled && cur.start.equals(sent.start) && cur.end.equals(sent.end);
    }

    /**
     * Con la ventana puesta y sin empezar: ya están los precios de mañana (la ventana era provisional) o cambiaron los
     * ajustes. Se recalcula; si la ventana cambia, se reenvía y se actualiza el aviso; si ahora toca cargar ya, se
     * devuelve el plan original.
     */
    private static void recalc(Context ctx, Config cfg, State st, CarCloud.Snapshot cs, long now, Car car, TimeZone tz) {
        double soc = cs.status.socBest();
        if (now >= st.startMs - CheapWindow.SOON_MS || Double.isNaN(soc)) {
            // Empezada (o a punto): ya no se mueve.
            st.provisional = false;
            st.recalc = false;
            st.save(ctx);
            return;
        }
        Tariff tariff = cfg.tariff();
        int target = st.recalc ? cfg.cheapTarget() : st.target;
        long depart = st.recalc ? CheapWindow.departure(now, cfg.cheapDepartMin(), tz) : st.departMs;
        if (Tariff.FLAT.equals(tariff.mode)) {
            toRestore(ctx, st, WHY_FLAT, "con la tarifa plana no hay hora barata");
            return;
        }
        boolean[] provisional = new boolean[1];
        CheapWindow.Prices prices = prices(ctx, tariff, now, depart, tz, provisional);
        st.lastTryMs = now;
        if (provisional[0] && !st.recalc) {
            // Aún sin los precios de mañana: se queda la provisional.
            st.save(ctx);
            return;
        }
        double cap = cs.capacityKwh > 0 ? cs.capacityKwh : EnergyModel.usableKwh();
        CheapWindow.Result r = CheapWindow.plan(now, depart, soc, target, cap, cfg.cheapKw(), prices, tz);
        L.i("carga barata: recalculo " + (st.recalc ? "con los ajustes nuevos" : "con los precios de mañana") + ": "
                + describe(r, tz, target));
        st.recalc = false;
        st.provisional = provisional[0];
        if (r.kind == CheapWindow.Kind.NO_PRICES) {
            st.save(ctx);
            return;
        }
        if (r.kind != CheapWindow.Kind.WINDOW) {
            st.cost = r.cost;
            st.costNow = r.costNow;
            toRestore(ctx, st, r.kind == CheapWindow.Kind.NO_FIT ? WHY_NO_FIT : r.kind == CheapWindow.Kind.NOT_NEEDED ? WHY_TARGET
                    : WHY_NOW, "ahora lo mejor es cargar ya");
            return;
        }
        ChargePlan base = planOf(st.original);
        ChargePlan plan = window(base != null ? base : defaultPlan(), r, target, tz);
        st.target = target;
        st.departMs = depart;
        st.cost = r.cost;
        st.costNow = r.costNow;
        st.startMs = r.startMs;
        st.endMs = r.endMs;
        st.chargeEndMs = r.chargeEndMs;
        st.lastTryMs = 0;
        if (plan.same(planOf(st.sent))) {
            // La misma ventana: nada que mandar; el aviso, con el coste ya definitivo.
            st.save(ctx);
            noticePlanned(ctx, st, tz);
            return;
        }
        st.sent = plan.content();
        st.confirmed = false;
        st.tries = 0;
        st.save(ctx);
        send(ctx, st, now, car, plan, tz);
    }

    private static void toRestore(Context ctx, State st, String why, String log) {
        st.phase = RESTORE;
        st.why = why;
        st.lastTryMs = 0;
        st.recalc = false;
        st.provisional = false;
        st.save(ctx);
        L.i("carga barata: " + log + "; toca devolver el plan del coche");
    }

    /**
     * Devuelve el plan original. Se lee el del coche: si ya es el original, nada que mandar; si lleva otra ventana que
     * no es la nuestra, la ha puesto el usuario y se respeta; si no, se manda el original. Si falla, se reintenta en la
     * próxima comprobación.
     */
    private static void restore(Context ctx, State st, long now, Car car, boolean stillPlugged, double soc) {
        ChargePlan original = planOf(st.original);
        String why = st.why;
        if (original != null) {
            try {
                ChargePlan cur = car.readPlan();
                if (cur != null && cur.same(original)) {
                    L.i("carga barata: el plan del coche ya está como estaba; nada que mandar");
                } else if (cur != null && cur.enabled && !st.windows.contains(cur.start + "-" + cur.end + ";")) {
                    L.i("carga barata: el coche lleva otra ventana (" + cur.start + "–" + cur.end + "), puesta por ti; se respeta");
                    why = WHY_CHANGED;
                } else if (!car.sendPlan(original)) {
                    st.lastTryMs = now;
                    st.error = "";
                    st.save(ctx);
                    L.w("carga barata: plan original mandado sin confirmar; se comprobará en la próxima lectura");
                    return;
                } else {
                    L.i("carga barata: plan del coche devuelto a como estaba (" + whyLog(st.why) + ")");
                }
            } catch (Exception e) {
                failed(ctx, st, now, e, "no se pudo devolver el plan del coche (" + whyLog(st.why) + "); se reintenta");
                return;
            }
        }
        boolean charged = !Double.isNaN(soc) && !Double.isNaN(st.startSoc) && soc > st.startSoc + 0.5;
        boolean finished = WHY_TARGET.equals(why) || WHY_DEPART.equals(why) || WHY_UNPLUGGED.equals(why);
        if (finished && charged && st.confirmed) noticeDone(ctx, st, soc);
        else if (WHY_NOW.equals(why) || WHY_NO_FIT.equals(why)) noticeNow(ctx, st, TimeZone.getDefault());
        else cancelNotice(ctx);
        st.restoredAtMs = now;
        st.original = "";
        st.sent = "";
        st.windows = "";
        st.settle(stillPlugged ? HOLD : IDLE, stillPlugged ? why : "");
        st.save(ctx);
    }

    private static void failed(Context ctx, State st, long now, Exception e, String what) {
        st.lastTryMs = now;
        st.error = errorOf(e);
        st.save(ctx);
        L.w("carga barata: " + what + " (" + Http.safeError(e) + ")");
    }

    static String errorOf(Exception e) {
        if (e instanceof LeapApi.SessionExpiredException) return ERR_SESSION;
        if (e instanceof CarCloudSession.NotConfiguredException) {
            return String.valueOf(e.getMessage()).contains("PIN") ? ERR_PIN : ERR_SESSION;
        }
        return ERR_NET;
    }

    /**
     * «Cargar ya» (el botón del aviso y el de la tarjeta): se devuelve el plan original, sin ventana, para que el coche
     * cargue. true si quedó hecho (si no, se reintenta en las comprobaciones). Con red: fuera del hilo principal.
     */
    static boolean chargeNow(Context ctx, Car car) {
        return chargeNow(ctx, car, System.currentTimeMillis());
    }

    static synchronized boolean chargeNow(Context ctx, Car car, long now) {
        State st = State.load(ctx);
        if (PLANNED.equals(st.phase)) {
            st.phase = RESTORE;
            st.why = WHY_USER;
            st.lastTryMs = 0;
            st.save(ctx);
            L.i("carga barata: «Cargar ya»");
        }
        if (!RESTORE.equals(st.phase)) return true;
        restore(ctx, st, now, car, !WHY_UNPLUGGED.equals(st.why), Double.NaN);
        return !RESTORE.equals(State.load(ctx).phase);
    }

    /** El botón «Cargar ya» del aviso. */
    public static final class NowReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Context app = ctx.getApplicationContext();
            PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    chargeNow(app, cloud(app));
                } catch (RuntimeException e) {
                    L.w("carga barata: «Cargar ya» fallido (" + e.getClass().getSimpleName() + ")");
                } finally {
                    pending.finish();
                }
            }, "hql-cheap-now").start();
        }
    }

    // ------------------------------------------------------------------ textos y avisos

    private static String whyLog(String why) {
        switch (why) {
            case WHY_UNPLUGGED:
                return "coche desenchufado";
            case WHY_DEPART:
                return "pasó la hora de salida";
            case WHY_TARGET:
                return "batería en el objetivo";
            case WHY_USER:
                return "«Cargar ya»";
            case WHY_OFF:
                return "función apagada";
            case WHY_FAILED:
                return "el coche no aceptó la ventana";
            default:
                return "toca cargar ya";
        }
    }

    /** Para el registro y el laboratorio: lo calculado, sin nada personal. */
    static String describe(CheapWindow.Result r, TimeZone tz, int target) {
        String need = String.format(Locale.US, "%.1f kWh en %d min", r.kwh, r.needMs / 60_000L);
        switch (r.kind) {
            case NOT_NEEDED:
                return "la batería ya está en el objetivo (" + target + " %)";
            case NO_PRICES:
                return "sin precios: se carga ya (" + need + ")";
            case NO_FIT:
                return "no cabe antes de la salida (" + need + "): se carga ya, sin ventana";
            case NOW:
                return "lo más barato es cargar ya (" + need + String.format(Locale.US, ", ~%.2f", r.cost) + "): sin ventana";
            default:
                return "ventana " + CheapWindow.hhmm(r.startMs, tz) + "–" + CheapWindow.hhmm(r.endMs, tz) + " hasta el " + target
                        + " % (" + need + String.format(Locale.US, ", ~%.2f; cargando ya ~%.2f)", r.cost, r.costNow);
        }
    }

    /** «02:00–05:30 · 80 % · 1,90 € (ahorras 1,40 €)». */
    static String plannedText(State st, TimeZone tz) {
        String t = Str.get(R.string.hql_cb_notif_planned, CheapWindow.hhmm(st.startMs, tz), CheapWindow.hhmm(st.endMs, tz), st.target);
        if (!Double.isNaN(st.cost)) t += " · " + Money.format(st.cost);
        if (st.saving() >= 0.01) t += " " + Str.get(R.string.hql_cb_saving, Money.format(st.saving()));
        return t;
    }

    /** «Cargado al 80 %. Ha costado unos 2,10 €, 1,40 € menos que cargando al enchufar». */
    static String doneText(State st, double soc) {
        // Lo cargado de verdad frente a lo previsto: el coste y el ahorro, en proporción.
        double planned = st.target - st.startSoc;
        double k = planned > 0 ? Math.max(0, Math.min(1.2, (soc - st.startSoc) / planned)) : 1;
        int pct = (int) Math.round(soc);
        if (Double.isNaN(st.cost)) return Str.get(R.string.hql_cb_notif_done_short, pct);
        if (st.saving() * k >= 0.01) {
            return Str.get(R.string.hql_cb_notif_done, pct, Money.format(st.cost * k), Money.format(st.saving() * k));
        }
        return Str.get(R.string.hql_cb_notif_done_plain, pct, Money.format(st.cost * k));
    }

    /** «No da tiempo antes de las 07:30: el coche carga ya, sin ventana» o «Ahora ya es lo más barato…». */
    static String nowText(State st, TimeZone tz) {
        return Str.get(WHY_NO_FIT.equals(st.why) ? R.string.hql_cb_now_nofit : R.string.hql_cb_now_cheapest,
                CheapWindow.hhmm(st.departMs, tz));
    }

    private static Notification.Builder builder(Context ctx, String title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        // El canal discreto de las cargas (sin sonido): esto pasa al enchufar en casa y de madrugada.
        nm.createNotificationChannel(new NotificationChannel(ChargeWatch.CHANNEL, Str.get(R.string.hql_live_channel),
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(ctx, ChargeWatch.CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(ctx.getColor(R.color.hql_accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(ChargeWatch.openApp(ctx))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
    }

    private static void post(Context ctx, Notification.Builder b) {
        try {
            ctx.getSystemService(NotificationManager.class).notify(NOTIF_ID, b.build());
        } catch (RuntimeException e) {
            L.w("carga barata: no se pudo avisar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** «Carga barata programada» con la ventana, el coste, el ahorro y el botón «Cargar ya». */
    private static void noticePlanned(Context ctx, State st, TimeZone tz) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        String text = plannedText(st, tz);
        if (st.provisional) text += "\n" + Str.get(R.string.hql_cb_provisional);
        PendingIntent now = PendingIntent.getBroadcast(ctx, NOTIF_ID, new Intent(ctx, NowReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        post(ctx, builder(ctx, Str.get(R.string.hql_cb_notif_planned_title), text)
                .setAutoCancel(false)
                .addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_cb_charge_now), now).build()));
    }

    private static void noticeDone(Context ctx, State st, double soc) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        post(ctx, builder(ctx, Str.get(R.string.hql_cb_title), doneText(st, soc)));
    }

    private static void noticeNow(Context ctx, State st, TimeZone tz) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        post(ctx, builder(ctx, Str.get(R.string.hql_cb_now_title), nowText(st, tz)));
    }

    private static void cancelNotice(Context ctx) {
        try {
            ctx.getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        } catch (RuntimeException ignored) {
            // Sin gestor de avisos: nada que quitar.
        }
    }

    // ------------------------------------------------------------------ para la tarjeta de «Mi coche» y el laboratorio

    /** Lo que enseña la tarjeta. */
    static final class Status {
        final String line;
        /** Segunda línea más pequeña (coste, ahorro, aviso de provisional), o "". */
        final String detail;
        /** 0 normal, 1 bien (programada, cargando), 2 aviso (falta algo o no se pudo). */
        final int level;
        /** Hay ventana puesta: se ofrece «Cargar ya». */
        final boolean canChargeNow;
        final State state;

        Status(String line, String detail, int level, boolean canChargeNow, State state) {
            this.line = line;
            this.detail = detail;
            this.level = level;
            this.canChargeNow = canChargeNow;
            this.state = state;
        }
    }

    /** El estado para la tarjeta, con lo guardado y la última lectura del coche. Sin red. */
    static Status status(Context ctx, CarCloud.Snapshot cs, long now, boolean hasPin, boolean sessionClosed) {
        Config cfg = new Config(ctx);
        State st = State.load(ctx);
        TimeZone tz = TimeZone.getDefault();
        LeapStatus s = cs == null ? null : cs.status;
        if (RESTORE.equals(st.phase)) {
            String cause = ERR_SESSION.equals(st.error) || sessionClosed ? Str.get(R.string.hql_cb_st_session)
                    : ERR_PIN.equals(st.error) ? Str.get(R.string.hql_cb_st_no_pin) : "";
            return new Status(Str.get(R.string.hql_cb_st_restoring), cause, 2, false, st);
        }
        if (PLANNED.equals(st.phase)) {
            String detail = Double.isNaN(st.cost) ? "" : st.saving() >= 0.01
                    ? Str.get(R.string.hql_cb_st_cost_saving, Money.format(st.cost), Money.format(st.saving()))
                    : Str.get(R.string.hql_cb_st_cost, Money.format(st.cost));
            if (st.provisional) detail += (detail.isEmpty() ? "" : "\n") + Str.get(R.string.hql_cb_provisional);
            String a = CheapWindow.hhmm(st.startMs, tz);
            String b = CheapWindow.hhmm(st.endMs, tz);
            if (!st.confirmed) return new Status(Str.get(R.string.hql_cb_st_sending, a, b), detail, 0, true, st);
            boolean charging = s != null && s.charging() && now >= st.startMs;
            return new Status(Str.get(charging ? R.string.hql_cb_st_charging : R.string.hql_cb_st_planned, a, b, st.target), detail, 1,
                    true, st);
        }
        if (!cfg.cheapCharge()) return new Status(Str.get(R.string.hql_cb_st_off), "", 0, false, st);
        if (sessionClosed) return new Status(Str.get(R.string.hql_cb_st_session), "", 2, false, st);
        if (!hasPin) return new Status(Str.get(R.string.hql_cb_st_no_pin), "", 2, false, st);
        if (Tariff.FLAT.equals(cfg.cheapTariff())) return new Status(Str.get(R.string.hql_cb_st_flat), "", 0, false, st);
        if (home(cfg) == null) return new Status(Str.get(R.string.hql_cb_st_no_home), "", 2, false, st);
        if (HOLD.equals(st.phase)) {
            switch (st.why) {
                case WHY_NOW:
                case WHY_NO_FIT:
                    return new Status(nowText(st, tz), "", 0, false, st);
                case WHY_RESPECTED:
                    return new Status(Str.get(R.string.hql_cb_st_respected), "", 0, false, st);
                case WHY_CHANGED:
                    return new Status(Str.get(R.string.hql_cb_st_changed), "", 0, false, st);
                case WHY_AWAY:
                    return new Status(Str.get(R.string.hql_cb_st_away), "", 0, false, st);
                case WHY_NO_POSITION:
                    return new Status(Str.get(R.string.hql_cb_st_no_position), "", 2, false, st);
                case WHY_FAILED:
                    return new Status(Str.get(R.string.hql_cb_st_failed), "", 2, false, st);
                case WHY_USER:
                    return new Status(Str.get(R.string.hql_cb_st_user), Str.get(R.string.hql_cb_st_restored), 0, false, st);
                default:
                    return new Status(Str.get(R.string.hql_cb_st_restored), "", 0, false, st);
            }
        }
        // IDLE.
        String restored = st.restoredAtMs > 0 && now - st.restoredAtMs < RESTORED_SHOWN_MS ? Str.get(R.string.hql_cb_st_restored) : "";
        boolean plugged = s != null && s.pluggedIn() && !s.dcPlugged();
        if (plugged && !Double.isNaN(s.socBest()) && s.socBest() >= cfg.cheapTarget() - TARGET_SLACK) {
            return new Status(Str.get(R.string.hql_cb_st_at_target, cfg.cheapTarget()), restored, 0, false, st);
        }
        if (plugged && ERR_PRICES.equals(st.error)) return new Status(Str.get(R.string.hql_cb_st_no_prices), "", 2, false, st);
        if (plugged) return new Status(Str.get(R.string.hql_cb_st_plugged), "", 0, false, st);
        return new Status(Str.get(R.string.hql_cb_st_waiting), restored, 0, false, st);
    }

    /**
     * Variante de pruebas («barata»): el estado guardado y el cálculo que se haría ahora con la última lectura del
     * coche, sin mandar nada ni preguntar la posición (con el PVPC puede pedir los precios a Red Eléctrica).
     */
    static String lab(Context ctx) {
        Config cfg = new Config(ctx);
        State st = State.load(ctx);
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        b.append(cfg.cheapCharge() ? "activada" : "apagada").append(" · tarifa ").append(cfg.cheapTariff()).append(" · salida ")
                .append(CheapWindow.hhmm(cfg.cheapDepartMin())).append(" · objetivo ").append(cfg.cheapTarget()).append(" % · ")
                .append(cfg.cheapKw()).append(" kW · casa ").append(home(cfg) == null ? "sin guardar" : "guardada").append(" · PIN ")
                .append(new CarCloudStore(ctx).hasPin() ? "sí" : "no").append(" · fase ").append(st.phase);
        if (!st.why.isEmpty()) b.append(" (").append(st.why).append(")");
        if (!st.error.isEmpty()) b.append(" · último fallo: ").append(st.error);
        if (PLANNED.equals(st.phase) || RESTORE.equals(st.phase)) {
            b.append(" · ventana ").append(CheapWindow.hhmm(st.startMs, tz)).append("–").append(CheapWindow.hhmm(st.endMs, tz))
                    .append(st.confirmed ? "" : " sin confirmar").append(st.provisional ? " provisional" : "");
        }
        CarCloud.Snapshot cs = CarCloud.car();
        if (cs == null || !cs.hasData() || cs.demo) return b.append(" · sin lectura del coche").toString();
        LeapStatus s = cs.status;
        b.append(" · coche ").append(s.pluggedIn() ? (s.dcPlugged() ? "enchufado (CC)" : "enchufado (CA)") : "sin enchufar");
        double soc = s.socBest();
        Tariff tariff = cfg.tariff();
        if (Double.isNaN(soc)) return b.append(" · sin % de batería").toString();
        if (Tariff.FLAT.equals(tariff.mode)) return b.append(" · tarifa plana: sin ventana, carga al enchufar").toString();
        long depart = CheapWindow.departure(now, cfg.cheapDepartMin(), tz);
        boolean[] provisional = new boolean[1];
        CheapWindow.Prices prices = prices(ctx, tariff, now, depart, tz, provisional);
        double cap = cs.capacityKwh > 0 ? cs.capacityKwh : EnergyModel.usableKwh();
        CheapWindow.Result r = CheapWindow.plan(now, depart, soc, cfg.cheapTarget(), cap, cfg.cheapKw(), prices, tz);
        return b.append(" · ahora, al ").append(Math.round(soc)).append(" %: ").append(describe(r, tz, cfg.cheapTarget()))
                .append(provisional[0] ? " (provisional: sin los precios de mañana)" : "").toString();
    }

    /** Para las pruebas: como recién instalada. */
    static void resetForTest(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
