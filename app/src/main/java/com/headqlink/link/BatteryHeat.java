package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import com.andrerinas.openheadunit.R;

import java.util.Locale;

/**
 * «Batería caliente para el cargador» (11-11): si el plan de la ruta lleva una parada de carga rápida, unos minutos
 * antes de llegar la app pide al coche por la nube de Leapmotor que caliente la batería (orden 160, «ptcon»), para que
 * cargue rápido desde el primer minuto. Lo dice en la pantalla del coche y por voz, con «Cancelar» (manda «ptcoff»).
 *
 * Solo con el ajuste encendido (apagado de serie), cuenta de Leapmotor y PIN guardado, fuera a menos de COLD_C y sin la
 * batería ya caliente. Una sola vez por parada; si la llegada se retrasa mucho, la parada sale del plan, el viaje acaba
 * o pasa la hora prevista + LATE_MIN sin llegar, se manda el apagado. Al enchufar a la carga rápida ya no hace falta
 * nada (el coche sigue con lo suyo).
 *
 * Lo mueve el bucle de la ruta (RoutePlanner, cada 5 s): decide() es pura y no manda nada; tick() aplica lo decidido,
 * como mucho un envío por minuto. Lo hecho se guarda (el proceso puede morir con la batería calentándose).
 *
 * Nada personal va al registro: ni el VIN, ni la posición, ni el nombre del cargador (solo su potencia y los minutos).
 * La orden está sin probar en el coche real: el laboratorio («calentar») cuenta qué haría.
 */
final class BatteryHeat {
    /** Se manda cuando faltan esto o menos para llegar a la parada rápida (min). */
    static final double LEAD_MIN = 25;
    /** Con menos de esto por delante ya no da tiempo a calentar (min): no se manda. */
    static final double MIN_LEAD_MIN = 5;
    /** Solo si fuera hace menos de esto (°C). */
    static final double COLD_C = 15;
    /** Con la celda más fría de la batería (señal 1182) a esto o más, ya está caliente (°C). */
    static final double WARM_BATTERY_C = 25;
    /** No se manda si según el plan se llega a la parada con menos de esto (%): calentar gasta batería. */
    static final double MIN_ARRIVE_PCT = 10;
    /** Se apaga si la llegada se retrasa esto o más frente a la prevista al encender, o si pasa la prevista + esto (min). */
    static final double LATE_MIN = 20;
    /** Como mucho un envío cada esto. */
    static final long SEND_GAP_MS = 60_000;
    /** Tanto tiempo sin la parada en el plan (un recálculo la quita y la vuelve a poner) y se apaga. */
    static final long GONE_GRACE_MS = 2 * 60_000;
    /** Los datos del coche (señal 1186, temperatura de la batería, enchufado) valen hasta esta edad. */
    static final long CAR_MAX_AGE_MS = 10 * 60_000;
    /** Intentos de apagar antes de dejarlo (el coche deja de calentar solo al cabo de un rato). */
    static final int OFF_TRIES = 3;
    private static final String PREFS = "hql_battery_heat";

    enum Action { NONE, ON, OFF }

    // Por qué (Step.why). Sin encender:
    static final String W_SEND = "send";
    static final String W_UNSUPPORTED = "unsupported";
    static final String W_DISABLED = "disabled";
    static final String W_NO_ACCOUNT = "no_account";
    static final String W_NO_PIN = "no_pin";
    static final String W_NO_TRIP = "no_trip";
    static final String W_NO_FAST_STOP = "no_fast_stop";
    static final String W_DONE = "done";
    static final String W_NO_ETA = "no_eta";
    static final String W_EARLY = "early";
    static final String W_TOO_CLOSE = "too_close";
    static final String W_NO_TEMP = "no_temp";
    static final String W_WARM_OUTSIDE = "warm_outside";
    static final String W_LOW_ARRIVAL = "low_arrival";
    static final String W_CHARGING = "charging";
    static final String W_ALREADY_HEATING = "already_heating";
    static final String W_BATTERY_WARM = "battery_warm";
    static final String W_GAP = "gap";
    // …y encendida:
    static final String W_HEATING = "heating";
    static final String W_PLUGGED = "plugged";
    static final String W_ARRIVED = "arrived";
    static final String W_GONE_WAIT = "gone_wait";
    static final String W_GONE = "gone";
    static final String W_LATE = "late";
    static final String W_DELAY = "delay";
    static final String W_USER = "user";

    /** Lo que se sabe ahora (del plan de la ruta, del tiempo y de la última lectura del coche). */
    static final class Input {
        boolean enabled;
        boolean account;
        boolean pin;
        /** Este coche ya contestó que no tiene la función. */
        boolean unsupported;
        /** Hay un viaje en curso con plan (sin llegar al destino). */
        boolean trip;
        /** La próxima parada rápida del plan (ChargerMarks.key), o null si no hay. */
        String stopKey;
        String stopName = "";
        double stopKw;
        /** Minutos hasta llegar a ella (NaN si no se sabe) y el % con el que se llega según el plan. */
        double minutes = Double.NaN;
        double arrivePct = Double.NaN;
        /** Temperatura de fuera (°C; NaN sin dato) y de dónde sale (para el registro). */
        double outsideC = Double.NaN;
        String tempSource = "";
        /** Señal 1186 (null: sin dato reciente) y la celda más fría de la batería (°C; NaN sin dato). */
        Integer thermal;
        double batteryC = Double.NaN;
        /** Enchufado a la carga rápida (CC). */
        boolean dcCharging;

        Boolean heating() {
            return thermal == null ? null : thermal != 0;
        }
    }

    /** Lo decidido: qué mandar y por qué. */
    static final class Step {
        final Action action;
        final String why;

        Step(Action action, String why) {
            this.action = action;
            this.why = why;
        }

        @Override
        public String toString() {
            return action + " (" + why + ")";
        }
    }

    /** Lo que hace falta del coche (la nube de Leapmotor; de mentira en las pruebas). */
    interface Car {
        /** Manda encender (true) o apagar; true si el coche lo confirma, false si no contesta a tiempo. */
        boolean send(boolean on) throws Exception;
    }

    /** Los avisos (en el coche y por voz; de mentira en las pruebas). */
    interface Notice {
        /** Tarjeta en el panel del coche; cancel: con «Cancelar». */
        void card(Context ctx, String title, String text, boolean cancel);

        void voice(Context ctx, String text);
    }

    static final Notice CAR_NOTICE = new Notice() {
        @Override
        public void card(Context ctx, String title, String text, boolean cancel) {
            RoutePlanner.ChargeAlertListener l = RoutePlanner.chargeAlerts;
            if (l != null) l.onChargeAlert(title, text, cancel ? RoutePlanner.ALERT_HEAT : RoutePlanner.ALERT_HEAT_NOTE);
        }

        @Override
        public void voice(Context ctx, String text) {
            if (new Config(ctx).planVoice()) VoiceAlert.say(ctx, text);
        }
    };

    static volatile Notice notice = CAR_NOTICE;

    /** Lo guardado entre una vuelta del bucle y la siguiente. */
    static final class State {
        /** La parada para la que se mandó (o se dejó) y su nombre y potencia (para los avisos y el registro). */
        String key = "";
        String name = "";
        double kw;
        /** Se mandó encender y aún no se ha apagado. */
        boolean on;
        /** Para esta parada ya no se hace nada más. */
        boolean done;
        /** La llegada prevista al encender y la de ahora (ms de pared). */
        long etaMs;
        long etaNowMs;
        long lastSendMs;
        /** Desde cuándo no está la parada en el plan (0: está). */
        long goneSinceMs;
        int offTries;
        /** Se tocó «Cancelar»: si el apagado falló, se reintenta aunque la parada siga valiendo. */
        boolean cancelled;
        /** El coche que contestó que no tiene la función («…1234»), o "". */
        String unsupportedCar = "";

        static State load(Context ctx) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            State s = new State();
            s.key = sp.getString("key", "");
            s.name = sp.getString("name", "");
            s.kw = sp.getFloat("kw", 0);
            s.on = sp.getBoolean("on", false);
            s.done = sp.getBoolean("done", false);
            s.etaMs = sp.getLong("eta", 0);
            s.etaNowMs = sp.getLong("eta_now", 0);
            s.lastSendMs = sp.getLong("last_send", 0);
            s.goneSinceMs = sp.getLong("gone_since", 0);
            s.offTries = sp.getInt("off_tries", 0);
            s.cancelled = sp.getBoolean("cancelled", false);
            s.unsupportedCar = sp.getString("unsupported_car", "");
            return s;
        }

        void save(Context ctx) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", key).putString("name", name)
                    .putFloat("kw", (float) kw).putBoolean("on", on).putBoolean("done", done).putLong("eta", etaMs)
                    .putLong("eta_now", etaNowMs).putLong("last_send", lastSendMs).putLong("gone_since", goneSinceMs)
                    .putInt("off_tries", offTries).putBoolean("cancelled", cancelled).putString("unsupported_car", unsupportedCar)
                    // commit: lo siguiente puede ser una orden al coche, y el proceso puede morir.
                    .commit();
        }

        /** Para esta parada ya está: nada encendido. */
        void finish() {
            on = false;
            done = true;
            offTries = 0;
            goneSinceMs = 0;
            cancelled = false;
        }

        /** Está a punto de llegar (o ya ha llegado) a la parada: lo que falta para la llegada de ahora. */
        boolean near(long now) {
            return etaNowMs > 0 && etaNowMs - now <= MIN_LEAD_MIN * 60_000;
        }
    }

    private BatteryHeat() {
    }

    // ------------------------------------------------------------------ lo puro

    /** Puro: ¿es de carga rápida? De continua (no solo enchufes de alterna) y con FAST_KW o más (0: no se sabe, no). */
    static boolean fast(RoutePlanner.Charger c) {
        return c != null && !c.acOnly && c.maxKw >= ChargePlanner.FAST_KW;
    }

    /**
     * Puro: la próxima parada rápida del plan (su índice en cp.stops), o -1. Cuenta también la de «aquí» (hasta HERE_KM
     * por detrás): al llegar a ella el plan ya no la da como próxima, y no por eso se apaga.
     */
    static int nextFast(ChargePlanner.Result cp) {
        if (cp == null) return -1;
        for (int i = 0; i < cp.stops.size(); i++) {
            ChargePlanner.Stop s = cp.stops.get(i);
            if (s.km < cp.fromKm - ChargePlanner.HERE_KM) continue;
            if (fast(s.charger)) return i;
        }
        return -1;
    }

    /**
     * Puro: minutos hasta la parada idx del plan: lo que se tarda en llegar según los segundos de cada tramo de la ruta
     * (los de Google con tráfico, si los había) más lo que se para en las paradas de antes (cargar y STOP_OVERHEAD_MIN).
     * Atascado, la ruta no avanza y la llegada se va retrasando sola. NaN sin tiempos.
     */
    static double minutesTo(double[] km, double[] segS, ChargePlanner.Result cp, int idx) {
        if (cp == null || idx < 0 || idx >= cp.stops.size()) return Double.NaN;
        ChargePlanner.Stop s = cp.stops.get(idx);
        double sec = driveSeconds(km, segS, cp.fromKm, s.km);
        if (Double.isNaN(sec)) return Double.NaN;
        for (int i = 0; i < idx; i++) {
            ChargePlanner.Stop b = cp.stops.get(i);
            if (b.km > cp.fromKm + ChargePlanner.HERE_KM) sec += (b.minutes + ChargePlanner.STOP_OVERHEAD_MIN) * 60;
        }
        return sec / 60;
    }

    /** Puro: segundos de conducción entre dos km de la ruta (segS[i]: del punto i-1 al i), o NaN sin tiempos. */
    static double driveSeconds(double[] km, double[] segS, double fromKm, double toKm) {
        if (km == null || segS == null || km.length < 2 || segS.length != km.length) return Double.NaN;
        if (toKm <= fromKm) return 0;
        return timeAt(km, segS, toKm) - timeAt(km, segS, fromKm);
    }

    private static double timeAt(double[] km, double[] segS, double x) {
        if (x <= km[0]) return 0;
        double t = 0;
        for (int i = 1; i < km.length; i++) {
            double seg = Math.max(0, segS[i]);
            if (x >= km[i]) {
                t += seg;
                continue;
            }
            double len = km[i] - km[i - 1];
            return t + (len > 0 ? seg * (x - km[i - 1]) / len : 0);
        }
        return t;
    }

    /** Puro: el valor del punto de la ruta más cercano al km x (la temperatura prevista a la hora de paso), o NaN. */
    static double valueAt(double[] km, double[] v, double x) {
        if (km == null || v == null || km.length == 0 || v.length != km.length) return Double.NaN;
        int best = 0;
        for (int i = 1; i < km.length; i++) if (Math.abs(km[i] - x) < Math.abs(km[best] - x)) best = i;
        return v[best];
    }

    /** Puro: ¿la nube dice que este coche no tiene la orden? («No such permission», código 40.) */
    static boolean unsupported(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof LeapApi.ApiException) {
                LeapApi.ApiException a = (LeapApi.ApiException) c;
                if (a.apiCode == 40 || a.serverMessage.toLowerCase(Locale.ROOT).contains("no such permission")) return true;
            }
        }
        return false;
    }

    /**
     * Puro: la orden pudo llegar al coche aunque falló (se agotó el tiempo esperando la respuesta, no al conectar): se da
     * por encendida, para apagarla luego si hace falta.
     */
    static boolean maybeSent(Throwable e) {
        if (LeapApi.notSent(e)) return false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    /** Puro: qué toca ahora con lo que se sabe y lo guardado. No manda nada. */
    static Step decide(Input in, State st, long now) {
        boolean gap = st.lastSendMs > 0 && now >= st.lastSendMs && now - st.lastSendMs < SEND_GAP_MS;
        if (st.on) {
            // Enchufado a la carga rápida: ya está, el coche sigue con lo suyo.
            if (in.dcCharging) return new Step(Action.NONE, W_PLUGGED);
            if (gap) return new Step(Action.NONE, W_GAP);
            if (st.cancelled) return new Step(Action.OFF, W_USER);
            if (!in.enabled) return new Step(Action.OFF, W_DISABLED);
            if (!in.trip) return st.near(now) ? new Step(Action.NONE, W_ARRIVED) : new Step(Action.OFF, W_NO_TRIP);
            if (in.stopKey == null || !in.stopKey.equals(st.key)) {
                if (st.goneSinceMs > 0 && now - st.goneSinceMs >= GONE_GRACE_MS) return new Step(Action.OFF, W_GONE);
                return new Step(Action.NONE, W_GONE_WAIT);
            }
            long late = Math.round(LATE_MIN * 60_000);
            if (now > st.etaMs + late) return new Step(Action.OFF, W_LATE);
            if (!Double.isNaN(in.minutes) && now + Math.round(in.minutes * 60_000) > st.etaMs + late) {
                return new Step(Action.OFF, W_DELAY);
            }
            return new Step(Action.NONE, W_HEATING);
        }
        if (in.unsupported) return new Step(Action.NONE, W_UNSUPPORTED);
        if (!in.enabled) return new Step(Action.NONE, W_DISABLED);
        if (!in.account) return new Step(Action.NONE, W_NO_ACCOUNT);
        if (!in.pin) return new Step(Action.NONE, W_NO_PIN);
        if (!in.trip) return new Step(Action.NONE, W_NO_TRIP);
        if (in.stopKey == null) return new Step(Action.NONE, W_NO_FAST_STOP);
        if (st.done && in.stopKey.equals(st.key)) return new Step(Action.NONE, W_DONE);
        if (Double.isNaN(in.minutes)) return new Step(Action.NONE, W_NO_ETA);
        if (in.minutes > LEAD_MIN) return new Step(Action.NONE, W_EARLY);
        if (in.minutes < MIN_LEAD_MIN) return new Step(Action.NONE, W_TOO_CLOSE);
        if (Double.isNaN(in.outsideC)) return new Step(Action.NONE, W_NO_TEMP);
        if (in.outsideC >= COLD_C) return new Step(Action.NONE, W_WARM_OUTSIDE);
        if (!Double.isNaN(in.arrivePct) && in.arrivePct < MIN_ARRIVE_PCT) return new Step(Action.NONE, W_LOW_ARRIVAL);
        if (in.dcCharging) return new Step(Action.NONE, W_CHARGING);
        if (Boolean.TRUE.equals(in.heating())) return new Step(Action.NONE, W_ALREADY_HEATING);
        if (!Double.isNaN(in.batteryC) && in.batteryC >= WARM_BATTERY_C) return new Step(Action.NONE, W_BATTERY_WARM);
        if (gap) return new Step(Action.NONE, W_GAP);
        return new Step(Action.ON, W_SEND);
    }

    // ------------------------------------------------------------------ el motor

    /** El último valor de la señal 1186 apuntado en el registro (solo en memoria). */
    private static Integer loggedThermal;
    private static boolean thermalLogged;

    /**
     * Una vuelta: decide y, si toca y allowSend, manda (con red: fuera del hilo de la interfaz). Sin allowSend, si toca
     * mandar no hace nada (lo hará la vuelta que se lance en otro hilo). Devuelve lo decidido.
     */
    static synchronized Step tick(Context ctx, Input in, long now, Car car, boolean allowSend) {
        State st = State.load(ctx);
        Step s = decide(in, st, now);
        if (s.action != Action.NONE && !allowSend) return s;
        boolean dirty = false;
        if (st.on) {
            logThermal(in);
            boolean here = in.stopKey != null && in.stopKey.equals(st.key);
            if (here && st.goneSinceMs != 0) {
                st.goneSinceMs = 0;
                dirty = true;
            } else if (!here && st.goneSinceMs == 0 && W_GONE_WAIT.equals(s.why)) {
                st.goneSinceMs = now;
                dirty = true;
                L.i("calentar batería: la parada rápida ya no está en el plan; se apaga si no vuelve en "
                        + GONE_GRACE_MS / 60_000 + " min");
            }
            if (here && !Double.isNaN(in.minutes)) {
                long eta = now + Math.round(in.minutes * 60_000);
                if (Math.abs(eta - st.etaNowMs) >= 60_000) {
                    st.etaNowMs = eta;
                    dirty = true;
                }
            }
        }
        switch (s.why) {
            case W_PLUGGED:
                st.finish();
                st.save(ctx);
                L.i("calentar batería: enchufado a la carga rápida, ya no hace falta nada más; señal 1186 "
                        + (in.thermal == null ? "sin dato" : "= " + in.thermal + (in.thermal != 0 ? " (sigue activa)" : " (apagada)")));
                return s;
            case W_ARRIVED:
                st.finish();
                st.save(ctx);
                L.i("calentar batería: el viaje se acaba junto a la parada rápida; se deja calentando (el coche lo apaga solo)");
                return s;
            default:
                break;
        }
        if (s.action == Action.ON) {
            sendOn(ctx, st, in, now, car);
        } else if (s.action == Action.OFF) {
            sendOff(ctx, st, s.why, now, car);
        } else if (dirty) {
            st.save(ctx);
        }
        return s;
    }

    private static void logThermal(Input in) {
        if (thermalLogged && java.util.Objects.equals(loggedThermal, in.thermal)) return;
        thermalLogged = true;
        loggedThermal = in.thermal;
        L.i("calentar batería: señal 1186 (batteryThermalRequest) " + (in.thermal == null ? "sin dato" : "= " + in.thermal)
                + (Double.isNaN(in.batteryC) ? "" : String.format(Locale.US, ", batería a %.0f °C", in.batteryC)));
    }

    private static void sendOn(Context ctx, State st, Input in, long now, Car car) {
        st.key = in.stopKey;
        st.name = in.stopName == null ? "" : in.stopName;
        st.kw = in.stopKw;
        st.on = false;
        st.done = false;
        st.offTries = 0;
        st.goneSinceMs = 0;
        st.cancelled = false;
        st.lastSendMs = now;
        st.etaMs = now + Math.round(in.minutes * 60_000);
        st.etaNowMs = st.etaMs;
        // Guardado ANTES de mandar: si el proceso muere a medias, la siguiente vuelta sabe qué hay.
        st.save(ctx);
        thermalLogged = false;
        String what = String.format(Locale.US, "parada rápida de %.0f kW a %.0f min, llegando con %.0f %%; fuera %.0f °C (%s), batería %s, "
                        + "señal 1186 %s", in.stopKw, in.minutes, in.arrivePct, in.outsideC, in.tempSource,
                Double.isNaN(in.batteryC) ? "sin dato" : String.format(Locale.US, "a %.0f °C", in.batteryC),
                in.thermal == null ? "sin dato" : "= " + in.thermal);
        int mins = (int) Math.max(1, Math.round(in.minutes));
        try {
            boolean ok = car.send(true);
            st.on = true;
            L.i("calentar batería: encendida " + (ok ? "(confirmada por el coche)" : "(sin confirmar)") + " para una " + what);
            st.save(ctx);
            Notice n = notice;
            n.card(ctx, Str.get(R.string.hql_bh_title), Str.get(R.string.hql_bh_card, st.name, mins), true);
            n.voice(ctx, Str.get(R.string.hql_bh_voice, st.name, mins));
            return;
        } catch (Exception e) {
            if (unsupported(e)) {
                st.finish();
                st.unsupportedCar = carId(ctx);
                st.save(ctx);
                L.w("calentar batería: el coche no tiene la función (" + Http.safeError(e) + "); queda apagada para este coche");
                notice.card(ctx, Str.get(R.string.hql_bh_title), Str.get(R.string.hql_bh_unsupported), false);
                return;
            }
            if (maybeSent(e)) {
                // Pudo llegar: se da por encendida (si luego hace falta, se apaga). Sin aviso: no se sabe.
                st.on = true;
                st.save(ctx);
                L.w("calentar batería: sin respuesta de la nube (" + Http.safeError(e) + "); puede estar encendida, para una " + what);
                return;
            }
            st.finish();
            st.save(ctx);
            L.w("calentar batería: no se pudo encender (" + Http.safeError(e) + "); se deja para esta parada");
            notice.card(ctx, Str.get(R.string.hql_bh_title), Str.get(R.string.hql_bh_failed, st.name), false);
        }
    }

    private static void sendOff(Context ctx, State st, String why, long now, Car car) {
        st.lastSendMs = now;
        st.save(ctx);
        try {
            boolean ok = car.send(false);
            st.finish();
            st.save(ctx);
            L.i("calentar batería: apagada " + (ok ? "(confirmada por el coche)" : "(sin confirmar)") + ": " + whyLog(why));
            int text = W_LATE.equals(why) || W_DELAY.equals(why) ? R.string.hql_bh_off_late
                    : W_GONE.equals(why) ? R.string.hql_bh_off_gone : 0;
            if (text != 0) notice.card(ctx, Str.get(R.string.hql_bh_title), Str.get(text, st.name), false);
        } catch (Exception e) {
            st.offTries++;
            if (st.offTries >= OFF_TRIES || unsupported(e)) {
                st.finish();
                st.save(ctx);
                L.w("calentar batería: no se pudo apagar (" + Http.safeError(e) + ") tras " + OFF_TRIES
                        + " intentos; se deja (el coche deja de calentar solo)");
            } else {
                st.save(ctx);
                L.w("calentar batería: no se pudo apagar (" + Http.safeError(e) + "); otra vez en un minuto");
            }
        }
    }

    static String whyLog(String why) {
        switch (why) {
            case W_DISABLED:
                return "función apagada en los ajustes";
            case W_NO_TRIP:
                return "el viaje ha terminado";
            case W_GONE:
                return "la parada ya no está en el plan";
            case W_LATE:
                return "pasó la hora prevista + " + Math.round(LATE_MIN) + " min sin llegar";
            case W_DELAY:
                return "la llegada se retrasa " + Math.round(LATE_MIN) + " min o más";
            case W_USER:
                return "«Cancelar»";
            default:
                return why;
        }
    }

    /** El coche elegido («…1234»; nunca al registro), para apuntar que no tiene la función. */
    private static String carId(Context ctx) {
        String t = CarCloudSession.vinTail(ctx);
        return t.isEmpty() ? "?" : t;
    }

    /**
     * «Cancelar» (la tarjeta del coche): se apaga ya, sin esperar al minuto entre envíos, y para esta parada no se vuelve
     * a encender. Con red: fuera del hilo de la interfaz.
     */
    static synchronized void cancel(Context ctx, Car car, long now) {
        State st = State.load(ctx);
        if (!st.on) {
            if (!st.done && !st.key.isEmpty()) {
                st.finish();
                st.save(ctx);
            }
            return;
        }
        L.i("calentar batería: «Cancelar»");
        st.cancelled = true;
        sendOff(ctx, st, W_USER, now, car);
        // Si no se pudo, se reintenta en las vueltas del bucle (sigue «on»), pero ya no se vuelve a encender.
    }

    static void cancelAsync(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Thread(() -> {
            try {
                cancel(app, cloud(app), System.currentTimeMillis());
            } catch (RuntimeException e) {
                L.w("calentar batería: «Cancelar» fallido (" + e.getClass().getSimpleName() + ")");
            }
        }, "hql-battery-heat-cancel").start();
    }

    /**
     * La ruta se para (el coche se desconecta): si estaba encendida y no se está llegando a la parada, se apaga; junto a
     * ella, se deja (se va a cargar). Con red: fuera del hilo de la interfaz.
     */
    static synchronized void tripStopped(Context ctx, Car car, long now) {
        State st = State.load(ctx);
        if (!st.on) return;
        if (st.near(now)) {
            st.finish();
            st.save(ctx);
            L.i("calentar batería: el coche se desconecta junto a la parada rápida; se deja calentando");
            return;
        }
        sendOff(ctx, st, W_NO_TRIP, now, car);
    }

    // ------------------------------------------------------------------ entradas reales

    /** El coche de verdad: la orden 160 por la nube con el PIN guardado (CarCloudSession.command). */
    static Car cloud(Context app) {
        return on -> CarCloudSession.command(app, new CarCommand(on ? CarCommand.Kind.BATTERY_HEAT_ON : CarCommand.Kind.BATTERY_HEAT_OFF));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Lo que se sabe ahora para decidir: el ajuste, la cuenta, la próxima parada rápida del plan (p y cp; null sin
     * viaje), la temperatura de fuera (outsideC: la del tiempo de ahora, la del panel; sin ella, la prevista en la
     * parada a la hora de paso) y la última lectura del coche (cs, si no tiene más de CAR_MAX_AGE_MS).
     */
    static Input input(Context ctx, RoutePlanner.Plan p, ChargePlanner.Result cp, CarCloud.Snapshot cs, double outsideC, long now) {
        Input in = new Input();
        Config cfg = new Config(ctx);
        in.enabled = cfg.batteryHeat();
        CarCloudStore store = new CarCloudStore(ctx);
        in.account = store.hasSession() && store.reloginReason() == null;
        in.pin = store.hasPin();
        String bad = State.load(ctx).unsupportedCar;
        in.unsupported = !bad.isEmpty() && bad.equals(carId(ctx));
        in.trip = p != null && p.n >= 2 && p.arrivedAtMs == 0;
        int idx = in.trip ? nextFast(cp) : -1;
        if (idx >= 0) {
            ChargePlanner.Stop s = cp.stops.get(idx);
            in.stopKey = ChargerMarks.key(s.charger);
            in.stopName = RoutePlanner.chargerName(s.charger);
            in.stopKw = s.charger.maxKw;
            in.minutes = minutesTo(p.km, p.segS, cp, idx);
            in.arrivePct = s.arrivePct;
            if (!Double.isNaN(outsideC)) {
                in.outsideC = outsideC;
                in.tempSource = "el tiempo de ahora";
            } else {
                in.outsideC = valueAt(p.km, p.temp, s.km);
                in.tempSource = "la previsión en la parada";
            }
        } else {
            in.outsideC = outsideC;
            in.tempSource = "el tiempo de ahora";
        }
        if (cs != null && cs.hasData() && !cs.demo && cs.ageMs(now) <= CAR_MAX_AGE_MS) {
            in.thermal = cs.status.batteryThermalRequest;
            in.batteryC = cs.status.minBatteryTempC;
            in.dcCharging = cs.status.dcPlugged();
        }
        return in;
    }

    /**
     * Desde el bucle de la ruta (cada 5 s, un viaje de verdad: ni demostración ni coche virtual): decide y, si toca
     * mandar, lo hace en otro hilo (una orden tarda y no debe parar la ruta).
     */
    static void onRoute(Context ctx, RoutePlanner.Plan p, ChargePlanner.Result cp) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // Sin el ajuste y sin nada encendido, ni se mira (lo de siempre: nada).
        if (!new Config(app).batteryHeat() && !State.load(app).on) return;
        if (busy.get()) return;
        long now = System.currentTimeMillis();
        Input in = input(app, p, cp, CarCloud.car(), CarSensors.outsideTempC(), now);
        Car car = cloud(app);
        if (tick(app, in, now, car, false).action == Action.NONE) return;
        if (!busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                tick(app, in, now, car, true);
            } catch (RuntimeException e) {
                L.w("calentar batería: vuelta fallida (" + e.getClass().getSimpleName() + ")");
            } finally {
                busy.set(false);
            }
        }, "hql-battery-heat").start();
    }

    /** La ruta se para (RoutePlanner.stop): en otro hilo. */
    static void onRouteStopped(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (!State.load(app).on) return;
        new Thread(() -> {
            try {
                tripStopped(app, cloud(app), System.currentTimeMillis());
            } catch (RuntimeException e) {
                L.w("calentar batería: al parar la ruta (" + e.getClass().getSimpleName() + ")");
            }
        }, "hql-battery-heat-stop").start();
    }

    // ------------------------------------------------------------------ ajustes y laboratorio

    /** Lo que falta para que funcione (para los ajustes), o "" si nada. */
    static String missing(Context ctx) {
        CarCloudStore store = new CarCloudStore(ctx);
        String bad = State.load(ctx).unsupportedCar;
        if (!bad.isEmpty() && bad.equals(carId(ctx))) return Str.get(R.string.hql_bh_set_unsupported);
        if (!store.hasSession() || store.reloginReason() != null || !store.hasPin()) return Str.get(R.string.hql_bh_set_needs);
        return "";
    }

    /** Cambió el ajuste: si se apaga con la batería calentándose, la próxima vuelta del bucle manda el apagado. */
    static void settingChanged(Context ctx, boolean on) {
        L.i("calentar batería para el cargador: " + (on ? "activada" : "apagada")
                + (!on && State.load(ctx).on ? " (estaba calentando: se apaga en la próxima vuelta de la ruta)" : ""));
    }

    /**
     * Variante de pruebas («calentar»): el ajuste, lo guardado, la próxima parada rápida, los minutos a la llegada, la
     * temperatura, la señal 1186 y lo que haría ahora. No manda nada ni pide nada a la red.
     */
    static String lab(Context ctx) {
        long now = System.currentTimeMillis();
        RoutePlanner r = RoutePlanner.get();
        RoutePlanner.Plan p = r == null ? null : r.plan();
        ChargePlanner.Result cp = r == null ? null : r.lastChargePlan();
        Input in = input(ctx, p, cp, CarCloud.car(), CarSensors.outsideTempC(), now);
        State st = State.load(ctx);
        StringBuilder b = new StringBuilder();
        b.append(in.enabled ? "activada" : "apagada").append(" · cuenta ").append(in.account ? "sí" : "no").append(" · PIN ")
                .append(in.pin ? "sí" : "no");
        if (in.unsupported) b.append(" · el coche no tiene la función");
        b.append(" · ").append(r == null ? "sin ruta (sin coche)" : !in.trip ? "sin viaje en curso" : "viaje en curso");
        if (RoutePlanner.testMode()) b.append(" (coche virtual: solo se cuenta, no se manda)");
        if (in.stopKey != null) {
            b.append(String.format(Locale.US, " · próxima parada rápida %.0f kW, a %s, llegando con %.0f %%", in.stopKw,
                    Double.isNaN(in.minutes) ? "? min" : String.format(Locale.US, "%.0f min", in.minutes), in.arrivePct));
        } else if (in.trip) {
            b.append(" · sin parada rápida en el plan");
        }
        b.append(" · fuera ").append(Double.isNaN(in.outsideC) ? "sin dato" : String.format(Locale.US, "%.1f °C (%s)", in.outsideC, in.tempSource));
        b.append(" · señal 1186 ").append(in.thermal == null ? "sin dato reciente" : "= " + in.thermal);
        if (!Double.isNaN(in.batteryC)) b.append(String.format(Locale.US, " · batería %.0f °C", in.batteryC));
        if (in.dcCharging) b.append(" · enchufado en CC");
        b.append(" · guardado: ").append(st.on ? "encendida" : st.done ? "hecho" : "nada");
        if (st.on && st.etaMs > 0) b.append(String.format(Locale.US, " (llegada prevista en %d min)", (st.etaNowMs - now) / 60_000));
        b.append(" · ahora: ").append(decide(in, st, now));
        return b.toString();
    }

    /** Para las pruebas: como recién instalada. */
    static void resetForTest(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        loggedThermal = null;
        thermalLogged = false;
        notice = CAR_NOTICE;
    }
}
