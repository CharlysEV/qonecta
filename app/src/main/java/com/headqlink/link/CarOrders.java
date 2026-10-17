package com.headqlink.link;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

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

    /** ¿Se pueden mandar órdenes? (el ajuste y el PIN guardado) */
    static boolean enabled(Context c) {
        return new Config(c).getBool(K_ENABLED) && new CarCloudStore(c).hasPin();
    }

    /** Repinta el widget de Qonecta+ (tras una orden o al cambiar el ajuste). */
    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, LinkWidget.class));
        if (ids != null && ids.length > 0) WidgetUpdater.render(c, m, ids, WidgetUpdater.glance(c));
    }

    /** El toque de un botón («lock», «unlock», «trunk», «climate», «find» o la voz). */
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
}
