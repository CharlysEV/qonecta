package com.headqlink.link;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.andrerinas.openheadunit.R;

import java.util.List;

/**
 * Modo «Auto» (sin nuestra pantalla en el coche): la pregunta «¿Usar la ruta que planificaste?» de la ruta guardada en
 * el móvil (PlannedTrip), en una notificación del móvil (Android Auto también las enseña), con «Ir con las paradas»
 * (Google Maps con las paradas tal y como salieron en el móvil; la ruta se da por usada) y «No» (se descarta). Sin
 * respuesta, se quita sola al desconectar del coche o cuando caduca la ruta; la ruta sigue guardada para el viaje
 * siguiente. Nada se abre solo. En «Auto extendido» la pregunta es la tarjeta del panel (RoutePlanner.offerPlanned).
 */
final class PlannedNotice {
    private static final String CHANNEL = "hql_planned";
    static final int ID = 7761;
    /** Tras conectar se espera esto antes de preguntar (que Android Auto ya esté en el coche y la enseñe). */
    static final long AFTER_MS = 12_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    private PlannedNotice() {
    }

    /** Sesión con el coche (otra vez) en marcha. Solo en el modo «Auto» y con una ruta guardada; hilo principal. */
    static void onCarConnected(Context c, String mode) {
        if (!Config.MODE_AA.equals(mode)) return;
        Context app = c.getApplicationContext();
        if (PlannedTrip.peek(app) == null) return;
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            post(app);
        };
        main.postDelayed(pending, AFTER_MS);
    }

    /** Sesión con el coche terminada: fuera la pregunta (y la que iba a salir). Hilo principal. */
    static void onCarLost(Context c) {
        if (pending != null) main.removeCallbacks(pending);
        pending = null;
        cancel(c);
    }

    static void cancel(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(ID);
        } catch (RuntimeException ignored) {
        }
    }

    private static void post(Context app) {
        PlannedTrip t = PlannedTrip.peek(app);
        // Con el planificador del coche en marcha («Auto extendido»), ya pregunta su tarjeta.
        if (t == null || RoutePlanner.get() != null) return;
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_planned_applied_title),
                    NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(app, ID, HomeActivity.openIntent(app),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // «Ir»: una actividad (sin ventana) y no un receptor, porque desde Android 12 un receptor de una notificación no
            // puede abrir otra app (Maps).
            PendingIntent go = PendingIntent.getActivity(app, ID + 1, new Intent(app, Go.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent no = PendingIntent.getBroadcast(app, ID + 2, new Intent(app, No.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            int n = t.stops.size();
            String text = t.dest.name + " · " + PlannedTrip.stopsText(n);
            long left = Math.max(60_000, t.expiresAtMs() - System.currentTimeMillis());
            nm.notify(ID, new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setColor(app.getColor(R.color.hql_accent))
                    .setContentTitle(Str.get(R.string.hql_planned_ask_title))
                    .setContentText(text)
                    .setCategory(Notification.CATEGORY_RECOMMENDATION)
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(left)
                    .setContentIntent(open)
                    // Sin paradas de carga, «Ir con las paradas» no tiene sentido: solo «Ir» (como en la pestaña Ruta).
                    .addAction(new Notification.Action.Builder(null, Str.get(n > 0 ? R.string.hql_plan_go : R.string.hql_go), go).build())
                    .addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_planned_no), no).build())
                    .build());
            L.i("ruta del móvil: modo Auto, pregunto en una notificación si la uso (" + n + " paradas de carga)");
        } catch (RuntimeException e) {
            L.w("ruta del móvil: no se pudo preguntar en una notificación: " + e);
        }
    }

    /** «Ir con las paradas»: Google Maps con las paradas del móvil, una vez; la ruta queda usada. */
    static void go(Context c) {
        Context app = c.getApplicationContext();
        PlannedTrip t = PlannedTrip.answer(app, true);
        cancel(app);
        if (t == null) return;
        List<double[]> pts = NavApps.ahead(t.route, -1, NavApps.MAX_WAYPOINTS);
        if (pts.isEmpty()) pts.add(new double[]{t.dest.lat, t.dest.lon, t.totalKm});
        L.i("ruta del móvil: modo Auto, abro Google Maps con las paradas del móvil (" + (pts.size() - 1) + " por el camino)");
        NavApps.goThrough(app, pts);
    }

    /** «No»: se descarta. */
    static void no(Context c) {
        PlannedTrip.answer(c.getApplicationContext(), false);
        cancel(c);
    }

    /** La acción «Ir con las paradas» (sin ventana: abre Maps y se cierra). */
    public static final class Go extends Activity {
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            go(this);
            finish();
        }
    }

    /** La acción «No». */
    public static final class No extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            no(ctx);
        }
    }
}
