package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Leapmotor Mate leído de Home Assistant, para que la app use «el coche» con el dato más reciente (CarCloud.car). Se lee
 * mientras alguien lo necesite, igual que la nube (CarCloud.acquire lo pide también aquí): la sesión con el coche, la
 * pantalla del móvil, una carga en directo; cada SLOW_MS, o cada FAST_MS mientras se ven Estado o Mandos.
 *
 * Sin Home Assistant configurado o sin Mate en él, no hace nada: la app funciona igual que solo con la cuenta de
 * Leapmotor. La primera lectura trae toda la casa (/api/states) para reconocer el coche; las siguientes, solo sus
 * elementos (HomeAssistant.statesOf, unos KB en vez de toda la casa: en el coche va con datos móviles).
 */
final class MateFeed {
    static final long SLOW_MS = 30_000;
    static final long FAST_MS = 10_000;
    /** Una foto de Mate más vieja que esto (sin lecturas: Home Assistant no responde) ya no se usa. */
    private static final long STALE_READ_MS = 10 * 60_000L;
    /**
     * Home Assistant no contesta (el ordenador de casa apagado, sin red…): se espera antes de volver a probar, cada vez
     * el doble, de RETRY_MIN_MS a RETRY_MAX_MS; mientras, la app usa la nube de Leapmotor como siempre.
     */
    static final long RETRY_MIN_MS = 2 * 60_000L;
    static final long RETRY_MAX_MS = 15 * 60_000L;
    private static volatile int failures;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<String> owners = new HashSet<>();
    private static final Set<String> fast = new HashSet<>();
    private static Thread thread;
    private static volatile MateHa.Car car;
    private static volatile CarCloud.Snapshot snap;
    private static volatile long readAtMs;
    /** Motivo del último fallo de lectura (para la pantalla), o null. */
    private static volatile String error;

    private MateFeed() {
    }

    /** ¿Tiene la casa Leapmotor Mate? (lo último que se vio; sin Home Assistant configurado, no). */
    static boolean seen(Context c) {
        HomeAssistant ha = new HomeAssistant(c);
        return ha.configured() && ha.mateSeen();
    }

    static MateHa.Car car() {
        return car;
    }

    /** La última foto de Mate, o null sin datos (o si hace mucho que Home Assistant no contesta). */
    static CarCloud.Snapshot snapshot() {
        CarCloud.Snapshot s = snap;
        return s != null && System.currentTimeMillis() - readAtMs < STALE_READ_MS ? s : null;
    }

    static String error() {
        return error;
    }

    /** Lo necesita who (con la casa sin Mate, nada); fastRead: cada FAST_MS (Estado, Mandos). */
    static void acquire(Context c, String who, boolean fastRead) {
        if (!seen(c)) return;
        synchronized (MateFeed.class) {
            owners.add(who);
            if (fastRead) fast.add(who);
            if (thread == null) {
                Context app = c.getApplicationContext();
                Thread t = new Thread(() -> loop(app), "hql-mate");
                thread = t;
                t.start();
            } else if (fastRead) {
                thread.interrupt(); // a leer ya, al ritmo rápido
            }
        }
    }

    static synchronized void release(String who) {
        owners.remove(who);
        fast.remove(who);
        if (owners.isEmpty() && thread != null) {
            Thread t = thread;
            thread = null;
            t.interrupt();
        }
    }

    /** Avisado en el hilo principal tras cada lectura. */
    static void listen(Runnable r) {
        LISTENERS.add(r);
    }

    static void unlisten(Runnable r) {
        LISTENERS.remove(r);
    }

    private static boolean running(Thread t) {
        synchronized (MateFeed.class) {
            return thread == t;
        }
    }

    private static void loop(Context app) {
        Thread me = Thread.currentThread();
        HomeAssistant ha = new HomeAssistant(app);
        while (running(me)) {
            if (!ha.configured()) {
                car = null;
                snap = null;
                break;
            }
            read(ha);
            long wait;
            synchronized (MateFeed.class) {
                wait = failures > 0 ? retryMs(failures) : fast.isEmpty() ? SLOW_MS : FAST_MS;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                // Otro ritmo o fin: se vuelve a mirar arriba.
            }
        }
    }

    private static void read(HomeAssistant ha) {
        try {
            long t0 = System.currentTimeMillis();
            MateHa.Car before = car;
            JSONArray states = before != null ? ha.statesOf(before.prefix) : null;
            MateHa.Car c = states != null ? MateHa.find(states) : null;
            if (c == null) {
                // La primera vez (o si ya no está ahí con ese nombre): toda la casa.
                states = ha.states();
                c = MateHa.find(states);
            }
            long ms = System.currentTimeMillis() - t0;
            car = c;
            snap = c == null ? null : MateHa.snapshot(c, DemoMode.wallClockMs(), ms);
            readAtMs = System.currentTimeMillis();
            error = null;
            failures = 0;
            ha.noteMate(c != null);
        } catch (Exception e) {
            error = HomeAssistant.reason(e);
            failures++;
            L.w("Mate: Home Assistant no responde (" + error + "); otra vez en " + retryMs(failures) / 60_000 + " min");
        }
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) r.run();
        });
    }

    /** Una orden a Mate (en su hilo); al terminar, done en el principal con el error o null. */
    static void call(Context c, String domain, String service, String entityId, JSONObject data, java.util.function.Consumer<String> done) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                new HomeAssistant(app).call(domain, service, entityId, data);
                L.i("Mate: " + domain + "." + service);
            } catch (Exception e) {
                err = HomeAssistant.reason(e);
                L.w("Mate: no se pudo (" + err + ")");
            }
            String fe = err;
            MAIN.post(() -> done.accept(fe));
            // Mate lo refleja enseguida en lo que publica: se relee pronto y otra vez algo después.
            try {
                Thread.sleep(1_500);
                read(new HomeAssistant(app));
                Thread.sleep(5_000);
                read(new HomeAssistant(app));
            } catch (InterruptedException ignored) {
            }
        }, "hql-mate-call").start();
    }

    /** Puro: espera tras n fallos seguidos (2, 4, 8 y 15 min como mucho). */
    static long retryMs(int n) {
        long w = RETRY_MIN_MS << Math.max(0, Math.min(n - 1, 4));
        return Math.min(RETRY_MAX_MS, w);
    }

    /**
     * Puro: «el coche»: la foto de la nube de Leapmotor o la de Mate, la de dato del coche más reciente. A igualdad, la
     * de la nube (trae más cosas: depósito, avisos de las ruedas); la de la demostración, siempre. Sin Mate,
     * la de la nube tal cual: la app funciona igual que solo con la cuenta de Leapmotor.
     */
    static CarCloud.Snapshot freshest(CarCloud.Snapshot cloud, CarCloud.Snapshot mate) {
        if (mate == null || !mate.hasData()) return cloud;
        if (cloud != null && cloud.demo) return cloud;
        if (cloud == null || !cloud.hasData()) return mate;
        return mate.dataTimeMs() > cloud.dataTimeMs() ? mate : cloud;
    }
}
