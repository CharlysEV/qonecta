package com.headqlink.link;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;

/**
 * Carga en directo sin la app abierta: Android la lanza cada 15 min (con red) y lee una vez la nube de Leapmotor. Si
 * el coche carga y no se sabía, empieza la carga en directo (la rápida la vigila ChargeWatchService como siempre; la
 * lenta, en casa, la siguen estas comprobaciones, con su notificación); si ya se seguía una lenta, le pasa la lectura.
 * Así se sabe cuándo empieza (con 15 min de margen) y cuándo acaba cualquier carga, aunque no se abra la app.
 *
 * Solo con cuenta de Leapmotor. Unas 96 lecturas al día de las 400 del tope (CarCloud.Policy.DAILY_CAP); Android puede
 * espaciarlas con el móvil quieto mucho rato.
 */
public class ChargeCheckJob extends JobService {
    private static final int JOB_ID = 4211;
    static final long EVERY_MS = 15 * 60_000L;
    /** Lo que se espera a una lectura nueva de la nube antes de dejarlo para la próxima. */
    private static final long WAIT_READ_MS = 45_000;
    private static final String OWNER = "check";

    /** La programa (si no lo estaba) con cuenta de Leapmotor; sin cuenta, la quita. */
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!PhoneInsights.hasAccount(c)) {
            if (js.getPendingJob(JOB_ID) != null) {
                js.cancel(JOB_ID);
                L.i("carga en directo: sin cuenta de Leapmotor, sin comprobación de fondo");
            }
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, ChargeCheckJob.class))
                .setPeriodic(EVERY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        int r = js.schedule(job);
        L.i("carga en directo: comprobación de fondo cada 15 min " + (r == JobScheduler.RESULT_SUCCESS ? "programada" : "NO programada"));
    }

    private volatile Thread thread;

    @Override
    public boolean onStartJob(JobParameters params) {
        Thread t = new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (RuntimeException e) {
                L.w("carga en directo: comprobación de fondo fallida (" + e.getClass().getSimpleName() + ")");
            }
            jobFinished(params, false);
        }, "hql-charge-check");
        thread = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = thread;
        if (t != null) t.interrupt();
        CarCloud.release(OWNER);
        return false;
    }

    /** Una lectura de la nube (la que haya, si ya se está leyendo por otro lado) y lo que toque con ella. */
    static void check(Context ctx) {
        // Lo que gasta la app en reposo, apuntado cada vez que Android la despierta (sin viaje en marcha).
        if (!LinkService.tripRunning) AppEnergy.checkpoint(ctx, false);
        if (!PhoneInsights.hasAccount(ctx)) return;
        ChargeWatch.restore(ctx);
        if (ChargeWatch.serviceRunning) return;
        boolean already = CarCloud.running();
        long before = CarCloud.snapshot().fetchedAtMs;
        CarCloud.acquire(ctx, OWNER);
        try {
            // Si ya se leía (la app abierta, el coche), la foto es reciente; si no, se espera la lectura que acaba de pedir.
            if (!already) {
                long until = SystemClock.elapsedRealtime() + WAIT_READ_MS;
                while (CarCloud.snapshot().fetchedAtMs == before && SystemClock.elapsedRealtime() < until) {
                    if (Thread.currentThread().isInterrupted()) return;
                    SystemClock.sleep(500);
                }
            }
            ChargeWatch.background(ctx, CarCloud.car());
        } finally {
            CarCloud.release(OWNER);
        }
    }
}
