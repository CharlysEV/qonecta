package com.headqlink.link

import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchPointer

/**
 * ¿Retiene el coche los toques? Ritmo de llegada de los toques del coche (la hora a la que el lector de la sesión los
 * sacó del enlace, `TouchEvent.receivedAtNanos`). Mientras se arrastra el dedo, el coche manda un movimiento cada
 * ~16 ms; si los retiene (o el enlace los atasca), dejan de llegar y luego llegan de golpe: una **ráfaga**, varios
 * mensajes casi a la vez ([BUNCH_NS]) justo tras un hueco largo ([BURST_GAP_MS]) en mitad de un gesto. El retraso de
 * los toques retenidos es, como poco, ese hueco.
 *
 * Solo el hilo de eventos de la sesión lo alimenta (en orden); el resumen lo lee el mismo hilo al cerrar. Ni log ni
 * objetos por toque: unas restas y un conjunto de dedos pequeño. Puro: lo prueban los tests.
 */
internal class CarTouchCadence {
    /** Una ráfaga: [touches] mensajes amontonados tras [gapMs] de silencio (-1 si empezó el gesto: hueco sin medir). */
    class Burst(val touches: Int, val gapMs: Long) {
        fun describe(): String =
            if (gapMs >= 0) "ráfaga de $touches toques tras un hueco de $gapMs ms en mitad de un gesto"
            else "ráfaga de $touches toques al empezar un gesto"
    }

    /** Dedos en contacto (id del coche). Hasta 3 en el C10. */
    private val down = HashSet<Int>(4)
    private var lastNs = 0L

    /** Racha en curso de mensajes amontonados: cuántos y el hueco antes del primero (-1 = el primero empezó el gesto). */
    private var run = 0
    private var runGapMs = -1L
    private var runReported = false

    var messages = 0L
        private set

    /** Mensajes que llegaron a menos de [BUNCH_NS] del anterior (dentro de un gesto). */
    var bunched = 0L
        private set

    /** Ráfagas de [BURST_MIN] mensajes o más (con hueco previo o al empezar un gesto). */
    var bursts = 0
        private set

    /** Ráfagas tras un hueco de [BURST_GAP_MS] o más en mitad de un gesto. */
    var burstsAfterGap = 0
        private set

    /** La mayor ráfaga tras hueco (mensajes) y su hueco (ms). */
    var worstBurstTouches = 0
        private set
    var worstBurstGapMs = 0L
        private set

    /** Hueco más largo entre dos mensajes de un mismo gesto (dedo sin levantar), ms. */
    var maxGestureGapMs = 0L
        private set

    /**
     * Un mensaje táctil que llegó en [atNs] con sus [pointers]. Devuelve la ráfaga en cuanto llega a [BURST_MIN]
     * mensajes (una vez por ráfaga), o null.
     */
    fun onTouch(atNs: Long, pointers: List<TouchPointer>): Burst? {
        messages++
        val inGesture = down.isNotEmpty()
        val gapNs = if (lastNs == 0L) Long.MAX_VALUE else atNs - lastNs
        lastNs = atNs
        var burst: Burst? = null
        if (inGesture && gapNs >= STALE_GESTURE_NS) {
            // Un gesto «abierto» tanto rato es un UP perdido: no es un hueco de este gesto.
            down.clear()
            run = 0
        }
        val gestureGoing = down.isNotEmpty()
        if (gestureGoing && gapNs < BUNCH_NS) {
            bunched++
            if (run == 0) {
                // El anterior ya era el primero de la racha (llegó tras un hueco o empezó el gesto).
                run = 1
            }
            run++
            if (run >= BURST_MIN && !runReported) {
                runReported = true
                bursts++
                if (runGapMs >= BURST_GAP_MS) {
                    burstsAfterGap++
                }
                burst = Burst(run, runGapMs)
            }
        } else {
            closeRun()
            // Este mensaje puede ser el primero de una racha: se apunta el hueco antes de él.
            runGapMs = if (gestureGoing) gapNs / 1_000_000 else -1L
            if (gestureGoing && runGapMs > maxGestureGapMs) maxGestureGapMs = runGapMs
        }
        for (p in pointers) {
            when (p.action) {
                TouchCodec.FINGER_UP -> down.remove(p.id)
                else -> down.add(p.id) // DOWN, MOVE (aunque se perdiera su DOWN) o acción rara
            }
        }
        if (down.isEmpty()) {
            // Fin del gesto: la racha se cierra aquí (el siguiente empieza otro gesto).
            closeRun()
        }
        return burst
    }

    /** Cierra la racha en curso y actualiza la peor ráfaga con hueco. */
    private fun closeRun() {
        if (run >= BURST_MIN && runGapMs >= BURST_GAP_MS && run > 0) {
            if (runGapMs > worstBurstGapMs || (runGapMs == worstBurstGapMs && run > worstBurstTouches)) {
                worstBurstGapMs = runGapMs
                worstBurstTouches = run
            }
        }
        run = 0
        runReported = false
    }

    /** Para el resumen: cierra la racha pendiente (al acabar la sesión). */
    fun flush() = closeRun()

    /** «2 ráfagas (máx. 14 toques tras 2300 ms) · hueco máx. en gesto 2300 ms · amontonados 41», o "" sin toques. */
    fun text(): String {
        if (messages == 0L) return ""
        val sb = StringBuilder()
        sb.append(bursts).append(if (bursts == 1) " ráfaga" else " ráfagas")
        if (burstsAfterGap > 0) {
            sb.append(" (").append(burstsAfterGap).append(" tras hueco; peor ").append(worstBurstTouches).append(" toques tras ")
                .append(worstBurstGapMs).append(" ms)")
        }
        sb.append(" · hueco máx. en gesto ").append(maxGestureGapMs).append(" ms · amontonados ").append(bunched)
        return sb.toString()
    }

    companion object {
        /** Dos mensajes a menos de esto llegaron «a la vez»: el coche manda uno cada ~16 ms al arrastrar. */
        const val BUNCH_NS = 2_000_000L

        /** Mensajes amontonados seguidos que hacen una ráfaga (un DOWN y su UP juntos no lo son). */
        const val BURST_MIN = 5

        /** Hueco antes de la ráfaga, en mitad de un gesto, para contarla como retenida (15 movimientos sin llegar). */
        const val BURST_GAP_MS = 250L

        /** Un gesto sin noticias en tanto rato se da por acabado (UP perdido). */
        const val STALE_GESTURE_NS = 20_000_000_000L
    }
}

/**
 * Los latidos del coche (`HEARTBEAT`, cada ~3 s según QDLink): cuánto se retrasan sobre lo esperado y si llegan
 * **juntos** (dos a menos de [BUNCH_MS]), señal de que el coche o el enlace los retuvo y los soltó de golpe. Con la hora
 * del lector (`TraceEvent.timeMillis`). En el C10 el ritmo es irregular (2,8-14,8 s en sesiones sanas), así que el
 * retraso solo informa; el aviso sale únicamente de un latido retenido (uno tarde y el siguiente pegado a él).
 * Solo el hilo de eventos de la sesión lo alimenta. Puro: lo prueban los tests.
 */
internal class CarHeartbeatCadence(private val expectedMs: Long = EXPECTED_MS) {
    private var lastMs = 0L
    private var lastIntervalMs = -1L

    var count = 0L
        private set

    var maxIntervalMs = 0L
        private set

    /** Intervalos de más de lo esperado + [LATE_MS]. */
    var late = 0
        private set

    /** Latidos a menos de [BUNCH_MS] del anterior. */
    var bunched = 0
        private set

    /**
     * Un latido en [atMs]. Devuelve el retraso estimado (ms) si es un latido retenido de [LagMeter.LAG_MS] o más: el
     * anterior llegó tarde esa cantidad y este, pegado a él. -1 si no.
     */
    fun onHeartbeat(atMs: Long): Long {
        count++
        val prev = lastMs
        lastMs = atMs
        if (prev == 0L) return -1
        val interval = atMs - prev
        if (interval < 0 || interval > MAX_SANE_MS) {
            // Reloj cambiado (la hora del sistema) o sesión dormida: no es un intervalo.
            lastIntervalMs = -1
            return -1
        }
        if (interval > maxIntervalMs) maxIntervalMs = interval
        if (interval > expectedMs + LATE_MS) late++
        var held = -1L
        if (interval < BUNCH_MS) {
            bunched++
            val before = lastIntervalMs
            if (before > expectedMs) {
                val delay = before - expectedMs
                if (delay >= LagMeter.LAG_MS) held = delay
            }
        }
        lastIntervalMs = interval
        return held
    }

    /** «latidos máx. 3400 ms (tarde 0, juntos 0)», o "" con menos de dos. */
    fun text(): String =
        if (count < 2) "" else "latidos máx. $maxIntervalMs ms (tarde $late, juntos $bunched)"

    companion object {
        /** Ritmo de los latidos del coche (QDLink: cada 3 s). */
        const val EXPECTED_MS = 3_000L

        /** Un intervalo de más de lo esperado + esto cuenta como latido tarde. */
        const val LATE_MS = 1_000L

        /** Dos latidos a menos de esto llegaron juntos (el coche los manda cada ~3 s). */
        const val BUNCH_MS = 500L

        /** Más que esto entre dos latidos no es un intervalo (reloj del sistema cambiado). */
        const val MAX_SANE_MS = 120_000L
    }
}
