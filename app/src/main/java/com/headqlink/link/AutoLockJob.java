package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * «Cerrar el coche al alejarme» (AutoLock) con la app en segundo plano y el móvil bloqueado: un trabajo de Android
 * (como ChargeCheckJob) que corre al minuto de perder el Bluetooth del coche, con red; si a los 3 min sigue sin red,
 * corre igual (y avisa de que no ha podido comprobar el coche). Ningún servicio en primer plano. «Reintentar» del
 * aviso lo vuelve a lanzar ya.
 */
public class AutoLockJob extends JobService {
    private static final int JOB_ID = 4213;
    /** Si no hay red al minuto, se espera esto más antes de correr sin ella. */
    private static final long DEADLINE_SLACK_MS = 2 * 60_000L;
    private static final String EXTRA_RETRY = "retry";
    private static final String CHANNEL = "auto_lock";
    private static final int NOTIF_ID = 7320;

    /** Programa la comprobación dentro de delayMs (retry: la pide «Reintentar»). Sustituye a la que hubiera. */
    static void schedule(Context c, long delayMs, boolean retry) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        PersistableBundle x = new PersistableBundle();
        x.putBoolean(EXTRA_RETRY, retry);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, AutoLockJob.class))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + DEADLINE_SLACK_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(x)
                .build();
        if (js.schedule(job) != JobScheduler.RESULT_SUCCESS) L.w("cierre al alejarse: Android no deja programar la comprobación");
    }

    static void cancel(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    private volatile Thread thread;

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean retry = params.getExtras().getBoolean(EXTRA_RETRY, false);
        Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                L.init(app);
                Str.init(app);
                long now = System.currentTimeMillis();
                AutoLock.Verdict v = AutoLock.run(app, new CloudCar(app), new Notices(app), now, retry);
                // Android lo ha adelantado: lo que falte del minuto.
                if (v == AutoLock.Verdict.WAIT) schedule(app, AutoLock.remainingMs(app, now), false);
            } catch (RuntimeException e) {
                L.w("cierre al alejarse: comprobación fallida (" + e.getClass().getSimpleName() + ")");
            }
            jobFinished(params, false);
        }, "hql-auto-lock");
        thread = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = thread;
        if (t != null) t.interrupt();
        return false;
    }

    /** El coche de verdad: la nube de Leapmotor por CarCloudSession, como «Leer estado ahora» y el widget. */
    static final class CloudCar implements AutoLock.Car {
        private final Context c;

        CloudCar(Context c) {
            this.c = c;
        }

        @Override
        public boolean ready() {
            return !DemoMode.active() && AutoLock.ready(c);
        }

        @Override
        public LeapStatus read() throws Exception {
            if (!CarCloud.takeDailyQuota()) throw new java.io.IOException("tope diario de lecturas");
            long t0 = SystemClock.elapsedRealtime();
            LeapStatus s = CarCloudSession.readStatus(c);
            long lat = SystemClock.elapsedRealtime() - t0;
            // Lo leído lo ven también las pantallas y el widget.
            CarCloud.publish(c, s, lat);
            L.i("cierre al alejarse: " + s.logLine() + " · " + lat + " ms");
            return s;
        }

        @Override
        public boolean lock() throws Exception {
            boolean ok = CarCloudSession.command(c, new CarCommand(CarCommand.Kind.LOCK));
            // El botón de abrir/cerrar del widget y de «Mi coche»: cerrado ya, sin esperar a la nube.
            if (ok) CarOrders.noteLock(c, true, System.currentTimeMillis());
            return ok;
        }

        @Override
        public void pause(long ms) {
            SystemClock.sleep(ms);
        }
    }

    /** Los avisos en el móvil, en su canal (con sonido: el móvil va en el bolsillo). */
    static final class Notices implements AutoLock.Notifier {
        private final Context c;

        Notices(Context c) {
            this.c = c;
        }

        @Override
        public void locked() {
            String title = Str.get(R.string.hql_autolock_done);
            lastResult(title);
            Notification.Builder b = builder(title, Str.get(R.string.hql_autolock_done_text), false);
            b.addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_autolock_unlock), unlock()).build());
            post(b);
        }

        @Override
        public void stillOpen(List<Integer> open) {
            StringBuilder list = new StringBuilder();
            int[] places = {R.string.hql_cloud_pos_fl, R.string.hql_cloud_pos_fr, R.string.hql_cloud_pos_rl,
                    R.string.hql_cloud_pos_rr, R.string.hql_cloud_pos_boot};
            for (int i : open) list.append(list.length() > 0 ? ", " : "").append(Str.get(places[i]));
            String text = Str.get(R.string.hql_autolock_open_text, Str.get(R.string.hql_cloud_open_list, list));
            post(builder(Str.get(R.string.hql_autolock_open_title), text, true));
        }

        @Override
        public void unknown() {
            post(builder(Str.get(R.string.hql_autolock_unknown_title), Str.get(R.string.hql_autolock_unknown_text), true));
        }

        @Override
        public void lockFailed(String why) {
            String title = Str.get(R.string.hql_autolock_failed_title);
            lastResult(title);
            Notification.Builder b = builder(title, Str.get(R.string.hql_autolock_failed_text, why), true);
            PendingIntent retry = PendingIntent.getBroadcast(c, NOTIF_ID, new Intent(c, Retry.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_autolock_retry), retry).build());
            post(b);
        }

        /** La línea de la última orden del widget («… · 12:03»), como las que se mandan desde él. */
        private void lastResult(String text) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            new Config(c).putString(CarOrders.K_LAST, text + " · " + time);
            try {
                CarOrders.refresh(c);
            } catch (RuntimeException e) {
                L.w("cierre al alejarse: widget sin repintar (" + e.getClass().getSimpleName() + ")");
            }
        }

        /**
         * «Abrir»: la ventanita del widget (CarControlActivity), que pide confirmación y desbloquear el móvil antes de
         * abrir el coche; vale aunque las órdenes del widget estén apagadas.
         */
        private PendingIntent unlock() {
            Intent i = new Intent(c, CarControlActivity.class).putExtra(CarControlActivity.EXTRA_WHAT, "unlock")
                    .putExtra(CarControlActivity.EXTRA_AUTO_LOCK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return PendingIntent.getActivity(c, NOTIF_ID, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }

        private Notification.Builder builder(String title, String text, boolean warn) {
            PendingIntent open = PendingIntent.getActivity(c, NOTIF_ID + 1, new Intent(c, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(warn ? android.R.drawable.stat_sys_warning : R.drawable.hql_ic_notif)
                    .setColor(c.getColor(warn ? R.color.hql_warn : R.color.hql_accent))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true);
        }

        private void post(Notification.Builder b) {
            try {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_autolock_channel),
                        NotificationManager.IMPORTANCE_HIGH));
                nm.notify(NOTIF_ID, b.build());
            } catch (RuntimeException e) {
                L.w("cierre al alejarse: sin notificación (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    /** «Reintentar» del aviso: la comprobación otra vez, ya (vuelve a mirar las puertas antes de cerrar). */
    public static final class Retry extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            L.init(ctx);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
            L.i("cierre al alejarse: «Reintentar» desde el aviso");
            schedule(ctx, 0, true);
        }
    }
}
