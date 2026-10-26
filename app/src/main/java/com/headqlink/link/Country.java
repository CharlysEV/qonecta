package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import java.util.Locale;

/**
 * El país en el que está el móvil: el de la red móvil a la que está conectado (correcto también con una SIM de otro país
 * en itinerancia; sin permisos). Sin red un momento (un túnel), se queda el último conocido.
 */
final class Country {
    private static final long FRESH_MS = 60_000;
    private static volatile String last = "";
    private static volatile long checkedAt = Long.MIN_VALUE / 2;

    private Country() {
    }

    /** Código ISO en minúsculas («es», «de»…), o "" si nunca se ha sabido. */
    static String current(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - checkedAt < FRESH_MS) return last;
        checkedAt = now;
        try {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            String iso = tm == null ? null : tm.getNetworkCountryIso();
            if (iso != null && !iso.isEmpty()) last = iso.toLowerCase(Locale.US);
        } catch (RuntimeException ignored) {
            // Sin telefonía (tableta) o restringido: el último conocido.
        }
        return last;
    }

    /**
     * Puro: países donde la ley prohíbe al conductor usar avisadores de radar, también como app en el móvil: Alemania
     * (§ 23 Abs. 1c StVO) y Suiza (art. 98a LCR).
     */
    static boolean radarWarningsForbidden(String iso) {
        return "de".equals(iso) || "ch".equals(iso);
    }
}
