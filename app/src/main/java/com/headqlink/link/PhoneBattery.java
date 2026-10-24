package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * La batería del móvil en un momento: el %, el contador de carga (µAh: lo que queda, más fino que el %; Long.MIN_VALUE si
 * el móvil no lo da), si está enchufado y cuándo. Para lo que gasta el móvil en cada viaje y para estimar su capacidad.
 */
final class PhoneBattery {
    /** Índices de read(): %, µAh, enchufado (1/0), hora (ms de pared). */
    static final int PCT = 0;
    static final int UAH = 1;
    static final int PLUGGED = 2;
    static final int AT = 3;

    private PhoneBattery() {
    }

    /** Ahora mismo, o null si Android no lo dice. */
    static long[] read(Context c) {
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return null;
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl < 0 || scale <= 0) return null;
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            long uah = Long.MIN_VALUE;
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (v > 0 && v != Integer.MIN_VALUE) uah = v;
            }
            return new long[]{lvl * 100L / scale, uah, plugged != 0 ? 1 : 0, System.currentTimeMillis()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Puro: la capacidad de la batería (mAh) por el contador y el %: si con el 50 % quedan 2.500.000 µAh, son 5.000 mAh.
     * 0 si no se puede saber.
     */
    static double capacityMah(long pct, long uah) {
        if (pct < 5 || uah <= 0) return 0;
        return uah / 1000.0 / (pct / 100.0);
    }
}
