package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

import java.util.Locale;

/**
 * Resumen del viaje en el móvil al desconectar: fps medios, reconexiones, cortes, calor y el acierto de la ruta. Lo mismo
 * que va al registro (SessionSummary.tripSummary), en una notificación, para no tener que pedir el registro para saber
 * cómo fue. Solo con un viaje de al menos MIN_MINUTES con vídeo y el ajuste «Resumen del viaje» puesto.
 */
final class TripNotice {
    private static final String CHANNEL = "hql_trip";
    private static final int ID = 7751;
    static final double MIN_MINUTES = 2;

    private TripNotice() {
    }

    /** Puro: el texto del aviso (líneas), o null si no toca. */
    static String text(SessionSummary.TripStats s, double peakC, double routePredictedKwh, double routeRealKwh) {
        return text(s, peakC, new double[]{routePredictedKwh, routeRealKwh, Double.NaN, Double.NaN, Double.NaN, Double.NaN});
    }

    /** arrival: lo de RoutePlanner.lastArrival ({kWh previsto, real, km, % previsto, % real, puntos que bajó}). */
    static String text(SessionSummary.TripStats s, double peakC, double[] arrival) {
        double routePredictedKwh = arrival[0];
        double routeRealKwh = arrival[1];
        if (s == null || s.getVideoMinutes() < MIN_MINUTES) return null;
        StringBuilder b = new StringBuilder();
        b.append(Str.get(R.string.hql_trip_notice_fps, s.getFps())).append(" · ")
                .append(Str.plural(R.plurals.hql_trip_notice_reconnects, s.getReconnects())).append(" · ")
                .append(Str.plural(R.plurals.hql_trip_notice_cuts, s.getStalls()));
        if (s.getThermalMax() >= 0 || !Double.isNaN(peakC)) {
            b.append('\n').append(Str.get(R.string.hql_trip_notice_heat, Math.max(0, s.getThermalMax()), Double.isNaN(peakC) ? 0.0 : peakC));
        }
        if (!Double.isNaN(routePredictedKwh) && !Double.isNaN(routeRealKwh)) {
            b.append('\n').append(Str.get(R.string.hql_trip_notice_route_summary, RoutePlanner.arrivalSummary(routePredictedKwh, routeRealKwh,
                    arrival[3], arrival[4], arrival[2], arrival[5])));
        }
        return b.toString();
    }

    /**
     * Puro: lo que gastó la batería del MÓVIL en el viaje: «Batería del móvil: −6 % (312 mAh)», o con el móvil enchufado
     * «Batería del móvil: 60 % → 68 % (cargando)». "" si no se sabe.
     */
    static String phoneText(int pct0, int pct1, double mah, boolean plugged) {
        if (pct0 < 0 || pct1 < 0) return "";
        if (plugged) return Str.get(R.string.hql_trip_phone_charging, pct0, pct1);
        int used = Math.max(0, pct0 - pct1);
        if (!Double.isNaN(mah) && mah > 0) return Str.get(R.string.hql_trip_phone_used_mah, used, (int) Math.round(mah));
        return Str.get(R.string.hql_trip_phone_used, used);
    }

    /** phoneText con dos lecturas de PhoneBattery (inicio y fin), o "". */
    static String phoneText(long[] b0, long[] b1) {
        if (b0 == null || b1 == null) return "";
        boolean plugged = PhoneBattery.charged(b0, b1);
        double mah = b0[PhoneBattery.UAH] > 0 && b1[PhoneBattery.UAH] > 0 && b0[PhoneBattery.UAH] >= b1[PhoneBattery.UAH]
                ? (b0[PhoneBattery.UAH] - b1[PhoneBattery.UAH]) / 1000.0 : Double.NaN;
        return phoneText((int) b0[PhoneBattery.PCT], (int) b1[PhoneBattery.PCT], mah, plugged);
    }

    static void show(Context c, Config cfg, SessionSummary.TripStats s) {
        try {
            if (s == null || !cfg.tripNotice() || !Config.isAa(cfg.mode())) return;
            // Lo de la ruta terminada en este viaje (también si se quitó, o si la sesión acaba justo al llegar).
            RoutePlanner.finishNow();
            double[] a = RoutePlanner.lastArrival(System.currentTimeMillis() - Math.round((s.getMinutes() + 5) * 60_000));
            if (a == null) a = new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
            String text = text(s, ThermalGuard.tripPeakC(), a);
            if (text == null) return;
            // Lo que gastó el móvil entero en el viaje (LinkService lo deja al cerrar el viaje).
            long[][] pb = LinkService.lastTripBattery;
            String phone = pb == null ? "" : phoneText(pb[0], pb[1]);
            if (!phone.isEmpty()) text = text + "\n" + phone;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_trip_notice_channel),
                    NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(c, ID, HomeActivity.openIntent(c),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String title = Str.get(R.string.hql_trip_notice_title, DriveTab.duration(Math.round(s.getMinutes() * 60)));
            nm.notify(ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setContentTitle(title)
                    .setContentText(text.split("\n")[0])
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
            L.i(String.format(Locale.US, "viaje: resumen en el móvil (%.0f min, %.1f fps)", s.getMinutes(), s.getFps()));
        } catch (RuntimeException e) {
            L.w("viaje: no se pudo avisar del resumen: " + e);
        }
    }
}
