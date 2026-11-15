package com.headqlink.link

import dev.qdauto.core.wire.TouchPointer

/**
 * Dónde se van los segundos cuando los toques tardan en aplicarse (una sesión). Solo mide y cuenta; no cambia nada.
 *
 * 1. **Coche → móvil** ([touches], [heartbeats]): si el coche retiene los toques (ráfagas tras un hueco) o los latidos.
 * 2. **Dentro del móvil**, desde que el lector sacó el toque del enlace:
 *    - [Stage.EVENTOS]: hasta que lo atiende el hilo de eventos de la sesión (`QdSessionBridge.onTouch`);
 *    - [Stage.AA]: hasta que el toque queda escrito hacia Android Auto (hilo de envío de AapTransport);
 *    - [Stage.PANTALLA]: hasta que lo atiende el hilo principal en nuestra interfaz (CarUi, TouchService);
 *    - [Stage.GESTO]: hasta que TouchService lo da a `dispatchGesture` (modo app).
 * 3. **Imagen** ([Stage.IMAGEN]): desde que el relé GL dibuja el frame en nuestro codificador hasta que sale entero
 *    hacia el coche (codificar + cola de la sesión + write).
 *
 * Cada retraso de [LAG_MS] o más deja un aviso pendiente (el peor); el monitor de red de la sesión lo recoge cada
 * 100 ms con [takeAlarm] y lo escribe (fuera de los hilos sensibles), como mucho uno cada [LagGate.PERIOD_MS] en todo el
 * proceso. Hilos: [touches] y [heartbeats] solo desde el de eventos; [record] desde cualquiera. Puro: lo prueban los
 * tests.
 */
internal class LagMeter(private val gate: LagGate = LagGate()) {
    enum class Stage(val label: String) {
        COCHE("coche→móvil"),
        EVENTOS("eventos de la sesión"),
        AA("entrega a Android Auto"),
        PANTALLA("hilo principal (nuestra pantalla)"),
        GESTO("dispatchGesture (modo app)"),
        IMAGEN("imagen (dibujo→coche)"),
    }

    /** Un retraso de [LAG_MS] o más: qué parte, cuánto y lo que se sabe de él. */
    class Alarm(val stage: Stage, val ms: Long, val detail: String?) {
        /**
         * La línea «LAG …» (sin nivel): parte, retraso, detalle, [context] (enlace, cola, térmico…) y los avisos que
         * se callaron desde el anterior.
         */
        fun line(context: String, suppressed: Int): String = buildString {
            append("LAG ").append(stage.label).append(' ').append(ms).append(" ms")
            if (!detail.isNullOrEmpty()) append(" · ").append(detail)
            if (context.isNotEmpty()) append(" · ").append(context)
            if (suppressed > 0) append(" · +").append(suppressed).append(" avisos callados desde el anterior")
        }
    }

    val touches = CarTouchCadence()
    val heartbeats = CarHeartbeatCadence()
    private val stats = Array(Stage.values().size) { LagStat() }

    private val lock = Any()
    private var pending: Alarm? = null
    private var alarms = 0
    private var freezeAtMs = 0L
    private var freezeMs = 0L

    /** Imágenes cuya marca de tiempo no era la hora de dibujo (no se cuentan). */
    @Volatile
    var unreadableImages = 0L
        private set

    fun stat(stage: Stage): LagStat = stats[stage.ordinal]

    /** Un retraso de [stage]. Cualquier hilo; barato (una suma con candado y, si pasa de [LAG_MS], el aviso). */
    fun record(stage: Stage, ms: Long, detail: String? = null) {
        if (stage != Stage.COCHE) stats[stage.ordinal].add(ms)
        if (ms >= LAG_MS) offer(Alarm(stage, ms, detail))
    }

    private fun offer(a: Alarm) {
        synchronized(lock) {
            val p = pending
            if (p == null || a.ms > p.ms) pending = a
        }
    }

    /**
     * Hilo de eventos: un toque del coche que el lector sacó en [arrivalNs] y este hilo atiende en [nowNs]. Ritmo de
     * llegada ([touches]) y la espera en la cola de eventos ([Stage.EVENTOS]).
     */
    fun onCarTouch(arrivalNs: Long, nowNs: Long, pointers: List<TouchPointer>) {
        touches.onTouch(arrivalNs, pointers)?.let { b ->
            if (b.gapMs >= LAG_MS) offer(Alarm(Stage.COCHE, b.gapMs, b.describe()))
        }
        record(Stage.EVENTOS, (nowNs - arrivalNs) / 1_000_000)
    }

    /** Hilo de eventos: un latido del coche en [atMs] (hora del lector). */
    fun onCarHeartbeat(atMs: Long) {
        val held = heartbeats.onHeartbeat(atMs)
        if (held >= LAG_MS) offer(Alarm(Stage.COCHE, held, "latido retenido: llegó $held ms tarde y el siguiente pegado a él"))
    }

    /**
     * Escritor de la sesión: un frame del codificador propio (marca de tiempo = hora de dibujo del relé GL, en µs de
     * `System.nanoTime`) que acabó de escribirse en [writeEndNs].
     */
    fun onImageWritten(writeEndNs: Long, ptsUs: Long) {
        val age = imageAgeMs(writeEndNs, ptsUs)
        if (age < 0) {
            unreadableImages++
            return
        }
        record(Stage.IMAGEN, age)
    }

    /** Monitor de red: el proceso estuvo [gapMs] sin correr (entre dos vueltas de 100 ms), visto en [nowMs]. */
    fun noteFreeze(nowMs: Long, gapMs: Long) {
        synchronized(lock) {
            freezeAtMs = nowMs
            freezeMs = gapMs
        }
    }

    /** Para la línea LAG: si el móvil se congeló en los últimos [FREEZE_WINDOW_MS]. */
    fun freezeText(nowMs: Long): String = synchronized(lock) {
        if (freezeAtMs == 0L || nowMs - freezeAtMs > FREEZE_WINDOW_MS) "móvil sin congelarse"
        else "móvil congelado $freezeMs ms hace ${nowMs - freezeAtMs} ms"
    }

    /**
     * Monitor de red, cada 100 ms: el aviso pendiente si toca escribirlo ([LagGate]); si no toca, se cuenta como
     * callado. Devuelve el aviso y cuántos se callaron antes que él, o null.
     */
    fun takeAlarm(nowMs: Long): Pair<Alarm, Int>? {
        val a = synchronized(lock) {
            val p = pending ?: return null
            pending = null
            p
        }
        val suppressed = gate.tryPass(nowMs)
        if (suppressed < 0) return null
        synchronized(lock) { alarms++ }
        return a to suppressed
    }

    /**
     * La línea «retraso (enlace): …» del resumen de la sesión, o "" si no hubo nada que medir (ni toques, ni latidos,
     * ni imagen). [link]: «cable USB», «Wi-Fi Direct»…
     */
    fun summary(link: String): String {
        touches.flush()
        val car = listOf(touches.text(), heartbeats.text()).filter { it.isNotEmpty() }
        val inside = listOf(Stage.EVENTOS, Stage.AA, Stage.PANTALLA, Stage.GESTO)
            .mapNotNull { s -> stat(s).text().takeIf { it.isNotEmpty() }?.let { "${s.label} $it" } }
        val image = stat(Stage.IMAGEN).text()
        if (car.isEmpty() && inside.isEmpty() && image.isEmpty()) return ""
        return buildString {
            append("retraso")
            if (link.isNotEmpty()) append(" (").append(link).append(')')
            append(": coche→móvil ").append(car.joinToString(" · ").ifEmpty { "—" })
            append(" | dentro del móvil ").append(inside.joinToString(" · ").ifEmpty { "—" })
            append(" | imagen ").append(image.ifEmpty { "—" })
            if (unreadableImages > 0) append(" (sin hora de dibujo ").append(unreadableImages).append(')')
            append(" | avisos LAG ").append(synchronized(lock) { alarms })
        }
    }

    companion object {
        /** Retraso a partir del cual hay aviso «LAG» (lo que un conductor ya nota como «no responde»). */
        const val LAG_MS = 1_000L

        /** Un congelado del móvil cuenta para el aviso si fue en este rato. */
        const val FREEZE_WINDOW_MS = 30_000L

        /** Una imagen más vieja que esto no es un retraso: la marca de tiempo no era la hora de dibujo. */
        const val MAX_IMAGE_AGE_MS = 60_000L

        /** Antigüedad (ms) de un frame escrito en [writeEndNs] y dibujado en [ptsUs] (µs de nanoTime), o -1. */
        fun imageAgeMs(writeEndNs: Long, ptsUs: Long): Long {
            if (writeEndNs <= 0 || ptsUs <= 0) return -1
            val ms = (writeEndNs / 1_000 - ptsUs) / 1_000
            return if (ms < 0 || ms > MAX_IMAGE_AGE_MS) -1 else ms
        }
    }
}

/**
 * Freno de los avisos «LAG» (uno por [periodMs] en todo el proceso, aunque haya varias sesiones): el log de un usuario
 * no debe llenarse de ellos. Puro: lo prueban los tests.
 */
internal class LagGate(private val periodMs: Long = PERIOD_MS) {
    private var lastMs = 0L
    private var any = false
    private var suppressed = 0

    /** ¿Se escribe un aviso en [nowMs]? Devuelve cuántos se callaron desde el anterior (≥ 0), o -1 si se calla este. */
    @Synchronized
    fun tryPass(nowMs: Long): Int {
        if (any && nowMs - lastMs < periodMs) {
            suppressed++
            return -1
        }
        any = true
        lastMs = nowMs
        val s = suppressed
        suppressed = 0
        return s
    }

    companion object {
        const val PERIOD_MS = 30_000L

        /** El de la app: compartido por todas las sesiones. */
        @JvmField
        val PROCESS = LagGate()
    }
}
