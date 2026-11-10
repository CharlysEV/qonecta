package com.headqlink.link;

/**
 * Volumen de los avisos de voz (radares, plan de carga, guía, avisos del cable): de 50 a 150 %, 100 de serie.
 *
 * El motor de voz no deja pasar de su volumen máximo (KEY_PARAM_VOLUME llega a 1,0), así que hasta 100 se baja la voz
 * y por encima de 100 se sube el volumen multimedia del móvil mientras habla (el aviso sale por ese flujo) y se
 * devuelve al acabar. Sin saturar: nunca se pasa del máximo del flujo, que es lo que el sistema ya admite.
 */
final class VoiceVolume {
    static final int MIN = 50;
    static final int MAX = 150;
    static final int DEFAULT = 100;
    static final int STEP = 10;

    private VoiceVolume() {
    }

    /** Lo guardado, dentro de los límites; lo raro (negativo, enorme) vuelve al de serie o al límite. */
    static int clamp(int pct) {
        return Math.max(MIN, Math.min(MAX, pct));
    }

    /** Volumen del motor de voz (0,5 a 1,0): por debajo de 100 se baja; de 100 en adelante va al máximo. */
    static float ttsGain(int pct) {
        return Math.min(1f, clamp(pct) / 100f);
    }

    /**
     * Índice del volumen multimedia mientras habla: el actual si pct no pasa de 100; si pasa, sube hacia el máximo del
     * flujo en proporción (150 = el máximo), sin bajar nunca del actual ni pasar de max.
     */
    static int boostedIndex(int pct, int current, int max) {
        int p = clamp(pct);
        if (p <= DEFAULT || max <= current) return current;
        double f = (p - DEFAULT) / (double) (MAX - DEFAULT);
        int idx = (int) Math.round(current + (max - current) * f);
        return Math.max(current, Math.min(max, idx));
    }
}
