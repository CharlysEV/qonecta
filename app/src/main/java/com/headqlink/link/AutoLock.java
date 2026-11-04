package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * «Cerrar el coche al alejarme» (4-11; el T03 no se cierra solo al alejarte, el C10 sí): apagado de serie, para todos
 * los modelos y con la cuenta de Leapmotor y el PIN del coche. Cuando el móvil pierde el Bluetooth del coche
 * (CarBtReceiver: la señal de alejarse, no la caída de la sesión con el coche), espera WAIT_MS (AutoLockJob); si en ese
 * rato vuelve el Bluetooth, nada. Pasado el minuto, una lectura nueva de la nube (del tope diario) y:
 * <ul>
 *   <li>ya cerrado, encendido o en marcha: nada (una línea en el log);</li>
 *   <li>una puerta o el maletero abiertos: no cierra y avisa de cuál;</li>
 *   <li>sin dato fiable (la nube no contesta, la sesión caducó, el dato es de antes de alejarse o falta el cierre o
 *   las puertas): no cierra a ciegas y avisa de que puede seguir abierto;</li>
 *   <li>abierto, todo cerrado y dato fresco: la orden de cerrar (la del widget, con sus reintentos) y el resultado,
 *   con «Abrir» en el aviso; si falla, «Reintentar».</li>
 * </ul>
 * Una sola vez por alejamiento: se apunta antes de leer, así que un trabajo repetido no vuelve a mandar nada.
 */
final class AutoLock {
    /** Config: el ajuste (apagado de serie). */
    static final String K_ENABLED = "auto_lock";
    /** Lo que se espera tras perder el Bluetooth del coche: si vuelve antes, no se hace nada. */
    static final long WAIT_MS = 60_000;
    /**
     * El dato del coche vale si es de, como mucho, esto antes de perder el Bluetooth: el coche sube el estado al
     * apagarlo, al cerrar la puerta o al bloquearse, poco antes de que el móvil se aleje; más viejo, no dice cómo quedó.
     */
    static final long FRESH_BEFORE_MS = 5 * 60_000;
    /** Si Android retrasa el trabajo más que esto (móvil dormido, sin red), ya no se cierra: se avisa. */
    static final long LATE_MS = 10 * 60_000;
    /** Una orden sin confirmar: se mira otra vez el coche pasado esto. */
    static final long VERIFY_MS = 15_000;

    /** Lo de este móvil y del momento (no viaja en la copia de los ajustes): cuándo se perdió y qué se resolvió. */
    private static final String PREFS = "auto_lock";
    private static final String K_GONE_AT = "gone_at";
    private static final String K_DONE = "done_for";

    /** Lo que toca. */
    enum Verdict {
        /** Aún no ha pasado el minuto. */
        WAIT,
        NOTHING,
        /** Toca leer el coche. */
        READ,
        WARN_OPEN,
        WARN_UNKNOWN,
        LOCK
    }

    /** Puertas y maletero en el aviso: LeapStatus.FL…RR y BOOT. */
    static final int BOOT = 4;

    static final class Decision {
        final Verdict verdict;
        /** Por qué, para el log (sin datos personales). */
        final String why;
        /** Lo abierto (WARN_OPEN), en el orden FL, FR, RL, RR, BOOT. */
        final List<Integer> open;

        Decision(Verdict verdict, String why) {
            this(verdict, why, Collections.emptyList());
        }

        Decision(Verdict verdict, String why, List<Integer> open) {
            this.verdict = verdict;
            this.why = why;
            this.open = open;
        }
    }

    /** El coche en la nube: lo real (AutoLockJob.CloudCar) o el de las pruebas. */
    interface Car {
        /** Cuenta de Leapmotor y PIN del coche guardados. */
        boolean ready();

        /** Una lectura nueva del estado (del tope diario). */
        LeapStatus read() throws Exception;

        /** La orden de cerrar: true si el coche la confirma; false si no contesta a tiempo. */
        boolean lock() throws Exception;

        void pause(long ms);
    }

    /** Los avisos en el móvil. */
    interface Notifier {
        /** «He cerrado el coche al alejarte», con «Abrir». */
        void locked();

        /** «El coche sigue abierto: …» (open: Decision.open). */
        void stillOpen(List<Integer> open);

        /** «No he podido comprobar el coche: puede seguir abierto». */
        void unknown();

        /** «No he podido cerrar el coche» (why: el motivo, para la pantalla), con «Reintentar». */
        void lockFailed(String why);
    }

    private AutoLock() {
    }

    static boolean enabled(Context c) {
        return new Config(c).getBool(K_ENABLED);
    }

    /** ¿Puede mandar la orden? Cuenta de Leapmotor (con la lectura activada) y el PIN del coche. */
    static boolean ready(Context c) {
        return PhoneInsights.hasAccount(c) && new CarCloudStore(c).hasPin();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ decisión (pura)

    /** Puro: antes de leer. goneAtMs: cuándo se perdió el Bluetooth (0: ha vuelto o nada pendiente). */
    static Decision before(boolean enabled, boolean ready, long goneAtMs, long doneForMs, long nowMs, boolean retry) {
        if (!enabled) return new Decision(Verdict.NOTHING, "ajuste apagado");
        if (!ready) return new Decision(Verdict.NOTHING, "sin cuenta de Leapmotor o sin PIN del coche");
        if (goneAtMs <= 0) return new Decision(Verdict.NOTHING, "el Bluetooth del coche ha vuelto (o nada pendiente)");
        if (retry) return new Decision(Verdict.READ, "reintento pedido desde el aviso");
        if (doneForMs == goneAtMs) return new Decision(Verdict.NOTHING, "este alejamiento ya se resolvió");
        if (nowMs < goneAtMs + WAIT_MS) return new Decision(Verdict.WAIT, "aún no ha pasado el minuto");
        if (nowMs > goneAtMs + LATE_MS) {
            return new Decision(Verdict.WARN_UNKNOWN, "Android lo ha retrasado " + (nowMs - goneAtMs) / 60_000 + " min: ya no cierro");
        }
        return new Decision(Verdict.READ, "ha pasado el minuto sin el Bluetooth del coche");
    }

    /** Puro: con la lectura (null si falló). */
    static Decision after(LeapStatus s, long goneAtMs) {
        if (s == null) return new Decision(Verdict.WARN_UNKNOWN, "sin lectura de la nube");
        if (s.carTimeMs <= 0) return new Decision(Verdict.WARN_UNKNOWN, "la nube no da la hora del dato");
        if (s.carTimeMs < goneAtMs - FRESH_BEFORE_MS) {
            return new Decision(Verdict.WARN_UNKNOWN, "dato de " + (goneAtMs - s.carTimeMs) / 60_000 + " min antes de alejarse");
        }
        if (Boolean.TRUE.equals(s.powerOn)) return new Decision(Verdict.NOTHING, "el coche está encendido");
        if (!Double.isNaN(s.speedKmh) && s.speedKmh >= 1) return new Decision(Verdict.NOTHING, "el coche va en marcha");
        if (Boolean.TRUE.equals(s.locked)) return new Decision(Verdict.NOTHING, "ya estaba cerrado");
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (Boolean.TRUE.equals(s.doorOpen(i))) open.add(i);
        if (Boolean.TRUE.equals(s.bootOpen)) open.add(BOOT);
        if (!open.isEmpty()) return new Decision(Verdict.WARN_OPEN, open.size() + " puerta(s)/maletero abiertos", open);
        if (s.locked == null) return new Decision(Verdict.WARN_UNKNOWN, "la nube no dice si está cerrado");
        if (s.anyDoorOpen() == null) return new Decision(Verdict.WARN_UNKNOWN, "la nube no dice cómo están las puertas");
        return new Decision(Verdict.LOCK, "abierto con las puertas cerradas");
    }

    // ------------------------------------------------------------------ Bluetooth del coche

    /** CarBtReceiver: el Bluetooth del coche se conecta (vuelve) o se pierde (alejarse). */
    static void onCarBluetooth(Context ctx, boolean connected, long nowMs) {
        SharedPreferences sp = prefs(ctx);
        long gone = sp.getLong(K_GONE_AT, 0);
        if (connected) {
            if (gone <= 0) return;
            boolean pending = sp.getLong(K_DONE, 0) != gone;
            sp.edit().putLong(K_GONE_AT, 0).apply();
            AutoLockJob.cancel(ctx);
            if (pending) L.i("cierre al alejarse: el Bluetooth del coche ha vuelto a los " + Math.max(0, nowMs - gone) / 1000 + " s: no hago nada");
            return;
        }
        if (!enabled(ctx)) return;
        if (!ready(ctx)) {
            L.i("cierre al alejarse: Bluetooth del coche perdido, pero sin cuenta de Leapmotor o sin PIN: no hago nada");
            return;
        }
        // Dos pérdidas sin vuelta entre medias: sigue la primera (si un trabajo perdido la dejó colgada, se empieza otra).
        if (gone > 0 && sp.getLong(K_DONE, 0) != gone && nowMs - gone < LATE_MS) {
            L.i("cierre al alejarse: Bluetooth del coche perdido otra vez; sigue pendiente el de hace " + Math.max(0, nowMs - gone) / 1000 + " s");
            return;
        }
        markGone(ctx, nowMs);
        AutoLockJob.schedule(ctx, WAIT_MS, false);
        L.i("cierre al alejarse: Bluetooth del coche perdido; miro el coche en " + WAIT_MS / 1000 + " s si no vuelve");
    }

    /** Apunta el alejamiento (lo llama onCarBluetooth; las pruebas, directamente). */
    static void markGone(Context ctx, long nowMs) {
        prefs(ctx).edit().putLong(K_GONE_AT, nowMs).apply();
    }

    // ------------------------------------------------------------------ el trabajo

    /**
     * Lo que hace el trabajo (AutoLockJob) pasado el minuto, o al tocar «Reintentar» (retry). Devuelve lo decidido (en
     * WAIT, el trabajo se vuelve a programar para lo que falte).
     */
    static Verdict run(Context ctx, Car car, Notifier n, long nowMs, boolean retry) {
        SharedPreferences sp = prefs(ctx);
        long gone = sp.getLong(K_GONE_AT, 0);
        Decision pre = before(enabled(ctx), car.ready(), gone, sp.getLong(K_DONE, 0), nowMs, retry);
        if (pre.verdict == Verdict.WAIT || pre.verdict == Verdict.NOTHING) {
            L.i("cierre al alejarse: nada (" + pre.why + ")");
            return pre.verdict;
        }
        // Una sola vez por alejamiento: apuntado antes de leer y de mandar nada.
        sp.edit().putLong(K_DONE, gone).apply();
        if (pre.verdict == Verdict.WARN_UNKNOWN) {
            L.w("cierre al alejarse: no cierro (" + pre.why + "); aviso de que puede seguir abierto");
            n.unknown();
            return pre.verdict;
        }
        LeapStatus s = null;
        try {
            s = car.read();
        } catch (Exception e) {
            L.w("cierre al alejarse: la nube no da el estado (" + Http.safeError(e) + ")");
        }
        Decision d = after(s, gone);
        switch (d.verdict) {
            case NOTHING:
                L.i("cierre al alejarse: nada (" + d.why + ")");
                return d.verdict;
            case WARN_OPEN:
                L.i("cierre al alejarse: no cierro (" + d.why + "); aviso");
                n.stillOpen(d.open);
                return d.verdict;
            case WARN_UNKNOWN:
                L.w("cierre al alejarse: no cierro a ciegas (" + d.why + "); aviso de que puede seguir abierto");
                n.unknown();
                return d.verdict;
            default:
                break;
        }
        L.i("cierre al alejarse: " + d.why + ": mando cerrar");
        boolean ok = false;
        String why = null;
        try {
            ok = car.lock();
        } catch (Exception e) {
            why = e instanceof IOException || e instanceof java.security.GeneralSecurityException ? CarCloudActivity.describe(e)
                    : e.getClass().getSimpleName();
            L.w("cierre al alejarse: la orden de cerrar falló (" + Http.safeError(e) + ")");
        }
        if (!ok && why == null) {
            // Sin confirmar (puede aplicarse igualmente): otra lectura para ver si quedó cerrado.
            car.pause(VERIFY_MS);
            try {
                LeapStatus v = car.read();
                ok = v != null && Boolean.TRUE.equals(v.locked);
            } catch (Exception e) {
                L.w("cierre al alejarse: no se pudo comprobar el cierre (" + Http.safeError(e) + ")");
            }
            if (!ok) why = Str.get(com.andrerinas.openheadunit.R.string.hql_autolock_no_answer);
            L.i("cierre al alejarse: orden sin confirmar; tras comprobarlo, " + (ok ? "cerrado" : "sigue sin constar cerrado"));
        }
        if (ok) {
            L.i("cierre al alejarse: coche cerrado");
            n.locked();
        } else {
            n.lockFailed(why);
        }
        return Verdict.LOCK;
    }

    /** Solo pruebas: el estado de cada alejamiento, a cero. */
    static void resetForTest(Context c) {
        prefs(c).edit().clear().commit();
    }

    /** Lo que se espera aún al minuto (para volver a programar el trabajo si Android lo adelanta). */
    static long remainingMs(Context c, long nowMs) {
        long gone = prefs(c).getLong(K_GONE_AT, 0);
        return gone <= 0 ? 0 : Math.max(1_000, gone + WAIT_MS - nowMs);
    }
}
