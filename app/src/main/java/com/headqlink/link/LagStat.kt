package com.headqlink.link

/**
 * Máximo, número y p95 aproximado de un retraso (ms) a lo largo de una sesión, sin guardar las muestras: un histograma
 * fijo de [BUCKETS] cubetas (1 ms de resolución hasta 100 ms, 10 ms hasta 1 s, 100 ms hasta 10 s y una última para lo
 * que pase de 10 s). Lo alimentan varios hilos (eventos de la sesión, envío a Android Auto, hilo principal, escritor):
 * cada [add] es una suma con el candado del objeto, nada más. Puro: lo prueban los tests.
 */
internal class LagStat {
    private val hist = IntArray(BUCKETS)

    var count = 0L
        @Synchronized get
        private set

    var max = 0L
        @Synchronized get
        private set

    @Synchronized
    fun add(ms: Long) {
        val v = if (ms < 0) 0 else ms
        hist[bucketOf(v)]++
        count++
        if (v > max) max = v
    }

    /**
     * Percentil [p] (1-100) por rango más cercano, como el límite superior de su cubeta (sin pasar del máximo visto):
     * nunca dice menos de lo que hubo. 0 si no hay muestras.
     */
    @Synchronized
    fun percentile(p: Int): Long {
        if (count == 0L) return 0
        val rank = (count * p + 99) / 100
        var seen = 0L
        for (i in hist.indices) {
            seen += hist[i]
            if (seen >= rank) return minOf(upperOf(i), max)
        }
        return max
    }

    /** «máx. 12 p95 4 ms (1203)», o "" sin muestras. */
    @Synchronized
    fun text(): String = if (count == 0L) "" else "máx. $max p95 ${percentile(95)} ms ($count)"

    companion object {
        const val BUCKETS = 281

        /** Cubeta de [ms] (≥ 0). */
        fun bucketOf(ms: Long): Int = when {
            ms < 100 -> ms.toInt()
            ms < 1_000 -> 100 + ((ms - 100) / 10).toInt()
            ms < 10_000 -> 190 + ((ms - 1_000) / 100).toInt()
            else -> BUCKETS - 1
        }

        /** Límite superior (incluido) de la cubeta [i]; la última no tiene: Long.MAX_VALUE. */
        fun upperOf(i: Int): Long = when {
            i < 100 -> i.toLong()
            i < 190 -> 100L + (i - 100 + 1) * 10 - 1
            i < BUCKETS - 1 -> 1_000L + (i - 190 + 1) * 100 - 1
            else -> Long.MAX_VALUE
        }
    }
}
