package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.andrerinas.openheadunit.R;

import java.util.List;
import java.util.Locale;

/**
 * Gasto con el coche parado al subir al coche (ParkedDrain): con el primer dato del coche del viaje, lo que bajó la
 * batería desde el final del viaje anterior. Lo enseña Coche › Estado; si es más de lo normal en este coche, avisa en el
 * coche (la tarjeta de aviso) y en el móvil (notificación).
 */
final class ParkedNotice {
    private static final String CHANNEL = "parked_drain";
    private static final int ID = 7310;

    private ParkedNotice() {
    }

    /** Hilo de fondo: el viaje empieza (startMs) y el coche dice soc y odo a las timeMs. */
    static void check(Context ctx, long startMs, double soc, double odo, long timeMs, double capKwh) {
        if (DemoMode.active()) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                List<TripLog.Trip> recent = TripLog.recent(app, 80);
                ParkedDrain.sinceParked = null;
                ParkedDrain.sinceParkedUnusual = false;
                if (recent.isEmpty()) return;
                TripLog.Trip last = recent.get(0);
                if (last.startMs >= startMs) return;
                double cap = capKwh > 0 ? capKwh : last.capKwh;
                ParkedDrain.Park p = ParkedDrain.park(last.socEnd, last.odoEnd, last.endMs(), soc, odo, timeMs, cap);
                double typical = ParkedDrain.typicalPerDay(ParkedDrain.parks(recent, cap));
                ParkedDrain.sinceParkedTypical = typical;
                if (p == null) {
                    L.i("gasto parado: sin dato (sin datos del coche, se condujo o se cargó por medio, o menos de 2 h)");
                    return;
                }
                boolean unusual = ParkedDrain.unusual(p, typical);
                ParkedDrain.sinceParked = p;
                ParkedDrain.sinceParkedUnusual = unusual;
                L.i(String.format(Locale.US, "gasto parado: %.1f h · -%.1f %% (%.2f kWh) · %.2f %%/día · lo normal en este coche %.2f %%/día%s",
                        p.hours(), p.dropPct(), p.kwh(), p.perDayPct(), typical, unusual ? " · MÁS DE LO NORMAL" : ""));
                if (unusual) alert(app, p, typical);
            } catch (RuntimeException e) {
                L.w("gasto parado: " + e.getClass().getSimpleName());
            }
        }, "hql-parked").start();
    }

    /** Más de lo normal: la tarjeta de aviso del coche y una notificación en el móvil. */
    private static void alert(Context ctx, ParkedDrain.Park p, double typical) {
        String text = Str.get(R.string.hql_parked_notice_text, PhoneInsights.duration(Math.round(p.hours() * 60)),
                num(p.kwh(), 1), num(p.dropPct(), 1), num(typical, 1));
        String title = Str.get(R.string.hql_parked_notice_title);
        RoutePlanner.ChargeAlertListener l = RoutePlanner.chargeAlerts;
        if (l != null) l.onChargeAlert(title, text, RoutePlanner.ALERT_WARN);
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_parked_channel),
                    NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(ctx, ID, new Intent(ctx, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(ID, new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setColor(ctx.getColor(R.color.hql_warn))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (RuntimeException e) {
            L.w("gasto parado: sin notificación (" + e.getClass().getSimpleName() + ")");
        }
    }

    static String num(double v, int decimals) {
        return Double.isNaN(v) ? "—" : String.format(Str.locale(), "%." + decimals + "f", v);
    }
}
