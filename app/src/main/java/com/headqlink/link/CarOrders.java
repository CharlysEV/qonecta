package com.headqlink.link;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.andrerinas.openheadunit.R;

/**
 * Órdenes al coche desde el widget de Qonecta+ (su versión 4x3, LinkWidgetViews): el ajuste «Órdenes desde el widget»
 * (apagado de serie; con el PIN del coche guardado), el resultado de la última orden y el toque de cada botón, que abre
 * CarControlActivity (confirmación, voz y resultado).
 */
final class CarOrders {
    /** Config: el ajuste y el resultado de la última orden. */
    static final String K_ENABLED = "car_orders";
    static final String K_LAST = "car_orders_last";
    private static final int RC_BASE = 7800;
    /** Config: el clima encendido desde el widget, «MODO,hora de pared» («» apagado). */
    static final String K_CLIMATE = "car_climate_on";
    /** El widget enseña el clima encendido como mucho esto tras la orden (el coche lo apaga solo pasado un rato). */
    static final long CLIMATE_SHOW_MS = 30 * 60_000L;

    /** Tras una orden de clima que ha llegado al coche: encendido (y en qué modo) o apagado. */
    static void noteClimate(Context c, CarCommand cmd) {
        String v = cmd.kind == CarCommand.Kind.CLIMATE_OFF ? ""
                : (cmd.kind == CarCommand.Kind.DEFROST ? CarCommand.Mode.HEAT : cmd.mode).name() + "," + System.currentTimeMillis();
        new Config(c).putString(K_CLIMATE, v);
    }

    /** El modo del clima encendido desde el widget, o null (apagado o hace más de CLIMATE_SHOW_MS). */
    static CarCommand.Mode climate(Context c) {
        return climate(new Config(c).getString(K_CLIMATE), System.currentTimeMillis());
    }

    /** Puro: lo mismo con lo guardado. */
    static CarCommand.Mode climate(String stored, long nowMs) {
        if (stored == null || stored.isEmpty()) return null;
        String[] f = stored.split(",");
        try {
            if (f.length != 2 || nowMs - Long.parseLong(f[1]) > CLIMATE_SHOW_MS) return null;
            return CarCommand.Mode.valueOf(f[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private CarOrders() {
    }

    /**
     * ¿Hay que volver a entrar en la cuenta de Leapmotor? (la nube rechazó la sesión o no se puede abrir la guardada,
     * CarCloudSession). Con el ajuste de las órdenes puesto, el widget lo dice en lugar de un error al tocar.
     */
    static boolean sessionClosed(Context c) {
        return new Config(c).getBool(K_ENABLED) && CarCloudStore.supported() && new CarCloudStore(c).reloginReason() != null;
    }

    /** Se ha vuelto a entrar: fuera el resultado de la última orden (sería el «sesión cerrada» de antes). */
    static void sessionRestored(Context c) {
        Config cfg = new Config(c);
        String last = cfg.getString(K_LAST);
        if (last == null || last.isEmpty()) return;
        cfg.putString(K_LAST, "");
        refresh(c);
    }

    /** ¿Se pueden mandar órdenes? (el ajuste y el PIN guardado) */
    static boolean enabled(Context c) {
        return new Config(c).getBool(K_ENABLED) && new CarCloudStore(c).hasPin();
    }

    /**
     * ¿«Cable» (desbloquear el cable de carga) en lugar de «Localizar»? Con el cable enchufado según la última lectura
     * de la nube, cargando o ya cargado (29-10, LPMFF: se quiere al acabar la carga, con el conector aún bloqueado).
     */
    static boolean cableShown(Context c) {
        CarCloud.Snapshot s = CarCloud.car();
        if (s == null || !s.hasData() || s.status == null) return false;
        return cableShown(s.status.pluggedIn(), s.carType.isEmpty() ? new CarCloudStore(c).carType() : s.carType);
    }

    /** Puro: enchufado y no un T03 (no tiene la orden). */
    static boolean cableShown(boolean plugged, String carType) {
        return plugged && !CarCloudStore.isT03(carType);
    }

    /** Repinta el widget de Qonecta+ (tras una orden o al cambiar el ajuste). */
    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, LinkWidget.class));
        if (ids != null && ids.length > 0) WidgetUpdater.render(c, m, ids, WidgetUpdater.glance(c));
    }

    /** El toque de un botón («lock», «unlock», «trunk», «climate», «find», «cable» o la voz). */
    static PendingIntent tap(Context c, String what, int code) {
        Intent i = new Intent(c, CarControlActivity.class).putExtra(CarControlActivity.EXTRA_WHAT, what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(c, RC_BASE + code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Puro: «350 m», «1,2 km» (a cuánto está el coche, en «Localizar»). */
    static String distance(double m) {
        if (m < 1000) return Math.max(10, Math.round(m / 10.0) * 10) + " m";
        return String.format(java.util.Locale.getDefault(), "%.1f km", m / 1000.0);
    }

    // ------------------------------------------------------------------ el botón de abrir/cerrar

    /** El cierre del coche, según lo último que se sabe de él. */
    enum Lock {
        LOCKED,
        UNLOCKED,
        UNKNOWN
    }

    /**
     * Lo de este móvil y del momento (no viaja en la copia de los ajustes): el último cierre conocido, «1|0,hora del
     * dato,hora en que se supo» (ms de pared), de una lectura de la nube o de una orden que el coche confirmó.
     */
    private static final String LOCK_PREFS = "car_lock";
    private static final String K_LOCK = "known";
    /**
     * Sin noticias del coche (ni lectura ni orden) en este tiempo, el cierre guardado ya no vale y vuelven los dos
     * botones: alguien ha podido abrirlo con la llave, y con un solo botón equivocado no se podría mandar la otra orden.
     * El dato del coche sí puede ser más viejo (aparcado desde ayer): cuenta cuándo se miró por última vez.
     */
    static final long LOCK_KNOWN_MS = 6 * 3600_000L;
    /** Verde de «Cerrado con llave» y rojo de aviso (los de la app: hql_ok y hql_error). */
    static final int LOCKED_COLOR = 0xFF4CFF9F;
    static final int UNLOCKED_COLOR = 0xFFFF6B7A;
    /** «Tenue»: el fondo del botón, con el color del estado al 18 %. */
    static final int TINT_ALPHA = 0x2E;

    /** La orden de cerrar o abrir que se está mandando ahora («lock», «unlock») o null. */
    private static volatile String sending;
    private static final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** El coche de las órdenes de cerrar y abrir: la nube de verdad (CarCloudSession) o el de las pruebas. */
    interface Car {
        /** true si el coche confirma la orden; false si no contesta a tiempo (puede aplicarse igualmente). */
        boolean command(CarCommand c) throws Exception;

        /** Otra lectura del coche tras una orden sin confirmar: cerrado, abierto o null si no lo dice. */
        Boolean readLocked() throws Exception;

        void pause(long ms);
    }

    /** Solo pruebas: otro coche (null: el de verdad) y dónde corre la comprobación de una orden sin confirmar. */
    static Car carForTest;
    static java.util.concurrent.Executor verifier = r -> new Thread(r, "car-lock-verify").start();

    private static Car car(Context app) {
        Car t = carForTest;
        if (t != null) return t;
        AutoLockJob.CloudCar cloud = new AutoLockJob.CloudCar(app);
        return new Car() {
            @Override
            public boolean command(CarCommand cmd) throws Exception {
                return CarCloudSession.command(app, cmd);
            }

            @Override
            public Boolean readLocked() throws Exception {
                return cloud.read().locked;
            }

            @Override
            public void pause(long ms) {
                cloud.pause(ms);
            }
        };
    }

    /**
     * Lo que enseña el botón de abrir/cerrar de «Mi coche» y del widget. Con el cierre conocido, un solo botón con la
     * orden contraria: coche cerrado, «Abrir» con el candado cerrado en verde (está seguro); abierto, «Cerrar» con el
     * candado abierto en rojo (aviso). Sin saberlo, los dos botones de siempre, sin color.
     */
    static final class LockButton {
        static final LockButton UNKNOWN = new LockButton(Lock.UNKNOWN, null);
        final Lock state;
        /** La orden que se está mandando («lock», «unlock») o null: su botón enseña el progreso. */
        final String sending;

        LockButton(Lock state, String sending) {
            this.state = state == null ? Lock.UNKNOWN : state;
            this.sending = sending;
        }

        /** ¿Un solo botón? (si no, «Cerrar» y «Abrir» como siempre) */
        boolean single() {
            return state != Lock.UNKNOWN;
        }

        /** La orden del botón único: la contraria al estado. */
        String what() {
            return state == Lock.LOCKED ? "unlock" : "lock";
        }

        int label() {
            return state == Lock.LOCKED ? R.string.hql_cw_unlock : R.string.hql_cw_lock;
        }

        /** El icono dice cómo está el coche (candado cerrado o abierto); la etiqueta, lo que hace el botón. */
        int icon() {
            return state == Lock.LOCKED ? R.drawable.hql_ic_cw_lock : R.drawable.hql_ic_cw_unlock;
        }

        int color() {
            return state == Lock.LOCKED ? LOCKED_COLOR : UNLOCKED_COLOR;
        }

        /** El color del estado al 18 %, para el fondo. */
        int tint() {
            return (color() & 0x00FFFFFF) | (TINT_ALPHA << 24);
        }

        /** El fondo del botón en el widget (RemoteViews no deja pintarlo de un color: un dibujo por estado). */
        int widgetBackground() {
            return state == Lock.LOCKED ? R.drawable.hql_w_car_locked : R.drawable.hql_w_car_unlocked;
        }

        /** Para la accesibilidad: el estado y la acción («Coche cerrado. Abrir»). */
        int description() {
            return state == Lock.LOCKED ? R.string.hql_cw_cd_unlock : R.string.hql_cw_cd_lock;
        }

        /** ¿Se está mandando la orden de este botón? (el único, o el de «lock» / «unlock» con los dos a la vista) */
        boolean busy(String what) {
            return sending != null && (single() || sending.equals(what));
        }

        /** Lo que se ve: si no cambia, no hay que repintar ("" con los dos botones quietos, como antes). */
        String key() {
            return (state == Lock.UNKNOWN ? "" : state.name()) + (sending == null ? "" : "…" + sending);
        }
    }

    /** Puro: el cierre guardado a esta hora; UNKNOWN si no hay, no se entiende o hace mucho que no se sabe nada. */
    static Lock lockState(String stored, long nowMs) {
        long[] f = lockFields(stored);
        if (f == null || nowMs - f[2] > LOCK_KNOWN_MS) return Lock.UNKNOWN;
        return f[0] == 1 ? Lock.LOCKED : Lock.UNLOCKED;
    }

    /**
     * Puro: lo que se guarda al saber que el coche está cerrado (o abierto) con un dato de dataMs, sabido en nowMs. Un
     * dato más viejo que el guardado no lo pisa (la nube aún no ha visto la orden que el coche acaba de confirmar): si
     * dice lo mismo, solo se apunta que se ha vuelto a mirar; si dice otra cosa, no cuenta mientras lo guardado valga.
     */
    static String lockNoted(String stored, boolean locked, long dataMs, long nowMs) {
        long[] f = lockFields(stored);
        if (f != null && dataMs < f[1] && nowMs - f[2] <= LOCK_KNOWN_MS) {
            return (f[0] == 1) == locked ? f[0] + "," + f[1] + "," + nowMs : stored;
        }
        return (locked ? "1" : "0") + "," + dataMs + "," + nowMs;
    }

    /** {cerrado (1/0), hora del dato, hora en que se supo}, o null. */
    private static long[] lockFields(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        String[] f = stored.split(",");
        try {
            if (f.length != 3 || !(f[0].equals("1") || f[0].equals("0"))) return null;
            return new long[]{Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static android.content.SharedPreferences lockPrefs(Context c) {
        return c.getSharedPreferences(LOCK_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * El botón ahora. Sin las órdenes activadas o con la sesión de Leapmotor cerrada, los dos botones (la fila va
     * apagada y el toque lleva a arreglarlo).
     */
    static LockButton lockButton(Context c) {
        if (!enabled(c) || sessionClosed(c)) return LockButton.UNKNOWN;
        return new LockButton(lockState(lockPrefs(c).getString(K_LOCK, ""), System.currentTimeMillis()), sending);
    }

    /**
     * ¿Cerrado con llave? para el texto de «Mi coche»: lo guardado (que lleva también la última orden confirmada, sin
     * esperar a la nube) o, si no vale, lo que diga la foto de la nube (cloud; puede ser null).
     */
    static Boolean locked(Context c, Boolean cloud) {
        Lock l = lockState(lockPrefs(c).getString(K_LOCK, ""), System.currentTimeMillis());
        return l == Lock.UNKNOWN ? cloud : Boolean.valueOf(l == Lock.LOCKED);
    }

    /** El coche está cerrado (o abierto) con un dato de dataMs: una orden confirmada (ahora) o una lectura de la nube. */
    static void noteLock(Context c, boolean locked, long dataMs) {
        android.content.SharedPreferences p = lockPrefs(c);
        String before = p.getString(K_LOCK, "");
        long now = System.currentTimeMillis();
        String after = lockNoted(before, locked, dataMs, now);
        if (after.equals(before)) return;
        p.edit().putString(K_LOCK, after).apply();
        if (lockState(before, now) != lockState(after, now)) changed(c);
    }

    /** Una lectura de la nube (CarCloud): su cierre, si lo dice. Los datos inventados de la demostración no cuentan. */
    static void observeLock(Context c, CarCloud.Snapshot s) {
        if (c == null || s == null || s.demo || s.status == null || s.status.locked == null) return;
        noteLock(c, s.status.locked, s.dataTimeMs());
    }

    /** Otra cuenta u otro coche: lo guardado ya no vale. */
    static void forgetLock(Context c) {
        lockPrefs(c).edit().remove(K_LOCK).apply();
    }

    /**
     * Desde la comprobación de fondo (ChargeCheckJob, cada ~15 min): si el cierre guardado ya ha caducado (horas sin
     * poder leer el coche), se quita y el widget vuelve a los dos botones; nadie más lo repintaría.
     */
    static void expireLock(Context c) {
        String stored = lockPrefs(c).getString(K_LOCK, "");
        if (stored.isEmpty() || lockState(stored, System.currentTimeMillis()) != Lock.UNKNOWN) return;
        forgetLock(c);
        changed(c);
    }

    /** Avisa (en el hilo principal) cuando cambia lo que enseña el botón: «Mi coche» lo repinta. */
    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Ha cambiado lo que enseña el botón: el widget y quien escuche. */
    private static void changed(Context c) {
        try {
            refresh(c);
        } catch (RuntimeException ignored) {
            // Sin widgets, o el launcher no contesta: se verá en el siguiente repintado.
        }
        if (listeners.isEmpty()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (Runnable r : listeners) r.run();
        });
    }

    private static void sending(Context c, String what) {
        sending = what;
        changed(c);
    }

    /**
     * Manda una orden al coche (CarControlActivity, en su hilo; los fallos, como excepción). Con las de cerrar y abrir,
     * su botón enseña el progreso mientras dura: si el coche la confirma, el cierre nuevo queda apuntado sin esperar a la
     * nube; si falla, el botón vuelve a como estaba; si se queda sin confirmar (puede aplicarse igualmente), se mira otra
     * vez el coche pasado AutoLock.VERIFY_MS, en otro hilo, y el botón sigue con el progreso hasta entonces.
     */
    static boolean send(Context ctx, CarCommand cmd) throws Exception {
        Context c = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Car car = car(c);
        if (cmd.kind != CarCommand.Kind.LOCK && cmd.kind != CarCommand.Kind.UNLOCK) return car.command(cmd);
        boolean lock = cmd.kind == CarCommand.Kind.LOCK;
        sending(c, lock ? "lock" : "unlock");
        boolean ok;
        try {
            ok = car.command(cmd);
        } catch (Exception | Error e) {
            sending(c, null);
            throw e;
        }
        if (ok) {
            noteLock(c, lock, System.currentTimeMillis());
            sending(c, null);
        } else {
            verifier.execute(() -> verify(c, car, lock));
        }
        return ok;
    }

    /** Una orden de cerrar (o abrir) sin confirmar: otra lectura del coche para ver cómo quedó. */
    private static void verify(Context c, Car car, boolean lock) {
        try {
            car.pause(AutoLock.VERIFY_MS);
            Boolean now = car.readLocked();
            boolean done = now != null && now == lock;
            if (done) noteLock(c, lock, System.currentTimeMillis());
            L.i("órdenes: " + (lock ? "cerrar" : "abrir") + " sin confirmar; tras comprobarlo, "
                    + (done ? "hecho" : "el coche sigue como estaba (o no lo dice)"));
        } catch (Exception e) {
            L.w("órdenes: no se pudo comprobar el cierre (" + Http.safeError(e) + ")");
        } finally {
            sending(c, null);
        }
    }
}
