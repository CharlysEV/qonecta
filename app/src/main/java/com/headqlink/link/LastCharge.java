package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Los km del coche en la última carga (LPMFF, sayaz #238: «km seit dem letzten Laden»). Cada lectura de la nube con el
 * coche cargando (o con la carga acabada y el cable aún puesto) apunta el cuentakilómetros y la hora del dato; «Mi
 * coche» › «Carga» enseña los km desde entonces. Con la comprobación de fondo (ChargeCheckJob, cada 15 min) se ve
 * cualquier carga, aunque no se abra la app. Solo en el móvil.
 */
final class LastCharge {
    private static final String PREFS = "hql_last_charge";
    private static final String K_ODO = "odo";
    private static final String K_AT = "at";

    /** El cuentakilómetros y la hora del dato del coche en la última carga vista. */
    static final class Mark {
        final double odoKm;
        final long atMs;

        Mark(double odoKm, long atMs) {
            this.odoKm = odoKm;
            this.atMs = atMs;
        }
    }

    private LastCharge() {
    }

    /** ¿Cuenta como carga? Cargando, o con la carga acabada y aún enchufado (se vio después de cargar). */
    static boolean charged(LeapStatus s) {
        return s != null && (s.charging() || (s.pluggedIn() && Boolean.TRUE.equals(s.chargeCompleted)));
    }

    /** La marca con una lectura: la nueva si es de una carga y trae los km; si no (o el dato es más viejo), la de antes. */
    static Mark next(Mark prev, boolean charged, double odoKm, long dataMs) {
        if (!charged || Double.isNaN(odoKm) || dataMs <= 0) return prev;
        if (prev != null && dataMs < prev.atMs) return prev;
        return new Mark(odoKm, dataMs);
    }

    /** Los km desde la última carga, o NaN (sin marca, sin km, o el cuentakilómetros va hacia atrás: otro coche). */
    static double kmSince(Mark m, double odoNowKm) {
        if (m == null || Double.isNaN(odoNowKm) || odoNowKm < m.odoKm) return Double.NaN;
        return odoNowKm - m.odoKm;
    }

    /** Con cada lectura de la nube (CarCloud): apunta la marca si es de una carga. */
    static void observe(Context ctx, LeapStatus s, long dataMs) {
        if (!charged(s)) return;
        SharedPreferences sp = prefs(ctx);
        Mark prev = stored(sp);
        Mark m = next(prev, true, s.odometerKm, dataMs);
        if (m == prev) return;
        sp.edit().putLong(K_ODO, Math.round(m.odoKm)).putLong(K_AT, m.atMs).apply();
        if (prev == null || Math.round(prev.odoKm) != Math.round(m.odoKm)) {
            L.i(String.format(Locale.US, "última carga: el coche en %.0f km", m.odoKm));
        }
    }

    /** La última carga vista, o null (en la demostración, una de anteayer). */
    static Mark get(Context ctx) {
        if (DemoMode.active()) return DemoMode.lastCharge();
        return stored(prefs(ctx));
    }

    private static Mark stored(SharedPreferences sp) {
        long at = sp.getLong(K_AT, 0);
        return at <= 0 ? null : new Mark(sp.getLong(K_ODO, 0), at);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
