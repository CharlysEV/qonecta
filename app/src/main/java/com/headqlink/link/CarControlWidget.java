package com.headqlink.link;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.andrerinas.openheadunit.R;

/**
 * El widget «Coche»: cerrar, abrir, maletero, clima y localizar el coche por la nube de Leapmotor, y el botón grande de
 * la voz. Cada toque abre CarControlActivity (que confirma lo que abre, escucha y manda la orden); arriba, el resultado de
 * la última. Sin el ajuste «Órdenes desde el widget» (o sin el PIN del coche) lo dice ahí mismo.
 *
 * No exportado: Android le entrega sus avisos igualmente, y los toques van con PendingIntent propios.
 */
public final class CarControlWidget extends AppWidgetProvider {
    /** Config: el ajuste (apagado de serie) y el resultado de la última orden. */
    static final String K_ENABLED = "car_orders";
    static final String K_LAST = "car_orders_last";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        render(c, m, ids);
    }

    @Override
    public void onEnabled(Context c) {
        L.init(c);
        L.i("widget «Coche»: añadido a la pantalla de inicio");
    }

    @Override
    public void onDisabled(Context c) {
        L.init(c);
        L.i("widget «Coche»: quitado de la pantalla de inicio");
    }

    /** ¿Se pueden mandar órdenes? (el ajuste y el PIN guardado) */
    static boolean enabled(Context c) {
        return new Config(c).getBool(K_ENABLED) && new CarCloudStore(c).hasPin();
    }

    /** Repinta todos los widgets «Coche» (tras una orden o al cambiar el ajuste). */
    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, CarControlWidget.class));
        if (ids.length > 0) render(c, m, ids);
    }

    private static void render(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.hql_car_widget);
        String last = new Config(c).getString(K_LAST);
        v.setTextViewText(R.id.hql_cw_status, !enabled(c) ? Str.get(R.string.hql_cw_off)
                : last != null && !last.isEmpty() ? last : Str.get(R.string.hql_cw_ready));
        v.setOnClickPendingIntent(R.id.hql_cw_lock, tap(c, "lock", 1));
        v.setOnClickPendingIntent(R.id.hql_cw_unlock, tap(c, "unlock", 2));
        v.setOnClickPendingIntent(R.id.hql_cw_trunk, tap(c, "trunk", 3));
        v.setOnClickPendingIntent(R.id.hql_cw_climate, tap(c, "climate", 4));
        v.setOnClickPendingIntent(R.id.hql_cw_find, tap(c, "find", 5));
        v.setOnClickPendingIntent(R.id.hql_cw_voice, tap(c, CarControlActivity.VOICE, 6));
        for (int id : ids) m.updateAppWidget(id, v);
    }

    private static PendingIntent tap(Context c, String what, int code) {
        Intent i = new Intent(c, CarControlActivity.class).putExtra(CarControlActivity.EXTRA_WHAT, what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(c, 7800 + code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
