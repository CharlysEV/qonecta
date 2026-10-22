package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.andrerinas.openheadunit.R;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/**
 * El aviso del informe del mes: Android lanza esto cada 6 h; los primeros días del mes (hasta el NOTIFY_UNTIL_DAY),
 * una vez, guarda el resumen del mes que acaba de terminar y avisa «Tu octubre con el C10 está listo» si tuvo viajes.
 * Pasados esos días el mes se da por avisado sin aviso (p. ej. al instalar a mitad de mes, el anterior no se avisa).
 */
public class MonthlyReportJob extends JobService {
    private static final int JOB_ID = 4212;
    static final long EVERY_MS = 6 * 3_600_000L;
    /** Hasta qué día del mes se avisa del mes anterior. */
    static final int NOTIFY_UNTIL_DAY = 7;
    static final String NOTIFIED = "monthly_report_notified";
    private static final String CHANNEL = "monthly_report";
    private static final int NOTIFICATION_ID = 7320;

    /** La programa si no lo estaba (sin red: solo lee lo que ya hay en el móvil). */
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        int r = js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, MonthlyReportJob.class))
                .setPeriodic(EVERY_MS)
                .setPersisted(true)
                .build());
        L.i("informe del mes: comprobación cada 6 h " + (r == JobScheduler.RESULT_SUCCESS ? "programada" : "NO programada"));
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                check(getApplicationContext(), System.currentTimeMillis());
            } catch (RuntimeException e) {
                L.w("informe del mes: comprobación fallida (" + e.getClass().getSimpleName() + ")");
            }
            jobFinished(params, false);
        }, "hql-monthly-check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** Puro: ¿toca avisar del mes anterior? Solo los primeros días y si no se avisó ya (notified: su clave o vacío). */
    static boolean due(int dayOfMonth, String prevKey, String notified) {
        return dayOfMonth <= NOTIFY_UNTIL_DAY && !prevKey.equals(notified);
    }

    /** Hilo de fondo: guarda los meses y, si toca, avisa del anterior. */
    static void check(Context ctx, long now) {
        if (DemoMode.active()) return;
        Config cfg = new Config(ctx);
        TimeZone tz = TimeZone.getDefault();
        int[] cur = MonthlyReport.monthOf(now, tz);
        int[] p = MonthlyReport.shift(cur[0], cur[1], -1);
        String prevKey = MonthlyReport.key(p[0], p[1]);
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(now);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        String notified = cfg.getString(NOTIFIED);
        Map<String, MonthlyReport.Summary> all = MonthlyReport.refresh(ctx);
        if (!due(day, prevKey, notified)) {
            if (day > NOTIFY_UNTIL_DAY && !prevKey.equals(notified)) cfg.putString(NOTIFIED, prevKey);
            return;
        }
        cfg.putString(NOTIFIED, prevKey);
        MonthlyReport.Summary s = all.get(prevKey);
        if (s == null || s.trips == 0) {
            L.i("informe del mes: " + prevKey + " sin viajes, sin aviso");
            return;
        }
        notify(ctx, s);
    }

    private static void notify(Context ctx, MonthlyReport.Summary s) {
        String title = Str.carText(Str.get(R.string.hql_report_notice_title, MonthlyReportView.monthName(s.year, s.month)), CarSpec.current());
        String text = Str.get(R.string.hql_report_notice_text, MonthlyReportView.num(s.km, 0), MonthlyReportView.num(s.per100(), 1));
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT < 26 || nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_report_title),
                    NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, MonthlyReportActivity.intent(ctx, s.key())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTIFICATION_ID, new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setColor(ctx.getColor(R.color.hql_accent))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
            L.i("informe del mes: aviso de " + s.key() + " · " + s.trips + " viajes");
        } catch (RuntimeException e) {
            L.w("informe del mes: sin notificación (" + e.getClass().getSimpleName() + ")");
        }
    }
}
