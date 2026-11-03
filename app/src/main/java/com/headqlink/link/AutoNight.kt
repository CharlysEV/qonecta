package com.headqlink.link

/**
 * Día o noche con el «Tema» en Automático cuando el coche no dice el suyo (Global/DarkModeOn; el C10 no lo manda en
 * ninguno de los 5 logs mirados). Foro LPMFF, flash c10 #271: un Find X3 Pro por cable metido en el hueco de la consola,
 * a oscuras, con AA siempre de noche, también a mediodía, porque solo mandaba el sensor de luz del móvil. Pura, por
 * sesión (reset al conectar):
 * - sin posición, el sensor de luz como hasta ahora;
 * - hasta que el sensor vea en la sesión luz clara de día (DAYLIGHT_LUX), manda el sol en la posición del móvil: un móvil
 *   guardado no ve nunca la luz y no puede decir si es de día;
 * - visto ya el día, el sensor (túneles y garajes se oscurecen como el propio AA), salvo que lleve STOWED_MS seguidos a
 *   oscuras siendo de día por el sol: el móvil se ha guardado y vuelve a mandar el sol hasta que vea luz otra vez.
 * El tema que diga el coche manda por encima de todo esto (AaPassthroughSource.sessionNightMode lo fija y aquí no se llega).
 */
class AutoNight {
    companion object {
        /**
         * Luz que cuenta como «el móvil ve el día». 500 lux es una oficina bien iluminada: el habitáculo de día, en el
         * soporte o a la vista, da de 1000 lux (nublado) a decenas de miles (sol), y el hueco de una consola o una
         * bandeja de día se queda en decenas. Por encima del «día» de la histéresis (umbral ×2,5: 250 lux con los 100 de
         * serie), para que un reflejo en la consola o el garaje de casa no basten; y al anochecer, con el sol en el
         * horizonte, fuera ya hay menos de 500, así que no confunde el ocaso con un día visto.
         */
        const val DAYLIGHT_LUX = 500f

        /** A oscuras tanto rato siendo de día por el sol: el móvil está guardado (un túnel o un garaje duran menos). */
        const val STOWED_MS = 5 * 60_000L

        /** Elevación del sol por debajo de la cual es de noche: salida y puesta oficiales (refracción + radio del disco). */
        const val SUN_NIGHT_ELEVATION = -0.83

        /** De noche por el sol en (lat, lon) a la hora utcMs (RoadInfo.sunPosition, NOAA simplificado, sin red). */
        @JvmStatic
        fun sunNight(lat: Double, lon: Double, utcMs: Long): Boolean =
            RoadInfo.sunPosition(lat, lon, utcMs)[0] < SUN_NIGHT_ELEVATION
    }

    private var sawDaylight = false
    private var darkSinceMs = -1L

    /** La última decisión salió del sol (para el log: solo se avisa al cambiar). */
    var bySun = false
        private set

    /** Sesión nueva (al conectar): el sensor aún no ha visto el día. */
    fun reset() {
        sawDaylight = false
        darkSinceMs = -1L
        bySun = false
    }

    /**
     * @param lux lectura del sensor de luz (< 0 sin lectura).
     * @param sensorNight lo que diría el sensor solo, con su histéresis (false sin lectura, como hasta ahora).
     * @param sunNight de noche por el sol en la posición del móvil; null sin posición.
     * @param dayLux el «día» de la histéresis del sensor: lo visto tiene que pasar de él además de DAYLIGHT_LUX.
     */
    fun isNight(nowMs: Long, lux: Float, sensorNight: Boolean, sunNight: Boolean?, dayLux: Float = 0f): Boolean {
        if (sunNight == null) {
            bySun = false
            return sensorNight
        }
        if (lux >= maxOf(DAYLIGHT_LUX, dayLux)) sawDaylight = true
        if (!sawDaylight) {
            bySun = true
            return sunNight
        }
        if (sensorNight && !sunNight) {
            if (darkSinceMs < 0) darkSinceMs = nowMs
            if (nowMs - darkSinceMs >= STOWED_MS) {
                bySun = true
                return false
            }
        } else {
            darkSinceMs = -1L
        }
        bySun = false
        return sensorNight
    }
}
